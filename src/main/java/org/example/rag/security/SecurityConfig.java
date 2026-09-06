package org.example.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.example.rag.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Spring Security 配置：管理端使用表单登录和服务端 Session，MCP 保持匿名访问。
 */
@Configuration
public class SecurityConfig {

    private static final String LOGIN_URL = "/api/auth/login";
    private static final String LOGOUT_URL = "/api/auth/logout";
    private static final String LOGIN_PAGE = "/login.html";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AppProperties properties,
                                                   ObjectMapper objectMapper) throws Exception {
        String mcpEndpoint = properties.getMcp().getEndpoint();
        List<String> ignorePatterns = List.of(mcpEndpoint, mcpEndpoint + "/**");
        IpWhitelistFilter ipWhitelistFilter = new IpWhitelistFilter(
                properties.getSecurity().getIpWhitelist(),
                ignorePatterns,
                properties.getSecurity().getTrustedProxies(),
                objectMapper
        );

        http
                .csrf(csrf -> csrf.ignoringRequestMatchers(mcpEndpoint, mcpEndpoint + "/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                LOGIN_PAGE, "/login.css", "/login.js", "/favicon.ico",
                                LOGIN_URL, "/api/auth/status", "/error"
                        ).permitAll()
                        .requestMatchers(mcpEndpoint, mcpEndpoint + "/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage(LOGIN_PAGE)
                        .loginProcessingUrl(LOGIN_URL)
                        .successHandler((request, response, authentication) -> writeJson(
                                response,
                                HttpServletResponse.SC_OK,
                                objectMapper,
                                Map.of(
                                        "success", true,
                                        "authenticated", true,
                                        "username", authentication.getName()
                                )
                        ))
                        .failureHandler((request, response, exception) -> writeJson(
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                objectMapper,
                                Map.of("success", false, "message", "用户名或密码错误")
                        ))
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_URL)
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> writeJson(
                                response,
                                HttpServletResponse.SC_OK,
                                objectMapper,
                                Map.of("success", true, "message", "已退出登录")
                        ))
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            String path = request.getRequestURI().substring(request.getContextPath().length());
                            // 在发送工作台 HTML 前校验登录；API 与内部静态资源仍返回 JSON 401。
                            if ("/".equals(path) || "/index.html".equals(path)) {
                                response.sendRedirect(request.getContextPath() + LOGIN_PAGE);
                                return;
                            }
                            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, objectMapper,
                                    Map.of("success", false, "message", "登录已失效，请重新登录"));
                        })
                        .accessDeniedHandler((request, response, exception) -> writeJson(
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                objectMapper,
                                Map.of("success", false, "message", "请求被拒绝，请刷新页面后重试")
                        ))
                )
                .addFilterBefore(ipWhitelistFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(AppProperties properties) {
        UserDetails user = User.withUsername(properties.getSecurity().getUsername())
                // 兼容现有明文配置，同时支持环境变量提供 {bcrypt} 哈希。
                .password(encodedPassword(properties.getSecurity().getPassword()))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    private String encodedPassword(String password) {
        if (password == null || password.isBlank()) throw new IllegalArgumentException("管理端密码不能为空");
        return password.startsWith("{bcrypt}") ? password : "{noop}" + password;
    }

    private static void writeJson(HttpServletResponse response,
                                  int status,
                                  ObjectMapper objectMapper,
                                  Map<String, Object> payload) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), payload);
    }
}

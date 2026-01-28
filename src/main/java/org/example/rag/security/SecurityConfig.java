package org.example.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

/**
 * Spring Security 配置：Basic Auth + IP 白名单。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AppProperties properties,
                                                   ObjectMapper objectMapper) throws Exception {
        String mcpEndpoint = properties.getMcp().getEndpoint();
        List<String> ignorePatterns = List.of(mcpEndpoint, mcpEndpoint + "/**");
        IpWhitelistFilter ipWhitelistFilter = new IpWhitelistFilter(
                properties.getSecurity().getIpWhitelist(),
                ignorePatterns,
                objectMapper
        );

        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(mcpEndpoint, mcpEndpoint + "/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults())
                .addFilterBefore(ipWhitelistFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(AppProperties properties) {
        UserDetails user = User.withUsername(properties.getSecurity().getUsername())
                // 演示场景使用明文密码，生产环境请改为 BCrypt 并保存加密后的值
                .password("{noop}" + properties.getSecurity().getPassword())
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }
}

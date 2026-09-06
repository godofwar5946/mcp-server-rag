package org.example.rag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IP 白名单过滤器：对管理接口与前端页面生效，对 MCP 接口放行。
 */
public class IpWhitelistFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IpWhitelistFilter.class);

    private final List<String> ipWhitelist;
    private final List<String> ignorePatterns;
    private final List<String> trustedProxies;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public IpWhitelistFilter(List<String> ipWhitelist, List<String> ignorePatterns, ObjectMapper objectMapper) {
        this(ipWhitelist, ignorePatterns, List.of(), objectMapper);
    }

    public IpWhitelistFilter(List<String> ipWhitelist, List<String> ignorePatterns,
                             List<String> trustedProxies, ObjectMapper objectMapper) {
        this.ipWhitelist = ipWhitelist;
        this.ignorePatterns = ignorePatterns;
        this.objectMapper = objectMapper;
        this.trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String pattern : ignorePatterns) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = resolveClientIp(request);
        if (ipWhitelist == null || ipWhitelist.isEmpty()) {
            deny(response, clientIp, "IP 白名单为空，已拒绝访问。",
                    HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (!ipWhitelist.contains(clientIp)) {
            deny(response, clientIp, "IP 不在白名单内，已拒绝访问。",
                    HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, String ip, String message, int status) throws IOException {
        log.warn("IP 白名单拦截：{}", ip);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> payload = new HashMap<>();
        payload.put("success", false);
        payload.put("message", message);
        payload.put("clientIp", ip);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(payload));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!trustedProxies.contains(remote)) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 从可信代理一侧向左寻找首个非可信节点，不能信任用户自行添加的最左地址。
            String[] hops = forwarded.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (hop.isEmpty()) return remote;
                if (!trustedProxies.contains(hop)) return hop;
            }
        }
        return remote;
    }
}

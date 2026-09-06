package org.example.rag.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

/** 错误响应与日志使用同一个关联标识；不记录查询文本、密码或文件正文。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public class RequestIdFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String id=UUID.randomUUID().toString();
        response.setHeader("X-Request-Id",id); MDC.put("requestId",id);
        try { chain.doFilter(request,response); }
        finally { MDC.remove("requestId"); }
    }
}

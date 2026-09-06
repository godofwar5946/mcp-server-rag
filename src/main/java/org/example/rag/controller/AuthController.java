package org.example.rag.controller;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端 Session 状态接口，同时向静态前端提供 CSRF Token。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/status")
    public Map<String, Object> status(Authentication authentication, CsrfToken csrfToken) {
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("authenticated", authenticated);
        if (authenticated) {
            body.put("username", authentication.getName());
        }
        body.put("csrfToken", csrfToken.getToken());
        body.put("csrfHeaderName", csrfToken.getHeaderName());
        return body;
    }
}

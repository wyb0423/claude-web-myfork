package com.claude.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 认证过滤器：所有请求先经此处校验 cookie 令牌。
 * - 公开路径直接放行
 * - API 路径未认证 → 返回 401 JSON
 * - 页面路径未认证 → 重定向到 /login
 */
//@Component
//@Order(1)
public class AuthFilter implements Filter {

    public static final String TOKEN_COOKIE   = "claude_web_token";
    public static final String CURRENT_USER   = "currentUser";

    private final SessionStore   sessionStore;
    private final ObjectMapper   objectMapper;

    public AuthFilter(SessionStore sessionStore, ObjectMapper objectMapper) {
        this.sessionStore = sessionStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        // AUTH DISABLED: 跳过认证，注入默认用户后放行所有请求
        // 如需恢复认证，注释掉下面两行并取消注释下方代码块
        request.setAttribute(CURRENT_USER, new SessionStore.UserSession(
                0L, "admin", "admin", java.time.Instant.now().plusSeconds(86400L * 365)));
        chain.doFilter(req, res);

        /* --- AUTH DISABLED BEGIN ---
        String path = request.getRequestURI();

        if (isPublic(path)) {
            chain.doFilter(req, res);
            return;
        }

        String token = extractToken(request);
        SessionStore.UserSession session = sessionStore.getSession(token);

        if (session != null) {
            request.setAttribute(CURRENT_USER, session);
            chain.doFilter(req, res);
            return;
        }

        if (isApi(path)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    Map.of("error", "Unauthorized", "code", 401));
        } else {
            response.sendRedirect("/login");
        }
        --- AUTH DISABLED END --- */
    }

    private boolean isPublic(String path) {
        return "/login".equals(path)
            || "/auth/login".equals(path)
            || "/auth/logout".equals(path)
            || path.startsWith("/css/")
            || path.startsWith("/js/")
            || path.startsWith("/static/")
            || path.startsWith("/favicon");
    }

    private boolean isApi(String path) {
        return path.startsWith("/api/")
            || path.startsWith("/claude-api/")
            || path.startsWith("/auth/");
    }

    public static String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (TOKEN_COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}

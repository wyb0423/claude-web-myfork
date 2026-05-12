package com.claude.web.security;

import com.claude.web.config.ClaudeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

//@Component
//@Order(1)
public class AuthFilter implements Filter {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthFilter.class);
    private static final String TOKEN_COOKIE = "claude_web_token";
    private static final String LOGIN_PATH = "/auth/login";
    private static final SecureRandom secureRandom = new SecureRandom();
    
    private final ClaudeProperties properties;
    private final ObjectMapper objectMapper;
    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();
    
    public AuthFilter(ClaudeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        String path = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();
        
        // If auth is disabled, proceed
        if (!properties.isAuthEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        
        // Allow login page through
        if ("/login".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Handle login POST
        if ("POST".equalsIgnoreCase(method) && LOGIN_PATH.equals(path)) {
            handleLogin(httpRequest, httpResponse);
            return;
        }

        // Check for valid token
        String token = getTokenFromCookie(httpRequest);
        if (token != null && validTokens.contains(token)) {
            chain.doFilter(request, response);
            return;
        }

        // API requests return 401
        if (path.startsWith("/claude-api/") || path.startsWith("/api/")) {
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/json");
            objectMapper.writeValue(httpResponse.getWriter(), Map.of("error", "Unauthorized"));
            return;
        }

        // Redirect to login page for other requests
        httpResponse.sendRedirect("/login");
    }
    
    private void handleLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        LoginRequest loginRequest = objectMapper.readValue(request.getInputStream(), LoginRequest.class);
        
        if (loginRequest == null || loginRequest.password() == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getWriter(), Map.of("error", "Invalid request"));
            return;
        }
        
        if (!constantTimeCompare(loginRequest.password(), properties.getPassword())) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getWriter(), Map.of("error", "Invalid password"));
            return;
        }
        
        // Generate token
        String token = generateToken();
        validTokens.add(token);
        
        // Set cookie
        Cookie cookie = new Cookie(TOKEN_COOKIE, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // Set to true in production with HTTPS
        cookie.setMaxAge(7 * 24 * 60 * 60); // 7 days
        response.addCookie(cookie);
        
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), Map.of("ok", true));
    }
    
    private String getTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        
        for (Cookie cookie : cookies) {
            if (TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
    
    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    
    private boolean constantTimeCompare(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
    
    public void invalidateToken(String token) {
        validTokens.remove(token);
    }

    private record LoginRequest(String password) {}
}

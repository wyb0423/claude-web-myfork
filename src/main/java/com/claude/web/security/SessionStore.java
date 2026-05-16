package com.claude.web.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 Session 存储：管理登录令牌与用户信息的映射。
 * 令牌为 32 字节随机 URL-safe Base64，有效期 7 天。
 */
@Component
public class SessionStore {

    public static final int COOKIE_MAX_AGE = 7 * 24 * 60 * 60; // 7 days in seconds
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, UserSession> sessions = new ConcurrentHashMap<>();

    public String createSession(Long userId, String username, String role) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new UserSession(userId, username, role,
                Instant.now().plusSeconds(COOKIE_MAX_AGE)));
        return token;
    }

    public UserSession getSession(String token) {
        if (token == null) return null;
        UserSession session = sessions.get(token);
        if (session == null) return null;
        if (session.isExpired()) {
            sessions.remove(token);
            return null;
        }
        return session;
    }

    public void invalidate(String token) {
        if (token != null) sessions.remove(token);
    }

    public record UserSession(Long userId, String username, String role, Instant expiresAt) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}

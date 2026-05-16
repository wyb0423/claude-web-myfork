package com.claude.web.controller;

import com.claude.web.entity.User;
import com.claude.web.security.AuthFilter;
import com.claude.web.security.SessionStore;
import com.claude.web.security.SessionStore.UserSession;
import com.claude.web.service.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证 REST 接口：登录、登出、当前用户信息。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService    userService;
    private final SessionStore   sessionStore;

    public AuthController(UserService userService, SessionStore sessionStore) {
        this.userService  = userService;
        this.sessionStore = sessionStore;
    }

    /** POST /auth/login — 用用户名+密码换取 cookie 令牌 */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletResponse response) {
        if (body.username() == null || body.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名和密码不能为空"));
        }
        User user;
        try {
            user = userService.authenticate(body.username(), body.password());
        } catch (Exception e) {
            log.error("Login failed for user '{}': {}", body.username(), e.getMessage());
            return ResponseEntity.status(503).body(Map.of("error", "服务暂时不可用，请稍后重试"));
        }
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        String token = sessionStore.createSession(user.getId(), user.getUsername(), user.getRole());
        Cookie cookie = new Cookie(AuthFilter.TOKEN_COOKIE, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(SessionStore.COOKIE_MAX_AGE);
        response.addCookie(cookie);
        return ResponseEntity.ok(Map.of(
                "ok",       true,
                "username", user.getUsername(),
                "role",     user.getRole()));
    }

    /** POST /auth/logout — 使当前令牌失效，清除 cookie */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = AuthFilter.extractToken(request);
        sessionStore.invalidate(token);
        Cookie cookie = new Cookie(AuthFilter.TOKEN_COOKIE, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** GET /auth/me — 返回当前登录用户信息 */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        UserSession session = (UserSession) request.getAttribute(AuthFilter.CURRENT_USER);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        return ResponseEntity.ok(Map.of(
                "username", session.username(),
                "role",     session.role()));
    }

    private record LoginRequest(String username, String password) {}
}

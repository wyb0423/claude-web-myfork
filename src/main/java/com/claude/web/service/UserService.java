package com.claude.web.service;

import com.claude.web.entity.User;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用户业务服务（纯内存实现，无数据库依赖）。
 * 应用重启后用户数据重置为默认值，适合开发/演示环境。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final PasswordEncoder passwordEncoder;

    private final ConcurrentHashMap<String, User> userMap = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    public UserService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 验证用户名和密码，成功则更新最后登录时间并返回 User，否则返回 null。
     */
    public User authenticate(String username, String rawPassword) {
        if (username == null || rawPassword == null) return null;
        User user = userMap.get(username.trim().toLowerCase());
        if (user == null) return null;
        if (user.getStatus() == null || user.getStatus() != 1) return null;
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) return null;
        user.setLastLoginTime(LocalDateTime.now());
        return user;
    }

    /**
     * 创建新用户（密码自动 BCrypt 哈希）。
     */
    public User createUser(String username, String rawPassword, String role) {
        User user = new User();
        user.setId(idSeq.getAndIncrement());
        user.setUsername(username.trim().toLowerCase());
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        userMap.put(user.getUsername(), user);
        return user;
    }

    public User findByUsername(String username) {
        if (username == null) return null;
        return userMap.get(username.trim().toLowerCase());
    }

    /**
     * 启动时写入默认演示账号。
     */
    @PostConstruct
    public void initDefaultUsers() {
        createUser("admin", "Admin@2024!", "admin");
        createUser("demo",  "Demo@2024!",  "user");
        log.info("In-memory users ready — admin / Admin@2024!  |  demo / Demo@2024!");
    }
}

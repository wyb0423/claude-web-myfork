package com.claude.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * ClaudeWebApplication 集成测试 - 验证 Spring 上下文加载
 */
@SpringBootTest
@TestPropertySource(properties = {
    "claude.web.claude-agent.host=127.0.0.1",
    "claude.web.claude-agent.port=13001",
    "claude.web.password-enabled=false"
})
class ClaudeWebApplicationTest {

    @Test
    void contextLoads() {
        // 验证 Spring 上下文能够正常加载
    }
}

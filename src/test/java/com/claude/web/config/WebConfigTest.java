package com.claude.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * WebConfig 单元测试
 */
class WebConfigTest {

    @Test
    void testAddCorsMappings() {
        WebConfig config = new WebConfig();
        CorsRegistry registry = new CorsRegistry();

        assertDoesNotThrow(() -> config.addCorsMappings(registry));
    }
}

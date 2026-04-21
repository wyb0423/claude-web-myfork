package com.claude.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SecurityConfig 单元测试
 */
class SecurityConfigTest {

    @Test
    void testFilterChain() throws Exception {
        SecurityConfig config = new SecurityConfig();
        HttpSecurity httpSecurity = mock(HttpSecurity.class);

        // Mock the chain
        when(httpSecurity.csrf(any())).thenReturn(httpSecurity);
        when(httpSecurity.authorizeHttpRequests(any())).thenReturn(httpSecurity);

        // Should not throw
        assertDoesNotThrow(() -> config.filterChain(httpSecurity));
    }
}

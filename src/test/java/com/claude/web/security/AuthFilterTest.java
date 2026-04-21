package com.claude.web.security;

import com.claude.web.config.ClaudeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AuthFilter 单元测试
 */
class AuthFilterTest {

    private ClaudeProperties properties;
    private ObjectMapper objectMapper;
    private AuthFilter authFilter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        properties = new ClaudeProperties();
        objectMapper = new ObjectMapper();
        authFilter = new AuthFilter(properties, objectMapper);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @Test
    void testAuthDisabledProceeds() throws Exception {
        properties.setPasswordEnabled(false);
        when(request.getRequestURI()).thenReturn("/api/claude");

        authFilter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void testLoginPageAllowed() throws Exception {
        properties.setPassword("secret");
        when(request.getRequestURI()).thenReturn("/login");

        authFilter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void testApiWithoutTokenReturns401() throws Exception {
        properties.setPassword("secret");
        when(request.getRequestURI()).thenReturn("/api/claude");
        when(request.getCookies()).thenReturn(null);

        authFilter.doFilter(request, response, chain);
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void testSuccessfulLogin() throws Exception {
        properties.setPassword("secret");
        when(request.getRequestURI()).thenReturn("/auth/login");
        when(request.getMethod()).thenReturn("POST");
        String loginJson = objectMapper.writeValueAsString(Map.of("password", "secret"));
        when(request.getInputStream()).thenReturn(
            new MockServletInputStream(loginJson.getBytes())
        );

        authFilter.doFilter(request, response, chain);
        verify(response).setContentType("application/json");
        assertTrue(responseWriter.toString().contains("\"ok\":true"));
    }

    @Test
    void testFailedLogin() throws Exception {
        properties.setPassword("secret");
        when(request.getRequestURI()).thenReturn("/auth/login");
        when(request.getMethod()).thenReturn("POST");
        String loginJson = objectMapper.writeValueAsString(Map.of("password", "wrong"));
        when(request.getInputStream()).thenReturn(
            new MockServletInputStream(loginJson.getBytes())
        );

        authFilter.doFilter(request, response, chain);
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void testInvalidLoginRequest() throws Exception {
        properties.setPassword("secret");
        when(request.getRequestURI()).thenReturn("/auth/login");
        when(request.getMethod()).thenReturn("POST");
        String loginJson = "{}";
        when(request.getInputStream()).thenReturn(
            new MockServletInputStream(loginJson.getBytes())
        );

        authFilter.doFilter(request, response, chain);
        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    void testPageRequestWithoutTokenRedirects() throws Exception {
        properties.setPassword("secret");
        when(request.getRequestURI()).thenReturn("/");
        when(request.getCookies()).thenReturn(null);

        authFilter.doFilter(request, response, chain);
        verify(response).sendRedirect("/login");
    }

    @Test
    void testClaudeApiPathRequiresAuth() throws Exception {
        properties.setPassword("secret");
        when(request.getRequestURI()).thenReturn("/claude-api/events");
        when(request.getCookies()).thenReturn(null);

        authFilter.doFilter(request, response, chain);
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    // Mock ServletInputStream
    static class MockServletInputStream extends jakarta.servlet.ServletInputStream {
        private final ByteArrayInputStream inputStream;

        MockServletInputStream(byte[] data) {
            this.inputStream = new ByteArrayInputStream(data);
        }

        @Override
        public int read() {
            return inputStream.read();
        }

        @Override
        public boolean isFinished() {
            return inputStream.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
        }
    }
}

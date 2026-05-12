package com.claude.web.security;

import com.claude.web.config.ClaudeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthFilterTest {

    private ClaudeProperties properties;
    private AuthFilter filter;

    @BeforeEach
    void setUp() {
        properties = new ClaudeProperties();
        filter = new AuthFilter(properties, new ObjectMapper());
    }

    // ─── auth disabled ────────────────────────────────────────────────────────

    @Test
    void doFilter_passesThrough_whenAuthDisabled() throws Exception {
        properties.setPasswordEnabled(false);
        properties.setPassword("ignored");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/claude");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_passesThrough_whenPasswordEmpty() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/claude");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // ─── login page allowed ───────────────────────────────────────────────────

    @Test
    void doFilter_allowsLoginPage_withoutToken() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("secret");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // ─── login POST ───────────────────────────────────────────────────────────

    @Test
    void doFilter_login_success_setsCookieAndReturns200() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("correct");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setContent("{\"password\":\"correct\"}".getBytes());
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        Cookie tokenCookie = response.getCookie("claude_web_token");
        assertThat(tokenCookie).isNotNull();
        assertThat(tokenCookie.isHttpOnly()).isTrue();
        assertThat(tokenCookie.getMaxAge()).isEqualTo(7 * 24 * 60 * 60);
        assertThat(tokenCookie.getPath()).isEqualTo("/");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_login_wrongPassword_returns401() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("correct");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setContent("{\"password\":\"wrong\"}".getBytes());
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getCookie("claude_web_token")).isNull();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_login_missingPasswordField_returns400() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("secret");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setContent("{}".getBytes());
        request.setContentType("application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        verify(chain, never()).doFilter(any(), any());
    }

    // ─── unauthenticated access ───────────────────────────────────────────────

    @Test
    void doFilter_redirectsToLogin_forHtmlRequest() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("secret");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }

    @Test
    void doFilter_returns401_forApiRequest() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("secret");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/claude");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void doFilter_returns401_forClaudeApiRequest() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("secret");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/claude-api/events");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void doFilter_returns401_forInvalidToken() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("secret");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/claude");
        request.setCookies(new Cookie("claude_web_token", "bad-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ─── authenticated access with valid token ────────────────────────────────

    @Test
    void doFilter_passesThrough_forValidToken() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("secret");

        // Login first to obtain a token
        MockHttpServletRequest loginReq = new MockHttpServletRequest("POST", "/auth/login");
        loginReq.setContent("{\"password\":\"secret\"}".getBytes());
        loginReq.setContentType("application/json");
        MockHttpServletResponse loginResp = new MockHttpServletResponse();
        filter.doFilter(loginReq, loginResp, mock(FilterChain.class));

        Cookie tokenCookie = loginResp.getCookie("claude_web_token");
        assertThat(tokenCookie).isNotNull();

        // Use valid token for subsequent request
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/claude");
        request.setCookies(new Cookie("claude_web_token", tokenCookie.getValue()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_passesThrough_forApiWithValidToken() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("pass");

        // Login
        MockHttpServletRequest loginReq = new MockHttpServletRequest("POST", "/auth/login");
        loginReq.setContent("{\"password\":\"pass\"}".getBytes());
        loginReq.setContentType("application/json");
        MockHttpServletResponse loginResp = new MockHttpServletResponse();
        filter.doFilter(loginReq, loginResp, mock(FilterChain.class));
        String token = loginResp.getCookie("claude_web_token").getValue();

        // Access claude-api with token
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/claude-api/events");
        request.setCookies(new Cookie("claude_web_token", token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // ─── token invalidation ───────────────────────────────────────────────────

    @Test
    void invalidateToken_preventsSubsequentAccess() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("pass");

        // Login
        MockHttpServletRequest loginReq = new MockHttpServletRequest("POST", "/auth/login");
        loginReq.setContent("{\"password\":\"pass\"}".getBytes());
        loginReq.setContentType("application/json");
        MockHttpServletResponse loginResp = new MockHttpServletResponse();
        filter.doFilter(loginReq, loginResp, mock(FilterChain.class));
        String token = loginResp.getCookie("claude_web_token").getValue();

        // Invalidate
        filter.invalidateToken(token);

        // Subsequent request should fail
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/claude");
        request.setCookies(new Cookie("claude_web_token", token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ─── edge cases ───────────────────────────────────────────────────────────

    @Test
    void doFilter_noCookies_redirects() throws Exception {
        properties.setPasswordEnabled(true);
        properties.setPassword("secret");

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/thread/abc");
        // No cookies set
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }
}

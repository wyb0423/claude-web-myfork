package com.claude.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthFilterTest {

    private SessionStore sessionStore;
    private AuthFilter   filter;

    @BeforeEach
    void setUp() {
        sessionStore = new SessionStore();
        filter       = new AuthFilter(sessionStore, new ObjectMapper());
    }

    // ─── public paths pass through ───────────────────────────────────────────

    @Test
    void doFilter_allowsLoginPage() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/login");
        var res  = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    @Test
    void doFilter_allowsAuthEndpoints() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("POST", "/auth/login");
        var res  = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    @Test
    void doFilter_allowsStaticCss() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/css/style.css");
        var res  = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    @Test
    void doFilter_allowsStaticJs() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/js/core.js");
        var res  = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    // ─── unauthenticated — API returns 401 ───────────────────────────────────

    @Test
    void doFilter_returns401_forApiWithNoToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/api/claude");
        var res  = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void doFilter_returns401_forClaudeApiWithNoToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/claude-api/events");
        var res  = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void doFilter_returns401_forInvalidToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/api/claude");
        req.setCookies(new Cookie("claude_web_token", "bad-token"));
        var res  = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }

    // ─── unauthenticated — HTML redirects to /login ──────────────────────────

    @Test
    void doFilter_redirectsToLogin_forHtmlWithNoToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/");
        var res  = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getRedirectedUrl()).isEqualTo("/login");
    }

    @Test
    void doFilter_redirectsToLogin_forThreadPage() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/thread/abc123");
        var res  = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getRedirectedUrl()).isEqualTo("/login");
    }

    // ─── authenticated — valid token passes through ──────────────────────────

    @Test
    void doFilter_passesThrough_withValidToken() throws Exception {
        String token = sessionStore.createSession(1L, "alice", "user");

        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/api/claude");
        req.setCookies(new Cookie("claude_web_token", token));
        var res  = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(req.getAttribute(AuthFilter.CURRENT_USER)).isNotNull();
    }

    @Test
    void doFilter_setsCurrentUserAttribute() throws Exception {
        String token = sessionStore.createSession(2L, "bob", "admin");

        var req  = new MockHttpServletRequest("GET", "/api/claude");
        req.setCookies(new Cookie("claude_web_token", token));
        var res  = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        SessionStore.UserSession session =
                (SessionStore.UserSession) req.getAttribute(AuthFilter.CURRENT_USER);
        assertThat(session).isNotNull();
        assertThat(session.username()).isEqualTo("bob");
        assertThat(session.role()).isEqualTo("admin");
    }

    // ─── invalidated token is blocked ────────────────────────────────────────

    @Test
    void doFilter_blocks_afterSessionInvalidated() throws Exception {
        String token = sessionStore.createSession(3L, "charlie", "user");
        sessionStore.invalidate(token);

        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/api/claude");
        req.setCookies(new Cookie("claude_web_token", token));
        var res  = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(401);
    }

    // ─── no cookies at all ───────────────────────────────────────────────────

    @Test
    void doFilter_noCookies_redirectsForHtml() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req  = new MockHttpServletRequest("GET", "/");
        var res  = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getRedirectedUrl()).isEqualTo("/login");
    }
}

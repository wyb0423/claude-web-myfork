package com.claude.web.controller;

import com.claude.web.dto.PendingServerRequest;
import com.claude.web.service.AppServerProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudeApiControllerTest {

    private AppServerProcess mockProcess;
    private ClaudeApiController controller;

    @BeforeEach
    void setUp() {
        mockProcess = mock(AppServerProcess.class);
        controller = new ClaudeApiController(mockProcess);
    }

    // ─── rpcProxy ─────────────────────────────────────────────────────────────

    @Test
    void rpcProxy_returns403() {
        ResponseEntity<?> response = controller.rpcProxy(Map.of("method", "test"));
        assertThat(response.getStatusCodeValue()).isEqualTo(403);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rpcProxy_bodyContainsErrorAndMessage() {
        ResponseEntity<?> response = controller.rpcProxy(Map.of());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("error");
        assertThat(body).containsKey("message");
    }

    // ─── listPendingRequests ──────────────────────────────────────────────────

    @Test
    void listPendingRequests_returnsEmptyList() {
        when(mockProcess.listPendingServerRequests()).thenReturn(List.of());
        ResponseEntity<?> response = controller.listPendingRequests();
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listPendingRequests_returnsAllPending() {
        PendingServerRequest r1 = new PendingServerRequest(1, "method/a", Map.of(), "2026-01-01T00:00:00Z");
        PendingServerRequest r2 = new PendingServerRequest(2, "method/b", Map.of(), "2026-01-01T00:01:00Z");
        when(mockProcess.listPendingServerRequests()).thenReturn(List.of(r1, r2));

        ResponseEntity<?> response = controller.listPendingRequests();
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<?> data = (List<?>) body.get("data");
        assertThat(data).hasSize(2);
    }

    // ─── listMethods ─────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void listMethods_returnsExpectedMethods() {
        ResponseEntity<?> response = controller.listMethods();
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<String> data = (List<String>) body.get("data");
        assertThat(data).contains("chat", "abort", "list_sessions", "permission_response");
    }

    // ─── listNotificationMethods ──────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void listNotificationMethods_returnsExpectedNotifications() {
        ResponseEntity<?> response = controller.listNotificationMethods();
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<String> data = (List<String>) body.get("data");
        assertThat(data).contains("session_created", "stream_delta", "complete", "error", "permission_request");
    }

    // ─── respondToServerRequest – Claude Agent format (requestId) ────────────

    @Test
    void respondToServerRequest_withRequestId_allow_callsSendRaw() throws Exception {
        doNothing().when(mockProcess).sendRaw(anyString());

        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-uuid-1");
        body.put("allow", true);

        ResponseEntity<?> response = controller.respondToServerRequest(body);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRaw(argThat(json ->
            json.contains("permission_response") && json.contains("req-uuid-1") && json.contains("true")));
    }

    @Test
    void respondToServerRequest_withRequestId_deny_sendsFalse() throws Exception {
        doNothing().when(mockProcess).sendRaw(anyString());

        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-uuid-2");
        body.put("allow", false);

        ResponseEntity<?> response = controller.respondToServerRequest(body);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRaw(argThat(json -> json.contains("false")));
    }

    @Test
    void respondToServerRequest_withRequestId_andRememberEntry_sendsIt() throws Exception {
        doNothing().when(mockProcess).sendRaw(anyString());

        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-uuid-3");
        body.put("allow", true);
        body.put("rememberEntry", "always-allow-bash");

        ResponseEntity<?> response = controller.respondToServerRequest(body);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRaw(argThat(json -> json.contains("always-allow-bash")));
    }

    @Test
    void respondToServerRequest_withRequestId_sendRawThrows_returns502() throws Exception {
        doThrow(new RuntimeException("ws error")).when(mockProcess).sendRaw(anyString());

        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-fail");
        body.put("allow", true);

        ResponseEntity<?> response = controller.respondToServerRequest(body);

        assertThat(response.getStatusCodeValue()).isEqualTo(502);
    }

    // ─── respondToServerRequest – JSON-RPC format (id) ───────────────────────

    @Test
    void respondToServerRequest_withId_result_callsProcess() throws Exception {
        doNothing().when(mockProcess).respondToServerRequest(any());

        Map<String, Object> body = new HashMap<>();
        body.put("id", 42);
        body.put("result", Map.of("approved", true));

        ResponseEntity<?> response = controller.respondToServerRequest(body);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).respondToServerRequest(body);
    }

    @Test
    void respondToServerRequest_withId_processThrows_returns502() throws Exception {
        doThrow(new RuntimeException("no pending request")).when(mockProcess).respondToServerRequest(any());

        Map<String, Object> body = new HashMap<>();
        body.put("id", 999);
        body.put("result", Map.of());

        ResponseEntity<?> response = controller.respondToServerRequest(body);

        assertThat(response.getStatusCodeValue()).isEqualTo(502);
    }

    // ─── respondToServerRequest – invalid body ────────────────────────────────

    @Test
    void respondToServerRequest_withoutRequestIdOrId_returnsBadRequest() {
        ResponseEntity<?> response = controller.respondToServerRequest(Map.of("unknown", "field"));
        assertThat(response.getStatusCodeValue()).isEqualTo(400);
    }

    @Test
    void respondToServerRequest_emptyBody_returnsBadRequest() {
        ResponseEntity<?> response = controller.respondToServerRequest(Map.of());
        assertThat(response.getStatusCodeValue()).isEqualTo(400);
    }
}

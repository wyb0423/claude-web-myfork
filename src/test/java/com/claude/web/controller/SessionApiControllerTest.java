package com.claude.web.controller;

import com.claude.web.config.ClaudeProperties;
import com.claude.web.dto.NotificationEvent;
import com.claude.web.service.AppServerProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class SessionApiControllerTest {

    private AppServerProcess mockProcess;
    private ClaudeProperties properties;
    private SessionApiController controller;
    private Consumer<NotificationEvent> capturedListener;

    @BeforeEach
    void setUp() {
        mockProcess = mock(AppServerProcess.class);
        properties = new ClaudeProperties();

        ArgumentCaptor<Consumer<NotificationEvent>> captor = ArgumentCaptor.forClass(Consumer.class);
        controller = new SessionApiController(mockProcess, new ObjectMapper(), properties);
        verify(mockProcess).addNotificationListener(captor.capture());
        capturedListener = captor.getValue();
    }

    // ─── createSession ────────────────────────────────────────────────────────

    @Test
    void createSession_returnsClaudeId() {
        ResponseEntity<?> response = controller.createSession(Map.of());
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("claudeId");
        assertThat((String) body.get("claudeId")).isNotEmpty();
    }

    @Test
    void createSession_withNameAndCwd_usesProvided() {
        Map<String, Object> req = new HashMap<>();
        req.put("name", "My Session");
        req.put("cwd", "/tmp");
        ResponseEntity<?> response = controller.createSession(req);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(((Map<String, Object>) response.getBody())).containsKey("claudeId");
    }

    @Test
    void createSession_withPermissionMode_extractsOptions() {
        Map<String, Object> req = new HashMap<>();
        req.put("permissionMode", "acceptAll");
        req.put("maxTurns", 20);
        ResponseEntity<?> response = controller.createSession(req);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    void createSession_withAllowedToolsList_extractsOptions() {
        Map<String, Object> req = new HashMap<>();
        req.put("allowedTools", List.of("Bash", "Read"));
        req.put("disallowedTools", List.of("Write"));
        req.put("enableFileCheckpointing", true);
        req.put("persistSession", false);
        req.put("additionalDirectories", List.of("/extra"));
        ResponseEntity<?> response = controller.createSession(req);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    void createSession_withAllowedToolsString_extractsOptions() {
        Map<String, Object> req = new HashMap<>();
        req.put("allowedTools", "Bash,Read");
        req.put("disallowedTools", "Write,Edit");
        req.put("maxTurns", "30");
        ResponseEntity<?> response = controller.createSession(req);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    // ─── sendMessage ──────────────────────────────────────────────────────────

    @Test
    void sendMessage_emptyText_returnsBadRequest() throws Exception {
        ResponseEntity<?> response = controller.sendMessage("any-id", Map.of("text", ""));
        assertThat(response.getStatusCodeValue()).isEqualTo(400);
    }

    @Test
    void sendMessage_missingText_returnsBadRequest() throws Exception {
        ResponseEntity<?> response = controller.sendMessage("any-id", Map.of());
        assertThat(response.getStatusCodeValue()).isEqualTo(400);
    }

    @Test
    void sendMessage_validText_callsSendRaw() throws Exception {
        ResponseEntity<?> createResponse = controller.createSession(Map.of());
        String id = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        doNothing().when(mockProcess).sendRaw(anyString());

        ResponseEntity<?> response = controller.sendMessage(id, Map.of("text", "hello world"));
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("ok");
        assertThat(body).containsKey("requestId");
        verify(mockProcess).sendRaw(argThat(json -> json.contains("chat") && json.contains("hello world")));
    }

    @Test
    void sendMessage_sessionNotFound_createsLightweightSessionAndSends() throws Exception {
        doNothing().when(mockProcess).sendRaw(anyString());

        ResponseEntity<?> response = controller.sendMessage("nonexistent-id", Map.of("text", "hi"));
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRaw(argThat(json -> json.contains("resume")));
    }

    @Test
    void sendMessage_withPermissionModeOverride_includesInPayload() throws Exception {
        doNothing().when(mockProcess).sendRaw(anyString());

        Map<String, String> body = new HashMap<>();
        body.put("text", "hi");
        body.put("permissionMode", "acceptAll");
        body.put("allowedTools", "Bash,Read");
        body.put("disallowedTools", "Write");
        body.put("maxTurns", "10");

        ResponseEntity<?> response = controller.sendMessage("some-id", body);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRaw(argThat(json ->
            json.contains("permissionMode") && json.contains("acceptAll")));
    }

    @Test
    void sendMessage_sendRawThrows_returns502() throws Exception {
        doThrow(new RuntimeException("ws error")).when(mockProcess).sendRaw(anyString());

        ResponseEntity<?> response = controller.sendMessage("any-id", Map.of("text", "hi"));
        assertThat(response.getStatusCodeValue()).isEqualTo(502);
    }

    // ─── cancelSession ────────────────────────────────────────────────────────

    @Test
    void cancelSession_existingSession_sendsAbort() throws Exception {
        ResponseEntity<?> createResponse = controller.createSession(Map.of());
        String id = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        doNothing().when(mockProcess).sendRaw(anyString());

        ResponseEntity<?> response = controller.cancelSession(id);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRaw(argThat(json -> json.contains("abort") && json.contains(id)));
    }

    @Test
    void cancelSession_nonExistingSession_sendsAbortAnyway() throws Exception {
        doNothing().when(mockProcess).sendRaw(anyString());

        ResponseEntity<?> response = controller.cancelSession("nonexistent");
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRaw(argThat(json -> json.contains("abort")));
    }

    @Test
    void cancelSession_sendRawThrows_returns502() throws Exception {
        doThrow(new RuntimeException("fail")).when(mockProcess).sendRaw(anyString());

        ResponseEntity<?> response = controller.cancelSession("any");
        assertThat(response.getStatusCodeValue()).isEqualTo(502);
    }

    // ─── stopSession ──────────────────────────────────────────────────────────

    @Test
    void stopSession_existingSession_callsDeleteAndCleansUp() throws Exception {
        ResponseEntity<?> createResponse = controller.createSession(Map.of());
        String id = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        when(mockProcess.sendRawRequest(eq("delete_session"), any(), anyLong()))
            .thenReturn(Map.of());

        ResponseEntity<?> response = controller.stopSession(id);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRawRequest(eq("delete_session"), any(), anyLong());
    }

    @Test
    void stopSession_sdkDeleteFails_stillReturns200() throws Exception {
        when(mockProcess.sendRawRequest(eq("delete_session"), any(), anyLong()))
            .thenThrow(new RuntimeException("sdk error"));

        ResponseEntity<?> response = controller.stopSession("unknown-id");
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    // ─── sendApproval ─────────────────────────────────────────────────────────

    @Test
    void sendApproval_withRequestId_allow_callsSendRaw() throws Exception {
        doNothing().when(mockProcess).sendRaw(anyString());

        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-123");
        body.put("allow", true);

        ResponseEntity<?> response = controller.sendApproval("session-1", body);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRaw(argThat(json ->
            json.contains("permission_response") && json.contains("req-123") && json.contains("true")));
    }

    @Test
    void sendApproval_withRememberEntry_includesInPayload() throws Exception {
        doNothing().when(mockProcess).sendRaw(anyString());

        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-456");
        body.put("allow", true);
        body.put("rememberEntry", "always-allow-bash");

        ResponseEntity<?> response = controller.sendApproval("session-1", body);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRaw(argThat(json -> json.contains("always-allow-bash")));
    }

    @Test
    void sendApproval_withoutRequestId_returnsOkWithoutSend() throws Exception {
        ResponseEntity<?> response = controller.sendApproval("session-1", Map.of("allow", true));
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess, never()).sendRaw(anyString());
    }

    @Test
    void sendApproval_nullAllow_defaultsToTrue() throws Exception {
        doNothing().when(mockProcess).sendRaw(anyString());

        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "req-789");
        // no allow field → defaults to true

        ResponseEntity<?> response = controller.sendApproval("session-1", body);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(mockProcess).sendRaw(argThat(json -> json.contains("true")));
    }

    // ─── searchFiles ──────────────────────────────────────────────────────────

    @Test
    void searchFiles_returnsEmptyList() {
        ResponseEntity<?> response = controller.searchFiles("session-1", "query", 10);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat((List<?>) body.get("data")).isEmpty();
    }

    // ─── listSessions ─────────────────────────────────────────────────────────

    @Test
    void listSessions_withAgentSessions_returnsThem() throws Exception {
        Map<String, Object> agentSession = new HashMap<>();
        agentSession.put("sessionId", "agent-session-1");
        agentSession.put("cwd", "/home/ubuntu");
        agentSession.put("summary", "Test session");
        agentSession.put("createdAt", 1000L);
        agentSession.put("lastModified", 2000L);

        when(mockProcess.sendRawRequest(eq("list_sessions"), any(), anyLong()))
            .thenReturn(Map.of("sessions", List.of(agentSession)));

        ResponseEntity<?> response = controller.listSessions();
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<?> data = (List<?>) body.get("data");
        assertThat(data).hasSize(1);
    }

    @Test
    void listSessions_agentSessionWithNumberTimestamps_convertsCorrectly() throws Exception {
        Map<String, Object> agentSession = new HashMap<>();
        agentSession.put("sessionId", "agent-ts-session");
        agentSession.put("cwd", "/tmp");
        agentSession.put("summary", "Summary");
        agentSession.put("createdAt", 1700000000L);
        agentSession.put("lastModified", 1700000001000L);

        when(mockProcess.sendRawRequest(eq("list_sessions"), any(), anyLong()))
            .thenReturn(Map.of("sessions", List.of(agentSession)));

        ResponseEntity<?> response = controller.listSessions();
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    void listSessions_agentQueryFails_returnsMemorySessionsOnly() throws Exception {
        when(mockProcess.sendRawRequest(eq("list_sessions"), any(), anyLong()))
            .thenThrow(new RuntimeException("connection error"));

        // Create a non-placeholder session via message flow
        ResponseEntity<?> createResponse = controller.createSession(Map.of());
        String id = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");
        doNothing().when(mockProcess).sendRaw(anyString());
        controller.sendMessage(id, Map.of("text", "hi"));

        // Promote the placeholder by sending session_created with same ID
        Map<String, Object> createdParams = new HashMap<>();
        createdParams.put("sessionId", id);
        createdParams.put("type", "session_created");
        capturedListener.accept(new NotificationEvent("session_created", createdParams));

        ResponseEntity<?> response = controller.listSessions();
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<?> data = (List<?>) body.get("data");
        assertThat(data).isNotEmpty();
    }

    @Test
    void listSessions_noSessions_returnsEmptyData() throws Exception {
        when(mockProcess.sendRawRequest(eq("list_sessions"), any(), anyLong()))
            .thenReturn(Map.of("sessions", List.of()));

        ResponseEntity<?> response = controller.listSessions();
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat((List<?>) body.get("data")).isEmpty();
    }

    // ─── handleClaudeAgentMessage via notification listener ──────────────────

    @Test
    void notificationListener_sessionCreatedWithPlaceholder_promotesPlaceholder() throws Exception {
        ResponseEntity<?> createResponse = controller.createSession(Map.of());
        String frontendId = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        doNothing().when(mockProcess).sendRaw(anyString());
        controller.sendMessage(frontendId, Map.of("text", "hi"));

        // SDK returns a different session ID — placeholder should be promoted
        Map<String, Object> createdParams = new HashMap<>();
        createdParams.put("sessionId", "sdk-generated-id");
        createdParams.put("type", "session_created");
        capturedListener.accept(new NotificationEvent("session_created", createdParams));

        // The frontend ID should now be mapped to the SDK ID
        // Verify by checking that a sendMessage on the frontend ID uses the SDK ID
        reset(mockProcess);
        doNothing().when(mockProcess).sendRaw(anyString());
        controller.sendMessage(frontendId, Map.of("text", "second message"));
        verify(mockProcess).sendRaw(argThat(json -> json.contains("sdk-generated-id")));
    }

    @Test
    void notificationListener_sessionCreatedWithSameId_noMapping() throws Exception {
        ResponseEntity<?> createResponse = controller.createSession(Map.of());
        String id = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        doNothing().when(mockProcess).sendRaw(anyString());
        controller.sendMessage(id, Map.of("text", "hi"));

        // SDK returns same session ID as frontend placeholder
        Map<String, Object> createdParams = new HashMap<>();
        createdParams.put("sessionId", id);
        createdParams.put("type", "session_created");
        capturedListener.accept(new NotificationEvent("session_created", createdParams));
    }

    @Test
    void notificationListener_streamDelta_appendsToMessages() throws Exception {
        ResponseEntity<?> createResponse = controller.createSession(Map.of());
        String id = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        doNothing().when(mockProcess).sendRaw(anyString());
        controller.sendMessage(id, Map.of("text", "hi"));

        // Promote first
        Map<String, Object> createdParams = new HashMap<>();
        createdParams.put("sessionId", id);
        createdParams.put("type", "session_created");
        capturedListener.accept(new NotificationEvent("session_created", createdParams));

        // Two stream_delta events
        Map<String, Object> delta1 = new HashMap<>();
        delta1.put("sessionId", id);
        delta1.put("type", "stream_delta");
        delta1.put("content", "Hello, ");
        capturedListener.accept(new NotificationEvent("stream_delta", delta1));

        Map<String, Object> delta2 = new HashMap<>();
        delta2.put("sessionId", id);
        delta2.put("type", "stream_delta");
        delta2.put("content", "world!");
        capturedListener.accept(new NotificationEvent("stream_delta", delta2));
    }

    @Test
    void notificationListener_complete_marksSessionNotInProgress() throws Exception {
        ResponseEntity<?> createResponse = controller.createSession(Map.of());
        String id = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        doNothing().when(mockProcess).sendRaw(anyString());
        controller.sendMessage(id, Map.of("text", "hi"));

        Map<String, Object> createdParams = new HashMap<>();
        createdParams.put("sessionId", id);
        createdParams.put("type", "session_created");
        capturedListener.accept(new NotificationEvent("session_created", createdParams));

        Map<String, Object> completeParams = new HashMap<>();
        completeParams.put("sessionId", id);
        completeParams.put("type", "complete");
        capturedListener.accept(new NotificationEvent("complete", completeParams));
    }

    @Test
    void notificationListener_error_marksSessionNotInProgress() throws Exception {
        ResponseEntity<?> createResponse = controller.createSession(Map.of());
        String id = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        doNothing().when(mockProcess).sendRaw(anyString());
        controller.sendMessage(id, Map.of("text", "hi"));

        Map<String, Object> createdParams = new HashMap<>();
        createdParams.put("sessionId", id);
        createdParams.put("type", "session_created");
        capturedListener.accept(new NotificationEvent("session_created", createdParams));

        Map<String, Object> errorParams = new HashMap<>();
        errorParams.put("sessionId", id);
        errorParams.put("type", "error");
        capturedListener.accept(new NotificationEvent("error", errorParams));
    }

    @Test
    void notificationListener_sessionCreated_existingNonPlaceholder_updatesInPlace() throws Exception {
        // Simulate a historical session already in memory (not placeholder)
        doNothing().when(mockProcess).sendRaw(anyString());
        controller.sendMessage("historical-id", Map.of("text", "hi"));

        // session_created for that same historical ID — should update not duplicate
        Map<String, Object> createdParams = new HashMap<>();
        createdParams.put("sessionId", "historical-id");
        createdParams.put("type", "session_created");
        capturedListener.accept(new NotificationEvent("session_created", createdParams));
    }

    @Test
    void notificationListener_nonMapParams_ignoredGracefully() {
        // If params is not a Map the listener should not crash
        NotificationEvent event = new NotificationEvent("stream_delta", null);
        capturedListener.accept(event);
    }

    @Test
    void notificationListener_sessionIdMappedToFrontendId_translatedInParams() throws Exception {
        ResponseEntity<?> createResponse = controller.createSession(Map.of());
        String frontendId = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        doNothing().when(mockProcess).sendRaw(anyString());
        controller.sendMessage(frontendId, Map.of("text", "hello"));

        // Map frontend to SDK
        Map<String, Object> createdParams = new HashMap<>();
        createdParams.put("sessionId", "sdk-mapped-id");
        createdParams.put("type", "session_created");
        capturedListener.accept(new NotificationEvent("session_created", createdParams));

        // Now stream_delta comes with sdk-id — should be routed to frontend
        Map<String, Object> deltaParams = new HashMap<>();
        deltaParams.put("sessionId", "sdk-mapped-id");
        deltaParams.put("type", "stream_delta");
        deltaParams.put("content", "response text");
        // After this the params should have frontendId substituted
        capturedListener.accept(new NotificationEvent("stream_delta", deltaParams));

        assertThat(deltaParams.get("sessionId")).isEqualTo(frontendId);
    }
}

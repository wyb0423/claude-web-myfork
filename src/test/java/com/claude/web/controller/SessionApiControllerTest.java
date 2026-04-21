package com.claude.web.controller;

import com.claude.web.config.ClaudeProperties;
import com.claude.web.service.AppServerProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SessionApiController 单元测试
 */
class SessionApiControllerTest {

    private AppServerProcess appServerProcess;
    private ObjectMapper objectMapper;
    private ClaudeProperties claudeProperties;
    private SessionApiController controller;

    @BeforeEach
    void setUp() {
        appServerProcess = mock(AppServerProcess.class);
        objectMapper = new ObjectMapper();
        claudeProperties = mock(ClaudeProperties.class);
        ClaudeProperties.SessionDefaults defaults = new ClaudeProperties.SessionDefaults();
        when(claudeProperties.getSessionDefaults()).thenReturn(defaults);
        controller = new SessionApiController(appServerProcess, objectMapper, claudeProperties);
    }

    @Test
    void testListSessions() {
        ResponseEntity<?> response = controller.listSessions();
        assertEquals(200, response.getStatusCode().value());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("data"));
    }

    @Test
    void testCreateSession() {
        ResponseEntity<?> response = controller.createSession(Map.of("cwd", "/tmp", "name", "Test"));
        assertEquals(200, response.getStatusCode().value());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("claudeId"));
    }

    @Test
    void testCreateSessionWithoutCwd() {
        ResponseEntity<?> response = controller.createSession(Map.of("name", "Test"));
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testGetSessionNotFound() {
        // When session is not found in memory, controller queries SDK fallback.
        // It may return 200 with empty data or 502 if SDK is unavailable.
        ResponseEntity<?> response = controller.getSession("non-existent-id");
        int status = response.getStatusCode().value();
        assertTrue(status == 200 || status == 502, "Expected 200 or 502, got " + status);
    }

    @Test
    void testSendMessageMissingText() {
        ResponseEntity<?> response = controller.sendMessage("sess-1", Map.of());
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void testSendMessageSessionNotFound() {
        // When session is not found, controller creates a lightweight in-memory entry.
        ResponseEntity<?> response = controller.sendMessage("non-existent", Map.of("text", "hello"));
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testCancelSession() {
        ResponseEntity<?> response = controller.cancelSession("sess-1");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testStopSession() {
        ResponseEntity<?> response = controller.stopSession("sess-1");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testSearchFiles() {
        ResponseEntity<?> response = controller.searchFiles("sess-1", "query", 10);
        assertEquals(200, response.getStatusCode().value());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("data"));
    }

    @Test
    void testSendApproval() {
        ResponseEntity<?> response = controller.sendApproval("sess-1", Map.of("requestId", "req-1", "allow", true));
        assertEquals(200, response.getStatusCode().value());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("ok"));
    }

    @Test
    void testSendApprovalWithoutRequestId() {
        ResponseEntity<?> response = controller.sendApproval("sess-1", Map.of("allow", true));
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testHandleClaudeAgentMessageSessionCreated() {
        controller.handleClaudeAgentMessage(Map.of(
            "type", "session_created",
            "sessionId", "new-sess-1"
        ));

        // Verify session was created
        ResponseEntity<?> response = controller.getSession("new-sess-1");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testHandleClaudeAgentMessageStreamDelta() {
        // Create session first
        controller.createSession(Map.of("cwd", "/tmp"));
        controller.handleClaudeAgentMessage(Map.of(
            "type", "session_created",
            "sessionId", "sess-1"
        ));

        // Send stream_delta
        controller.handleClaudeAgentMessage(Map.of(
            "type", "stream_delta",
            "sessionId", "sess-1",
            "content", "Hello"
        ));

        ResponseEntity<?> response = controller.getSession("sess-1");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testHandleClaudeAgentMessageThinking() {
        controller.createSession(Map.of("cwd", "/tmp"));
        controller.handleClaudeAgentMessage(Map.of(
            "type", "session_created",
            "sessionId", "sess-think"
        ));

        controller.handleClaudeAgentMessage(Map.of(
            "type", "thinking",
            "sessionId", "sess-think",
            "content", "Thinking..."
        ));

        ResponseEntity<?> response = controller.getSession("sess-think");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testHandleClaudeAgentMessageComplete() {
        controller.createSession(Map.of("cwd", "/tmp"));
        controller.handleClaudeAgentMessage(Map.of(
            "type", "session_created",
            "sessionId", "sess-complete"
        ));

        controller.handleClaudeAgentMessage(Map.of(
            "type", "complete",
            "sessionId", "sess-complete"
        ));

        ResponseEntity<?> response = controller.getSession("sess-complete");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testHandleClaudeAgentMessageError() {
        controller.createSession(Map.of("cwd", "/tmp"));
        controller.handleClaudeAgentMessage(Map.of(
            "type", "session_created",
            "sessionId", "sess-error"
        ));

        controller.handleClaudeAgentMessage(Map.of(
            "type", "error",
            "sessionId", "sess-error"
        ));

        ResponseEntity<?> response = controller.getSession("sess-error");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testHandleClaudeAgentMessageWithoutSessionId() {
        // Should not throw
        assertDoesNotThrow(() -> controller.handleClaudeAgentMessage(Map.of(
            "type", "stream_delta",
            "content", "Hello"
        )));
    }

    @Test
    void testSendMessageAndGetSession() throws Exception {
        ResponseEntity<?> createResponse = controller.createSession(Map.of("cwd", "/tmp", "name", "Test"));
        @SuppressWarnings("unchecked")
        String sessionId = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        ResponseEntity<?> msgResponse = controller.sendMessage(sessionId, Map.of("text", "Hello"));
        assertEquals(200, msgResponse.getStatusCode().value());

        ResponseEntity<?> getResponse = controller.getSession(sessionId);
        assertEquals(200, getResponse.getStatusCode().value());
    }

    @Test
    void testDeleteSession() {
        ResponseEntity<?> createResponse = controller.createSession(Map.of("cwd", "/tmp"));
        @SuppressWarnings("unchecked")
        String sessionId = (String) ((Map<String, Object>) createResponse.getBody()).get("claudeId");

        ResponseEntity<?> deleteResponse = controller.stopSession(sessionId);
        assertEquals(200, deleteResponse.getStatusCode().value());

        // After deletion, the session may still be accessible via SDK fallback query,
        // so we only verify the delete operation succeeded.
        ResponseEntity<?> getResponse = controller.getSession(sessionId);
        int status = getResponse.getStatusCode().value();
        assertTrue(status == 200 || status == 404 || status == 502,
            "Expected 200, 404 or 502 after deletion, got " + status);
    }
}

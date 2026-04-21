package com.claude.web.controller;

import com.claude.web.service.AppServerProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ClaudeApiController 单元测试
 */
class ClaudeApiControllerTest {

    private AppServerProcess appServerProcess;
    private ClaudeApiController controller;

    @BeforeEach
    void setUp() {
        appServerProcess = mock(AppServerProcess.class);
        controller = new ClaudeApiController(appServerProcess);
    }

    @Test
    void testRpcBlockedInClaudeAgentMode() {
        ResponseEntity<?> response = controller.rpcProxy(Map.of("method", "someMethod"));
        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void testRpcMissingMethod() {
        ResponseEntity<?> response = controller.rpcProxy(Map.of());
        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void testListPendingRequests() {
        ResponseEntity<?> response = controller.listPendingRequests();
        assertEquals(200, response.getStatusCode().value());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("data"));
    }

    @Test
    void testListMethods() {
        ResponseEntity<?> response = controller.listMethods();
        assertEquals(200, response.getStatusCode().value());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("data"));
    }

    @Test
    void testListNotificationMethods() {
        ResponseEntity<?> response = controller.listNotificationMethods();
        assertEquals(200, response.getStatusCode().value());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("data"));
    }

    @Test
    void testRespondToServerRequest() throws Exception {
        ResponseEntity<?> response = controller.respondToServerRequest(Map.of(
            "requestId", "req-1",
            "allow", true
        ));
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void testRespondToServerRequestEmpty() throws Exception {
        ResponseEntity<?> response = controller.respondToServerRequest(Map.of());
        assertEquals(200, response.getStatusCode().value());
    }
}

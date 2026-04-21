package com.claude.web.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PendingServerRequest 单元测试
 */
class PendingServerRequestTest {

    @Test
    void testDefaultConstructor() {
        PendingServerRequest request = new PendingServerRequest();
        assertEquals(0, request.getId());
        assertNull(request.getMethod());
        assertNull(request.getParams());
        assertNull(request.getReceivedAtIso());
    }

    @Test
    void testParameterizedConstructor() {
        PendingServerRequest request = new PendingServerRequest(
            1, "test/method", Map.of("key", "value"), "2024-01-01T00:00:00Z"
        );
        assertEquals(1, request.getId());
        assertEquals("test/method", request.getMethod());
        assertNotNull(request.getParams());
        assertEquals("2024-01-01T00:00:00Z", request.getReceivedAtIso());
    }

    @Test
    void testSettersAndGetters() {
        PendingServerRequest request = new PendingServerRequest();
        request.setId(42);
        request.setMethod("item/tool/call");
        request.setParams(Map.of("tool", "test"));
        request.setReceivedAtIso("2024-06-01T12:00:00Z");

        assertEquals(42, request.getId());
        assertEquals("item/tool/call", request.getMethod());
        assertNotNull(request.getParams());
        assertEquals("2024-06-01T12:00:00Z", request.getReceivedAtIso());
    }
}

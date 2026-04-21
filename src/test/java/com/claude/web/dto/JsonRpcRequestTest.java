package com.claude.web.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonRpcRequest 单元测试
 */
class JsonRpcRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testDefaultConstructor() {
        JsonRpcRequest request = new JsonRpcRequest();
        assertEquals("2.0", request.getJsonrpc());
        assertNull(request.getId());
        assertNull(request.getMethod());
        assertNull(request.getParams());
    }

    @Test
    void testParameterizedConstructor() {
        JsonRpcRequest request = new JsonRpcRequest(1, "testMethod", Map.of("key", "value"));
        assertEquals("2.0", request.getJsonrpc());
        assertEquals(1, request.getId());
        assertEquals("testMethod", request.getMethod());
        assertNotNull(request.getParams());
    }

    @Test
    void testSettersAndGetters() {
        JsonRpcRequest request = new JsonRpcRequest();
        request.setId(42);
        request.setMethod("initialize");
        request.setParams(Map.of("clientInfo", "test"));
        request.setJsonrpc("2.0");

        assertEquals(42, request.getId());
        assertEquals("initialize", request.getMethod());
        assertEquals("2.0", request.getJsonrpc());
    }

    @Test
    void testSerialization() throws Exception {
        JsonRpcRequest request = new JsonRpcRequest(1, "test", Map.of("a", 1));
        String json = objectMapper.writeValueAsString(request);

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"method\":\"test\""));
        assertTrue(json.contains("\"params\""));
    }

    @Test
    void testDeserialization() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test\",\"params\":{\"a\":1}}";
        JsonRpcRequest request = objectMapper.readValue(json, JsonRpcRequest.class);

        assertEquals("2.0", request.getJsonrpc());
        assertEquals(1, request.getId());
        assertEquals("test", request.getMethod());
    }

    @Test
    void testNullParamsNotSerialized() throws Exception {
        JsonRpcRequest request = new JsonRpcRequest(1, "test", null);
        String json = objectMapper.writeValueAsString(request);
        assertFalse(json.contains("\"params\""));
    }
}

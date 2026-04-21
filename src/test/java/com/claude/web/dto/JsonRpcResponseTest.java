package com.claude.web.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonRpcResponse 单元测试
 */
class JsonRpcResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testDefaultConstructor() {
        JsonRpcResponse response = new JsonRpcResponse();
        assertEquals("2.0", response.getJsonrpc());
        assertNull(response.getId());
        assertNull(response.getResult());
        assertNull(response.getError());
        assertNull(response.getMethod());
        assertNull(response.getParams());
    }

    @Test
    void testErrorObject() {
        JsonRpcResponse.JsonRpcError error = new JsonRpcResponse.JsonRpcError(-32000, "Test error");
        assertEquals(-32000, error.getCode());
        assertEquals("Test error", error.getMessage());

        error.setCode(-32600);
        error.setMessage("Invalid request");
        assertEquals(-32600, error.getCode());
        assertEquals("Invalid request", error.getMessage());
    }

    @Test
    void testSettersAndGetters() {
        JsonRpcResponse response = new JsonRpcResponse();
        response.setId(1);
        response.setResult(Map.of("status", "ok"));
        response.setMethod("notification");
        response.setParams(Map.of("data", "test"));

        assertEquals(1, response.getId());
        assertNotNull(response.getResult());
        assertEquals("notification", response.getMethod());
        assertNotNull(response.getParams());
    }

    @Test
    void testSerializationWithResult() throws Exception {
        JsonRpcResponse response = new JsonRpcResponse();
        response.setId(1);
        response.setResult(Map.of("status", "ok"));

        String json = objectMapper.writeValueAsString(response);
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"result\""));
    }

    @Test
    void testSerializationWithError() throws Exception {
        JsonRpcResponse response = new JsonRpcResponse();
        response.setId(1);
        JsonRpcResponse.JsonRpcError error = new JsonRpcResponse.JsonRpcError(-32000, "Server error");
        response.setError(error);

        String json = objectMapper.writeValueAsString(response);
        assertTrue(json.contains("\"error\""));
        assertTrue(json.contains("\"code\":-32000"));
        assertTrue(json.contains("\"message\":\"Server error\""));
    }

    @Test
    void testDeserialization() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"ok\"}}";
        JsonRpcResponse response = objectMapper.readValue(json, JsonRpcResponse.class);

        assertEquals(1, response.getId());
        assertNotNull(response.getResult());
        assertNull(response.getError());
    }

    @Test
    void testDeserializationWithError() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"Error\"}}";
        JsonRpcResponse response = objectMapper.readValue(json, JsonRpcResponse.class);

        assertNotNull(response.getError());
        assertEquals(-32000, response.getError().getCode());
        assertEquals("Error", response.getError().getMessage());
    }

    @Test
    void testNotificationFormat() throws Exception {
        // Server notifications use method+params without id
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"test/notification\",\"params\":{\"data\":1}}";
        JsonRpcResponse response = objectMapper.readValue(json, JsonRpcResponse.class);

        assertNull(response.getId());
        assertEquals("test/notification", response.getMethod());
        assertNotNull(response.getParams());
    }
}

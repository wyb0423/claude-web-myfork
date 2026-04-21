package com.claude.web.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ServerRequestReply 单元测试
 */
class ServerRequestReplyTest {

    @Test
    void testDefaultConstructor() {
        ServerRequestReply reply = new ServerRequestReply();
        assertNull(reply.getResult());
        assertNull(reply.getError());
    }

    @Test
    void testJsonRpcError() {
        ServerRequestReply.JsonRpcError error = new ServerRequestReply.JsonRpcError(-32000, "Test error");
        assertEquals(-32000, error.getCode());
        assertEquals("Test error", error.getMessage());

        error.setCode(-32600);
        error.setMessage("Invalid Request");
        assertEquals(-32600, error.getCode());
        assertEquals("Invalid Request", error.getMessage());
    }

    @Test
    void testSettersAndGetters() {
        ServerRequestReply reply = new ServerRequestReply();
        reply.setResult(java.util.Map.of("approved", true));

        ServerRequestReply.JsonRpcError error = new ServerRequestReply.JsonRpcError(-32000, "Error");
        reply.setError(error);

        assertNotNull(reply.getResult());
        assertNotNull(reply.getError());
        assertEquals(-32000, reply.getError().getCode());
    }

    @Test
    void testErrorOnly() {
        ServerRequestReply reply = new ServerRequestReply();
        reply.setError(new ServerRequestReply.JsonRpcError(-32602, "Invalid params"));

        assertNull(reply.getResult());
        assertNotNull(reply.getError());
        assertEquals(-32602, reply.getError().getCode());
    }
}

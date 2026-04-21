package com.claude.web.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RpcProxyRequest 单元测试
 */
class RpcProxyRequestTest {

    @Test
    void testDefaultConstructor() {
        RpcProxyRequest request = new RpcProxyRequest();
        assertNull(request.getMethod());
        assertNull(request.getParams());
    }

    @Test
    void testParameterizedConstructor() {
        RpcProxyRequest request = new RpcProxyRequest("testMethod", Map.of("key", "value"));
        assertEquals("testMethod", request.getMethod());
        assertNotNull(request.getParams());
    }

    @Test
    void testSettersAndGetters() {
        RpcProxyRequest request = new RpcProxyRequest();
        request.setMethod("thread/list");
        request.setParams(Map.of());

        assertEquals("thread/list", request.getMethod());
        assertNotNull(request.getParams());
    }

    @Test
    void testNullParams() {
        RpcProxyRequest request = new RpcProxyRequest("method", null);
        assertEquals("method", request.getMethod());
        assertNull(request.getParams());
    }
}

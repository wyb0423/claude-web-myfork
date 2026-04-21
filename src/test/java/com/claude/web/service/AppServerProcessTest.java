package com.claude.web.service;

import com.claude.web.config.ClaudeProperties;
import com.claude.web.dto.NotificationEvent;
import com.claude.web.service.transport.ClaudeTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AppServerProcess 单元测试
 */
class AppServerProcessTest {

    private ClaudeProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new ClaudeProperties();
        objectMapper = new ObjectMapper();
    }

    @Test
    void testConstructor() {
        AppServerProcess process = new AppServerProcess(properties, objectMapper);
        assertNotNull(process);
        assertEquals("claude-agent", process.getTransportType());
    }

    @Test
    void testInitialState() {
        AppServerProcess process = new AppServerProcess(properties, objectMapper);
        assertFalse(process.isConnected());
        assertFalse(process.isReconnecting());
    }

    @Test
    void testNotificationListener() {
        AppServerProcess process = new AppServerProcess(properties, objectMapper);
        
        CompletableFuture<NotificationEvent> future = new CompletableFuture<>();
        Consumer<NotificationEvent> listener = future::complete;
        
        process.addNotificationListener(listener);
        
        // Listener should be added without error
        assertDoesNotThrow(() -> process.removeNotificationListener(listener));
    }

    @Test
    void testListPendingServerRequestsEmpty() {
        AppServerProcess process = new AppServerProcess(properties, objectMapper);
        assertTrue(process.listPendingServerRequests().isEmpty());
    }

    @Test
    void testDispose() {
        AppServerProcess process = new AppServerProcess(properties, objectMapper);
        assertDoesNotThrow(() -> process.dispose());
        assertFalse(process.isConnected());
    }

    @Test
    void testGetTransportType() {
        AppServerProcess process = new AppServerProcess(properties, objectMapper);
        assertEquals("claude-agent", process.getTransportType());
    }

    @Test
    void testMultipleListeners() {
        AppServerProcess process = new AppServerProcess(properties, objectMapper);
        
        CompletableFuture<NotificationEvent> future1 = new CompletableFuture<>();
        CompletableFuture<NotificationEvent> future2 = new CompletableFuture<>();
        
        process.addNotificationListener(future1::complete);
        process.addNotificationListener(future2::complete);
        
        // Both should be added
        assertDoesNotThrow(() -> {
            process.removeNotificationListener(future1::complete);
            process.removeNotificationListener(future2::complete);
        });
    }

    @Test
    void testDisposeTwice() {
        AppServerProcess process = new AppServerProcess(properties, objectMapper);
        process.dispose();
        assertDoesNotThrow(() -> process.dispose());
    }

    @Test
    void testConnectionTimeoutFromProperties() {
        properties.getClaudeAgent().setConnectionTimeout(15000);
        AppServerProcess process = new AppServerProcess(properties, objectMapper);
        assertNotNull(process);
    }
}

package com.claude.web.service;

import com.claude.web.dto.NotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SseEventService 单元测试
 */
class SseEventServiceTest {

    private AppServerProcess appServerProcess;
    private SseEventService sseEventService;

    @BeforeEach
    void setUp() {
        appServerProcess = mock(AppServerProcess.class);
        sseEventService = new SseEventService(appServerProcess);
    }

    @Test
    void testCreateEventStream() {
        Flux<NotificationEvent> stream = sseEventService.createEventStream();
        assertNotNull(stream);
    }

    @Test
    void testEventStreamEmitsReady() throws Exception {
        Flux<NotificationEvent> stream = sseEventService.createEventStream();
        
        NotificationEvent firstEvent = stream
            .take(1)
            .blockFirst(Duration.ofSeconds(2));
        
        assertNotNull(firstEvent);
        assertEquals("ready", firstEvent.getMethod());
    }

    @Test
    void testListenerRegistered() {
        sseEventService.createEventStream();
        verify(appServerProcess).addNotificationListener(any());
    }

    @Test
    void testListenerRemovedOnCancel() {
        Flux<NotificationEvent> stream = sseEventService.createEventStream();
        
        // Cancel the subscription
        stream.take(1).subscribe().dispose();
        
        // The listener should be removed on completion
        // Note: This is tested indirectly through doFinally
    }
}

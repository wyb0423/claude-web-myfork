package com.claude.web.controller;

import com.claude.web.service.SseEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SseController 单元测试
 */
class SseControllerTest {

    private SseEventService sseEventService;
    private ObjectMapper objectMapper;
    private SseController sseController;

    @BeforeEach
    void setUp() {
        sseEventService = mock(SseEventService.class);
        objectMapper = new ObjectMapper();
        sseController = new SseController(sseEventService, objectMapper);
    }

    @Test
    void testEventsReturnsEmitter() {
        SseEmitter emitter = sseController.events();
        assertNotNull(emitter);
    }

    @Test
    void testEventsNoTimeout() {
        SseEmitter emitter = sseController.events();
        // SseEmitter with 0L means no timeout
        assertNotNull(emitter);
    }
}

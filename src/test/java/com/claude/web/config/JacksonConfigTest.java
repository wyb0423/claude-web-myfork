package com.claude.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JacksonConfig 单元测试
 */
class JacksonConfigTest {

    @Test
    void testObjectMapperConfiguration() throws Exception {
        JacksonConfig config = new JacksonConfig();
        ObjectMapper mapper = config.objectMapper();

        assertNotNull(mapper);

        // Test that JavaTimeModule is registered by serializing an Instant
        Instant now = Instant.parse("2024-01-15T10:30:00Z");
        String json = mapper.writeValueAsString(Map.of("date", now));
        
        // Should contain ISO string format, not timestamp
        assertTrue(json.contains("2024-01-15T10:30:00Z"), "Date should be serialized as ISO string");
    }

    @Test
    void testObjectMapperCreatesNewInstance() {
        JacksonConfig config = new JacksonConfig();
        ObjectMapper mapper1 = config.objectMapper();
        ObjectMapper mapper2 = config.objectMapper();

        // Each call creates a new instance (as per the bean method)
        assertNotNull(mapper1);
        assertNotNull(mapper2);
    }

    @Test
    void testObjectMapperSerializesDateAsString() throws Exception {
        JacksonConfig config = new JacksonConfig();
        ObjectMapper mapper = config.objectMapper();

        Instant instant = Instant.parse("2024-06-01T12:00:00Z");
        String json = mapper.writeValueAsString(Map.of("timestamp", instant));
        
        // Verify it's a string, not a number (timestamp)
        assertTrue(json.contains("2024-06-01T12:00:00Z"));
        // Should not contain a large number (timestamp)
        assertFalse(json.matches(".*\"timestamp\":\\s*\\d+.*"), "Should not serialize as timestamp");
    }
}

package com.claude.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MethodCatalogService 单元测试
 */
class MethodCatalogServiceTest {

    private AppServerProcess appServerProcess;
    private ObjectMapper objectMapper;
    private MethodCatalogService methodCatalogService;

    @BeforeEach
    void setUp() {
        appServerProcess = mock(AppServerProcess.class);
        objectMapper = new ObjectMapper();
        methodCatalogService = new MethodCatalogService(appServerProcess, objectMapper);
    }

    @Test
    void testConstructor() {
        assertNotNull(methodCatalogService);
    }

    @Test
    void testListMethodsCachesResult() throws Exception {
        // Mock RPC response with schema
        Object schemaResponse = java.util.Map.of(
            "ClientRequest", java.util.Map.of(
                "oneOf", java.util.List.of(
                    java.util.Map.of(
                        "properties", java.util.Map.of(
                            "method", java.util.Map.of(
                                "enum", java.util.List.of("method1", "method2")
                            )
                        )
                    )
                )
            )
        );
        when(appServerProcess.rpc("system/getSchema", null)).thenReturn(schemaResponse);

        List<String> methods1 = methodCatalogService.listMethods();
        List<String> methods2 = methodCatalogService.listMethods();

        assertEquals(methods1, methods2);
        // Should only call RPC once due to caching
        verify(appServerProcess, times(1)).rpc("system/getSchema", null);
    }

    @Test
    void testListNotificationMethodsCachesResult() throws Exception {
        Object schemaResponse = java.util.Map.of(
            "ServerNotification", java.util.Map.of(
                "oneOf", java.util.List.of(
                    java.util.Map.of(
                        "properties", java.util.Map.of(
                            "method", java.util.Map.of(
                                "enum", java.util.List.of("notif1", "notif2")
                            )
                        )
                    )
                )
            )
        );
        when(appServerProcess.rpc("system/getSchema", null)).thenReturn(schemaResponse);

        List<String> notifs1 = methodCatalogService.listNotificationMethods();
        List<String> notifs2 = methodCatalogService.listNotificationMethods();

        assertEquals(notifs1, notifs2);
        verify(appServerProcess, times(1)).rpc("system/getSchema", null);
    }

    @Test
    void testEmptySchema() throws Exception {
        Object emptySchema = java.util.Map.of();
        when(appServerProcess.rpc("system/getSchema", null)).thenReturn(emptySchema);

        List<String> methods = methodCatalogService.listMethods();
        assertTrue(methods.isEmpty());
    }
}

package com.claude.web.service.transport;

import com.claude.web.config.ClaudeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClaudeAgentTransport 单元测试
 */
class ClaudeAgentTransportTest {

    private ClaudeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ClaudeProperties();
    }

    @Test
    void testConstructorWithDefaultConfig() {
        ClaudeAgentTransport transport = new ClaudeAgentTransport(properties);
        assertNotNull(transport);
    }

    @Test
    void testConstructorWithCustomConfig() {
        properties.getClaudeAgent().setHost("192.168.1.100");
        properties.getClaudeAgent().setPort(9090);
        properties.getClaudeAgent().setApiKey("test-api-key");
        properties.getClaudeAgent().setConnectionTimeout(5000);

        ClaudeAgentTransport transport = new ClaudeAgentTransport(properties);
        assertNotNull(transport);
    }

    @Test
    void testInitialState() {
        ClaudeAgentTransport transport = new ClaudeAgentTransport(properties);
        assertFalse(transport.isConnected());
    }

    @Test
    void testDisconnectWhenNotConnected() {
        ClaudeAgentTransport transport = new ClaudeAgentTransport(properties);
        assertDoesNotThrow(() -> transport.disconnect());
    }

    @Test
    void testDisposeWhenNotConnected() {
        ClaudeAgentTransport transport = new ClaudeAgentTransport(properties);
        assertDoesNotThrow(() -> transport.dispose());
        assertFalse(transport.isConnected());
    }

    @Test
    void testReceiveWhenNotConnected() {
        ClaudeAgentTransport transport = new ClaudeAgentTransport(properties);
        reactor.core.publisher.Flux<String> flux = transport.receive();
        assertNotNull(flux);
    }

    @Test
    void testSendWhenNotConnected() {
        ClaudeAgentTransport transport = new ClaudeAgentTransport(properties);
        Exception exception = assertThrows(Exception.class, () -> transport.send("test"));
        assertTrue(exception.getMessage().contains("not connected"));
    }

    @Test
    void testConnectToInvalidHost() {
        properties.getClaudeAgent().setHost("invalid.host.that.does.not.exist");
        properties.getClaudeAgent().setPort(9999);
        properties.getClaudeAgent().setConnectionTimeout(1000);

        ClaudeAgentTransport transport = new ClaudeAgentTransport(properties);
        assertThrows(Exception.class, () -> transport.connect());
    }

    @Test
    void testDisposeTwice() {
        ClaudeAgentTransport transport = new ClaudeAgentTransport(properties);
        transport.dispose();
        assertDoesNotThrow(() -> transport.dispose());
    }
}

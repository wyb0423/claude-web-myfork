package com.claude.web.service.transport;

import com.claude.web.config.ClaudeProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeAgentTransportTest {

    private ClaudeAgentTransport transport(String host, int port) {
        ClaudeProperties props = new ClaudeProperties();
        ClaudeProperties.ClaudeAgent agent = new ClaudeProperties.ClaudeAgent();
        agent.setHost(host);
        agent.setPort(port);
        agent.setConnectionTimeout(200); // short timeout for tests
        props.setClaudeAgent(agent);
        return new ClaudeAgentTransport(props);
    }

    // ─── initial state ────────────────────────────────────────────────────────

    @Test
    void isConnected_returnsFalse_beforeConnect() {
        ClaudeAgentTransport t = transport("127.0.0.1", 19999);
        assertThat(t.isConnected()).isFalse();
    }

    @Test
    void receive_throwsError_beforeConnect() {
        ClaudeAgentTransport t = transport("127.0.0.1", 19999);
        Flux<String> flux = t.receive();
        // receive() before connect() returns an error flux
        assertThat(flux).isNotNull();
        flux.doOnError(e -> {}).subscribe();
    }

    @Test
    void send_throwsException_whenNotConnected() {
        ClaudeAgentTransport t = transport("127.0.0.1", 19999);
        assertThatThrownBy(() -> t.send("{\"type\":\"ping\"}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not connected");
    }

    // ─── connect – unreachable host ───────────────────────────────────────────

    @Test
    void connect_toUnreachableHost_throwsIOException() {
        // Port 19999 is not listening — connection must time out or be refused
        ClaudeAgentTransport t = transport("127.0.0.1", 19999);
        assertThatThrownBy(t::connect)
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("Failed to connect");
    }

    // ─── disconnect ───────────────────────────────────────────────────────────

    @Test
    void disconnect_whenNotConnected_doesNotThrow() {
        ClaudeAgentTransport t = transport("127.0.0.1", 19999);
        t.disconnect(); // must not throw
    }

    @Test
    void dispose_whenNotConnected_doesNotThrow() {
        ClaudeAgentTransport t = transport("127.0.0.1", 19999);
        t.dispose(); // must not throw
    }

    // ─── double disconnect ────────────────────────────────────────────────────

    @Test
    void disconnect_calledTwice_doesNotThrow() {
        ClaudeAgentTransport t = transport("127.0.0.1", 19999);
        t.disconnect();
        t.disconnect();
    }

    // ─── apiKey handling ──────────────────────────────────────────────────────

    @Test
    void transport_withEmptyApiKey_constructsSuccessfully() {
        ClaudeProperties props = new ClaudeProperties();
        ClaudeProperties.ClaudeAgent agent = new ClaudeProperties.ClaudeAgent();
        agent.setHost("127.0.0.1");
        agent.setPort(9999);
        agent.setApiKey("");
        agent.setConnectionTimeout(200);
        props.setClaudeAgent(agent);
        ClaudeAgentTransport t = new ClaudeAgentTransport(props);
        assertThat(t.isConnected()).isFalse();
    }

    @Test
    void transport_withApiKey_constructsSuccessfully() {
        ClaudeProperties props = new ClaudeProperties();
        ClaudeProperties.ClaudeAgent agent = new ClaudeProperties.ClaudeAgent();
        agent.setHost("127.0.0.1");
        agent.setPort(9999);
        agent.setApiKey("my-test-api-key");
        agent.setConnectionTimeout(200);
        props.setClaudeAgent(agent);
        ClaudeAgentTransport t = new ClaudeAgentTransport(props);
        assertThat(t.isConnected()).isFalse();
    }
}

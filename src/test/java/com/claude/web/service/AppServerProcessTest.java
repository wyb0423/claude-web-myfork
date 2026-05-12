package com.claude.web.service;

import com.claude.web.config.ClaudeProperties;
import com.claude.web.dto.NotificationEvent;
import com.claude.web.service.transport.ClaudeAgentTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AppServerProcessTest {

    private AppServerProcess process;
    private MockedConstruction<ClaudeAgentTransport> mockedTransport;

    @AfterEach
    void tearDown() {
        if (process != null) {
            process.dispose();
        }
        if (mockedTransport != null) {
            mockedTransport.close();
        }
    }

    private AppServerProcess createProcess(ClaudeAgentTransport mockTransport) {
        return new com.claude.web.service.AppServerProcess(
            defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper()
        );
    }

    private ClaudeProperties defaultProperties() {
        ClaudeProperties props = new ClaudeProperties();
        ClaudeProperties.ClaudeAgent agent = new ClaudeProperties.ClaudeAgent();
        agent.setHost("127.0.0.1");
        agent.setPort(8011);
        agent.setConnectionTimeout(1000);
        props.setClaudeAgent(agent);
        return props;
    }

    // ─── constructor / initial state ──────────────────────────────────────────

    @Test
    void isConnected_returnsFalse_beforeConnect() {
        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) ->
                when(mock.receive()).thenReturn(Flux.never()))) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
            assertThat(process.isConnected()).isFalse();
        }
    }

    @Test
    void isReconnecting_returnsFalse_initially() {
        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) ->
                when(mock.receive()).thenReturn(Flux.never()))) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
            assertThat(process.isReconnecting()).isFalse();
        }
    }

    @Test
    void getTransportType_returnsClaudeAgent() {
        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) ->
                when(mock.receive()).thenReturn(Flux.never()))) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
            assertThat(process.getTransportType()).isEqualTo("claude-agent");
        }
    }

    // ─── addNotificationListener / removeNotificationListener ────────────────

    @Test
    void addAndRemoveNotificationListener_workRoundtrip() {
        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) ->
                when(mock.receive()).thenReturn(Flux.never()))) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
            Consumer<NotificationEvent> listener = event -> {};
            process.addNotificationListener(listener);
            process.removeNotificationListener(listener);
        }
    }

    // ─── listPendingServerRequests ────────────────────────────────────────────

    @Test
    void listPendingServerRequests_returnsEmptyInitially() {
        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) ->
                when(mock.receive()).thenReturn(Flux.never()))) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
            assertThat(process.listPendingServerRequests()).isEmpty();
        }
    }

    // ─── sendRaw – not connected ──────────────────────────────────────────────

    @Test
    void sendRaw_whenNotConnected_throwsException() {
        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) -> {
            when(mock.receive()).thenReturn(Flux.never());
            doThrow(new java.io.IOException("no server")).when(mock).connect();
        })) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
            assertThatThrownBy(() -> process.sendRaw("{}"))
                .isInstanceOf(Exception.class);
        }
    }

    // ─── notification listener forwarding ────────────────────────────────────

    @Test
    void notificationListener_receivedAfterConnect() throws Exception {
        Sinks.Many<String> messageSink = Sinks.many().multicast().onBackpressureBuffer();

        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) -> {
            when(mock.receive()).thenReturn(messageSink.asFlux());
            doAnswer(inv -> {
                // simulate successful connect
                return null;
            }).when(mock).connect();
            when(mock.isConnected()).thenReturn(true);
        })) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());

            List<NotificationEvent> received = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            process.addNotificationListener(event -> {
                received.add(event);
                latch.countDown();
            });

            // Trigger connect by calling connectRemote
            process.connectRemote();

            // Emit a JSON-RPC notification (no id, has method)
            String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"stream_delta\",\"params\":{\"sessionId\":\"s1\",\"content\":\"hi\"}}";
            messageSink.tryEmitNext(notification);

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).hasSize(1);
            assertThat(received.get(0).getMethod()).isEqualTo("stream_delta");
        }
    }

    @Test
    void notificationListener_claudeAgentProtocolMessage_forwarded() throws Exception {
        Sinks.Many<String> messageSink = Sinks.many().multicast().onBackpressureBuffer();

        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) -> {
            when(mock.receive()).thenReturn(messageSink.asFlux());
            doAnswer(inv -> null).when(mock).connect();
            when(mock.isConnected()).thenReturn(true);
        })) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());

            List<NotificationEvent> received = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            process.addNotificationListener(event -> {
                received.add(event);
                latch.countDown();
            });

            process.connectRemote();

            // Claude Agent protocol message (not JSON-RPC)
            String agentMsg = "{\"type\":\"session_created\",\"sessionId\":\"test-session\"}";
            messageSink.tryEmitNext(agentMsg);

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get(0).getMethod()).isEqualTo("session_created");
        }
    }

    @Test
    void notificationListener_jsonRpcResponse_completesRequest() throws Exception {
        Sinks.Many<String> messageSink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicReference<String> capturedSend = new AtomicReference<>();

        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) -> {
            when(mock.receive()).thenReturn(messageSink.asFlux());
            doAnswer(inv -> null).when(mock).connect();
            when(mock.isConnected()).thenReturn(true);
            doAnswer(inv -> {
                capturedSend.set(inv.getArgument(0));
                return null;
            }).when(mock).send(anyString());
        })) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
            process.connectRemote();

            // Start rpc call in background
            java.util.concurrent.Future<Object> rpcFuture = java.util.concurrent.Executors
                .newSingleThreadExecutor()
                .submit(() -> process.rpc("test/method", Map.of()));

            // Wait for the send to happen
            for (int i = 0; i < 50 && capturedSend.get() == null; i++) {
                Thread.sleep(50);
            }
            assertThat(capturedSend.get()).isNotNull();

            // Extract the id from the sent message
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> sentMsg = om.readValue(capturedSend.get(), Map.class);
            int id = ((Number) sentMsg.get("id")).intValue();

            // Send back the response
            String response = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"ok\":true}}";
            messageSink.tryEmitNext(response);

            Object result = rpcFuture.get(3, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(Map.class);
        }
    }

    // ─── dispose ─────────────────────────────────────────────────────────────

    @Test
    void dispose_doesNotThrow() {
        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) ->
                when(mock.receive()).thenReturn(Flux.never()))) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
            process.dispose();
            process = null; // prevent double-dispose in tearDown
        }
    }

    @Test
    void dispose_completesPendingRequestsExceptionally() throws Exception {
        Sinks.Many<String> messageSink = Sinks.many().multicast().onBackpressureBuffer();

        try (MockedConstruction<ClaudeAgentTransport> mc = mockConstruction(ClaudeAgentTransport.class, (mock, ctx) -> {
            when(mock.receive()).thenReturn(messageSink.asFlux());
            doAnswer(inv -> null).when(mock).connect();
            when(mock.isConnected()).thenReturn(true);
            doNothing().when(mock).send(anyString());
        })) {
            process = new AppServerProcess(defaultProperties(), new com.fasterxml.jackson.databind.ObjectMapper());
            process.connectRemote();

            java.util.concurrent.Future<Object> rpcFuture = java.util.concurrent.Executors
                .newSingleThreadExecutor()
                .submit(() -> process.rpc("test/method", Map.of()));

            // Give the rpc call time to register
            Thread.sleep(100);

            process.dispose();
            process = null;

            try {
                rpcFuture.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("stopped");
            }
        }
    }
}

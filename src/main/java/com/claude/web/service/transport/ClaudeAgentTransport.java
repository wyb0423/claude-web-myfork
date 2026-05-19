package com.claude.web.service.transport;

import com.claude.web.config.ClaudeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket transport for connecting to claude-agent Node.js service.
 * Uses API Key authentication (Authorization: Bearer <apiKey>).
 */
public class ClaudeAgentTransport implements ClaudeTransport {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeAgentTransport.class);
    private static final int KEEP_ALIVE_INTERVAL_SECONDS = 30;
    private static final int PING_TIMEOUT_SECONDS = 5;

    private final String uri;
    private final String apiKey;
    private final int connectionTimeout;
    private final HttpClient httpClient;

    private volatile WebsocketOutbound outbound;
    private volatile boolean connected = false;
    private volatile boolean stopping = false;
    private Sinks.Many<String> receiveSink;
    private reactor.core.Disposable connectionDisposable;
    private ScheduledExecutorService pingExecutor;
    private ScheduledFuture<?> pingTask;

    public ClaudeAgentTransport(ClaudeProperties properties) {
        ClaudeProperties.ClaudeAgent config = properties.getClaudeAgent();
        this.uri = "ws://" + config.getHost() + ":" + config.getPort() + "/";
        this.apiKey = config.getApiKey();
        this.connectionTimeout = config.getConnectionTimeout();
        this.httpClient = HttpClient.create();
    }

    public ClaudeAgentTransport(String host, int port, String apiKey, int connectionTimeout) {
        this.uri = "ws://" + host + ":" + port + "/";
        this.apiKey = apiKey;
        this.connectionTimeout = connectionTimeout;
        this.httpClient = HttpClient.create();
    }

    @Override
    public void connect() throws Exception {
        if (connected) {
            return;
        }
        stopping = false;
        receiveSink = Sinks.many().multicast().onBackpressureBuffer();

        shutdownPingExecutor();
        pingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-agent-ping-");
            t.setDaemon(true);
            return t;
        });

        logger.info("Connecting to Claude Agent WebSocket: {}", uri);

        HttpClient client = httpClient;
        if (apiKey != null && !apiKey.isEmpty()) {
            client = httpClient.headers(h -> h.set("Authorization", "Bearer " + apiKey));
        }

        if (connectionDisposable != null && !connectionDisposable.isDisposed()) {
            connectionDisposable.dispose();
        }

        connectionDisposable = client
            .websocket(WebsocketClientSpec.builder()
                .maxFramePayloadLength(16 * 1024 * 1024) // Max frame payload length: 16 MB
                .build())
            .uri(uri)
            .handle((inbound, out) -> {
                this.outbound = out;
                connected = true;
                logger.info("Connected to Claude Agent at {}", uri);

                startPingKeepAlive();

                inbound.receive()
                    .asString()
                    .doOnNext(line -> {
                        if (!line.trim().isEmpty()) {
                            logger.debug("Received from Claude Agent: {}", line.trim().substring(0, Math.min(100, line.trim().length())));
                            Sinks.EmitResult result = receiveSink.tryEmitNext(line.trim());
                            if (result.isFailure()) {
                                logger.warn("Failed to emit received line: {}", result);
                            }
                        }
                    })
                    .doOnError(err -> {
                        if (!stopping) {
                            logger.error("Claude Agent inbound error", err);
                        }
                        connected = false;
                        stopPingKeepAlive();
                        receiveSink.tryEmitError(err);
                    })
                    .doOnComplete(() -> {
                        if (!stopping) {
                            logger.warn("Claude Agent inbound completed");
                        }
                        connected = false;
                        stopPingKeepAlive();
                        receiveSink.tryEmitComplete();
                    })
                    .subscribe();

                return Mono.never();
            })
            .subscribe(
                unused -> { },
                err -> {
                    if (!stopping) {
                        logger.error("Claude Agent connection error", err);
                    }
                    connected = false;
                    stopPingKeepAlive();
                    receiveSink.tryEmitError(err);
                }
            );

        int waitMs = 0;
        while (!connected && waitMs < connectionTimeout) {
            Thread.sleep(100);
            waitMs += 100;
        }
        if (!connected) {
            throw new java.io.IOException("Failed to connect to Claude Agent within timeout");
        }
    }

    private void startPingKeepAlive() {
        if (pingExecutor == null || pingExecutor.isShutdown()) {
            return;
        }
        stopPingKeepAlive();
        pingTask = pingExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!connected || stopping || outbound == null) {
                    return;
                }
                outbound.sendString(Mono.just("{\"type\":\"ping\"}"))
                    .then()
                    .block(Duration.ofSeconds(PING_TIMEOUT_SECONDS));
                logger.debug("Claude Agent ping sent successfully");
            } catch (Exception e) {
                if (!stopping) {
                    logger.warn("Claude Agent ping failed: {}", e.getMessage());
                }
                connected = false;
                stopPingKeepAlive();
                if (receiveSink != null) {
                    receiveSink.tryEmitComplete();
                }
            }
        }, KEEP_ALIVE_INTERVAL_SECONDS, KEEP_ALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        logger.info("Claude Agent ping keepalive started (interval {}s)", KEEP_ALIVE_INTERVAL_SECONDS);
    }

    private void stopPingKeepAlive() {
        if (pingTask != null && !pingTask.isDone()) {
            pingTask.cancel(false);
            pingTask = null;
        }
    }

    private void shutdownPingExecutor() {
        stopPingKeepAlive();
        if (pingExecutor != null && !pingExecutor.isShutdown()) {
            pingExecutor.shutdown();
            pingExecutor = null;
        }
    }

    @Override
    public void disconnect() {
        stopping = true;
        connected = false;
        stopPingKeepAlive();
        if (connectionDisposable != null && !connectionDisposable.isDisposed()) {
            connectionDisposable.dispose();
        }
        if (outbound != null) {
            try {
                outbound.sendClose(1000, "Normal closure").then().block(Duration.ofSeconds(5));
            } catch (Exception e) {
                logger.debug("WebSocket close signal failed", e);
            }
            outbound = null;
        }
        if (receiveSink != null) {
            receiveSink.tryEmitComplete();
        }
    }

    @Override
    public void dispose() {
        disconnect();
        shutdownPingExecutor();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void send(String line) throws Exception {
        if (outbound == null || !connected) {
            throw new IllegalStateException("Claude Agent transport not connected");
        }
        try {
            outbound.sendString(Mono.just(line))
                .then()
                .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            connected = false;
            if (receiveSink != null) {
                receiveSink.tryEmitComplete();
            }
            throw e;
        }
    }

    @Override
    public Flux<String> receive() {
        if (receiveSink == null) {
            return Flux.error(new IllegalStateException("Transport not connected"));
        }
        return receiveSink.asFlux();
    }
}

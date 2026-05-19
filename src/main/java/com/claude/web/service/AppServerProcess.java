package com.claude.web.service;

import com.claude.web.dto.*;
import com.claude.web.config.ClaudeProperties;
import com.claude.web.service.transport.ClaudeTransport;
import com.claude.web.service.transport.ClaudeAgentTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.Disposable;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * AppServerProcess - Manages connection to the Claude Agent backend via WebSocket.
 * Features: auto-reconnect on disconnect with infinite retry.
 */
@Service
public class AppServerProcess {

    private static final Logger logger = LoggerFactory.getLogger(AppServerProcess.class);

    private final ClaudeProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final ScheduledExecutorService reconnectExecutor;
    private volatile ClaudeTransport transport;

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<Integer, PendingServerRequest> pendingServerRequests = new ConcurrentHashMap<>();
    private final List<Consumer<NotificationEvent>> notificationListeners = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<Map<String, Object>>> pendingRawRequests = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private volatile boolean stopping = false;
    private final Object initLock = new Object();
    private final Object connectionLock = new Object();
    private volatile boolean connected = false;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private ScheduledFuture<?> reconnectTask;
    private Disposable transportSubscription;

    // Reconnect settings
    private static final int RECONNECT_DELAY_MS = 3000;
    private static final int MAX_RECONNECT_ATTEMPTS_PER_CYCLE = 5;

    public AppServerProcess(ClaudeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "claude-app-server-");
            t.setDaemon(true);
            return t;
        });
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-reconnect-");
            t.setDaemon(true);
            return t;
        });

        this.transport = new ClaudeAgentTransport(properties);
    }

    /**
     * Attempt to connect to the remote server with retry.
     * This method blocks until connection is established or stopping is true.
     * It retries indefinitely with a fixed delay between attempts.
     */
    public void connectRemote() throws Exception {
        if (stopping) return;

        synchronized (connectionLock) {
            if (connected || reconnecting.get()) {
                return;
            }

            int attempts = 0;
            Exception lastException = null;
            while (!stopping) {
                attempts++;
                try {
                    transport.connect();
                    connected = true;
                    subscribeToTransport();
                    logger.info("Transport connection established successfully (attempt {})", attempts);
                    return;
                } catch (Exception e) {
                    lastException = e;
                    logger.warn("Connection attempt {} failed: {}", attempts, e.getMessage());
                    try {
                        transport.disconnect();
                    } catch (Exception ex) {
                        // ignore
                    }
                    if (!stopping) {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    }
                }
            }
            throw new IOException("Failed to connect (stopping=true)", lastException);
        }
    }

    private void subscribeToTransport() {
        if (transportSubscription != null && !transportSubscription.isDisposed()) {
            transportSubscription.dispose();
        }
        transportSubscription = transport.receive()
            .subscribe(
                this::handleLine,
                error -> {
                    if (!stopping) {
                        logger.error("Transport receive error", error);
                        handleDisconnect();
                    }
                },
                () -> {
                    if (!stopping) {
                        logger.warn("Transport receive stream completed");
                        handleDisconnect();
                    }
                }
            );
    }

    private void handleDisconnect() {
        if (stopping || reconnecting.get()) {
            return;
        }

        synchronized (connectionLock) {
            if (!connected || reconnecting.get()) {
                return;
            }

            reconnecting.set(true);
            connected = false;
            initialized = false;

            logger.warn("Transport connection lost, scheduling reconnect...");

            emitNotification(new NotificationEvent("connection/lost", Map.of(
                "message", "Connection lost, attempting to reconnect...",
                "timestamp", Instant.now().toString()
            )));

            cleanupTransport();

            // Cancel any pending reconnect task
            if (reconnectTask != null && !reconnectTask.isDone()) {
                reconnectTask.cancel(false);
            }

            // Schedule periodic reconnection attempts
            reconnectTask = reconnectExecutor.scheduleAtFixedRate(() -> {
                if (stopping || connected) {
                    return;
                }
                try {
                    // Must clear reconnecting flag before calling connectRemote
                    // because connectRemote checks this flag and returns early if true
                    reconnecting.set(false);
                    connectRemote();

                    // Connection successful - cancel the periodic task
                    if (reconnectTask != null && !reconnectTask.isDone()) {
                        reconnectTask.cancel(false);
                        reconnectTask = null;
                    }

                    synchronized (initLock) {
                        initialized = false;
                        ensureInitialized();
                    }

                    logger.info("Successfully reconnected to remote server");

                    emitNotification(new NotificationEvent("connection/restored", Map.of(
                        "message", "Connection restored",
                        "timestamp", Instant.now().toString()
                    )));

                } catch (Exception e) {
                    logger.warn("Reconnect attempt failed: {}", e.getMessage());
                    // Keep reconnecting flag true so next scheduled attempt can proceed
                    reconnecting.set(true);
                }
            }, 0, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void cleanupTransport() {
        connected = false;
        if (transportSubscription != null && !transportSubscription.isDisposed()) {
            transportSubscription.dispose();
            transportSubscription = null;
        }
        try {
            transport.disconnect();
        } catch (Exception e) {
            // ignore
        }
    }

    private void handleLine(String line) {
        try {
            logger.debug("Transport line: {}", line);

            // Try to parse as JSON-RPC response first (for Claude compatibility)
            JsonRpcResponse response = objectMapper.readValue(line, JsonRpcResponse.class);

            // Handle response to our request
            if (response.getId() != null && pendingRequests.containsKey(response.getId())) {
                CompletableFuture<Object> future = pendingRequests.remove(response.getId());
                if (response.getError() != null) {
                    future.completeExceptionally(new RuntimeException(response.getError().getMessage()));
                } else {
                    future.complete(response.getResult());
                }
                return;
            }

            // Handle notification (no id)
            if (response.getMethod() != null && response.getId() == null) {
                emitNotification(new NotificationEvent(response.getMethod(), response.getParams()));
                return;
            }

            // Handle server-initiated request
            if (response.getId() != null && response.getMethod() != null) {
                handleServerRequest(response.getId(), response.getMethod(), response.getParams());
                return;
            }
        } catch (Exception e) {
            // Not JSON-RPC, may be Claude Agent protocol message
            // Forward as generic notification for the frontend to handle
            try {
                Map<String, Object> msg = objectMapper.readValue(line, Map.class);
                String type = (String) msg.get("type");
                if (type != null) {
                    emitNotification(new NotificationEvent(type, msg));
                }
            } catch (Exception ex2) {
                logger.warn("Failed to parse line: {}", line);
            }
        }
    }

    private void handleServerRequest(int requestId, String method, Object params) {
        logger.info("Received server request: id={}, method={}", requestId, method);
        PendingServerRequest request = new PendingServerRequest(
            requestId,
            method,
            params,
            Instant.now().toString()
        );
        pendingServerRequests.put(requestId, request);

        // Auto-approve permission requests if enabled
        if (properties.isAutoApprove() && isApprovalRequest(method)) {
            logger.info("Auto-approving server request: id={}, method={}", requestId, method);
            resolvePendingServerRequest(requestId, Map.of("approved", true), null);
            return;
        }

        Map<String, Object> notificationParams = Map.of(
            "id", requestId,
            "method", method,
            "params", params,
            "receivedAtIso", request.getReceivedAtIso()
        );
        emitNotification(new NotificationEvent("server/request", notificationParams));
    }

    private boolean isApprovalRequest(String method) {
        return method != null && (
            method.endsWith("/requestApproval") ||
            method.equals("item/tool/call")
        );
    }

    private void emitNotification(NotificationEvent event) {
        // Check if this is a response to a pending raw request
        Object rawParams = event.getParams();
        if (rawParams instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) rawParams;
            String requestId = (String) params.get("requestId");
            if (requestId != null) {
                CompletableFuture<Map<String, Object>> future = pendingRawRequests.remove(requestId);
                if (future != null) {
                    future.complete(params);
                    return;
                }
            }
        }

        for (Consumer<NotificationEvent> listener : notificationListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Notification listener failed", e);
            }
        }
    }

    private void ensureInitialized() throws Exception {
        if (initialized) return;

        synchronized (initLock) {
            if (initialized) return;

            start();
            initialized = true;
        }
    }

    private void start() throws Exception {
        if (!connected) {
            connectRemote();
        }
    }

    public Object rpc(String method, Object params) throws Exception {
        int waitAttempts = 0;
        while (reconnecting.get() && waitAttempts < 300) { // wait up to 30 seconds
            Thread.sleep(100);
            waitAttempts++;
        }

        ensureInitialized();
        return call(method, params);
    }

    private Object call(String method, Object params) throws Exception {
        if (!connected) {
            throw new RuntimeException("backend is not connected");
        }

        int id = nextId.getAndIncrement();
        JsonRpcRequest request = new JsonRpcRequest(id, method, params);

        CompletableFuture<Object> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        String line = objectMapper.writeValueAsString(request);
        logger.info("To backend: {}", line);
        transport.send(line);

        int timeout = properties.getClaudeAgent().getConnectionTimeout() / 1000;
        if (timeout <= 0) timeout = 60;
        return future.get(timeout, TimeUnit.SECONDS);
    }

    /**
     * Send a raw message to the transport and wait for a response with matching requestId.
     * Used for request-response patterns over the Claude Agent protocol.
     */
    public Map<String, Object> sendRawRequest(String type, Map<String, Object> params, long timeoutMs) throws Exception {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> msg = new HashMap<>(params);
        msg.put("type", type);
        msg.put("requestId", requestId);

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingRawRequests.put(requestId, future);

        try {
            sendRaw(objectMapper.writeValueAsString(msg));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            pendingRawRequests.remove(requestId);
            throw new RuntimeException("Request timeout: " + type);
        } finally {
            pendingRawRequests.remove(requestId);
        }
    }

    /**
     * Send a raw message to the transport (for Claude Agent protocol).
     * Lazily initializes the connection if not already connected.
     * Waits for reconnection if the backend is currently reconnecting.
     */
    public void sendRaw(String message) throws Exception {
        // Wait if reconnecting
        int waitAttempts = 0;
        while (reconnecting.get() && waitAttempts < 500) { // wait up to 50 seconds
            Thread.sleep(100);
            waitAttempts++;
        }

        if (!connected) {
            ensureInitialized();
        }
        if (!connected) {
            throw new RuntimeException("backend is not connected");
        }
        transport.send(message);
    }

    public void respondToServerRequest(Object payload) throws Exception {
        ensureInitialized();

        if (!(payload instanceof Map)) {
            throw new IllegalArgumentException("Invalid response payload: expected object");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) payload;

        Object idObj = body.get("id");
        if (!(idObj instanceof Number)) {
            throw new IllegalArgumentException("Invalid response payload: 'id' must be an integer");
        }
        int id = ((Number) idObj).intValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> rawError = (Map<String, Object>) body.get("error");
        if (rawError != null) {
            String message = (String) rawError.get("message");
            if (message == null || message.trim().isEmpty()) {
                message = "Server request rejected by client";
            }
            Object codeObj = rawError.get("code");
            int code = (codeObj instanceof Number) ? ((Number) codeObj).intValue() : -32000;
            resolvePendingServerRequest(id, null, new ServerRequestReply.JsonRpcError(code, message));
            return;
        }

        if (!body.containsKey("result")) {
            throw new IllegalArgumentException("Invalid response payload: expected 'result' or 'error'");
        }

        resolvePendingServerRequest(id, body.get("result"), null);
    }

    private void resolvePendingServerRequest(int requestId, Object result, ServerRequestReply.JsonRpcError error) {
        PendingServerRequest pendingRequest = pendingServerRequests.remove(requestId);
        if (pendingRequest == null) {
            throw new IllegalStateException("No pending server request found for id " + requestId);
        }

        JsonRpcResponse response = new JsonRpcResponse();
        response.setId(requestId);
        if (error != null) {
            JsonRpcResponse.JsonRpcError rpcError = new JsonRpcResponse.JsonRpcError();
            rpcError.setCode(error.getCode());
            rpcError.setMessage(error.getMessage());
            response.setError(rpcError);
        } else {
            response.setResult(result != null ? result : Map.of());
        }

        try {
            String line = objectMapper.writeValueAsString(response);
            transport.send(line);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send response", e);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> requestParams = (Map<String, Object>) pendingRequest.getParams();
        String threadId = "";
        if (requestParams != null && requestParams.get("threadId") instanceof String) {
            threadId = (String) requestParams.get("threadId");
        }

        Map<String, Object> resolvedParams = Map.of(
            "id", requestId,
            "method", pendingRequest.getMethod(),
            "threadId", threadId,
            "mode", properties.isAutoApprove() && isApprovalRequest(pendingRequest.getMethod()) ? "auto" : "manual",
            "resolvedAtIso", Instant.now().toString()
        );
        emitNotification(new NotificationEvent("server/request/resolved", resolvedParams));
    }

    public List<PendingServerRequest> listPendingServerRequests() {
        return new ArrayList<>(pendingServerRequests.values());
    }

    public void addNotificationListener(Consumer<NotificationEvent> listener) {
        notificationListeners.add(listener);
    }

    public void removeNotificationListener(Consumer<NotificationEvent> listener) {
        notificationListeners.remove(listener);
    }

    public boolean isConnected() {
        return connected && !reconnecting.get();
    }

    public boolean isReconnecting() {
        return reconnecting.get();
    }

    public String getTransportType() {
        return "claude-agent";
    }

    /**
     * 运行时切换 Claude Agent 连接目标。
     * 断开当前连接，创建新 Transport，重新建立连接。
     */
    public synchronized void switchAgent(String host, int port, String apiKey) throws Exception {
        logger.info("Switching Claude Agent to {}:{}", host, port);

        // 停止自动重连，避免干扰
        stopping = true;
        reconnecting.set(false);
        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }

        // 销毁旧连接，并让所有待处理请求立即失败
        cleanupTransport();
        transport.dispose();
        RuntimeException switchError = new RuntimeException("Agent switched");
        pendingRequests.forEach((id, f) -> f.completeExceptionally(switchError));
        pendingRequests.clear();
        pendingRawRequests.forEach((id, f) -> f.completeExceptionally(switchError));
        pendingRawRequests.clear();
        pendingServerRequests.clear();
        initialized = false;

        // 更新内存中的配置
        properties.getClaudeAgent().setHost(host);
        properties.getClaudeAgent().setPort(port);
        properties.getClaudeAgent().setApiKey(apiKey != null ? apiKey : "");

        // 用新参数创建 Transport 并连接
        int timeout = properties.getClaudeAgent().getConnectionTimeout();
        transport = new ClaudeAgentTransport(host, port, apiKey != null ? apiKey : "", timeout);
        stopping = false;

        connectRemote();
        logger.info("Successfully switched Claude Agent to {}:{}", host, port);
    }

    @PreDestroy
    public void dispose() {
        stopping = true;
        initialized = false;

        // Cancel reconnect task
        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(false);
        }

        cleanupTransport();

        Exception failure = new RuntimeException("backend stopped");
        for (CompletableFuture<Object> future : pendingRequests.values()) {
            future.completeExceptionally(failure);
        }
        pendingRequests.clear();
        pendingServerRequests.clear();

        reconnectExecutor.shutdown();
        executor.shutdown();

        transport.dispose();
    }
}

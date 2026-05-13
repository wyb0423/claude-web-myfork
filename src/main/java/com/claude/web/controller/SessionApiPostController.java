package com.claude.web.controller;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.claude.web.config.ClaudeProperties;
import com.claude.web.entity.AiSession;
import com.claude.web.mapper.AiSessionMapper;
import com.claude.web.service.AppServerProcess;
import com.claude.web.service.SseEventService;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/claude/v2")
public class SessionApiPostController {

    private static final Logger logger = LoggerFactory.getLogger(SessionApiPostController.class);

    private final AppServerProcess appServerProcess;
    private final ObjectMapper objectMapper;
    private final ClaudeProperties claudeProperties;
    private final SseEventService sseEventService;
    private final AiSessionMapper aiSessionMapper;
    private final ExecutorService executor;

    private final Map<String, ClaudeSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> sessionMessages = new ConcurrentHashMap<>();
    private final Map<String, String> frontendToSdkId = new ConcurrentHashMap<>();
    private final Map<String, String> sdkToFrontendId = new ConcurrentHashMap<>();

    public SessionApiPostController(AppServerProcess appServerProcess, ObjectMapper objectMapper, ClaudeProperties claudeProperties, SseEventService sseEventService, AiSessionMapper aiSessionMapper) {
        this.appServerProcess = appServerProcess;
        this.objectMapper = objectMapper;
        this.claudeProperties = claudeProperties;
        this.sseEventService = sseEventService;
        this.aiSessionMapper = aiSessionMapper;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sse-post-");
            t.setDaemon(true);
            return t;
        });
        
        this.appServerProcess.addNotificationListener(event -> {
            String method = event.getMethod();
            Object params = event.getParams();
            if (params instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = new HashMap<>((Map<String, Object>) params);
                if (method != null && !method.startsWith("connection/") && !method.startsWith("server/")) {
                    msg.put("type", method);
                }
                handleClaudeAgentMessage(msg);

                @SuppressWarnings("unchecked")
                Map<String, Object> originalParams = (Map<String, Object>) params;
                Object sessionIdObj = originalParams.get("sessionId");
                if (sessionIdObj instanceof String) {
                    String sdkId = (String) sessionIdObj;
                    String frontendId = sdkToFrontendId.get(sdkId);
                    if (frontendId != null) {
                        originalParams.put("sessionId", frontendId);
                    }
                }
            }
        });
    }

    @PostMapping("/list")
    public ResponseEntity<Map<String, Object>> listSessions(@RequestBody Map<String, Object> body) {
        try {
            String userId = (String) body.get("userId");
            String appId = (String) body.get("appId");
            String sessionPath;
            if(userId == null || userId.trim().isEmpty()) {
                sessionPath = "/home/ubuntu";
            }else{
                sessionPath = "/home/ubuntu/" + userId;
            }
            List<Map<String, Object>> result = new ArrayList<>();
            Map<String, Map<String, Object>> resultById = new HashMap<>();

            try {
                Map<String, Object> response = appServerProcess.sendRawRequest(
                    "list_sessions",
                    Map.of("cwd", sessionPath),
                    10000
                );
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> agentSessions = (List<Map<String, Object>>) response.get("sessions");
                if (agentSessions != null) {
                    for (Map<String, Object> s : agentSessions) {
                        String sessionId = (String) s.get("sessionId");
                        if (sessionId == null) continue;
                        if (sdkToFrontendId.containsKey(sessionId)) continue;

                        Map<String, Object> item = new HashMap<>();
                        item.put("appId", appId != null ? appId : "");
                        item.put("userId", userId != null ? userId : "");
                        item.put("sessionId", sessionId);
                        item.put("sessionTitle", s.get("summary"));
                        item.put("sessionStatus", "active");
                        Object createdAt = s.get("createdAt");
                        if (createdAt instanceof Number) {
                            item.put("createTime", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(((Number) createdAt).longValue())));
                        } else {
                            item.put("createTime", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                        }
                        result.add(item);
                        resultById.put(sessionId, item);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to query Claude Agent for local sessions: {}", e.getMessage());
            }

            for (ClaudeSession session : sessions.values()) {
                if (session.isPlaceholder) continue;
                Map<String, Object> item = resultById.get(session.id);
                if (item == null) {
                    item = new HashMap<>();
                    item.put("appId", appId != null ? appId : "");
                    item.put("userId", userId != null ? userId : "");
                    item.put("sessionId", session.id);
                    item.put("sessionTitle", session.preview);
                    item.put("sessionStatus", session.inProgress ? "active" : "created");
                    item.put("createTime", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(session.createdAt)));
                    result.add(item);
                }
            }

            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            logger.error("Failed to list sessions", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/listMysql")
    public ResponseEntity<Map<String, Object>> listSessionMysql(@RequestBody Map<String, Object> body) {
        try {
            String appId = (String) body.get("appId");
            String userId = (String) body.get("userId");
            String userCwd = (String) body.get("userCwd");
            String sessionId = (String) body.get("sessionId");
            String sessionTitle = (String) body.get("sessionTitle");
            String sessionStatus = (String) body.get("sessionStatus");
            String createTimeStart = (String) body.get("createTimeStart");
            String createTimeEnd = (String) body.get("createTimeEnd");

            LocalDateTime startTime = null;
            LocalDateTime endTime = null;
            if (createTimeStart != null && !createTimeStart.isEmpty()) {
                startTime = LocalDateTime.parse(createTimeStart);
            }
            if (createTimeEnd != null && !createTimeEnd.isEmpty()) {
                endTime = LocalDateTime.parse(createTimeEnd);
            }

            List<AiSession> sessions = aiSessionMapper.searchSessions(
                appId, userId, userCwd, sessionId, sessionTitle, sessionStatus, startTime, endTime
            );

            List<Map<String, Object>> result = new ArrayList<>();
            for (AiSession session : sessions) {
                Map<String, Object> item = new HashMap<>();
                item.put("appId", session.getAppId());
                item.put("userId", session.getUserId());
                item.put("userCwd", session.getUserCwd());
                item.put("sessionId", session.getSessionId());
                item.put("sessionTitle", session.getSessionTitle());
                item.put("sessionStatus", session.getSessionStatus());
                item.put("createTime", session.getCreateTime());
                result.add(item);
            }

            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            logger.error("Failed to list sessions from MySQL", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/getLatestSession")
    public ResponseEntity<Map<String, Object>> getLatestSession(@RequestBody Map<String, Object> body) {
        try {
            String userId = (String) body.get("userId");

            if (userId == null || userId.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(400, "userId is required"));
            }

            AiSession aiSession = aiSessionMapper.selectLatestByUserId(userId);

            if (aiSession == null) {
                return ResponseEntity.ok(ApiResponse.error(404, "No session found for user"));
            }

            Map<String, Object> data = new HashMap<>();
            data.put("appId", aiSession.getAppId());
            data.put("userId", aiSession.getUserId());
            data.put("userCwd", aiSession.getUserCwd());
            data.put("sessionId", aiSession.getSessionId());
            data.put("sessionTitle", aiSession.getSessionTitle());
            data.put("sessionStatus", aiSession.getSessionStatus());
            data.put("createTime", aiSession.getCreateTime());

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            logger.error("Failed to get latest session", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/get")
    public ResponseEntity<Map<String, Object>> getSession(@RequestBody Map<String, Object> body) {
        try {
            String userId = (String) body.get("userId");
            String sessionId = (String) body.get("sessionId");
            String sessionPath = "";
            if(userId == null || userId.trim().isEmpty()) {
                sessionPath = "/home/ubuntu";
            }else{
                sessionPath = "/home/ubuntu/" + userId;
            }
            if (sessionId == null || sessionId.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(400, "sessionId is required"));
            }
            
            ClaudeSession session = sessions.get(sessionId);
            List<Map<String, Object>> msgs;
            String cwd;

            String sdkId = frontendToSdkId.get(sessionId);
            String queryId = sdkId != null ? sdkId : sessionId;

            if (sdkId != null) {
                Map<String, Object> response = appServerProcess.sendRawRequest(
                    "get_session_messages",
                    Map.of("sessionId", queryId, "cwd", sessionPath),
                    15000
                );
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> agentMessages = (List<Map<String, Object>>) response.get("messages");
                msgs = convertAgentMessages(agentMessages);
                cwd = session != null ? session.cwd : sessionPath;
            } else if (session != null) {
                if (session.inProgress) {
                    try {
                        Map<String, Object> response = appServerProcess.sendRawRequest(
                            "get_session_messages",
                            Map.of("sessionId", queryId, "cwd", sessionPath),
                            15000
                        );
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> agentMessages = (List<Map<String, Object>>) response.get("messages");
                        msgs = new ArrayList<>(convertAgentMessages(agentMessages));
                        List<Map<String, Object>> memMsgs = sessionMessages.getOrDefault(sessionId, new ArrayList<>());
                        msgs.addAll(memMsgs);
                    } catch (Exception e) {
                        msgs = sessionMessages.getOrDefault(sessionId, new ArrayList<>());
                    }
                } else {
                    Map<String, Object> response = appServerProcess.sendRawRequest(
                        "get_session_messages",
                        Map.of("sessionId", queryId, "cwd", sessionPath),
                        15000
                    );
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> agentMessages = (List<Map<String, Object>>) response.get("messages");
                    msgs = convertAgentMessages(agentMessages);
                }
                cwd = session.cwd;
            } else {
                Map<String, Object> response = appServerProcess.sendRawRequest(
                    "get_session_messages",
                    Map.of("sessionId", queryId, "cwd", sessionPath),
                    15000
                );
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> agentMessages = (List<Map<String, Object>>) response.get("messages");
                msgs = convertAgentMessages(agentMessages);
                cwd = sessionPath;
            }

            Map<String, Object> thread = new HashMap<>();
            thread.put("id", sessionId);
            thread.put("cwd", cwd);
            thread.put("turns", buildTurns(msgs));

            Map<String, Object> data = new HashMap<>();
            data.put("thread", thread);

            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            logger.error("Failed to get session", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody Map<String, Object> body) {
        String userId = (String) body.get("userId");
        String appId = (String) body.get("appId");
        String name = (String) body.get("sessionTitle");

        String cwd = "";
        if(userId == null || userId.trim().isEmpty()) {
            cwd = "/home/ubuntu";
        }else{
            cwd = "/home/ubuntu/" + userId;
        }

        if (userId == null || userId.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error(400, "userId is required"));
        }
        if (appId == null || appId.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error(400, "appId is required"));
        }
        try {
            String id = UUID.randomUUID().toString();
            ClaudeSession session = new ClaudeSession();
            session.id = id;
            // Use provided cwd, or default from properties, or empty string
            String defaultCwd = claudeProperties.getSessionDefaults() != null
                    ? claudeProperties.getSessionDefaults().getCwd()
                    : null;
            session.cwd = cwd != null ? cwd : (defaultCwd != null ? defaultCwd : "");
            session.preview = name != null ? name : "New Session";
            session.createdAt = System.currentTimeMillis() / 1000;
            session.updatedAt = session.createdAt;
            session.inProgress = false;
            session.isPlaceholder = true;
            // Store session options for recovery
            session.options = extractSessionOptions(body);
            sessions.put(id, session);
            sessionMessages.put(id, new ArrayList<>());


            AiSession aiSession = new AiSession();
            aiSession.setAppId(appId);
            aiSession.setUserId(userId);
            aiSession.setUserCwd("/home/ubuntu/" + userId);
            aiSession.setSessionId(id);
            aiSession.setSessionTitle("");
            aiSession.setSessionStatus("created");
            aiSession.setCreateTime(LocalDateTime.now());
            aiSessionMapper.insert(aiSession);

            Map<String, Object> data = new HashMap<>();
            data.put("codexId", id);

            return ResponseEntity.ok(ApiResponse.success(data, "session created"));
        } catch (Exception e) {
            logger.error("Failed to create session", e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sendMessage")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, Object> body) {
        String userId = (String) body.get("userId");
        String sessionId = (String) body.get("sessionId");
        String text = (String) body.get("text");
        String sessionPath = "";
        if(userId == null || userId.trim().isEmpty()) {
            sessionPath = "/home/ubuntu";
        }else{
            sessionPath = "/home/ubuntu/" + userId;
        }
        if (sessionId == null || sessionId.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error(400, "sessionId is required"));
        }
        if (text == null || text.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error(400, "text is required"));
        }
        try {
            ClaudeSession session = sessions.get(sessionId);
            if (session == null) {
                session = new ClaudeSession();
                session.id = sessionId;
                session.userId = userId;
                session.cwd = sessionPath;
                session.preview = text.length() > 50 ? text.substring(0, 50) + "..." : text;
                session.createdAt = System.currentTimeMillis() / 1000;
                session.updatedAt = session.createdAt;
                session.inProgress = false;
                session.isPlaceholder = false;
                sessions.put(sessionId, session);
                sessionMessages.put(sessionId, new ArrayList<>());
            }
            session.inProgress = true;
            session.updatedAt = System.currentTimeMillis() / 1000;

            if (session.isPlaceholder) {
                session.preview = text.length() > 50 ? text.substring(0, 50) + "..." : text;
                session.isPlaceholder = false;
            }

            List<Map<String, Object>> msgs = sessionMessages.computeIfAbsent(sessionId, k -> new ArrayList<>());
            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("text", text);
            userMsg.put("id", "u_" + System.currentTimeMillis());
            msgs.add(userMsg);

            Map<String, Object> chatMsg = new HashMap<>();
            chatMsg.put("type", "chat");
            chatMsg.put("prompt", text);
            Map<String, Object> options = new HashMap<>();

            String sdkSessionId = frontendToSdkId.get(sessionId);
            options.put("sessionId", sdkSessionId != null ? sdkSessionId : sessionId);
            options.put("cwd", session.cwd);

            if (!session.isPlaceholder) {
                options.put("resume", true);
            }
            if (session.options != null) {
                options.putAll(session.options);
            }
            String permissionMode = (String) body.get("permissionMode");
            if (permissionMode != null) {
                options.put("permissionMode", permissionMode);
            }
            String allowedTools = (String) body.get("allowedTools");
            if (allowedTools != null) {
                options.put("allowedTools", Arrays.asList(allowedTools.split(",")));
            }
            String disallowedTools = (String) body.get("disallowedTools");
            if (disallowedTools != null) {
                options.put("disallowedTools", Arrays.asList(disallowedTools.split(",")));
            }
            Object maxTurns = body.get("maxTurns");
            if (maxTurns != null) {
                options.put("maxTurns", maxTurns instanceof Integer ? maxTurns : Integer.parseInt(maxTurns.toString()));
            }
            chatMsg.put("options", options);

            appServerProcess.sendRaw(objectMapper.writeValueAsString(chatMsg));

            String title = text.length() > 12 ? text.substring(0, 12) : text;
            try {
                AiSession aiSession = aiSessionMapper.selectBySessionId(sessionId);
                if (aiSession != null) {
                    aiSession.setSessionStatus("active");
                    aiSession.setSessionTitle(title);
                    aiSessionMapper.update(aiSession);
                }
            } catch (Exception e) {
                logger.warn("Failed to update session status in MySQL: {}", e.getMessage());
            }

            Map<String, Object> data = new HashMap<>();
            data.put("ok", true);
            data.put("requestId", System.currentTimeMillis());

            return ResponseEntity.ok(ApiResponse.success(data, "message sent"));
        } catch (Exception e) {
            logger.error("Failed to send message", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/{id}/message")
    public ResponseEntity<?> sendMessageOrigin(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            String text = body.get("text");
            if (text == null || text.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
            }
            String userId = body.get("userId");
            String sessionPath = "";
            if(userId == null || userId.trim().isEmpty()) {
                sessionPath = "/home/ubuntu";
            }else{
                sessionPath = "/home/ubuntu/" + userId;
            }

            ClaudeSession session = sessions.get(id);
            if (session == null) {
                // Historical session from SDK local storage not yet loaded into memory.
                // Create a lightweight in-memory entry so subsequent messages target
                // the same session and replies are tracked under the same ID.
                session = new ClaudeSession();
                session.id = id;
                session.cwd = sessionPath;
                session.preview = "Session";
                session.createdAt = System.currentTimeMillis() / 1000;
                session.updatedAt = session.createdAt;
                session.inProgress = false;
                session.isPlaceholder = false;
                sessions.put(id, session);
                sessionMessages.put(id, new ArrayList<>());
            }
            session.inProgress = true;
            session.updatedAt = System.currentTimeMillis() / 1000;

            // Store user message
            List<Map<String, Object>> msgs = sessionMessages.computeIfAbsent(id, k -> new ArrayList<>());
            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("text", text);
            userMsg.put("id", "u_" + System.currentTimeMillis());
            msgs.add(userMsg);

            // Send chat message to Claude Agent via WebSocket
            Map<String, Object> chatMsg = new HashMap<>();
            chatMsg.put("type", "chat");
            chatMsg.put("prompt", text);
            Map<String, Object> options = new HashMap<>();

            // Resolve to the SDK session ID if a frontend→SDK mapping exists.
            // For historical sessions the ID itself is the SDK ID.
            String sdkSessionId = frontendToSdkId.get(id);
            options.put("sessionId", sdkSessionId != null ? sdkSessionId : id);
            options.put("cwd", session.cwd);

            // Tell the SDK to resume the existing session instead of creating a new one.
            // Skip for brand-new placeholder sessions that have not been acknowledged yet.
            if (!session.isPlaceholder) {
                options.put("resume", true);
            }
            // Add stored session options (permissionMode, allowedTools, etc.)
            if (session.options != null) {
                options.putAll(session.options);
            }
            // Allow per-message override of options
            String permissionMode = body.get("permissionMode");
            if (permissionMode != null) {
                options.put("permissionMode", permissionMode);
            }
            String allowedTools = body.get("allowedTools");
            if (allowedTools != null) {
                options.put("allowedTools", Arrays.asList(allowedTools.split(",")));
            }
            String disallowedTools = body.get("disallowedTools");
            if (disallowedTools != null) {
                options.put("disallowedTools", Arrays.asList(disallowedTools.split(",")));
            }
            String maxTurns = body.get("maxTurns");
            if (maxTurns != null) {
                options.put("maxTurns", Integer.parseInt(maxTurns));
            }
            chatMsg.put("options", options);

            appServerProcess.sendRaw(objectMapper.writeValueAsString(chatMsg));

            String title = text.length() > 12 ? text.substring(0, 12) : text;
            try {
                AiSession aiSession = aiSessionMapper.selectBySessionId(id);
                if (aiSession != null) {
                    aiSession.setSessionStatus("active");
                    aiSession.setSessionTitle(title);
                    aiSessionMapper.update(aiSession);
                }
            } catch (Exception e) {
                logger.warn("Failed to update session status in MySQL: {}", e.getMessage());
            }

            Map<String, Object> data = new HashMap<>();
            data.put("ok", true);
            data.put("requestId", System.currentTimeMillis());

            return ResponseEntity.ok(ApiResponse.success(data, "message sent"));
        } catch (Exception e) {
            logger.error("Failed to send message to session: {}", id, e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }
    /**
     * 向指定会话发送消息，通过SSE实时推送处理结果
     *
     * 功能说明：
     * - 将用户输入的文本消息发送到Claude Agent处理
     * - 通过SSE（Server-Sent Events）实时推送处理进度和结果
     * - 调用方建立SSE连接后，可持续接收turn/started、turn/turnOutput等事件
     *
     * SSE事件类型：
     * - ready：连接就绪
     * - turn/started：Claude开始处理
     * - stream_delta：处理输出（包含AI回复文本片段）
     * - complete：处理完成
     * - ping：心跳保活
     * - turn/completed：处理完成
     * - turn/aborted：处理被中断
     *
     * @param body 请求体，包含会话ID（sessionId字段）和消息文本（text字段）
     * @return SSE事件流
     */
    @PostMapping(value = "/sendMessageStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@RequestBody Map<String, Object> body) {
        String userId = (String) body.get("userId");
        String sessionId = (String) body.get("sessionId");
        String text = (String) body.get("text");
        String sessionPath = "";
        if(userId == null || userId.trim().isEmpty()) {
            sessionPath = "/home/ubuntu";
        }else{
            sessionPath = "/home/ubuntu/" + userId;
        }

        if (sessionId == null || sessionId.isEmpty() || text == null || text.isEmpty()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().data("{\"error\":\"missing required parameters\"}"));
            } catch (IOException e) {
                logger.error("Failed to send error event", e);
            }
            emitter.complete();
            return emitter;
        }

        final String finalSessionId = sessionId;
        final String finalText = text;

        ClaudeSession session = sessions.get(finalSessionId);
        if (session == null) {
            session = new ClaudeSession();
            session.id = finalSessionId;
            session.cwd = sessionPath;
            session.preview = finalText.length() > 50 ? finalText.substring(0, 50) + "..." : finalText;
            session.createdAt = System.currentTimeMillis() / 1000;
            session.updatedAt = session.createdAt;
            session.inProgress = false;
            session.isPlaceholder = false;
            sessions.put(finalSessionId, session);
            sessionMessages.put(finalSessionId, new ArrayList<>());
        }

        SseEmitter emitter = new SseEmitter(0L);
        final long startTime = System.currentTimeMillis();
        final long MAX_EXECUTION_TIME = 300000;
        final AtomicBoolean completed = new AtomicBoolean(false);

        final ClaudeSession finalSession = session;
        finalSession.inProgress = true;
        finalSession.updatedAt = System.currentTimeMillis() / 1000;

        List<Map<String, Object>> msgs = sessionMessages.computeIfAbsent(finalSessionId, k -> new ArrayList<>());
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("text", finalText);
        userMsg.put("id", "u_" + System.currentTimeMillis());
        msgs.add(userMsg);

        Map<String, Object> chatMsg = new HashMap<>();
        chatMsg.put("type", "chat");
        chatMsg.put("prompt", finalText);
        Map<String, Object> options = new HashMap<>();

        String sdkSessionId = frontendToSdkId.get(finalSessionId);
        options.put("sessionId", sdkSessionId != null ? sdkSessionId : finalSessionId);
        options.put("cwd", finalSession.cwd);
        if (!finalSession.isPlaceholder) {
            options.put("resume", true);
        }
        if (finalSession.options != null) {
            options.putAll(finalSession.options);
        }
        options.put("permissionMode", "default");
        options.put("maxTurns", 50);
        chatMsg.put("options", options);

        executor.submit(() -> {
            try {
                emitter.send(SseEmitter.event()
                    .data("{\"method\":\"ready\",\"params\":{\"sessionId\":\"" + finalSessionId + "\"}}"));

                sseEventService.createEventStream()
                    .subscribe(
                        event -> {
                            if (completed.get()) {
                                return;
                            }
                            try {
                                if ("ping".equals(event.getMethod())) {
                                    emitter.send(": ping\n\n", MediaType.TEXT_PLAIN);
                                    long elapsed = System.currentTimeMillis() - startTime;
                                    if (elapsed > MAX_EXECUTION_TIME) {
                                        emitter.send(SseEmitter.event().data("{\"timeout\":true,\"message\":\"execution timeout\"}"));
                                        emitter.complete();
                                        completed.set(true);
                                    }
                                    return;
                                }

                                Object paramsObj = event.getParams();
                                if (paramsObj instanceof Map) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> paramsMap = (Map<String, Object>) paramsObj;
                                    Object eventSessionId = paramsMap.get("sessionId");
                                    if (eventSessionId != null && !finalSessionId.equals(eventSessionId.toString())) {
                                        return;
                                    }
                                }

                                String data = objectMapper.writeValueAsString(event);
                                emitter.send(SseEmitter.event().data(data));

                                if ("complete".equals(event.getMethod()) || "result".equals(event.getMethod())) {
                                    finalSession.inProgress = false;
                                    finalSession.updatedAt = System.currentTimeMillis() / 1000;
                                    emitter.complete();
                                    completed.set(true);
                                } else if ("error".equals(event.getMethod()) || "aborted".equals(event.getMethod())) {
                                    finalSession.inProgress = false;
                                    emitter.complete();
                                    completed.set(true);
                                }
                            } catch (IOException e) {
                                logger.debug("SSE send failed for session: {}", finalSessionId);
                                if (completed.compareAndSet(false, true)) {
                                    emitter.completeWithError(e);
                                }
                            } catch (IllegalStateException e) {
                                logger.debug("SSE emitter already completed for session: {}", finalSessionId);
                                completed.set(true);
                            }
                        },
                        error -> {
                            logger.error("SSE stream error for session: {}", finalSessionId, error);
                            if (completed.compareAndSet(false, true)) {
                                emitter.completeWithError(error);
                            }
                        },
                        () -> {
                            logger.debug("SSE stream completed for session: {}", finalSessionId);
                            if (completed.compareAndSet(false, true)) {
                                emitter.complete();
                            }
                        }
                    );

                appServerProcess.sendRaw(objectMapper.writeValueAsString(chatMsg));

                String title = finalText.length() > 12 ? finalText.substring(0, 12) : finalText;
                try {
                    AiSession aiSession = aiSessionMapper.selectBySessionId(finalSessionId);
                    if (aiSession != null) {
                        aiSession.setSessionStatus("active");
                        aiSession.setSessionTitle(title);
                        aiSessionMapper.update(aiSession);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to update session status in MySQL: {}", e.getMessage());
                }

            } catch (Exception e) {
                logger.error("send message failed for session {}: {}", finalSessionId, e.getMessage());
                try {
                    emitter.send(SseEmitter.event().data("{\"error\":\"" + e.getMessage() + "\"}"));
                } catch (IOException ex) {
                    logger.error("Failed to send error event", ex);
                }
                emitter.complete();
            }
        });

        emitter.onCompletion(() -> {
            completed.set(true);
            logger.debug("SSE connection completed for session: {}", finalSessionId);
        });
        emitter.onTimeout(() -> {
            completed.set(true);
            logger.debug("SSE connection timeout for session: {}", finalSessionId);
        });
        emitter.onError(e -> {
            completed.set(true);
            logger.debug("SSE connection error for session: {}", finalSessionId, e);
        });

        return emitter;
    }

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancelSession(@RequestBody Map<String, Object> body) {
        try {
            String sessionId = (String) body.get("sessionId");
            if (sessionId == null || sessionId.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(400, "sessionId is required"));
            }
            
            ClaudeSession session = sessions.get(sessionId);
            if (session != null) {
                session.inProgress = false;
            }
            Map<String, Object> abortMsg = new HashMap<>();
            abortMsg.put("type", "abort");
            abortMsg.put("sessionId", sessionId);
            appServerProcess.sendRaw(objectMapper.writeValueAsString(abortMsg));
            
            return ResponseEntity.ok(ApiResponse.success(null, "cancelled"));
        } catch (Exception e) {
            logger.error("Failed to cancel session", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/approval")
    public ResponseEntity<Map<String, Object>> sendApproval(@RequestBody Map<String, Object> body) {
        try {
            String sessionId = (String) body.get("sessionId");
            String action = (String) body.get("action");
            String approvalId = (String) body.get("approvalId");
            
            if (sessionId == null || sessionId.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(400, "sessionId is required"));
            }
            
            if (approvalId != null) {
                Map<String, Object> respMsg = new HashMap<>();
                respMsg.put("type", "permission_response");
                respMsg.put("requestId", approvalId);
                respMsg.put("allow", "approve".equalsIgnoreCase(action));
                appServerProcess.sendRaw(objectMapper.writeValueAsString(respMsg));
            }
            
            Map<String, Object> data = new HashMap<>();
            data.put("ok", true);
            
            return ResponseEntity.ok(ApiResponse.success(data, "approval sent"));
        } catch (Exception e) {
            logger.error("Failed to send approval", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<Map<String, Object>> stopSession(@RequestBody Map<String, Object> body) {
        try {
            String sessionId = (String) body.get("sessionId");
            String userId = (String) body.get("userId");
            String sessionPath = "";
            if(userId == null || userId.trim().isEmpty()) {
                sessionPath = "/home/ubuntu";
            }else{
                sessionPath = "/home/ubuntu/" + userId;
            }
            if (sessionId == null || sessionId.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(400, "sessionId is required"));
            }
            
            String sdkId = frontendToSdkId.get(sessionId);
            String queryId = sdkId != null ? sdkId : sessionId;

            try {
                appServerProcess.sendRawRequest(
                    "delete_session",
                    Map.of("sessionId", queryId, "cwd", sessionPath),
                    10000
                );
            } catch (Exception ex) {
                logger.warn("SDK delete_session failed for {}: {}", queryId, ex.getMessage());
            }

            if (sdkId != null) {
                sdkToFrontendId.remove(sdkId);
                frontendToSdkId.remove(sessionId);
            } else {
                String frontendId = sdkToFrontendId.remove(sessionId);
                if (frontendId != null) {
                    frontendToSdkId.remove(frontendId);
                }
            }

            sessions.remove(sessionId);
            sessionMessages.remove(sessionId);
            
            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", sessionId);
            data.put("status", "archived");
            
            return ResponseEntity.ok(ApiResponse.success(data, "archived"));
        } catch (Exception e) {
            logger.error("Failed to stop session", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/searchFiles")
    public ResponseEntity<Map<String, Object>> searchFiles(@RequestBody Map<String, Object> body) {
        try {
            String sessionId = (String) body.get("sessionId");
            String q = (String) body.get("q");
            Integer limit = body.get("limit") != null ? 
                (body.get("limit") instanceof Integer ? (Integer) body.get("limit") : Integer.parseInt(body.get("limit").toString())) 
                : 10;
            
            if (sessionId == null || sessionId.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(400, "sessionId is required"));
            }
            
            List<Map<String, Object>> files = new ArrayList<>();
            return ResponseEntity.ok(ApiResponse.success(files));
        } catch (Exception e) {
            logger.error("Failed to search files", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    @PostMapping("/updateTitle")
    public ResponseEntity<Map<String, Object>> updateTitle(@RequestBody Map<String, Object> body) {
        try {
            String sessionId = (String) body.get("sessionId");
            String title = (String) body.get("title");
            
            if (sessionId == null || sessionId.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(400, "sessionId is required"));
            }
            if (title == null || title.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(400, "title is required"));
            }
            
            ClaudeSession session = sessions.get(sessionId);
            if (session != null) {
                session.preview = title;
                session.updatedAt = System.currentTimeMillis() / 1000;
            }

            try {
                AiSession aiSession = aiSessionMapper.selectBySessionId(sessionId);
                if (aiSession != null) {
                    aiSession.setSessionTitle(title);
                    aiSessionMapper.update(aiSession);
                }
            } catch (Exception e) {
                logger.warn("Failed to update session title in MySQL: {}", e.getMessage());
            }

            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", sessionId);
            data.put("title", title);

            return ResponseEntity.ok(ApiResponse.success(data, "title updated"));
        } catch (Exception e) {
            logger.error("Failed to update title", e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }

    public void handleClaudeAgentMessage(Map<String, Object> msg) {
        String type = (String) msg.get("type");
        String sessionId = (String) msg.get("sessionId");

        if (sessionId != null) {
            String frontendId = sdkToFrontendId.get(sessionId);
            if (frontendId != null) {
                sessionId = frontendId;
            }
        }

        if ("session_created".equals(type) && msg.get("sessionId") != null) {
            String sdkSessionId = (String) msg.get("sessionId");

            ClaudeSession existingSession = sessions.get(sdkSessionId);
            if (existingSession != null && !existingSession.isPlaceholder) {
                existingSession.inProgress = true;
                existingSession.updatedAt = System.currentTimeMillis() / 1000;
                cleanupPlaceholderSessions();
                return;
            }

            String existingFrontendId = sdkToFrontendId.get(sdkSessionId);
            if (existingFrontendId != null) {
                ClaudeSession existing = sessions.get(existingFrontendId);
                if (existing != null) {
                    existing.isPlaceholder = false;
                    existing.inProgress = true;
                    existing.updatedAt = System.currentTimeMillis() / 1000;
                }
                cleanupPlaceholderSessions();
                return;
            }

            ClaudeSession resumingSession = null;
            for (ClaudeSession s : sessions.values()) {
                if (!s.isPlaceholder && s.inProgress) {
                    if (resumingSession == null || s.updatedAt > resumingSession.updatedAt) {
                        resumingSession = s;
                    }
                }
            }
            if (resumingSession != null) {
                long ageSeconds = (System.currentTimeMillis() / 1000) - resumingSession.updatedAt;
                if (ageSeconds < 30) {
                    frontendToSdkId.put(resumingSession.id, sdkSessionId);
                    sdkToFrontendId.put(sdkSessionId, resumingSession.id);
                    logger.info("Mapped resuming session {} to SDK session {}", resumingSession.id, sdkSessionId);
                    resumingSession.updatedAt = System.currentTimeMillis() / 1000;
                    cleanupPlaceholderSessions();
                    return;
                }
            }

            ClaudeSession placeholder = findRecentPlaceholder();
            if (placeholder != null && !sdkSessionId.equals(placeholder.id)) {
                frontendToSdkId.put(placeholder.id, sdkSessionId);
                sdkToFrontendId.put(sdkSessionId, placeholder.id);
                logger.info("Mapped frontend session {} to SDK session {}", placeholder.id, sdkSessionId);

                placeholder.isPlaceholder = false;
                placeholder.inProgress = true;
                placeholder.updatedAt = System.currentTimeMillis() / 1000;
                return;
            } else {
                cleanupPlaceholderSessions();
            }

            ClaudeSession session = sessions.get(sessionId);
            if (session == null) {
                session = new ClaudeSession();
                session.id = sessionId;
                session.cwd = "";
                session.preview = "Session";
                session.createdAt = System.currentTimeMillis() / 1000;
                session.updatedAt = session.createdAt;
                session.inProgress = true;
                session.isPlaceholder = false;
                sessions.put(sessionId, session);
                sessionMessages.put(sessionId, new ArrayList<>());
            }
        }

        if ("stream_delta".equals(type) && sessionId != null) {
            String content = (String) msg.get("content");
            if (content != null) {
                List<Map<String, Object>> msgs = sessionMessages.computeIfAbsent(sessionId, k -> new ArrayList<>());
                if (!msgs.isEmpty()) {
                    Map<String, Object> last = msgs.get(msgs.size() - 1);
                    if ("assistant".equals(last.get("role"))) {
                        last.put("text", last.get("text") + content);
                        return;
                    }
                }
                Map<String, Object> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("text", content);
                assistantMsg.put("id", "a_" + System.currentTimeMillis());
                msgs.add(assistantMsg);
            }
        }

        if ("result".equals(type) && sessionId != null) {
            ClaudeSession session = sessions.get(sessionId);
            if (session != null) {
                session.inProgress = false;
                session.updatedAt = System.currentTimeMillis() / 1000;
                @SuppressWarnings("unchecked")
                List<String> resultCategories = (List<String>) msg.get("resultCategories");
                if (resultCategories != null && !resultCategories.isEmpty()) {
                    session.preview = String.join(", ", resultCategories);
                }
            }
            cleanupPlaceholderSessions();
        }
    }

    private List<Map<String, Object>> convertAgentMessages(List<Map<String, Object>> agentMessages) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (agentMessages == null) return result;

        for (Map<String, Object> msg : agentMessages) {
            String type = (String) msg.get("type");
            String uuid = (String) msg.get("uuid");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) msg.get("message");

            if ("user".equals(type)) {
                Map<String, Object> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("id", uuid);
                userMsg.put("text", extractTextFromContent(message));
                result.add(userMsg);
            } else if ("assistant".equals(type)) {
                String text = extractTextFromContent(message);
                String thinking = extractThinkingFromContent(message);
                if (thinking != null && !thinking.isEmpty()) {
                    Map<String, Object> thinkMsg = new HashMap<>();
                    thinkMsg.put("role", "thinking");
                    thinkMsg.put("id", uuid + "_think");
                    thinkMsg.put("text", thinking);
                    result.add(thinkMsg);
                }
                if (text != null && !text.isEmpty()) {
                    Map<String, Object> assistantMsg = new HashMap<>();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("id", uuid);
                    assistantMsg.put("text", text);
                    result.add(assistantMsg);
                }
            }
        }
        return result;
    }

    private String extractThinkingFromContent(Map<String, Object> message) {
        if (message == null) return "";
        Object content = message.get("content");
        if (content instanceof List) {
            List<?> blocks = (List<?>) content;
            StringBuilder sb = new StringBuilder();
            for (Object blockObj : blocks) {
                if (blockObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> block = (Map<String, Object>) blockObj;
                    String blockType = (String) block.get("type");
                    if ("thinking".equals(blockType)) {
                        String thinking = (String) block.get("thinking");
                        if (thinking != null) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(thinking);
                        }
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String extractTextFromContent(Map<String, Object> message) {
        if (message == null) return "";
        Object content = message.get("content");
        if (content instanceof List) {
            List<?> blocks = (List<?>) content;
            StringBuilder sb = new StringBuilder();
            for (Object blockObj : blocks) {
                if (blockObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> block = (Map<String, Object>) blockObj;
                    String blockType = (String) block.get("type");
                    if ("text".equals(blockType)) {
                        String text = (String) block.get("text");
                        if (text != null) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(text);
                        }
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private List<Map<String, Object>> buildTurns(List<Map<String, Object>> msgs) {
        List<Map<String, Object>> turns = new ArrayList<>();
        if (msgs == null || msgs.isEmpty()) {
            return turns;
        }
        // Convert internal message format to frontend-compatible format
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> msg : msgs) {
            String role = (String) msg.get("role");
            Map<String, Object> item = new HashMap<>();
            item.put("id", msg.get("id"));
            if ("assistant".equals(role)) {
                item.put("type", "agentMessage");
                item.put("text", msg.get("text"));
            } else if ("thinking".equals(role)) {
                item.put("type", "thinkingMessage");
                item.put("text", msg.get("text"));
            } else if ("user".equals(role)) {
                item.put("type", "userMessage");
                List<Map<String, Object>> content = new ArrayList<>();
                Map<String, Object> textBlock = new HashMap<>();
                textBlock.put("type", "text");
                textBlock.put("text", msg.get("text"));
                content.add(textBlock);
                item.put("content", content);
            }
            items.add(item);
        }
        Map<String, Object> turn = new HashMap<>();
        turn.put("id", "turn_1");
        turn.put("items", items);
        turns.add(turn);
        return turns;
    }

    private Map<String, Object> extractSessionOptions(Map<String, Object> body) {
        Map<String, Object> options = new HashMap<>();
        
        String permissionMode = (String) body.get("permissionMode");
        if (permissionMode != null) {
            options.put("permissionMode", permissionMode);
        }
        
        String allowedTools = (String) body.get("allowedTools");
        if (allowedTools != null) {
            options.put("allowedTools", Arrays.asList(allowedTools.split(",")));
        }
        
        String disallowedTools = (String) body.get("disallowedTools");
        if (disallowedTools != null) {
            options.put("disallowedTools", Arrays.asList(disallowedTools.split(",")));
        }
        
        Object maxTurns = body.get("maxTurns");
        if (maxTurns != null) {
            options.put("maxTurns", maxTurns instanceof Integer ? maxTurns : Integer.parseInt(maxTurns.toString()));
        }
        
        return options.isEmpty() ? null : options;
    }

    private void cleanupPlaceholderSessions() {
        long cutoff = System.currentTimeMillis() / 1000 - 300;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, ClaudeSession> entry : sessions.entrySet()) {
            ClaudeSession s = entry.getValue();
            if (s.isPlaceholder && s.updatedAt < cutoff) {
                toRemove.add(entry.getKey());
            }
        }
        for (String id : toRemove) {
            sessions.remove(id);
            sessionMessages.remove(id);
        }
    }

    private ClaudeSession findRecentPlaceholder() {
        long cutoff = System.currentTimeMillis() / 1000 - 60;
        for (ClaudeSession s : sessions.values()) {
            if (s.isPlaceholder && s.createdAt >= cutoff) {
                return s;
            }
        }
        return null;
    }

    private static class ClaudeSession {
        String id;
        String userId;
        String appId;
        String cwd;
        String preview;
        long createdAt;
        long updatedAt;
        boolean inProgress;
        boolean isPlaceholder;
        Map<String, Object> options;
    }

    static class ApiResponse {
        private int code;
        private String message;
        private Object data;

        public ApiResponse(int code, String message, Object data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }

        public static Map<String, Object> success(Object data) {
            return Map.of("code", 200, "message", "success", "data", data != null ? data : new Object());
        }

        public static Map<String, Object> success(Object data, String message) {
            return Map.of("code", 200, "message", message, "data", data != null ? data : new Object());
        }

        public static Map<String, Object> error(int code, String message) {
            return Map.of("code", code, "message", message);
        }
    }
}

package com.claude.web.controller;

import com.claude.web.config.ClaudeProperties;
import com.claude.web.service.AppServerProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SessionApiController - Provides session management API compatible with frontend expectations.
 * Maps to /api/claude endpoints.
 *
 * Acts as a stateful proxy that translates REST API calls to Claude Agent WebSocket messages.
 * Now supports claude-agent-sdk features: session recovery, cwd, permissionMode, allowedTools, etc.
 */
@RestController
@RequestMapping("/api/claude")
public class SessionApiController {

    private static final Logger logger = LoggerFactory.getLogger(SessionApiController.class);

    private final AppServerProcess appServerProcess;
    private final ObjectMapper objectMapper;
    private final ClaudeProperties claudeProperties;

    // In-memory session store
    private final Map<String, ClaudeSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> sessionMessages = new ConcurrentHashMap<>();

    // Mapping between frontend-generated session IDs and SDK-generated session IDs.
    // When the SDK ignores the client-provided sessionId and generates its own,
    // we map the frontend ID (shown to the user) to the SDK ID (used for storage).
    private final Map<String, String> frontendToSdkId = new ConcurrentHashMap<>();
    private final Map<String, String> sdkToFrontendId = new ConcurrentHashMap<>();

    public SessionApiController(AppServerProcess appServerProcess, ObjectMapper objectMapper, ClaudeProperties claudeProperties) {
        this.appServerProcess = appServerProcess;
        this.objectMapper = objectMapper;
        this.claudeProperties = claudeProperties;
        
        // Register notification listener for Claude Agent protocol messages
        this.appServerProcess.addNotificationListener(event -> {
            String method = event.getMethod();
            Object params = event.getParams();
            if (params instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = new HashMap<>((Map<String, Object>) params);
                // Ensure type field is set for Claude Agent protocol messages
                if (method != null && !method.startsWith("connection/") && !method.startsWith("server/")) {
                    msg.put("type", method);
                }
                handleClaudeAgentMessage(msg);

                // Translate SDK session ID to frontend session ID in the original event
                // so downstream SSE listeners send the frontend ID to the browser.
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

    /**
     * List all sessions.
     * Queries both in-memory sessions and Claude Agent local session storage.
     * SDK summaries take precedence over in-memory previews to avoid
     * "Session" / "New Session" placeholders overriding historical titles.
     */
    @GetMapping
    public ResponseEntity<?> listSessions() {
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            Map<String, Map<String, Object>> resultById = new HashMap<>();

            // First, query Claude Agent for locally stored sessions — these have
            // the canonical summaries and should be the source of truth for titles.
            try {
                Map<String, Object> response = appServerProcess.sendRawRequest(
                    "list_sessions",
                    Map.of("cwd", "/home/ubuntu"),
                    10000
                );
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> agentSessions = (List<Map<String, Object>>) response.get("sessions");
                if (agentSessions != null) {
                    for (Map<String, Object> s : agentSessions) {
                        String sessionId = (String) s.get("sessionId");
                        if (sessionId == null) continue;
                        // Skip SDK sessions already mapped to a frontend session
                        // to avoid duplicates in the sidebar.
                        if (sdkToFrontendId.containsKey(sessionId)) continue;

                        Map<String, Object> item = new HashMap<>();
                        item.put("id", sessionId);
                        item.put("cwd", s.get("cwd"));
                        item.put("preview", s.get("summary"));
                        Object createdAt = s.get("createdAt");
                        if (createdAt instanceof Number) {
                            item.put("createdAt", ((Number) createdAt).longValue());
                        } else {
                            item.put("createdAt", System.currentTimeMillis() / 1000);
                        }
                        Object lastModified = s.get("lastModified");
                        if (lastModified instanceof Number) {
                            item.put("updatedAt", ((Number) lastModified).longValue() / 1000);
                        } else {
                            item.put("updatedAt", System.currentTimeMillis() / 1000);
                        }
                        item.put("inProgress", false);
                        result.add(item);
                        resultById.put(sessionId, item);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to query Claude Agent for local sessions: {}", e.getMessage());
            }

            // Merge in-memory sessions on top of SDK sessions.
            // In-memory data provides live inProgress status; SDK data provides the title.
            for (ClaudeSession session : sessions.values()) {
                if (session.isPlaceholder) continue;
                Map<String, Object> item = resultById.get(session.id);
                if (item == null) {
                    // Session exists only in memory (not yet persisted to SDK or new session)
                    item = new HashMap<>();
                    item.put("id", session.id);
                    item.put("cwd", session.cwd);
                    item.put("preview", session.preview);
                    item.put("createdAt", session.createdAt);
                    item.put("updatedAt", session.updatedAt);
                    item.put("inProgress", session.inProgress);
                    result.add(item);
                    resultById.put(session.id, item);
                } else {
                    // Merge: keep SDK summary unless it's empty, but use memory inProgress
                    Object sdkSummary = item.get("preview");
                    if (sdkSummary == null || sdkSummary.toString().isEmpty()) {
                        item.put("preview", session.preview);
                    }
                    item.put("inProgress", session.inProgress);
                    // Use the most recent updatedAt
                    item.put("updatedAt", Math.max(
                        ((Number) item.get("updatedAt")).longValue(),
                        session.updatedAt
                    ));
                }
            }

            return ResponseEntity.ok(Map.of("data", result));
        } catch (Exception e) {
            logger.error("Failed to list sessions", e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get a specific session by ID.
     * Queries in-memory messages first; falls back to Claude Agent local storage.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id) {
        try {
            ClaudeSession session = sessions.get(id);
            List<Map<String, Object>> msgs;
            String cwd;

            // Resolve to the SDK session ID if a mapping exists.
            String sdkId = frontendToSdkId.get(id);
            String queryId = sdkId != null ? sdkId : id;

            // Always query the SDK local storage when we know the SDK session ID.
            // The SDK is the source of truth; in-memory messages may be incomplete
            // if the WebSocket connection dropped during streaming.
            if (sdkId != null) {
                Map<String, Object> response = appServerProcess.sendRawRequest(
                    "get_session_messages",
                    Map.of("sessionId", queryId, "cwd", "/home/ubuntu"),
                    15000
                );
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> agentMessages = (List<Map<String, Object>>) response.get("messages");
                msgs = convertAgentMessages(agentMessages);
                cwd = session != null ? session.cwd : "/home/ubuntu";
            } else if (session != null) {
                if (session.inProgress) {
                    // Actively streaming — merge SDK historical messages with in-memory messages
                    // so the just-sent user message and any partial assistant content remain visible.
                    try {
                        Map<String, Object> response = appServerProcess.sendRawRequest(
                            "get_session_messages",
                            Map.of("sessionId", queryId, "cwd", "/home/ubuntu"),
                            15000
                        );
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> agentMessages = (List<Map<String, Object>>) response.get("messages");
                        msgs = new ArrayList<>(convertAgentMessages(agentMessages));
                        // Append in-memory messages (user message + streaming assistant content)
                        List<Map<String, Object>> memMsgs = sessionMessages.getOrDefault(id, new ArrayList<>());
                        msgs.addAll(memMsgs);
                    } catch (Exception e) {
                        // Fallback to in-memory only if SDK query fails mid-stream
                        msgs = sessionMessages.getOrDefault(id, new ArrayList<>());
                    }
                } else {
                    // Not streaming — SDK is the source of truth for complete history
                    Map<String, Object> response = appServerProcess.sendRawRequest(
                        "get_session_messages",
                        Map.of("sessionId", queryId, "cwd", "/home/ubuntu"),
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
                    Map.of("sessionId", queryId, "cwd", "/home/ubuntu"),
                    15000
                );
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> agentMessages = (List<Map<String, Object>>) response.get("messages");
                msgs = convertAgentMessages(agentMessages);
                cwd = "/home/ubuntu";
            }

            Map<String, Object> thread = new HashMap<>();
            thread.put("id", id);
            thread.put("cwd", cwd);
            thread.put("turns", buildTurns(msgs));
            return ResponseEntity.ok(Map.of("thread", thread));
        } catch (Exception e) {
            logger.error("Failed to get session: {}", id, e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Create a new session.
     * Supports options: cwd, name, permissionMode, allowedTools, disallowedTools, maxTurns
     */
    @PostMapping
    public ResponseEntity<?> createSession(@RequestBody Map<String, Object> body) {
        try {
            String cwd = (String) body.get("cwd");
            String name = (String) body.get("name");

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
            return ResponseEntity.ok(Map.of("claudeId", id));
        } catch (Exception e) {
            logger.error("Failed to create session", e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Send a message to a session.
     * Supports resuming existing sessions via sessionId.
     */
    @PostMapping("/{id}/message")
    public ResponseEntity<?> sendMessage(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            String text = body.get("text");
            if (text == null || text.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
            }

            ClaudeSession session = sessions.get(id);
            if (session == null) {
                // Historical session from SDK local storage not yet loaded into memory.
                // Create a lightweight in-memory entry so subsequent messages target
                // the same session and replies are tracked under the same ID.
                session = new ClaudeSession();
                session.id = id;
                session.cwd = "/home/ubuntu";
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

            return ResponseEntity.ok(Map.of("ok", true, "requestId", System.currentTimeMillis()));
        } catch (Exception e) {
            logger.error("Failed to send message to session: {}", id, e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Cancel current operation in a session.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelSession(@PathVariable String id) {
        try {
            ClaudeSession session = sessions.get(id);
            if (session != null) {
                session.inProgress = false;
            }
            Map<String, Object> abortMsg = new HashMap<>();
            abortMsg.put("type", "abort");
            abortMsg.put("sessionId", id);
            appServerProcess.sendRaw(objectMapper.writeValueAsString(abortMsg));
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Failed to cancel session: {}", id, e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Send approval/denial for a pending action.
     * Supports rememberEntry for persistent permission rules.
     */
    @PostMapping("/{id}/approval")
    public ResponseEntity<?> sendApproval(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            String requestId = (String) body.get("requestId");
            Boolean allow = (Boolean) body.get("allow");
            String rememberEntry = (String) body.get("rememberEntry");
            if (requestId != null) {
                Map<String, Object> respMsg = new HashMap<>();
                respMsg.put("type", "permission_response");
                respMsg.put("requestId", requestId);
                respMsg.put("allow", allow != null ? allow : true);
                if (rememberEntry != null) {
                    respMsg.put("rememberEntry", rememberEntry);
                }
                appServerProcess.sendRaw(objectMapper.writeValueAsString(respMsg));
            }
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            logger.error("Failed to send approval for session: {}", id, e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Stop/close a session.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> stopSession(@PathVariable String id) {
        try {
            // Resolve to SDK session ID if mapping exists
            String sdkId = frontendToSdkId.get(id);
            String queryId = sdkId != null ? sdkId : id;

            // Call SDK to delete the persistent session
            try {
                appServerProcess.sendRawRequest(
                    "delete_session",
                    Map.of("sessionId", queryId, "cwd", "/home/ubuntu"),
                    10000
                );
            } catch (Exception ex) {
                logger.warn("SDK delete_session failed for {}: {}", queryId, ex.getMessage());
                // Continue with local cleanup even if SDK delete fails
            }

            // Clean up bidirectional ID mappings
            if (sdkId != null) {
                sdkToFrontendId.remove(sdkId);
                frontendToSdkId.remove(id);
            } else {
                String frontendId = sdkToFrontendId.remove(id);
                if (frontendId != null) {
                    frontendToSdkId.remove(frontendId);
                }
            }

            sessions.remove(id);
            sessionMessages.remove(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Failed to stop session: {}", id, e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Search files in a session.
     */
    @GetMapping("/{id}/files")
    public ResponseEntity<?> searchFiles(@PathVariable String id, @RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
        try {
            return ResponseEntity.ok(Map.of("data", new ArrayList<>()));
        } catch (Exception e) {
            logger.error("Failed to search files in session: {}", id, e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Handle incoming Claude Agent messages (called by SSE event handler).
     */
    public void handleClaudeAgentMessage(Map<String, Object> msg) {
        String type = (String) msg.get("type");
        String sessionId = (String) msg.get("sessionId");

        // Translate SDK session ID back to frontend session ID if a mapping exists.
        // The SDK may generate its own session ID that differs from the frontend-provided one.
        if (sessionId != null) {
            String frontendId = sdkToFrontendId.get(sessionId);
            if (frontendId != null) {
                sessionId = frontendId;
            }
        }

        if ("session_created".equals(type) && msg.get("sessionId") != null) {
            String sdkSessionId = (String) msg.get("sessionId");

            // If this SDK session ID is already tracked as a non-placeholder session
            // (e.g., a historical session loaded from list_sessions that was just resumed
            // in sendMessage()), update it directly instead of mapping a placeholder to it.
            // This prevents an unrelated placeholder session from hijacking the historical
            // session ID and showing as an erroneous duplicate entry in the sidebar.
            ClaudeSession existingSession = sessions.get(sdkSessionId);
            if (existingSession != null && !existingSession.isPlaceholder) {
                existingSession.inProgress = true;
                existingSession.updatedAt = System.currentTimeMillis() / 1000;
                cleanupPlaceholderSessions();
                return;
            }

            // Check if this SDK session is already mapped to a frontend session.
            // This handles the case where a new placeholder session was promoted earlier.
            String existingFrontendId = sdkToFrontendId.get(sdkSessionId);
            if (existingFrontendId != null) {
                // Already mapped — just update the existing session state.
                ClaudeSession existing = sessions.get(existingFrontendId);
                if (existing != null) {
                    existing.isPlaceholder = false;
                    existing.inProgress = true;
                    existing.updatedAt = System.currentTimeMillis() / 1000;
                }
                cleanupPlaceholderSessions();
                return;
            }

            // If the SDK returned a different sessionId than what we sent (can happen
            // when resuming a historical session), check if there is an in-progress
            // non-placeholder session that was recently resumed. Map to it instead of
            // an unrelated placeholder to prevent duplicate sidebar entries.
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
                if (ageSeconds < 30) {  // Only recently-active sessions (< 30s)
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
                // SDK generated a different sessionId than the frontend placeholder.
                // Record the mapping so subsequent SDK messages are routed to the frontend ID.
                frontendToSdkId.put(placeholder.id, sdkSessionId);
                sdkToFrontendId.put(sdkSessionId, placeholder.id);
                logger.info("Mapped frontend session {} to SDK session {}", placeholder.id, sdkSessionId);

                // Promote placeholder to a real session — keep using the frontend ID
                // so the frontend doesn't need to know about the SDK's internal ID.
                placeholder.isPlaceholder = false;
                placeholder.inProgress = true;
                placeholder.updatedAt = System.currentTimeMillis() / 1000;

                // Do NOT create a duplicate session entry under the SDK ID.
                // The frontend ID remains the canonical key for this conversation.
                return;
            } else {
                cleanupPlaceholderSessions();
            }

            // Only reach here when there was no placeholder (e.g. CLI-created session)
            // or the SDK reused the frontend-provided ID.
            ClaudeSession session = sessions.get(sessionId);
            if (session == null) {
                session = new ClaudeSession();
                session.id = sessionId;
                session.cwd = "";
                session.preview = "New Session";
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
                // Append to last assistant message or create new one
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

        // thinking content is extracted from assistant message content blocks
        // during convertAgentMessages; no need to store streaming thinking here.
        if ("thinking".equals(type) && sessionId != null) {
            // Streamed thinking is shown via liveOverlay only.
            // The final thinking content comes from get_session_messages.
        }

        if ("complete".equals(type) && sessionId != null) {
            ClaudeSession session = sessions.get(sessionId);
            if (session != null) {
                session.inProgress = false;
                session.updatedAt = System.currentTimeMillis() / 1000;
            }
            cleanupPlaceholderSessions();
        }

        if ("error".equals(type) && sessionId != null) {
            ClaudeSession session = sessions.get(sessionId);
            if (session != null) {
                session.inProgress = false;
            }
            cleanupPlaceholderSessions();
        }
    }

    /**
     * Convert Claude Agent SDK session messages to internal message format.
     */
    /**
     * Find the most recently created placeholder session.
     * Used when the SDK generates its own session ID to determine which
     * frontend-created placeholder session should be promoted.
     */
    private ClaudeSession findRecentPlaceholder() {
        ClaudeSession recent = null;
        for (ClaudeSession session : sessions.values()) {
            if (session.isPlaceholder) {
                if (recent == null || session.createdAt > recent.createdAt) {
                    recent = session;
                }
            }
        }
        return recent;
    }

    /**
     * Remove placeholder sessions created by createSession that were never
     * promoted to real SDK sessions. Called when the SDK creates or completes
     * a real session.
     */
    private void cleanupPlaceholderSessions() {
        for (Iterator<Map.Entry<String, ClaudeSession>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ClaudeSession> entry = it.next();
            if (entry.getValue().isPlaceholder) {
                it.remove();
                sessionMessages.remove(entry.getKey());
            }
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

    /**
     * Extract text content from SDK message content blocks.
     * Only handles 'text' block type.
     */
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

    /**
     * Extract thinking content from SDK message content blocks.
     */
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

    /**
     * Extract session-level options from create request body.
     */
    private Map<String, Object> extractSessionOptions(Map<String, Object> body) {
        Map<String, Object> options = new HashMap<>();
        
        String permissionMode = (String) body.get("permissionMode");
        if (permissionMode != null) {
            options.put("permissionMode", permissionMode);
        }
        
        Object allowedTools = body.get("allowedTools");
        if (allowedTools instanceof List) {
            options.put("allowedTools", allowedTools);
        } else if (allowedTools instanceof String) {
            options.put("allowedTools", Arrays.asList(((String) allowedTools).split(",")));
        }
        
        Object disallowedTools = body.get("disallowedTools");
        if (disallowedTools instanceof List) {
            options.put("disallowedTools", disallowedTools);
        } else if (disallowedTools instanceof String) {
            options.put("disallowedTools", Arrays.asList(((String) disallowedTools).split(",")));
        }
        
        Object maxTurns = body.get("maxTurns");
        if (maxTurns instanceof Number) {
            options.put("maxTurns", ((Number) maxTurns).intValue());
        } else if (maxTurns instanceof String) {
            try {
                options.put("maxTurns", Integer.parseInt((String) maxTurns));
            } catch (NumberFormatException e) {
                // ignore invalid value
            }
        }
        
        Object enableFileCheckpointing = body.get("enableFileCheckpointing");
        if (enableFileCheckpointing instanceof Boolean) {
            options.put("enableFileCheckpointing", (Boolean) enableFileCheckpointing);
        }
        
        Object persistSession = body.get("persistSession");
        if (persistSession instanceof Boolean) {
            options.put("persistSession", (Boolean) persistSession);
        }
        
        Object additionalDirectories = body.get("additionalDirectories");
        if (additionalDirectories instanceof List) {
            options.put("additionalDirectories", additionalDirectories);
        }
        
        return options.isEmpty() ? null : options;
    }

    private static class ClaudeSession {
        String id;
        String cwd;
        String preview;
        long createdAt;
        long updatedAt;
        boolean inProgress;
        boolean isPlaceholder;
        Map<String, Object> options;
    }
}

package com.claude.web.controller;

import com.claude.web.service.AppServerProcess;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * ClaudeApiController - Provides Claude Agent protocol compatible endpoints.
 * Maps to /claude-api endpoints.
 *
 * Updated for claude-agent-sdk: supports permission responses, session recovery,
 * and extended notification methods.
 */
@RestController
@RequestMapping("/claude-api")
public class ClaudeApiController {

    private final AppServerProcess appServerProcess;

    public ClaudeApiController(AppServerProcess appServerProcess) {
        this.appServerProcess = appServerProcess;
    }

    /**
     * RPC proxy endpoint.
     * In Claude Agent mode, this returns 403 as RPC is not supported.
     */
    @PostMapping("/rpc")
    public ResponseEntity<?> rpcProxy(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(403).body(Map.of(
            "error", "Claude Agent mode does not support direct RPC calls",
            "message", "Use /api/claude endpoints instead"
        ));
    }

    /**
     * Forward a permission response to the Claude Agent.
     * Supports rememberEntry for persistent permission rules.
     */
    @PostMapping("/server-requests/respond")
    public ResponseEntity<?> respondToServerRequest(@RequestBody Map<String, Object> body) {
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
                appServerProcess.sendRaw(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(respMsg));
            }
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List pending server requests.
     */
    @GetMapping("/server-requests/pending")
    public ResponseEntity<?> listPendingRequests() {
        return ResponseEntity.ok(Map.of("data", new ArrayList<>()));
    }

    /**
     * List supported RPC methods.
     */
    @GetMapping("/meta/methods")
    public ResponseEntity<?> listMethods() {
        return ResponseEntity.ok(Map.of("data", java.util.List.of(
            "initialize",
            "chat",
            "abort",
            "permission_response",
            "resume_session",
            "list_sessions"
        )));
    }

    /**
     * List supported notification methods.
     * Updated for claude-agent-sdk protocol.
     */
    @GetMapping("/meta/notifications")
    public ResponseEntity<?> listNotificationMethods() {
        return ResponseEntity.ok(Map.of("data", java.util.List.of(
            "session_created",
            "session_resumed",
            "stream_delta",
            "stream_end",
            "tool_use",
            "tool_result",
            "thinking",
            "permission_request",
            "permission_cancelled",
            "complete",
            "error",
            "system/init",
            "pong",
            "connected"
        )));
    }
}

package com.claude.web.controller;

import com.claude.web.config.ClaudeProperties;
import com.claude.web.service.AppServerProcess;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 运行时切换 Claude Agent 连接的 REST 接口。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AppServerProcess appServerProcess;
    private final ClaudeProperties properties;

    public AgentController(AppServerProcess appServerProcess, ClaudeProperties properties) {
        this.appServerProcess = appServerProcess;
        this.properties = properties;
    }

    /** GET /api/agent/status — 查询当前连接状态与目标地址 */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        ClaudeProperties.ClaudeAgent agent = properties.getClaudeAgent();
        return ResponseEntity.ok(Map.of(
                "host",      agent.getHost(),
                "port",      agent.getPort(),
                "connected", appServerProcess.isConnected(),
                "apiKeySet", agent.getApiKey() != null && !agent.getApiKey().isEmpty()
        ));
    }

    /** POST /api/agent/switch — 切换到指定的 Claude Agent */
    @PostMapping("/switch")
    public ResponseEntity<?> switchAgent(@RequestBody SwitchRequest body) {
        if (body.host() == null || body.host().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "host 不能为空"));
        }
        if (body.port() <= 0 || body.port() > 65535) {
            return ResponseEntity.badRequest().body(Map.of("error", "port 范围应在 1–65535 之间"));
        }
        try {
            appServerProcess.switchAgent(body.host(), body.port(), body.apiKey());
            return ResponseEntity.ok(Map.of(
                    "ok",   true,
                    "host", body.host(),
                    "port", body.port()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "连接失败: " + e.getMessage()));
        }
    }

    private record SwitchRequest(String host, int port, String apiKey) {}
}
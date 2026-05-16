package com.claude.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "claude.web")
public class ClaudeProperties {
    
    private String password = "";
    private boolean passwordEnabled = true;
    
    // Auto-approve server permission requests from claude
    private boolean autoApprove = false;
    
    // Claude Agent Configuration
    private ClaudeAgent claudeAgent = new ClaudeAgent();
    
    // Default session options for claude-agent-sdk
    private SessionDefaults sessionDefaults = new SessionDefaults();
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public boolean isPasswordEnabled() {
        return passwordEnabled;
    }
    
    public void setPasswordEnabled(boolean passwordEnabled) {
        this.passwordEnabled = passwordEnabled;
    }
    
    public boolean isAuthEnabled() {
        return passwordEnabled && password != null && !password.isEmpty();
    }
    
    public boolean isAutoApprove() {
        return autoApprove;
    }
    
    public void setAutoApprove(boolean autoApprove) {
        this.autoApprove = autoApprove;
    }
    
    public boolean isRemoteMode() {
        return claudeAgent != null && claudeAgent.getHost() != null && !claudeAgent.getHost().isEmpty();
    }
    
    public ClaudeAgent getClaudeAgent() {
        return claudeAgent;
    }
    
    public void setClaudeAgent(ClaudeAgent claudeAgent) {
        this.claudeAgent = claudeAgent;
    }
    
    public SessionDefaults getSessionDefaults() {
        return sessionDefaults;
    }
    
    public void setSessionDefaults(SessionDefaults sessionDefaults) {
        this.sessionDefaults = sessionDefaults;
    }
    
    public static class ClaudeAgent {
        private String host = "127.0.0.1";
        private int port = 8011;
        private String apiKey = "";
        private int connectionTimeout = 30000;
        
        public String getHost() {
            return host;
        }
        
        public void setHost(String host) {
            this.host = host;
        }
        
        public int getPort() {
            return port;
        }
        
        public void setPort(int port) {
            this.port = port;
        }
        
        public String getApiKey() {
            return apiKey;
        }
        
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
        
        public int getConnectionTimeout() {
            return connectionTimeout;
        }
        
        public void setConnectionTimeout(int connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }
    }
    
    /**
     * Default session options for claude-agent-sdk.
     * These can be overridden per-session or per-message via the API.
     */
    public static class SessionDefaults {
        // Model name (e.g., claude-sonnet-4-6, claude-opus-4). No default set.
        private String model;
        
        // Default working directory for the agent
        private String cwd = "/home/ubuntu";
        
        // Permission mode: default, acceptEdits, bypassPermissions, rejectAll
        private String permissionMode = "bypassPermissions";
        
        // Maximum turns per query
        private int maxTurns = 50;
        
        // Whether to persist sessions for recovery
        private boolean persistSession = true;
        
        // Whether to enable file checkpointing
        private boolean enableFileCheckpointing = false;
        
        // Default allowed tools (empty = all allowed)
        private List<String> allowedTools;
        
        // Default disallowed tools
        private List<String> disallowedTools;
        
        // Additional directories for the agent to access
        private List<String> additionalDirectories;
        
        public String getModel() {
            return model;
        }
        
        public void setModel(String model) {
            this.model = model;
        }
        
        public String getCwd() {
            return cwd;
        }
        
        public void setCwd(String cwd) {
            this.cwd = cwd;
        }
        
        public String getPermissionMode() {
            return permissionMode;
        }
        
        public void setPermissionMode(String permissionMode) {
            this.permissionMode = permissionMode;
        }
        
        public int getMaxTurns() {
            return maxTurns;
        }
        
        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }
        
        public boolean isPersistSession() {
            return persistSession;
        }
        
        public void setPersistSession(boolean persistSession) {
            this.persistSession = persistSession;
        }
        
        public boolean isEnableFileCheckpointing() {
            return enableFileCheckpointing;
        }
        
        public void setEnableFileCheckpointing(boolean enableFileCheckpointing) {
            this.enableFileCheckpointing = enableFileCheckpointing;
        }
        
        public List<String> getAllowedTools() {
            return allowedTools;
        }
        
        public void setAllowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools;
        }
        
        public List<String> getDisallowedTools() {
            return disallowedTools;
        }
        
        public void setDisallowedTools(List<String> disallowedTools) {
            this.disallowedTools = disallowedTools;
        }
        
        public List<String> getAdditionalDirectories() {
            return additionalDirectories;
        }
        
        public void setAdditionalDirectories(List<String> additionalDirectories) {
            this.additionalDirectories = additionalDirectories;
        }
    }
}

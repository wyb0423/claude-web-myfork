package com.claude.web.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudePropertiesTest {

    // ─── isAuthEnabled ────────────────────────────────────────────────────────

    @Test
    void isAuthEnabled_false_whenPasswordDisabled() {
        ClaudeProperties props = new ClaudeProperties();
        props.setPasswordEnabled(false);
        props.setPassword("secret");
        assertThat(props.isAuthEnabled()).isFalse();
    }

    @Test
    void isAuthEnabled_false_whenPasswordNull() {
        ClaudeProperties props = new ClaudeProperties();
        props.setPasswordEnabled(true);
        props.setPassword(null);
        assertThat(props.isAuthEnabled()).isFalse();
    }

    @Test
    void isAuthEnabled_false_whenPasswordEmpty() {
        ClaudeProperties props = new ClaudeProperties();
        props.setPasswordEnabled(true);
        props.setPassword("");
        assertThat(props.isAuthEnabled()).isFalse();
    }

    @Test
    void isAuthEnabled_true_whenEnabledWithPassword() {
        ClaudeProperties props = new ClaudeProperties();
        props.setPasswordEnabled(true);
        props.setPassword("my-password");
        assertThat(props.isAuthEnabled()).isTrue();
    }

    // ─── isRemoteMode ─────────────────────────────────────────────────────────

    @Test
    void isRemoteMode_false_whenClaudeAgentNull() {
        ClaudeProperties props = new ClaudeProperties();
        props.setClaudeAgent(null);
        assertThat(props.isRemoteMode()).isFalse();
    }

    @Test
    void isRemoteMode_false_whenHostNull() {
        ClaudeProperties props = new ClaudeProperties();
        ClaudeProperties.ClaudeAgent agent = new ClaudeProperties.ClaudeAgent();
        agent.setHost(null);
        props.setClaudeAgent(agent);
        assertThat(props.isRemoteMode()).isFalse();
    }

    @Test
    void isRemoteMode_false_whenHostEmpty() {
        ClaudeProperties props = new ClaudeProperties();
        ClaudeProperties.ClaudeAgent agent = new ClaudeProperties.ClaudeAgent();
        agent.setHost("");
        props.setClaudeAgent(agent);
        assertThat(props.isRemoteMode()).isFalse();
    }

    @Test
    void isRemoteMode_true_whenHostSet() {
        ClaudeProperties props = new ClaudeProperties();
        ClaudeProperties.ClaudeAgent agent = new ClaudeProperties.ClaudeAgent();
        agent.setHost("192.168.1.100");
        props.setClaudeAgent(agent);
        assertThat(props.isRemoteMode()).isTrue();
    }

    // ─── ClaudeProperties defaults ────────────────────────────────────────────

    @Test
    void properties_hasCorrectDefaults() {
        ClaudeProperties props = new ClaudeProperties();
        assertThat(props.getPassword()).isEqualTo("");
        assertThat(props.isPasswordEnabled()).isTrue();
        assertThat(props.isAutoApprove()).isFalse();
        assertThat(props.getClaudeAgent()).isNotNull();
        assertThat(props.getSessionDefaults()).isNotNull();
    }

    @Test
    void properties_setters() {
        ClaudeProperties props = new ClaudeProperties();
        props.setPassword("newpass");
        props.setPasswordEnabled(false);
        props.setAutoApprove(true);

        assertThat(props.getPassword()).isEqualTo("newpass");
        assertThat(props.isPasswordEnabled()).isFalse();
        assertThat(props.isAutoApprove()).isTrue();
    }

    // ─── ClaudeAgent defaults ─────────────────────────────────────────────────

    @Test
    void claudeAgent_hasCorrectDefaults() {
        ClaudeProperties.ClaudeAgent agent = new ClaudeProperties.ClaudeAgent();
        assertThat(agent.getHost()).isEqualTo("127.0.0.1");
        assertThat(agent.getPort()).isEqualTo(8011);
        assertThat(agent.getApiKey()).isEqualTo("");
        assertThat(agent.getConnectionTimeout()).isEqualTo(30000);
    }

    @Test
    void claudeAgent_setters() {
        ClaudeProperties.ClaudeAgent agent = new ClaudeProperties.ClaudeAgent();
        agent.setHost("10.0.0.1");
        agent.setPort(9090);
        agent.setApiKey("test-key");
        agent.setConnectionTimeout(5000);

        assertThat(agent.getHost()).isEqualTo("10.0.0.1");
        assertThat(agent.getPort()).isEqualTo(9090);
        assertThat(agent.getApiKey()).isEqualTo("test-key");
        assertThat(agent.getConnectionTimeout()).isEqualTo(5000);
    }

    // ─── SessionDefaults ──────────────────────────────────────────────────────

    @Test
    void sessionDefaults_hasCorrectDefaults() {
        ClaudeProperties.SessionDefaults defaults = new ClaudeProperties.SessionDefaults();
        assertThat(defaults.getCwd()).isEqualTo("/home/ubuntu");
        assertThat(defaults.getPermissionMode()).isEqualTo("default");
        assertThat(defaults.getMaxTurns()).isEqualTo(50);
        assertThat(defaults.isPersistSession()).isTrue();
        assertThat(defaults.isEnableFileCheckpointing()).isFalse();
        assertThat(defaults.getModel()).isNull();
        assertThat(defaults.getAllowedTools()).isNull();
        assertThat(defaults.getDisallowedTools()).isNull();
        assertThat(defaults.getAdditionalDirectories()).isNull();
    }

    @Test
    void sessionDefaults_setters() {
        ClaudeProperties.SessionDefaults d = new ClaudeProperties.SessionDefaults();
        d.setModel("claude-opus-4");
        d.setCwd("/workspace");
        d.setPermissionMode("acceptAll");
        d.setMaxTurns(100);
        d.setPersistSession(false);
        d.setEnableFileCheckpointing(true);
        d.setAllowedTools(List.of("Bash", "Read"));
        d.setDisallowedTools(List.of("Write"));
        d.setAdditionalDirectories(List.of("/extra"));

        assertThat(d.getModel()).isEqualTo("claude-opus-4");
        assertThat(d.getCwd()).isEqualTo("/workspace");
        assertThat(d.getPermissionMode()).isEqualTo("acceptAll");
        assertThat(d.getMaxTurns()).isEqualTo(100);
        assertThat(d.isPersistSession()).isFalse();
        assertThat(d.isEnableFileCheckpointing()).isTrue();
        assertThat(d.getAllowedTools()).containsExactly("Bash", "Read");
        assertThat(d.getDisallowedTools()).containsExactly("Write");
        assertThat(d.getAdditionalDirectories()).containsExactly("/extra");
    }
}

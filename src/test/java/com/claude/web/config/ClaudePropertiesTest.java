package com.claude.web.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClaudeProperties 单元测试
 */
class ClaudePropertiesTest {

    @Test
    void testDefaultValues() {
        ClaudeProperties props = new ClaudeProperties();

        assertEquals("", props.getPassword());
        assertTrue(props.isPasswordEnabled());
        assertFalse(props.isAutoApprove());
    }

    @Test
    void testAuthEnabled() {
        ClaudeProperties props = new ClaudeProperties();
        // Default: password empty, enabled true -> auth disabled
        assertFalse(props.isAuthEnabled());

        props.setPassword("secret");
        assertTrue(props.isAuthEnabled());

        props.setPasswordEnabled(false);
        assertFalse(props.isAuthEnabled());
    }

    @Test
    void testClaudeAgentConfiguration() {
        ClaudeProperties props = new ClaudeProperties();
        ClaudeProperties.ClaudeAgent agent = props.getClaudeAgent();

        assertNotNull(agent);
        assertEquals("127.0.0.1", agent.getHost());
        assertEquals(3001, agent.getPort());
        assertEquals("", agent.getApiKey());
        assertEquals(30000, agent.getConnectionTimeout());
    }

    @Test
    void testRemoteMode() {
        ClaudeProperties props = new ClaudeProperties();
        // Default host is 127.0.0.1 -> should be remote
        assertTrue(props.isRemoteMode());

        props.getClaudeAgent().setHost("");
        assertFalse(props.isRemoteMode());
    }

    @Test
    void testSettersAndGetters() {
        ClaudeProperties props = new ClaudeProperties();

        props.setPassword("mypassword");
        assertEquals("mypassword", props.getPassword());

        props.setPasswordEnabled(false);
        assertFalse(props.isPasswordEnabled());

        props.setAutoApprove(true);
        assertTrue(props.isAutoApprove());
    }

    @Test
    void testClaudeAgentSetters() {
        ClaudeProperties.ClaudeAgent agent = new ClaudeProperties.ClaudeAgent();
        agent.setHost("192.168.1.1");
        agent.setPort(8080);
        agent.setApiKey("test-key");
        agent.setConnectionTimeout(10000);

        assertEquals("192.168.1.1", agent.getHost());
        assertEquals(8080, agent.getPort());
        assertEquals("test-key", agent.getApiKey());
        assertEquals(10000, agent.getConnectionTimeout());
    }

    @Test
    void testAuthEnabledWithEmptyPassword() {
        ClaudeProperties props = new ClaudeProperties();
        props.setPassword("");
        props.setPasswordEnabled(true);
        assertFalse(props.isAuthEnabled());
    }

    @Test
    void testAuthEnabledWithNullPassword() {
        ClaudeProperties props = new ClaudeProperties();
        props.setPassword(null);
        props.setPasswordEnabled(true);
        assertFalse(props.isAuthEnabled());
    }
}

package com.claude.web.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoginController 单元测试
 */
class LoginControllerTest {

    @Test
    void testLogin() {
        LoginController controller = new LoginController();
        String view = controller.login();
        assertEquals("login", view);
    }
}

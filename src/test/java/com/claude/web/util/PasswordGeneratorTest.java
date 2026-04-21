package com.claude.web.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PasswordGenerator 单元测试
 */
class PasswordGeneratorTest {

    @Test
    void testGeneratePasswordLength() {
        String password = PasswordGenerator.generatePassword();
        // 16 bytes -> URL-safe Base64 without padding = 22 characters
        assertEquals(22, password.length());
    }

    @Test
    void testGeneratePasswordUniqueness() {
        String p1 = PasswordGenerator.generatePassword();
        String p2 = PasswordGenerator.generatePassword();
        assertNotEquals(p1, p2);
    }

    @Test
    void testGeneratePasswordCharacterSet() {
        String password = PasswordGenerator.generatePassword();
        // URL-safe Base64: A-Z, a-z, 0-9, -, _
        assertTrue(password.matches("^[A-Za-z0-9_-]+$"));
    }

    @Test
    void testGeneratePasswordNoPadding() {
        String password = PasswordGenerator.generatePassword();
        assertFalse(password.contains("="));
    }

    @Test
    void testMainMethod() {
        // Should not throw
        assertDoesNotThrow(() -> PasswordGenerator.main(new String[]{}));
    }

    @Test
    void testMultipleGenerations() {
        for (int i = 0; i < 100; i++) {
            String password = PasswordGenerator.generatePassword();
            assertEquals(22, password.length());
            assertFalse(password.isEmpty());
        }
    }
}

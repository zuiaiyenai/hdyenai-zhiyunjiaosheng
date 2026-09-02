package com.a09.tts.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy(8, 16);

    @Test
    void enforcesLengthAndBasicComplexity() {
        assertThrows(IllegalArgumentException.class, () -> policy.validate("short1"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("onlyletters"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("12345678"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("Password123456789"));
        assertDoesNotThrow(() -> policy.validate("Password1"));
    }
}

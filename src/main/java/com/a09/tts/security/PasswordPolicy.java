package com.a09.tts.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {
    private final int minLength;
    private final int maxLength;

    public PasswordPolicy(
            @Value("${app.security.password.min-length:8}") int minLength,
            @Value("${app.security.password.max-length:128}") int maxLength) {
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

    public void validate(String password) {
        if (password == null || password.length() < minLength || password.length() > maxLength) {
            throw new IllegalArgumentException("密码长度必须为 " + minLength + " 到 " + maxLength + " 位");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("密码必须同时包含字母和数字");
        }
    }
}

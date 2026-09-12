package com.a09.tts.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ClientIpResolver {
    private final Set<String> trustedProxies;

    public ClientIpResolver(@Value("${app.security.trusted-proxies:127.0.0.1,::1}") String trustedProxies) {
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalize(request.getRemoteAddr());
        if (!trustedProxies.contains(remoteAddress)) {
            return remoteAddress;
        }
        String realIp = normalize(request.getHeader("X-Real-IP"));
        return validLiteral(realIp) ? realIp : remoteAddress;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private boolean validLiteral(String value) {
        return value.length() <= 45 && value.matches("[0-9A-Fa-f:.]+")
                && (value.contains(":") || validIpv4(value));
    }

    private boolean validIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                if (part.isEmpty() || Integer.parseInt(part) > 255) {
                    return false;
                }
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }
}

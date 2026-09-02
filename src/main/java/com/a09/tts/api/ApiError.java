package com.a09.tts.api;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        int code,
        String errorCode,
        String message,
        Instant timestamp,
        Map<String, String> details
) {
    public static ApiError of(int code, String errorCode, String message) {
        return new ApiError(code, errorCode, message, Instant.now(), Map.of());
    }
}

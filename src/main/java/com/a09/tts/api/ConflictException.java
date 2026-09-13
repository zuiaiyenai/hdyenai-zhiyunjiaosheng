package com.a09.tts.api;

public class ConflictException extends RuntimeException {
    private final String errorCode;

    public ConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

package com.a09.tts.task;

public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMEOUT;

    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED || this == TIMEOUT;
    }
}

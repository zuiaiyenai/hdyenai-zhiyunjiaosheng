package com.a09.tts.task;

import com.a09.tts.api.ServiceUnavailableException;

public class ResourceCapacityException extends ServiceUnavailableException {
    public ResourceCapacityException(String message) {
        super(message);
    }

    public ResourceCapacityException(String message, Throwable cause) {
        super(message, cause);
    }
}

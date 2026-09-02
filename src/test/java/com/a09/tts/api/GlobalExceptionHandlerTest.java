package com.a09.tts.api;

import com.a09.tts.task.AsyncTaskService.TaskCapacityException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsExpectedFailuresToStableCodes() {
        assertError(handler.badRequest(new IllegalArgumentException("参数错误")),
                HttpStatus.BAD_REQUEST, "API_INVALID_ARGUMENT", "参数错误");
        assertError(handler.authorization(new SecurityException("owner=alice")),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无权执行此操作");
        assertError(handler.notFound(new ResourceNotFoundException("资源不存在")),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在");
        assertError(handler.taskCapacity(new TaskCapacityException("队列已满")),
                HttpStatus.TOO_MANY_REQUESTS, "TASK_CAPACITY_EXCEEDED", "队列已满");
    }

    @Test
    void masksFileThirdPartyAndUnexpectedFailureDetails() {
        var file = handler.fileError(new IOException("C:\\secret\\voice.wav"));
        assertError(file, HttpStatus.INTERNAL_SERVER_ERROR,
                "FILE_OPERATION_FAILED", "文件处理失败");
        assertFalse(file.getBody().message().contains("secret"));

        var thirdParty = handler.unavailable(
                new ServiceUnavailableException("apiKey=should-not-leak"));
        assertError(thirdParty, HttpStatus.SERVICE_UNAVAILABLE,
                "THIRD_PARTY_UNAVAILABLE", "外部服务暂不可用");
        assertFalse(thirdParty.getBody().message().contains("apiKey"));

        var unexpected = handler.unexpected(new RuntimeException("jdbc:mysql://secret"));
        assertError(unexpected, HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "服务器处理请求失败");
        assertFalse(unexpected.getBody().message().contains("jdbc"));
    }

    private void assertError(org.springframework.http.ResponseEntity<ApiError> response,
                             HttpStatus status, String errorCode, String message) {
        assertEquals(status, response.getStatusCode());
        assertEquals(errorCode, response.getBody().errorCode());
        assertEquals(message, response.getBody().message());
    }
}

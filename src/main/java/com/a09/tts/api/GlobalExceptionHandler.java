package com.a09.tts.api;

import com.a09.tts.task.AsyncTaskService.TaskCapacityException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class,
            ConstraintViolationException.class})
    ResponseEntity<ApiError> badRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "API_INVALID_ARGUMENT", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> details.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ApiError(400, "API_VALIDATION_FAILED",
                        "请求参数校验失败", Instant.now(), details));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<ApiError> authorization(SecurityException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(403, "ACCESS_DENIED", "无权执行此操作"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "RESOURCE_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(TaskCapacityException.class)
    ResponseEntity<ApiError> taskCapacity(TaskCapacityException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiError.of(429, "TASK_CAPACITY_EXCEEDED", exception.getMessage()));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ApiError> unavailable(ServiceUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(503, "THIRD_PARTY_UNAVAILABLE", "外部服务暂不可用"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(413, "FILE_TOO_LARGE", "上传文件超过大小限制"));
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<ApiError> fileError(IOException exception) {
        log.error("File operation failed", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, "FILE_OPERATION_FAILED", "文件处理失败"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("Unhandled request error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, "INTERNAL_ERROR", "服务器处理请求失败"));
    }
}

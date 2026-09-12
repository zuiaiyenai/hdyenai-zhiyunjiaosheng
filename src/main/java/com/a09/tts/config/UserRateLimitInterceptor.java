package com.a09.tts.config;

import com.a09.tts.security.UserEndpointRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Component
public class UserRateLimitInterceptor implements HandlerInterceptor {
    private final UserEndpointRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public UserRateLimitInterceptor(UserEndpointRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Object username = request.getAttribute("username");
        if (username == null || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        UserEndpointRateLimiter.Decision decision = rateLimiter.check(
                username.toString(), endpoint(request, handler));
        if (decision.allowed()) {
            return true;
        }
        long retryAfterSeconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1_000);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "code", 429,
                "errorCode", "USER_ENDPOINT_RATE_LIMITED",
                "message", "请求过于频繁，请稍后重试",
                "timestamp", Instant.now().toString()));
        return false;
    }

    private String endpoint(HttpServletRequest request, Object handler) {
        if (handler instanceof HandlerMethod method) {
            return request.getMethod() + ":" + method.getBeanType().getName() + "#" + method.getMethod().getName();
        }
        return request.getMethod() + ":unmapped";
    }
}

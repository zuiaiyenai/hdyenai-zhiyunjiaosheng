package com.a09.tts.config;

import com.a09.tts.security.UserEndpointRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRateLimitInterceptorTest {
    @Test
    void usesAuthenticatedUsernameAndReturnsRetryAfter() throws Exception {
        UserEndpointRateLimiter limiter = mock(UserEndpointRateLimiter.class);
        UserRateLimitInterceptor interceptor = new UserRateLimitInterceptor(limiter, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tasks/123");
        request.setAttribute("username", "alice");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new TestHandler(), TestHandler.class.getMethod("get"));
        String endpoint = "GET:" + TestHandler.class.getName() + "#get";
        when(limiter.check(eq("alice"), eq(endpoint))).thenReturn(
                new UserEndpointRateLimiter.Decision(false, Duration.ofMillis(2_500), "redis"));

        assertFalse(interceptor.preHandle(request, response, handler));

        assertEquals(429, response.getStatus());
        assertEquals("3", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("USER_ENDPOINT_RATE_LIMITED"));
        verify(limiter).check("alice", endpoint);
    }

    @Test
    void skipsRequestWithoutAuthenticatedUser() throws Exception {
        UserEndpointRateLimiter limiter = mock(UserEndpointRateLimiter.class);
        UserRateLimitInterceptor interceptor = new UserRateLimitInterceptor(limiter, new ObjectMapper());
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), new Object()));
    }

    static class TestHandler {
        public void get() {
        }
    }
}

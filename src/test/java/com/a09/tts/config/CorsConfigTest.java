package com.a09.tts.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorsConfigTest {
    @Test
    void allowsConfiguredOriginAndRejectsOtherOrigins() throws Exception {
        var filter = new CorsConfig(List.of("https://app.example.com")).corsFilter();

        MockHttpServletResponse allowed = preflight(filter, "https://app.example.com");
        assertEquals("https://app.example.com", allowed.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", allowed.getHeader("Access-Control-Allow-Credentials"));

        MockHttpServletResponse denied = preflight(filter, "https://evil.example.com");
        assertNull(denied.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void refusesWildcardConfigurationWithCredentials() {
        assertThrows(IllegalStateException.class, () -> new CorsConfig(List.of("*")));
    }

    private MockHttpServletResponse preflight(org.springframework.web.filter.CorsFilter filter, String origin)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/user/login");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}

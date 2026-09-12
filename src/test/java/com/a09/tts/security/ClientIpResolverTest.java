package com.a09.tts.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {
    @Test
    void acceptsRealIpOnlyFromConfiguredProxy() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");
        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.setRemoteAddr("127.0.0.1");
        proxied.addHeader("X-Real-IP", "203.0.113.7");
        assertEquals("203.0.113.7", resolver.resolve(proxied));

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.4");
        direct.addHeader("X-Real-IP", "203.0.113.8");
        assertEquals("198.51.100.4", resolver.resolve(direct));
    }

    @Test
    void rejectsNonLiteralForwardedValue() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "attacker.example");
        assertEquals("127.0.0.1", resolver.resolve(request));
    }
}

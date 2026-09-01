package com.a09.tts.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AliyunNlsCredentialsTest {
    @Test
    void cachesTokenAndRefreshesFiveMinutesBeforeExpiry() {
        AtomicLong now = new AtomicLong(1_000);
        AtomicInteger fetches = new AtomicInteger();
        AliyunNlsCredentials credentials = new AliyunNlsCredentials(
                "app-key", "", "wss://example.invalid", "access-key-id", "access-key-secret",
                (keyId, keySecret) -> new AliyunNlsCredentials.FetchedToken(
                        "token-" + fetches.incrementAndGet(), now.get() + 1_000),
                now::get);

        assertThat(credentials.resolveToken()).isEqualTo("token-1");
        now.set(1_699);
        assertThat(credentials.resolveToken()).isEqualTo("token-1");
        assertThat(fetches).hasValue(1);

        now.set(1_700);
        assertThat(credentials.resolveToken()).isEqualTo("token-2");
        assertThat(fetches).hasValue(2);
    }
}

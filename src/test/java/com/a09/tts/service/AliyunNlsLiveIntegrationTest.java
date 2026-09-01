package com.a09.tts.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("nodb")
@EnabledIfEnvironmentVariable(named = "ALIYUN_NLS_LIVE_TEST", matches = "true")
class AliyunNlsLiveIntegrationTest {
    @Autowired
    private AliyunNlsCredentials credentials;

    @Autowired
    private AliyunSpeechService speechService;

    @Test
    void obtainsSdkTokenAndSynthesizesPlayableAudio() {
        assertThat(credentials.tokenMode()).isEqualTo("auto-refresh");
        assertThat(credentials.resolveToken()).isNotBlank();

        byte[] audio = speechService.synthesize("你好，这是阿里云长期令牌在线验收。", "cuijie");

        assertThat(audio.length).isGreaterThan(1_000);
    }
}

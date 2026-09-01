package com.a09.tts;

import com.a09.tts.controller.DialectTTSController;
import com.a09.tts.service.AliyunSpeechService;
import com.a09.tts.service.DialectVoiceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AliyunSpeechIntegrationTest {
    @Test
    void exposesOnlyRealAliyunDialectVoiceIds() {
        assertThat(DialectVoiceCatalog.require("shanshan").dialect()).isEqualTo("粤语");
        assertThatThrownBy(() -> DialectVoiceCatalog.require("粤语女声"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsMissingCloudConfigurationWithoutExposingSecrets() {
        AliyunSpeechService service = new AliyunSpeechService("", "", "wss://example.invalid", "", "");
        Map<String, Object> capabilities = service.capabilities(false, "http://127.0.0.1:9880/tts");

        assertThat(capabilities).containsEntry("defaultProvider", "aliyun");
        assertThat(capabilities.toString()).contains("ALIYUN_NLS_APP_KEY", "ALIYUN_AK_SECRET");
        assertThat(capabilities.toString()).doesNotContain("access-key-secret");
    }

    @Test
    void usesAccessKeyForAutomaticTokenRefresh() {
        AliyunSpeechService service = new AliyunSpeechService(
                "app-key", "", "wss://example.invalid", "access-key-id", "access-key-secret");

        Map<String, Object> capabilities = service.capabilities(false, "http://127.0.0.1:9880/tts");
        @SuppressWarnings("unchecked")
        Map<String, Object> aliyun = (Map<String, Object>) capabilities.get("aliyun");

        assertThat(aliyun).containsEntry("synthesisReady", true);
        assertThat(aliyun).containsEntry("cloneReady", true);
        assertThat(aliyun).containsEntry("tokenMode", "auto-refresh");
        assertThat(capabilities.toString()).doesNotContain("access-key-id", "access-key-secret");
    }

    @Test
    void dialectControllerReturnsPlayableAudio() {
        AliyunSpeechService service = mock(AliyunSpeechService.class);
        when(service.synthesize("你好", "cuijie")).thenReturn(new byte[]{1, 2, 3});
        DialectTTSController controller = new DialectTTSController(service);

        ResponseEntity<?> response = controller.synthesizeDialect(Map.of("text", "你好", "voice", "cuijie"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("audio/mpeg");
        assertThat((byte[]) response.getBody()).containsExactly(1, 2, 3);
    }
}

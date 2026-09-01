package com.a09.tts;

import com.a09.tts.service.MoonshotChatClient;
import com.a09.tts.service.impl.AccessibilityServiceImpl;
import com.a09.tts.service.impl.PPTServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MoonshotChatIntegrationTest {
    @Test
    void stripsVersionSuffixBecauseSpringAiAppendsIt() {
        String moonshotUrl = ReflectionTestUtils.invokeMethod(MoonshotChatClient.class,
                "normalizeBaseUrl", "https://api.moonshot.cn/v1");
        String gatewayUrl = ReflectionTestUtils.invokeMethod(MoonshotChatClient.class,
                "normalizeBaseUrl", "https://gateway.example.com/v1/");

        assertThat(moonshotUrl).isEqualTo("https://api.moonshot.cn");
        assertThat(gatewayUrl).isEqualTo("https://gateway.example.com");
    }

    @Test
    void missingApiKeyDoesNotInitializeCloudClient() {
        MoonshotChatClient client = new MoonshotChatClient(
                "", "https://api.moonshot.cn/v1", "test-model");

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.generate("system", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOONSHOT_API_KEY");
    }

    @Test
    void studySummaryMarksSuccessfulCloudResponseAsAi() {
        MoonshotChatClient client = mock(MoonshotChatClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.generate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn("AI 学习纪要");
        AccessibilityServiceImpl service = new AccessibilityServiceImpl(client);

        Map<String, Object> result = service.generateStudySummary("学习内容");

        assertThat(result).containsEntry("source", "ai");
        assertThat(result).containsEntry("summary", "AI 学习纪要");
    }

    @Test
    void studySummaryFallsBackLocallyWhenCloudFails() {
        MoonshotChatClient client = mock(MoonshotChatClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.generate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("network"));
        AccessibilityServiceImpl service = new AccessibilityServiceImpl(client);

        Map<String, Object> result = service.generateStudySummary("学习内容");

        assertThat(result).containsEntry("source", "local");
        assertThat((String) result.get("summary")).contains("学习纪要", "学习内容");
    }

    @Test
    void coursewareGenerationUsesSharedChatClient() {
        MoonshotChatClient client = mock(MoonshotChatClient.class);
        when(client.generate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.contains("PPT正文"))).thenReturn("课件结果");
        PPTServiceImpl service = new PPTServiceImpl(mock(RestTemplate.class), client);

        String result = ReflectionTestUtils.invokeMethod(service,
                "generateCoursewareContent", "PPT正文");

        assertThat(result).isEqualTo("课件结果");
    }
}

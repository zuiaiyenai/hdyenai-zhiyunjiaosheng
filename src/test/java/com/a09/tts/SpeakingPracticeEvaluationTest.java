package com.a09.tts;

import com.a09.tts.api.ServiceUnavailableException;
import com.a09.tts.service.ASRService;
import com.a09.tts.service.DialogueSessionStore;
import com.a09.tts.service.impl.InMemoryDialogueSessionStore;
import com.a09.tts.service.impl.SpeakingPracticeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpeakingPracticeEvaluationTest {

    @Test
    void unavailableAsrDoesNotGenerateScoresOrHistory() {
        ASRService asrService = mock(ASRService.class);
        when(asrService.transcribe(anyString(), eq("zh")))
                .thenThrow(new ServiceUnavailableException("ASR 服务不可用"));
        SpeakingPracticeServiceImpl service = serviceWith(asrService);

        ResponseEntity<?> response = service.evaluate(
                "recording.wav", "我爱玩原神", "standard", null, "zh", "alice");

        assertEquals(503, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("ASR_UNAVAILABLE", body.get("code"));
        assertFalse(body.containsKey("fluency"));
        assertFalse(body.containsKey("history_data"));
    }

    @Test
    void emptyRecognitionDoesNotGenerateScoresOrHistory() {
        ASRService asrService = mock(ASRService.class);
        when(asrService.transcribe(anyString(), eq("zh"))).thenReturn("  ");
        SpeakingPracticeServiceImpl service = serviceWith(asrService);

        ResponseEntity<?> response = service.evaluate(
                "recording.wav", "我爱玩原神", "standard", null, "zh", "alice");

        assertEquals(422, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("NO_SPEECH", body.get("code"));
        assertFalse(body.containsKey("accuracy"));
        assertFalse(body.containsKey("history_data"));
    }

    private SpeakingPracticeServiceImpl serviceWith(ASRService asrService) {
        DialogueSessionStore store = new InMemoryDialogueSessionStore(Duration.ofMinutes(1));
        SpeakingPracticeServiceImpl service = new SpeakingPracticeServiceImpl(store);
        ReflectionTestUtils.setField(service, "asrService", asrService);
        return service;
    }
}

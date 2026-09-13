package com.a09.tts;

import com.a09.tts.api.ServiceUnavailableException;
import com.a09.tts.service.ASRService;
import com.a09.tts.service.DialogueSessionStore;
import com.a09.tts.service.impl.InMemoryDialogueSessionStore;
import com.a09.tts.service.impl.SpeakingPracticeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void databaseModeBuildsTrendFromSharedHistory() {
        ASRService asrService = mock(ASRService.class);
        when(asrService.transcribe(anyString(), eq("zh"))).thenReturn("我爱玩原神");
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(jdbcTemplate.queryForList(anyString(), eq("alice"), eq(5)))
                .thenReturn(List.of(
                        Map.of("fluency_score", 90.0, "pronunciation_score", 91.0,
                                "accuracy_score", 92.0),
                        Map.of("fluency_score", 60.0, "pronunciation_score", 61.0,
                                "accuracy_score", 62.0)));
        SpeakingPracticeServiceImpl service = serviceWith(asrService, jdbcTemplate);

        ResponseEntity<?> response = service.evaluate(
                "recording.wav", "我爱玩原神", "standard", "session-1", "zh", "alice");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> history = (Map<?, ?>) body.get("history_data");
        assertEquals(List.of(60.0, 90.0), history.get("fluency_trend"));
        assertEquals(List.of(61.0, 91.0), history.get("pronunciation_trend"));
        assertEquals(List.of(62.0, 92.0), history.get("accuracy_trend"));
    }

    @Test
    void databaseWriteFailureDoesNotReturnNodeLocalSuccess() {
        ASRService asrService = mock(ASRService.class);
        when(asrService.transcribe(anyString(), eq("zh"))).thenReturn("我爱玩原神");
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        SpeakingPracticeServiceImpl service = serviceWith(asrService, jdbcTemplate);

        ResponseEntity<?> response = service.evaluate(
                "recording.wav", "我爱玩原神", "standard", "session-1", "zh", "alice");

        assertEquals(503, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("SPEAKING_HISTORY_UNAVAILABLE", body.get("code"));
        assertFalse(body.containsKey("history_data"));
    }

    @Test
    void databaseReadFailureDoesNotFallBackToNodeLocalHistory() {
        ASRService asrService = mock(ASRService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq("alice"), eq(21), eq(0)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        SpeakingPracticeServiceImpl service = serviceWith(asrService, jdbcTemplate);

        ResponseEntity<?> response = service.getHistory(null, "alice", 0, 20);

        assertEquals(503, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("SPEAKING_HISTORY_UNAVAILABLE", body.get("code"));
        assertFalse(body.containsKey("history"));
    }

    private SpeakingPracticeServiceImpl serviceWith(ASRService asrService) {
        return serviceWith(asrService, null);
    }

    private SpeakingPracticeServiceImpl serviceWith(
            ASRService asrService, JdbcTemplate jdbcTemplate) {
        DialogueSessionStore store = new InMemoryDialogueSessionStore(Duration.ofMinutes(1));
        SpeakingPracticeServiceImpl service = new SpeakingPracticeServiceImpl(store);
        ReflectionTestUtils.setField(service, "asrService", asrService);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        return service;
    }
}

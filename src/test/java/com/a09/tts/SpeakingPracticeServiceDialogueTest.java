package com.a09.tts;

import com.a09.tts.service.DialogueSessionStore;
import com.a09.tts.service.SpeakingPracticeService;
import com.a09.tts.service.impl.InMemoryDialogueSessionStore;
import com.a09.tts.service.impl.SpeakingPracticeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpeakingPracticeServiceDialogueTest {

    @Test
    void dialogueProgressComesFromServerSideSessionStore() {
        DialogueSessionStore store =
                new InMemoryDialogueSessionStore(Duration.ofMinutes(1));
        SpeakingPracticeService service = new SpeakingPracticeServiceImpl(store);

        ResponseEntity<?> started = service.startDialogue("greeting", "alice");
        Map<?, ?> startBody = (Map<?, ?>) started.getBody();
        assertNotNull(startBody);
        String sessionId = startBody.get("session_id").toString();

        ResponseEntity<?> continued = service.continueDialogue(sessionId, "alice");
        Map<?, ?> continueBody = (Map<?, ?>) continued.getBody();

        assertNotNull(continueBody);
        assertEquals("greeting", continueBody.get("scenario_id"));
        assertEquals(1, continueBody.get("current_turn"));
    }
}

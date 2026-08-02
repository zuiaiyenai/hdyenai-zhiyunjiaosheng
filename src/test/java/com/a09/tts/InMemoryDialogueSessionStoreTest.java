package com.a09.tts;

import com.a09.tts.service.DialogueSessionStore;
import com.a09.tts.service.impl.InMemoryDialogueSessionStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDialogueSessionStoreTest {

    @Test
    void advancesServerSideTurnAndKeepsScenario() {
        InMemoryDialogueSessionStore store =
                new InMemoryDialogueSessionStore(Duration.ofMinutes(1));
        store.create("session-1", "greeting", "alice");

        DialogueSessionStore.DialogueSession first =
                store.advance("session-1", "alice").orElseThrow();
        DialogueSessionStore.DialogueSession second =
                store.advance("session-1", "alice").orElseThrow();

        assertEquals("greeting", first.scenarioId());
        assertEquals(1, first.currentTurn());
        assertEquals(2, second.currentTurn());
    }

    @Test
    void rejectsAnotherUserAndDeletesCompletedSession() {
        InMemoryDialogueSessionStore store =
                new InMemoryDialogueSessionStore(Duration.ofMinutes(1));
        store.create("session-1", "greeting", "alice");

        assertThrows(SecurityException.class,
                () -> store.advance("session-1", "bob"));

        store.delete("session-1");
        assertTrue(store.advance("session-1", "alice").isEmpty());
    }
}

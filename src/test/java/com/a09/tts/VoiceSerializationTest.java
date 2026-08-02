package com.a09.tts;

import com.a09.tts.pojo.Voice;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceSerializationTest {

    @Test
    void voiceCanUseDefaultRedisCacheSerialization() throws Exception {
        Voice voice = new Voice();
        voice.setVoiceId(1);
        voice.setVoiceName("test");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(voice);
        }

        assertTrue(bytes.size() > 0);
    }
}

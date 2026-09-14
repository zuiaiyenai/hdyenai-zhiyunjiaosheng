package com.a09.tts.service;

import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AliyunSpeechServiceListenerTest {
    @Test
    void collectsAudioBytesFromSdkCallback() {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        AtomicReference<String> failure = new AtomicReference<>();
        SpeechSynthesizerListener listener = AliyunSpeechService.listener(audio, failure);

        listener.onMessage(ByteBuffer.wrap(new byte[]{1, 2, 3}));

        assertThat(audio.toByteArray()).containsExactly(1, 2, 3);
        assertThat(failure).hasValue(null);
    }

    @Test
    void recordsSdkFailureDetails() {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        AtomicReference<String> failure = new AtomicReference<>();
        SpeechSynthesizerListener listener = AliyunSpeechService.listener(audio, failure);
        SpeechSynthesizerResponse response = mock(SpeechSynthesizerResponse.class);
        when(response.getTaskId()).thenReturn("task-1");
        when(response.getStatusText()).thenReturn("STATE_FAIL");

        listener.onFail(response);

        assertThat(failure).hasValue("taskId=task-1，STATE_FAIL");
    }

    @Test
    void writesAndFlushesEveryStreamingAudioChunk() {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<IOException> writeFailure = new AtomicReference<>();
        AtomicBoolean firstAudioReceived = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        SpeechSynthesizerListener listener =
                AliyunSpeechService.streamingListener(
                        audio, failure, writeFailure, firstAudioReceived, finished);

        listener.onMessage(ByteBuffer.wrap(new byte[]{1, 2}));
        listener.onMessage(ByteBuffer.wrap(new byte[]{3}));

        assertThat(audio.toByteArray()).containsExactly(1, 2, 3);
        assertThat(failure).hasValue(null);
        assertThat(writeFailure).hasValue(null);
        assertThat(firstAudioReceived).isTrue();
    }

    @Test
    void stopsWaitingWhenSdkEmitsNoStreamingCallback() throws Exception {
        SpeechSynthesizer synthesizer = mock(SpeechSynthesizer.class);

        assertThatThrownBy(() -> AliyunSpeechService.awaitStreamingCompletion(
                synthesizer, new AtomicBoolean(), new AtomicBoolean()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("阿里云方言语音首包等待超时");
        verify(synthesizer).waitForComplete(AliyunSpeechService.FIRST_AUDIO_TIMEOUT_MILLIS);
    }
}

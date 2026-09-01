package com.a09.tts.service;

import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AliyunStreamingAsrSessionFactory implements StreamingAsrSessionFactory {
    private final AliyunNlsCredentials credentials;

    public AliyunStreamingAsrSessionFactory(AliyunNlsCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public Session open(Listener listener) throws Exception {
        NlsClient client = credentials.createNlsClient();
        SpeechTranscriber transcriber = null;
        try {
            transcriber = new SpeechTranscriber(client, aliyunListener(listener));
            transcriber.setAppKey(credentials.appKey());
            transcriber.setFormat(InputFormatEnum.PCM);
            transcriber.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            transcriber.setEnableIntermediateResult(true);
            transcriber.setEnablePunctuation(true);
            transcriber.setEnableITN(true);
            transcriber.start();
            return new AliyunSession(client, transcriber);
        } catch (Exception exception) {
            if (transcriber != null) {
                transcriber.close();
            }
            client.shutdown();
            throw exception;
        }
    }

    private static SpeechTranscriberListener aliyunListener(Listener listener) {
        return new SpeechTranscriberListener() {
            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
                listener.onReady();
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                listener.onFinal(response.getTransSentenceText());
            }

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {
                listener.onPartial(response.getTransSentenceText());
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {
                listener.onComplete();
            }

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                listener.onError(response.getStatusText());
            }
        };
    }

    private static final class AliyunSession implements Session {
        private final NlsClient client;
        private final SpeechTranscriber transcriber;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private AliyunSession(NlsClient client, SpeechTranscriber transcriber) {
            this.client = client;
            this.transcriber = transcriber;
        }

        @Override
        public void send(byte[] pcm) {
            if (!stopped.get() && pcm.length > 0) {
                transcriber.send(pcm);
            }
        }

        @Override
        public void stop() throws Exception {
            if (stopped.compareAndSet(false, true)) {
                transcriber.stop();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                transcriber.close();
                client.shutdown();
            }
        }
    }
}

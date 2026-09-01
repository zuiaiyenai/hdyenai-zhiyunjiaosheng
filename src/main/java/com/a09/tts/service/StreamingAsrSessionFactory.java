package com.a09.tts.service;

public interface StreamingAsrSessionFactory {
    Session open(Listener listener) throws Exception;

    interface Listener {
        void onReady();
        void onPartial(String text);
        void onFinal(String text);
        void onComplete();
        void onError(String message);
    }

    interface Session extends AutoCloseable {
        void send(byte[] pcm);
        void stop() throws Exception;
        @Override void close();
    }
}

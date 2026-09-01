package com.a09.tts.config;

import com.a09.tts.websocket.StreamingAsrWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AsrWebSocketConfig implements WebSocketConfigurer {
    private final StreamingAsrWebSocketHandler handler;

    public AsrWebSocketConfig(StreamingAsrWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/asr/stream");
    }

}

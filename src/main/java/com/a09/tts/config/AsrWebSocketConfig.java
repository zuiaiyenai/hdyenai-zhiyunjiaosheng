package com.a09.tts.config;

import com.a09.tts.websocket.StreamingAsrWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

@Configuration
@EnableWebSocket
public class AsrWebSocketConfig implements WebSocketConfigurer {
    private final StreamingAsrWebSocketHandler handler;
    private final String[] allowedOrigins;

    public AsrWebSocketConfig(StreamingAsrWebSocketHandler handler,
                              @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
                              List<String> allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins.stream().map(String::trim).filter(value -> !value.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/asr/stream").setAllowedOrigins(allowedOrigins);
    }

}

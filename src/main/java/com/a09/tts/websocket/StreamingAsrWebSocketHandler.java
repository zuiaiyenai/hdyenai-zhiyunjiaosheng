package com.a09.tts.websocket;

import com.a09.tts.service.StreamingAsrSessionFactory;
import com.a09.tts.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class StreamingAsrWebSocketHandler extends AbstractWebSocketHandler {
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(5);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 1_048_576;

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final StreamingAsrSessionFactory sessionFactory;
    private final TaskScheduler taskScheduler;
    private final Map<String, ClientState> clients = new ConcurrentHashMap<>();

    public StreamingAsrWebSocketHandler(JwtUtil jwtUtil, ObjectMapper objectMapper,
                                        StreamingAsrSessionFactory sessionFactory,
                                        TaskScheduler taskScheduler) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
        this.sessionFactory = sessionFactory;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        ClientState state = new ClientState(safeSession);
        clients.put(session.getId(), state);
        state.authTimeout = taskScheduler.schedule(
                () -> authenticationTimedOut(session.getId()), Instant.now().plus(AUTH_TIMEOUT));
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        ClientState state = clients.get(session.getId());
        if (state == null) {
            close(session, CloseStatus.SERVER_ERROR);
            return;
        }
        if (message instanceof TextMessage textMessage) {
            handleText(state, textMessage.getPayload());
        } else if (message instanceof BinaryMessage binaryMessage) {
            handleAudio(state, binaryMessage);
        }
    }

    private void handleText(ClientState state, String payload) throws Exception {
        JsonNode message;
        try {
            message = objectMapper.readTree(payload);
        } catch (Exception exception) {
            fail(state, "INVALID_MESSAGE", "消息必须是有效 JSON");
            return;
        }

        String type = message.path("type").asText();
        if (!state.authenticated.get()) {
            start(state, message, type);
            return;
        }
        if ("stop".equals(type)) {
            state.asrSession.stop();
        } else {
            fail(state, "INVALID_MESSAGE", "实时识别仅支持 stop 控制消息");
        }
    }

    private void start(ClientState state, JsonNode message, String type) {
        String token = message.path("token").asText();
        String language = message.path("language").asText();
        int sampleRate = message.path("sampleRate").asInt();
        if (!"start".equals(type) || token.isBlank() || !jwtUtil.verifyToken(token)) {
            fail(state, "UNAUTHORIZED", "访问令牌无效");
            return;
        }
        if (!"zh".equals(language) || sampleRate != 16_000) {
            fail(state, "UNSUPPORTED_AUDIO", "仅支持 zh、16 kHz 单声道 PCM");
            return;
        }

        try {
            state.asrSession = sessionFactory.open(listener(state));
            state.authenticated.set(true);
            cancel(state.authTimeout);
        } catch (Exception exception) {
            fail(state, "ASR_UNAVAILABLE", exception.getMessage());
        }
    }

    private void handleAudio(ClientState state, BinaryMessage message) {
        if (!state.authenticated.get() || state.asrSession == null) {
            fail(state, "UNAUTHORIZED", "请先发送 start 鉴权消息");
            return;
        }
        byte[] pcm = new byte[message.getPayloadLength()];
        message.getPayload().get(pcm);
        state.asrSession.send(pcm);
    }

    private StreamingAsrSessionFactory.Listener listener(ClientState state) {
        return new StreamingAsrSessionFactory.Listener() {
            @Override
            public void onReady() {
                send(state, Map.of("type", "ready"));
            }

            @Override
            public void onPartial(String text) {
                send(state, Map.of("type", "partial", "text", safeText(text)));
            }

            @Override
            public void onFinal(String text) {
                String value = safeText(text);
                if (!value.isBlank()) {
                    state.finalText.append(value);
                }
                send(state, Map.of("type", "final", "text", value));
            }

            @Override
            public void onComplete() {
                send(state, Map.of("type", "complete", "text", state.finalText.toString()));
                close(state.session, CloseStatus.NORMAL);
            }

            @Override
            public void onError(String message) {
                fail(state, "ALIYUN_ASR_ERROR", safeText(message));
            }
        };
    }

    private void authenticationTimedOut(String sessionId) {
        ClientState state = clients.get(sessionId);
        if (state != null && !state.authenticated.get()) {
            fail(state, "AUTH_TIMEOUT", "连接后 5 秒内必须完成鉴权");
        }
    }

    private void fail(ClientState state, String code, String message) {
        send(state, Map.of("type", "error", "code", code,
                "message", message == null || message.isBlank() ? "实时识别失败" : message));
        close(state.session, CloseStatus.POLICY_VIOLATION);
    }

    private void send(ClientState state, Map<String, ?> payload) {
        if (!state.session.isOpen()) {
            return;
        }
        try {
            state.session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception exception) {
            close(state.session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        close(session, CloseStatus.SERVER_ERROR);
    }

    private void cleanup(String sessionId) {
        ClientState state = clients.remove(sessionId);
        if (state == null || !state.closed.compareAndSet(false, true)) {
            return;
        }
        cancel(state.authTimeout);
        if (state.asrSession != null) {
            state.asrSession.close();
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
        } finally {
            cleanup(session.getId());
        }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String safeText(String text) {
        return text == null ? "" : text;
    }

    private static final class ClientState {
        private final WebSocketSession session;
        private final AtomicBoolean authenticated = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final StringBuilder finalText = new StringBuilder();
        private volatile ScheduledFuture<?> authTimeout;
        private volatile StreamingAsrSessionFactory.Session asrSession;

        private ClientState(WebSocketSession session) {
            this.session = session;
        }
    }
}

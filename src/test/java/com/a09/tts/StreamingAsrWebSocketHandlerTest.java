package com.a09.tts;

import com.a09.tts.service.StreamingAsrSessionFactory;
import com.a09.tts.util.JwtUtil;
import com.a09.tts.websocket.StreamingAsrWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingAsrWebSocketHandlerTest {
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final StreamingAsrSessionFactory sessionFactory = mock(StreamingAsrSessionFactory.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> authTimeout = mock(ScheduledFuture.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private StreamingAsrWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        doReturn(authTimeout).when(taskScheduler)
                .schedule(any(Runnable.class), any(Instant.class));
        handler = new StreamingAsrWebSocketHandler(
                jwtUtil, objectMapper, sessionFactory, taskScheduler);
    }

    @Test
    void rejectsInvalidJwtBeforeOpeningAliyunSession() throws Exception {
        WebSocketSession browser = browserSession("invalid");
        List<String> messages = captureMessages(browser);
        when(jwtUtil.verifyToken("bad-token")).thenReturn(false);

        handler.afterConnectionEstablished(browser);
        handler.handleMessage(browser, new TextMessage(
                """
                {"type":"start","token":"bad-token","language":"zh","sampleRate":16000}
                """));

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message).contains("\"type\":\"error\"");
            assertThat(message).contains("\"code\":\"UNAUTHORIZED\"");
        });
        verify(browser).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void closesConnectionWhenAuthenticationTimesOut() throws Exception {
        WebSocketSession browser = browserSession("timeout");
        List<String> messages = captureMessages(browser);
        ArgumentCaptor<Runnable> timeoutCaptor = ArgumentCaptor.forClass(Runnable.class);

        handler.afterConnectionEstablished(browser);
        verify(taskScheduler).schedule(timeoutCaptor.capture(), any(Instant.class));
        timeoutCaptor.getValue().run();

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message).contains("\"type\":\"error\"");
            assertThat(message).contains("\"code\":\"AUTH_TIMEOUT\"");
        });
        verify(authTimeout).cancel(false);
        verify(browser).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void forwardsPcmAndMapsAliyunCallbacks() throws Exception {
        WebSocketSession browser = browserSession("valid");
        List<String> messages = captureMessages(browser);
        StreamingAsrSessionFactory.Session aliyunSession =
                mock(StreamingAsrSessionFactory.Session.class);
        ArgumentCaptor<StreamingAsrSessionFactory.Listener> listenerCaptor =
                ArgumentCaptor.forClass(StreamingAsrSessionFactory.Listener.class);
        when(jwtUtil.verifyToken("valid-token")).thenReturn(true);
        when(sessionFactory.open(listenerCaptor.capture())).thenReturn(aliyunSession);

        handler.afterConnectionEstablished(browser);
        handler.handleMessage(browser, new TextMessage(
                """
                {"type":"start","token":"valid-token","language":"zh","sampleRate":16000}
                """));
        StreamingAsrSessionFactory.Listener listener = listenerCaptor.getValue();
        listener.onReady();
        handler.handleMessage(browser, new BinaryMessage(new byte[]{1, 2, 3, 4}));
        listener.onPartial("你好");
        listener.onFinal("你好。");
        handler.handleMessage(browser, new TextMessage("{\"type\":\"stop\"}"));
        listener.onComplete();

        verify(aliyunSession).send(new byte[]{1, 2, 3, 4});
        verify(aliyunSession).stop();
        verify(aliyunSession).close();
        verify(browser).close(CloseStatus.NORMAL);
        assertThat(messages).anyMatch(message -> message.contains("\"type\":\"ready\""));
        assertThat(messages).anyMatch(message -> message.contains("\"type\":\"partial\"")
                && message.contains("\"text\":\"你好\""));
        assertThat(messages).anyMatch(message -> message.contains("\"type\":\"final\"")
                && message.contains("\"text\":\"你好。\""));
        assertThat(messages).anyMatch(message -> message.contains("\"type\":\"complete\"")
                && message.contains("\"text\":\"你好。\""));
    }

    private static WebSocketSession browserSession(String id) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static List<String> captureMessages(WebSocketSession session) throws Exception {
        List<String> messages = new ArrayList<>();
        doAnswer(invocation -> {
            WebSocketMessage<?> message = invocation.getArgument(0);
            messages.add(String.valueOf(message.getPayload()));
            return null;
        }).when(session).sendMessage(any(WebSocketMessage.class));
        return messages;
    }
}

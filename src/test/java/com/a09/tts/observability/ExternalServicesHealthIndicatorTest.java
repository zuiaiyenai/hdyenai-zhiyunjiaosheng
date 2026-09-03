package com.a09.tts.observability;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalServicesHealthIndicatorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reportsUpAndPublishesDependencyGauges() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tts", exchange -> {
            exchange.sendResponseHeaders(422, -1);
            exchange.close();
        });
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExternalServicesHealthIndicator indicator = indicator(false, registry);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(registry.get("fctts.external.service.up")
                .tag("service", "gpt-sovits").gauge().value()).isEqualTo(1);
        assertThat(registry.get("fctts.external.service.up")
                .tag("service", "funasr").gauge().value()).isEqualTo(1);
    }

    @Test
    void optionalFailureIsUnknownButRequiredFailureIsDown() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.stop(0);

        SimpleMeterRegistry optionalRegistry = new SimpleMeterRegistry();
        ExternalServicesHealthIndicator optional = indicator(port, false, optionalRegistry);
        ExternalServicesHealthIndicator required = indicator(
                port, true, new SimpleMeterRegistry());

        assertThat(optional.health().getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(required.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(optionalRegistry.get("fctts.external.service.up")
                .tag("service", "gpt-sovits").gauge().value()).isZero();
        assertThat(optionalRegistry.get("fctts.external.service.up")
                .tag("service", "funasr").gauge().value()).isZero();
    }

    private ExternalServicesHealthIndicator indicator(boolean required,
                                                       SimpleMeterRegistry registry) {
        return indicator(server.getAddress().getPort(), required, registry);
    }

    private ExternalServicesHealthIndicator indicator(int port, boolean required,
                                                       SimpleMeterRegistry registry) {
        return new ExternalServicesHealthIndicator(
                URI.create("http://127.0.0.1:" + port + "/tts"),
                URI.create("http://127.0.0.1:" + port + "/health"),
                required,
                Duration.ofMillis(200),
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build(),
                registry);
    }
}

package com.a09.tts.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component("externalServices")
public class ExternalServicesHealthIndicator implements HealthIndicator {
    private final URI ttsHealthUri;
    private final URI asrHealthUri;
    private final boolean required;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final AtomicInteger ttsUp = new AtomicInteger();
    private final AtomicInteger asrUp = new AtomicInteger();

    @Autowired
    public ExternalServicesHealthIndicator(
            @Value("${app.observability.tts-health-url:http://127.0.0.1:9880/tts}") String ttsHealthUrl,
            @Value("${app.observability.asr-health-url:http://127.0.0.1:9977/health}") String asrHealthUrl,
            @Value("${app.observability.external-services-required:false}") boolean required,
            @Value("${app.observability.probe-timeout:2s}") Duration timeout,
            MeterRegistry meterRegistry) {
        this(URI.create(ttsHealthUrl), URI.create(asrHealthUrl), required, timeout,
                HttpClient.newBuilder().connectTimeout(timeout).build(), meterRegistry);
    }

    ExternalServicesHealthIndicator(URI ttsHealthUri, URI asrHealthUri, boolean required,
                                    Duration timeout, HttpClient httpClient,
                                    MeterRegistry meterRegistry) {
        this.ttsHealthUri = ttsHealthUri;
        this.asrHealthUri = asrHealthUri;
        this.required = required;
        this.timeout = timeout;
        this.httpClient = httpClient;
        Gauge.builder("fctts.external.service.up", ttsUp, AtomicInteger::get)
                .tag("service", "gpt-sovits")
                .description("Whether the configured GPT-SoVITS endpoint is reachable")
                .register(meterRegistry);
        Gauge.builder("fctts.external.service.up", asrUp, AtomicInteger::get)
                .tag("service", "funasr")
                .description("Whether the configured FunASR endpoint reports UP")
                .register(meterRegistry);
    }

    @Override
    public Health health() {
        boolean ttsAvailable = probeTts();
        boolean asrAvailable = probeAsr();
        ttsUp.set(ttsAvailable ? 1 : 0);
        asrUp.set(asrAvailable ? 1 : 0);

        Health.Builder builder;
        if (ttsAvailable && asrAvailable) {
            builder = Health.up();
        } else if (required) {
            builder = Health.down();
        } else {
            builder = Health.status(Status.UNKNOWN);
        }
        return builder
                .withDetail("gptSovits", state(ttsAvailable))
                .withDetail("funAsr", state(asrAvailable))
                .withDetail("required", required)
                .build();
    }

    private boolean probeTts() {
        try {
            HttpResponse<Void> response = sendHead(ttsHealthUri);
            return response.statusCode() >= 200 && response.statusCode() < 500;
        } catch (Exception exception) {
            restoreInterrupt(exception);
            return false;
        }
    }

    private boolean probeAsr() {
        try {
            HttpResponse<String> response = send(asrHealthUri, HttpResponse.BodyHandlers.ofString());
            String compactBody = response.body().replaceAll("\\s+", "");
            return response.statusCode() == 200
                    && compactBody.contains("\"status\":\"UP\"");
        } catch (Exception exception) {
            restoreInterrupt(exception);
            return false;
        }
    }

    private HttpResponse<Void> sendHead(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private <T> HttpResponse<T> send(URI uri, HttpResponse.BodyHandler<T> bodyHandler)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .GET()
                .build();
        return httpClient.send(request, bodyHandler);
    }

    private void restoreInterrupt(Exception exception) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private String state(boolean available) {
        return available ? "UP" : "DOWN";
    }
}

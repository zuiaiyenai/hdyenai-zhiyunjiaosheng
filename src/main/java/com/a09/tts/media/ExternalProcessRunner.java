package com.a09.tts.media;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class ExternalProcessRunner {
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 1024 * 1024;
    private final Duration defaultTimeout;
    private final Map<Thread, Process> activeProcesses = new ConcurrentHashMap<>();

    @Autowired
    public ExternalProcessRunner(
            @Value("${app.media.process-timeout:10m}") Duration defaultTimeout) {
        if (defaultTimeout == null || defaultTimeout.isZero() || defaultTimeout.isNegative()) {
            throw new IllegalArgumentException("外部进程超时必须大于 0");
        }
        this.defaultTimeout = defaultTimeout;
    }

    public ProcessResult run(List<String> command, String failureMessage) throws IOException {
        return run(command, defaultTimeout, failureMessage);
    }

    public ProcessResult run(List<String> command, Duration timeout, String failureMessage)
            throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("外部进程命令不能为空");
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Thread owner = Thread.currentThread();
        activeProcesses.put(owner, process);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (var input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int captured = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    int writable = Math.min(read, MAX_CAPTURED_OUTPUT_BYTES - captured);
                    if (writable > 0) {
                        output.write(buffer, 0, writable);
                        captured += writable;
                    }
                }
            } catch (IOException ignored) {
                // Process termination closes the stream.
            }
        }, "media-process-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                join(reader);
                throw new IOException(failureMessage + "：处理超时");
            }
            join(reader);
            int exitCode = process.exitValue();
            String text = output.toString(StandardCharsets.UTF_8);
            if (exitCode != 0) {
                throw new IOException(failureMessage + "（退出码 " + exitCode + "）");
            }
            return new ProcessResult(exitCode, text);
        } catch (InterruptedException exception) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new IOException(failureMessage + "：处理被取消", exception);
        } finally {
            activeProcesses.remove(owner, process);
            if (process.isAlive()) {
                terminate(process);
            }
        }
    }

    public void cancelCurrentThreadProcess(Thread thread) {
        Process process = activeProcesses.get(thread);
        if (process != null) {
            terminate(process);
        }
    }

    private void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private void join(Thread reader) throws InterruptedException {
        reader.join(2_000);
    }

    public record ProcessResult(int exitCode, String output) {
    }
}

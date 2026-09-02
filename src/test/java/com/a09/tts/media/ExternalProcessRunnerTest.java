package com.a09.tts.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalProcessRunnerTest {

    @Test
    void capturesOutputAndExitCode() throws Exception {
        ExternalProcessRunner runner = new ExternalProcessRunner(Duration.ofSeconds(5));

        ExternalProcessRunner.ProcessResult result = runner.run(
                List.of(javaExecutable(), "-version"), "Java 启动失败");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("version"));
    }

    @Test
    void terminatesProcessOnTimeout() {
        ExternalProcessRunner runner = new ExternalProcessRunner(Duration.ofMillis(100));
        long started = System.nanoTime();

        IOException exception = assertThrows(IOException.class, () -> runner.run(
                List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                        SlowProcess.class.getName()),
                "测试进程失败"));

        assertTrue(exception.getMessage().contains("超时"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    public static class SlowProcess {
        public static void main(String[] arguments) throws Exception {
            Thread.sleep(10_000);
        }
    }
}

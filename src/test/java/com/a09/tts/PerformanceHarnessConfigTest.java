package com.a09.tts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceHarnessConfigTest {

    @Test
    void coreApiWorkloadUsesClosedModelAndEndpointScopedSlos() throws Exception {
        String script = Files.readString(Path.of("performance/core-api.js"));

        assertTrue(script.contains("executor: 'constant-vus'"));
        assertTrue(script.contains("warmupDuration"));
        assertTrue(script.contains("steadyDuration"));
        assertTrue(script.contains("'p(50)<100', 'p(95)<300', 'p(99)<800'"));
        assertTrue(script.contains("fctts_l0_errors: ['rate<0.01']"));
        assertTrue(script.contains("endpoint: 'task_create'"));
        assertTrue(script.contains("'task_status'"));
        assertTrue(script.contains("taskSharePercent"));
        assertTrue(script.contains("stateScenario !== exec.scenario.name"));
        assertTrue(script.contains("selectedForPercent(userIndex, taskSharePercent)"));
        assertTrue(script.contains("handleSummary"));
        assertFalse(script.contains("/voice/synthesize"));
        assertFalse(script.contains("/voice/stream"));
    }

    @Test
    void runnerUsesIsolatedDataAndTwoBackendProcesses() throws Exception {
        String runner = Files.readString(Path.of("performance/run_phase11.ps1"));
        String seed = Files.readString(Path.of("performance/prepare_data.ps1"));

        assertTrue(runner.contains("fctts_phase11_"));
        assertTrue(runner.contains("$RedisDatabase = 14"));
        assertTrue(runner.contains("$RedisPort = 6380"));
        assertTrue(runner.contains("$env:SPRING_DATASOURCE_URL = $env:DB_URL"));
        assertTrue(runner.contains("3306/${schema}?useUnicode=true"));
        assertTrue(runner.contains("Assert-BackendSchema 'backend-1'"));
        assertTrue(runner.contains("--server.tomcat.mbeanregistry.enabled=true"));
        assertTrue(runner.contains("'appendonly no'"));
        assertTrue(runner.contains("Remove-Item -LiteralPath $redisConfigPath"));
        assertTrue(runner.contains("Start-Backend 8081 9091 'backend-1'"));
        assertTrue(runner.contains("Start-Backend 8082 9092 'backend-2'"));
        assertTrue(runner.contains("--app.tasks.poll-interval=24h"));
        assertTrue(runner.contains("DROP DATABASE IF EXISTS"));
        assertTrue(seed.contains("$Schema -eq 'zhiyunjiaos'"));
        assertTrue(seed.contains("new BCryptPasswordEncoder(12)"));
        assertTrue(seed.contains("$RegisteredUsers -lt 1000"));
        assertTrue(seed.contains("$ActiveUsers -lt 200"));
    }

    @Test
    void collectorCoversEveryRequiredPhaseElevenResource() throws Exception {
        String collector = Files.readString(Path.of("performance/collect_metrics.ps1"));

        assertTrue(collector.contains("$labels = $Matches[2]"));
        assertTrue(collector.contains("$valueText = $Matches[3]"));
        assertTrue(collector.contains("collector.complete"));
        for (String metric : new String[]{
                "hostCpu", "availableMemoryMb", "jvm_heap_used_bytes",
                "jvm_gc_pause_seconds_sum", "tomcat_threads_busy_threads",
                "hikaricp_connections_active", "executor_active_threads",
                "fctts_task_admission_rejected_total", "ping_latency_ms",
                "Threads_connected", "Threads_running"}) {
            assertTrue(collector.contains(metric), metric);
        }
    }
}

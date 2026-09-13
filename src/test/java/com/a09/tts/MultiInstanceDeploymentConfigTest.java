package com.a09.tts;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiInstanceDeploymentConfigTest {

    @Test
    void composeDefinesTwoBackendsWithSharedStateConfiguration() throws Exception {
        Map<String, Object> compose = new Yaml().load(
                Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = map(compose.get("services"));
        Map<String, Object> first = map(services.get("backend-1"));
        Map<String, Object> second = map(services.get("backend-2"));
        Map<String, Object> firstEnvironment = map(first.get("environment"));
        Map<String, Object> secondEnvironment = map(second.get("environment"));

        for (String property : List.of(
                "DB_NAME", "REDIS_DATABASE", "JWT_SECRET", "OBJECT_STORAGE_PROVIDER",
                "ALIYUN_OSS_ENDPOINT", "ALIYUN_OSS_BUCKET",
                "ALIYUN_OSS_ACCESS_KEY_ID", "ALIYUN_OSS_ACCESS_KEY_SECRET",
                "TASK_WORKER_COUNT", "TASK_PER_USER_CONCURRENCY",
                "TASK_GLOBAL_QUEUE_LIMIT", "TASK_ADMISSION_RETRY_AFTER")) {
            assertEquals(firstEnvironment.get(property), secondEnvironment.get(property), property);
        }
        assertEquals("true", firstEnvironment.get("REDIS_ENABLED"));
        assertEquals("${OBJECT_STORAGE_PROVIDER:-aliyun-oss}",
                firstEnvironment.get("OBJECT_STORAGE_PROVIDER"));
        assertTrue(list(first.get("ports")).stream()
                .anyMatch(value -> value.toString().contains("BACKEND_1_PORT:-8081")));
        assertTrue(list(second.get("ports")).stream()
                .anyMatch(value -> value.toString().contains("BACKEND_2_PORT:-8082")));
        assertFalse(list(first.get("volumes")).equals(list(second.get("volumes"))),
                "节点本地临时工作目录不能共用一个 volume");
    }

    @Test
    void nginxBalancesHttpAndWebSocketWithoutStickySessions() throws Exception {
        String nginx = Files.readString(Path.of("deploy/nginx/conf/nginx.conf"));

        assertTrue(nginx.contains("upstream backend_cluster"));
        assertTrue(nginx.contains("server 127.0.0.1:8081"));
        assertTrue(nginx.contains("server 127.0.0.1:8082"));
        assertTrue(nginx.contains("least_conn;"));
        assertTrue(nginx.contains("proxy_pass http://backend_cluster/;"));
        assertTrue(nginx.contains("location /ws/"));
        String webSocketLocation = nginx.substring(
                nginx.indexOf("location /ws/"), nginx.indexOf("location ~*", nginx.indexOf("location /ws/")));
        assertTrue(webSocketLocation.contains("proxy_set_header Origin $backend_origin;"));
        assertTrue(webSocketLocation.contains("proxy_set_header Upgrade $http_upgrade;"));
        assertFalse(nginx.contains("ip_hash;"));
        assertFalse(nginx.contains("proxy_pass http://127.0.0.1:8081"));
    }

    @Test
    void prometheusScrapesBothBackendInstances() throws Exception {
        Map<String, Object> prometheus = new Yaml().load(
                Files.readString(Path.of("ops/prometheus/prometheus.yml")));
        List<Object> scrapeConfigs = list(prometheus.get("scrape_configs"));
        Map<String, Object> backendJob = map(scrapeConfigs.get(0));
        List<Object> staticConfigs = list(backendJob.get("static_configs"));
        List<Object> targets = list(map(staticConfigs.get(0)).get("targets"));

        assertEquals(List.of("backend-1:9091", "backend-2:9091"), targets);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }
}

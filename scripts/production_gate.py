import argparse
import json
import os
import re
import subprocess
import threading
import time
from array import array
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path

from load_smoke import login, percentile, request


PROMETHEUS_SAMPLE = re.compile(
    r"^(?P<name>[a-zA-Z_:][a-zA-Z0-9_:]*)(?P<labels>\{.*\})?\s+"
    r"(?P<value>[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?|NaN|[+-]Inf)"
)
REQUIRED_RESOURCE_METRICS = (
    "process_cpu_usage",
    "system_cpu_usage",
    "jvm_memory_used_bytes",
    "jvm_gc_pause_seconds_count",
    "jvm_gc_pause_seconds_sum",
    "jvm_threads_live_threads",
    "executor_active_threads",
    "executor_queued_tasks",
    "hikaricp_connections_active",
    "hikaricp_connections_idle",
    "hikaricp_connections_pending",
    "disk_free_bytes",
    "disk_total_bytes",
)


def check_http(name, url, timeout, accepted=None, contains=None, method="GET", accept="application/json"):
    started = time.perf_counter()
    try:
        status, payload, _ = request(url, method=method, timeout=timeout, accept=accept)
        status_ok = accepted(status) if accepted else 200 <= status < 300
        body_ok = contains is None or contains in payload.decode("utf-8", errors="replace")
        passed = status_ok and body_ok
        return {
            "name": name,
            "status": status,
            "passed": passed,
            "latencyMs": round((time.perf_counter() - started) * 1000, 2),
        }
    except Exception as error:
        return {
            "name": name,
            "status": "UNREACHABLE",
            "passed": False,
            "latencyMs": round((time.perf_counter() - started) * 1000, 2),
            "errorType": type(error).__name__,
        }


def run_probe(args):
    checks = [
        check_http("backend-health", args.management_url + "/actuator/health", args.timeout),
        check_http(
            "backend-readiness",
            args.management_url + "/actuator/health/readiness",
            args.timeout,
        ),
        check_http(
            "prometheus-metrics",
            args.management_url + "/actuator/prometheus",
            args.timeout,
            contains="jvm_memory_used_bytes",
            accept="text/plain;version=0.0.4,*/*;q=0.1",
        ),
        check_http(
            "gpt-sovits",
            args.tts_url,
            args.timeout,
            accepted=lambda status: 200 <= status < 500,
            method="HEAD",
        ),
        check_http(
            "funasr",
            args.asr_health_url,
            args.timeout,
            contains='"status":"UP"',
        ),
    ]
    report = {
        "type": "production-probe",
        "timestamp": datetime.now(UTC).isoformat(),
        "passed": all(check["passed"] for check in checks),
        "checks": checks,
    }
    emit(report, args.output)
    return report


def run_stability(args):
    scenarios = scenario_paths(args.scenario, args.task_id)
    redis_health = None
    if args.scenario == "redis_mixed":
        redis_health = {
            "before": check_http(
                "redis-health-before",
                args.management_url + "/actuator/health/redis",
                args.timeout,
            )
        }
        if not redis_health["before"]["passed"]:
            report = {
                "type": "stability-load",
                "timestamp": datetime.now(UTC).isoformat(),
                "scenario": args.scenario,
                "passed": False,
                "redisHealth": redis_health,
                "failures": ["Redis health check failed before load"],
            }
            emit(report, args.output)
            return report
    token = None
    if any(path != "/actuator/health" for _, path in scenarios):
        username = os.getenv("LOAD_TEST_USERNAME")
        password = os.getenv("LOAD_TEST_PASSWORD")
        if not username or not password:
            raise SystemExit("LOAD_TEST_USERNAME and LOAD_TEST_PASSWORD are required")
        token = login(args.base_url, username, password, args.timeout)

    resource_samples = []
    stop_sampling = threading.Event()
    sampler = None
    if getattr(args, "collect_resources", False):
        resource_samples.append(collect_resource_sample(args))

        def sample_until_stopped():
            while not stop_sampling.wait(args.resource_sample_interval):
                resource_samples.append(collect_resource_sample(args))

        sampler = threading.Thread(
            target=sample_until_stopped, name="qualification-resource-sampler", daemon=True
        )
        sampler.start()

    deadline = time.perf_counter() + args.duration_seconds
    started = time.perf_counter()
    lock = threading.Lock()
    durations = array("d")
    statuses = Counter()
    scenario_counts = Counter()
    next_scenario = 0

    def worker(_worker_id):
        nonlocal next_scenario
        while time.perf_counter() < deadline:
            with lock:
                index = next_scenario
                next_scenario += 1
            scenario, path = scenarios[index % len(scenarios)]
            request_started = time.perf_counter()
            try:
                if scenario == "login":
                    status, _, elapsed = request(
                        args.base_url + path,
                        method="POST",
                        body={"username": username, "password": password},
                        timeout=args.timeout,
                    )
                else:
                    status, _, elapsed = request(
                        args.base_url + path,
                        token=token,
                        timeout=args.timeout,
                    )
            except Exception as error:
                status = "EXCEPTION_" + type(error).__name__
                elapsed = (time.perf_counter() - request_started) * 1000
            with lock:
                statuses[status] += 1
                scenario_counts[scenario] += 1
                durations.append(elapsed)

    try:
        with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
            list(executor.map(worker, range(args.concurrency)))
    finally:
        if sampler is not None:
            stop_sampling.set()
            sampler.join(timeout=args.resource_sample_interval + args.timeout)
            resource_samples.append(collect_resource_sample(args))

    wall_seconds = time.perf_counter() - started
    total = sum(statuses.values())
    successful = sum(
        count for status, count in statuses.items()
        if isinstance(status, int) and 200 <= status < 300
    )
    error_rate = 1 - successful / total if total else 1.0
    failures = []
    if total < args.min_requests:
        failures.append(f"request count {total} is below minimum {args.min_requests}")
    if error_rate > args.max_error_rate:
        failures.append(
            f"error rate {error_rate:.6f} exceeds maximum {args.max_error_rate:.6f}"
        )
    if args.scenario == "redis_mixed":
        redis_health["after"] = check_http(
            "redis-health-after",
            args.management_url + "/actuator/health/redis",
            args.timeout,
        )
        if not redis_health["after"]["passed"]:
            failures.append("Redis health check failed after load")
        if scenario_counts["login"] == 0:
            failures.append("Redis-backed login path was not exercised")
    if resource_samples:
        resource_failures = validate_resource_samples(resource_samples)
        if getattr(args, "require_resource_metrics", False):
            failures.extend(resource_failures)
    report = {
        "type": "stability-load",
        "timestamp": datetime.now(UTC).isoformat(),
        "scenario": args.scenario,
        "durationSeconds": round(wall_seconds, 3),
        "concurrency": args.concurrency,
        "requests": total,
        "requestsPerSecond": round(total / wall_seconds, 2),
        "successRate": round(successful / total, 6) if total else 0,
        "errorRate": round(error_rate, 6),
        "statusCounts": dict(sorted(statuses.items(), key=lambda item: str(item[0]))),
        "scenarioCounts": dict(sorted(scenario_counts.items())),
        "latencyMs": {
            "p50": percentile(durations, 0.50),
            "p95": percentile(durations, 0.95),
            "p99": percentile(durations, 0.99),
            "max": round(max(durations), 2),
        } if durations else {},
        "thresholds": {
            "minRequests": args.min_requests,
            "maxErrorRate": args.max_error_rate,
        },
        "passed": not failures,
        "failures": failures,
    }
    if redis_health is not None:
        report["redisHealth"] = redis_health
        report["redisBackedEvidence"] = {
            "path": "/user/login",
            "requests": scenario_counts["login"],
            "mechanism": "LoginRateLimiter uses Redis when REDIS_ENABLED=true",
        }
    if resource_samples:
        report["resources"] = {
            "sampleIntervalSeconds": args.resource_sample_interval,
            "sampleCount": len(resource_samples),
            "start": resource_samples[0],
            "end": resource_samples[-1],
            "samples": resource_samples,
            "validationFailures": validate_resource_samples(resource_samples),
        }
    emit(report, args.output)
    return report


def selected_prometheus_metrics(payload):
    selected = {}
    for line in payload.decode("utf-8", errors="replace").splitlines():
        match = PROMETHEUS_SAMPLE.match(line)
        if not match:
            continue
        name = match.group("name")
        if not (
            name in REQUIRED_RESOURCE_METRICS
            or name in ("disk_free_bytes", "disk_total_bytes", "fctts_external_service_up")
            or "redis" in name
            or "lettuce" in name
        ):
            continue
        try:
            value = float(match.group("value"))
        except ValueError:
            continue
        selected[name + (match.group("labels") or "")] = value
    return selected


def compose_command(args, *command):
    result = subprocess.run(
        [
            "docker", "compose", "--project-name", args.compose_project_name,
            "--file", args.compose_file, *command,
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=args.timeout,
    )
    return result.stdout.strip()


def collect_resource_sample(args):
    sample = {"timestamp": datetime.now(UTC).isoformat()}
    errors = []
    try:
        status_code, payload, _ = request(
            args.management_url + "/actuator/prometheus",
            timeout=args.timeout,
            accept="text/plain;version=0.0.4,*/*;q=0.1",
        )
        if status_code != 200:
            raise RuntimeError(f"Prometheus returned HTTP {status_code}")
        sample["prometheus"] = selected_prometheus_metrics(payload)
    except Exception as error:
        errors.append("prometheus:" + type(error).__name__)

    if getattr(args, "compose_project_name", None):
        try:
            container_id = compose_command(args, "ps", "--quiet", "backend")
            sample["dockerStats"] = json.loads(subprocess.run(
                ["docker", "stats", "--no-stream", "--format", "{{json .}}", container_id],
                check=True,
                capture_output=True,
                text=True,
                timeout=args.timeout,
            ).stdout)
        except Exception as error:
            errors.append("dockerStats:" + type(error).__name__)
        commands = {
            "processRssBytes": "awk '/VmRSS/ {print $2 * 1024}' /proc/1/status",
            "uploadBytes": "du -sb /app/uploads | cut -f1",
            "uploadFileCount": "find /app/uploads -type f | wc -l",
            "tempFileCount": "find /tmp -type f | wc -l",
            "pendingCleanupCount": (
                "MYSQL_PWD=\"$MYSQL_PASSWORD\" exec mysql --protocol=tcp "
                "-h 127.0.0.1 -u\"$MYSQL_USER\" --skip-column-names "
                "-e 'SELECT COUNT(*) FROM pending_file_cleanup' \"$MYSQL_DATABASE\""
            ),
        }
        for key, command in commands.items():
            service = "mysql" if key == "pendingCleanupCount" else "backend"
            try:
                sample[key] = int(compose_command(
                    args, "exec", "-T", service, "sh", "-c", command
                ))
            except Exception as error:
                errors.append(key + ":" + type(error).__name__)
        try:
            backend_logs = compose_command(args, "logs", "--no-color", "backend")
            sample["backendLogBytes"] = len(backend_logs.encode("utf-8"))
            sample["redisErrorLines"] = sum(
                1 for line in backend_logs.splitlines()
                if re.search(r"(?i)(redis.*(error|timeout)|((error|timeout).*redis))", line)
            )
        except Exception as error:
            errors.append("backendLogs:" + type(error).__name__)
    if errors:
        sample["errors"] = errors
    return sample


def validate_resource_samples(samples):
    failures = []
    if len(samples) < 2:
        failures.append("Resource sampling did not capture start and end")
        return failures
    for position, sample in (("start", samples[0]), ("end", samples[-1])):
        if sample.get("errors"):
            failures.append(f"Resource {position} sample errors: {sample['errors']}")
        metrics = sample.get("prometheus", {})
        for required in REQUIRED_RESOURCE_METRICS:
            if not any(key.startswith(required) for key in metrics):
                failures.append(f"Resource {position} sample missing {required}")
        for required in ("dockerStats", "processRssBytes", "uploadBytes",
                         "uploadFileCount", "tempFileCount", "pendingCleanupCount",
                         "backendLogBytes", "redisErrorLines"):
            if required not in sample:
                failures.append(f"Resource {position} sample missing {required}")
    return failures


def scenario_paths(scenario, task_id):
    if scenario == "health":
        return [("health", "/actuator/health")]
    if scenario == "login":
        return [("login", "/user/login")]
    if scenario == "voices":
        return [("voices", "/voice_library/list?page=0&size=20")]
    if scenario == "tasks":
        return [("tasks", "/api/tasks?page=0&size=20")]
    if scenario == "courseware":
        return [("courseware", "/courseware/projects?page=0&size=20")]
    paths = [
        ("login", "/user/login"),
        ("voices", "/voice_library/list?page=0&size=20"),
        ("voices", "/voice_library/list?page=0&size=20"),
        ("voices", "/voice_library/list?page=0&size=20"),
        ("voices", "/voice_library/list?page=0&size=20"),
        ("tasks", "/api/tasks?page=0&size=20"),
        ("tasks", "/api/tasks?page=0&size=20"),
        ("tasks", "/api/tasks?page=0&size=20"),
        ("courseware", "/courseware/projects?page=0&size=20"),
        ("courseware", "/courseware/projects?page=0&size=20"),
    ]
    if task_id:
        paths.append(("task", f"/api/tasks/{task_id}"))
    return paths


def emit(report, output):
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if output:
        output_path = Path(output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(rendered + "\n", encoding="utf-8")


def build_parser():
    parser = argparse.ArgumentParser(description="FCTTS production gate probes")
    subparsers = parser.add_subparsers(dest="command", required=True)

    probe = subparsers.add_parser("probe", help="Probe backend, metrics, TTS and ASR")
    probe.add_argument("--management-url", default="http://127.0.0.1:8081")
    probe.add_argument("--tts-url", default="http://127.0.0.1:9880/tts")
    probe.add_argument("--asr-health-url", default="http://127.0.0.1:9977/health")
    probe.add_argument("--timeout", type=int, default=5)
    probe.add_argument("--output")

    stability = subparsers.add_parser("stability", help="Run a bounded stability load")
    stability.add_argument("--base-url", default="http://127.0.0.1:8081")
    stability.add_argument(
        "--scenario", choices=("health", "login", "voices", "tasks", "courseware", "mixed", "redis_mixed"),
        default="mixed",
    )
    stability.add_argument("--management-url", default="http://127.0.0.1:9091")
    stability.add_argument("--task-id")
    stability.add_argument("--duration-seconds", type=int, default=1800)
    stability.add_argument("--concurrency", type=int, default=20)
    stability.add_argument("--timeout", type=int, default=10)
    stability.add_argument("--min-requests", type=int, default=1000)
    stability.add_argument("--max-error-rate", type=float, default=0.005)
    stability.add_argument("--collect-resources", action="store_true")
    stability.add_argument("--require-resource-metrics", action="store_true")
    stability.add_argument("--resource-sample-interval", type=int, default=60)
    stability.add_argument("--compose-project-name")
    stability.add_argument("--compose-file", default="docker-compose.yml")
    stability.add_argument("--output")
    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    if args.command == "stability":
        if (args.duration_seconds < 1 or args.concurrency < 1 or args.min_requests < 1
                or args.resource_sample_interval < 1):
            parser.error("duration, concurrency and min-requests must be positive")
        if not 0 <= args.max_error_rate <= 1:
            parser.error("max-error-rate must be between 0 and 1")
        report = run_stability(args)
    else:
        report = run_probe(args)
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

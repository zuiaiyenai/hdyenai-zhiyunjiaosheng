import argparse
import hashlib
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from datetime import UTC, datetime
from pathlib import Path

from production_gate import selected_prometheus_metrics


def request_result(url, method="GET", body=None, headers=None, timeout=10):
    data = body.encode("utf-8") if body is not None else None
    request_headers = dict(headers or {})
    if data:
        request_headers["Content-Type"] = "application/json"
    try:
        with urllib.request.urlopen(
            urllib.request.Request(
                url, data=data, headers=request_headers, method=method
            ),
            timeout=timeout,
        ) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def status(url, method="GET", body=None, headers=None, timeout=10):
    return request_result(
        url, method=method, body=body, headers=headers, timeout=timeout
    )[0]


def wait_for(url, expected, timeout=180):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = status(url, timeout=5)
            if last in expected:
                return last
        except Exception as error:
            last = type(error).__name__
        time.sleep(2)
    raise RuntimeError(f"Timed out waiting for {url}; last result={last}")


def compose(args, action, service):
    subprocess.run(
        [
            "docker", "compose", "--project-name", args.project_name,
            "--file", args.compose_file, action, service,
        ],
        check=True,
    )


def login_token(app_url, username, password, timeout):
    login_status, payload = request_result(
        app_url + "/user/login",
        method="POST",
        body=json.dumps({"username": username, "password": password}),
        timeout=timeout,
    )
    if login_status != 200:
        raise RuntimeError(f"Login failed with HTTP {login_status}")
    token = json.loads(payload).get("token")
    if not token:
        raise RuntimeError("Login response did not contain a token")
    return token


def register_and_login(args):
    username = "phase11_fault_" + uuid.uuid4().hex
    password = "Phase11Fault!9"
    register_status = status(
        args.app_url + "/user/register",
        method="POST",
        body=json.dumps({"username": username, "password": password}),
        timeout=args.request_timeout,
    )
    if register_status != 201:
        raise RuntimeError(f"Registration failed with HTTP {register_status}")
    return username, password, login_token(
        args.app_url, username, password, args.request_timeout
    )


def authenticated_result(args, token, path):
    return request_result(
        args.app_url + path,
        headers={"Authorization": "Bearer " + token},
        timeout=args.request_timeout,
    )


def prometheus_metrics(args):
    metrics_status, payload = request_result(
        args.management_url + "/actuator/prometheus",
        headers={"Accept": "text/plain;version=0.0.4,*/*;q=0.1"},
        timeout=args.request_timeout,
    )
    if metrics_status != 200:
        raise RuntimeError(f"Prometheus returned HTTP {metrics_status}")
    return selected_prometheus_metrics(payload)


def payload_sha256(payload):
    return hashlib.sha256(payload).hexdigest()


def require_status(label, actual, expected):
    if actual not in expected:
        raise RuntimeError(
            f"{label} returned HTTP {actual}; expected {sorted(expected)}"
        )
    return actual


def run(args):
    if not args.confirm_isolated_stack:
        raise SystemExit("Refusing fault injection without --confirm-isolated-stack")

    health = args.management_url + "/actuator/health"
    redis_health = args.management_url + "/actuator/health/redis"
    liveness = args.management_url + "/actuator/health/liveness"
    readiness = args.management_url + "/actuator/health/readiness"
    voice_path = "/voice_library/list?page=0&size=20"

    wait_for(readiness, {200})
    wait_for(redis_health, {200})
    username, password, token = register_and_login(args)
    voice_status, voice_payload = authenticated_result(args, token, voice_path)
    require_status(
        "DB-backed voice API before faults",
        voice_status,
        {200},
    )
    baseline_voice_sha256 = payload_sha256(voice_payload)
    baseline_metrics = prometheus_metrics(args)
    results = []

    redis_stopped = False
    redis_result = None
    try:
        compose(args, "stop", "redis")
        redis_stopped = True
        redis_fault = wait_for(redis_health, {503})
        readiness_fault = wait_for(readiness, {503})
        live_status = wait_for(liveness, {200})
        missing_username = "phase11_missing_" + uuid.uuid4().hex
        login_statuses = [
            status(
                args.app_url + "/user/login",
                method="POST",
                body=json.dumps({
                    "username": missing_username,
                    "password": "WrongPassword1!",
                }),
                timeout=args.request_timeout,
            )
            for _ in range(6)
        ]
        if login_statuses != [401, 401, 401, 401, 401, 429]:
            raise RuntimeError(f"Redis fallback mismatch: {login_statuses}")
        redis_result = {
            "dependency": "redis",
            "componentHealthDuringFault": redis_fault,
            "readinessDuringFault": readiness_fault,
            "livenessDuringFault": live_status,
            "metricsBefore": baseline_metrics,
            "metricsDuringFault": prometheus_metrics(args),
            "expectedBehavior": "readiness 503; login limiter falls back in-process",
            "fallbackLoginStatuses": login_statuses,
            "backendRestartRequired": False,
            "passed": True,
        }
    finally:
        if redis_stopped:
            recovery_started = time.monotonic()
            compose(args, "start", "redis")
            recovered_redis_health = wait_for(redis_health, {200})
            recovered_readiness = wait_for(readiness, {200})
            if redis_result is not None:
                redis_result["recoverySeconds"] = round(
                    time.monotonic() - recovery_started, 3
                )
                redis_result["componentHealthAfterRecovery"] = recovered_redis_health
                redis_result["readinessAfterRecovery"] = recovered_readiness
                redis_result["metricsAfterRecovery"] = prometheus_metrics(args)

    token = login_token(args.app_url, username, password, args.request_timeout)
    recovered_status, recovered_payload = authenticated_result(args, token, voice_path)
    require_status(
        "DB-backed voice API after Redis recovery",
        recovered_status,
        {200},
    )
    if payload_sha256(recovered_payload) != baseline_voice_sha256:
        raise RuntimeError("DB-backed voice payload changed after Redis recovery")
    redis_result["recoveredLogin"] = 200
    redis_result["recoveredVoiceApi"] = 200
    redis_result["voicePayloadSha256"] = baseline_voice_sha256
    results.append(redis_result)

    mysql_stopped = False
    mysql_result = None
    mysql_metrics_before = prometheus_metrics(args)
    try:
        compose(args, "stop", "mysql")
        mysql_stopped = True
        health_status = wait_for(health, {503})
        readiness_fault = wait_for(readiness, {503})
        live_status = wait_for(liveness, {200})
        started = time.monotonic()
        db_api_status, _ = authenticated_result(args, token, voice_path)
        elapsed = time.monotonic() - started
        require_status("DB-backed voice API during MySQL fault", db_api_status, {500, 503})
        mysql_result = {
            "dependency": "mysql",
            "healthDuringFault": health_status,
            "readinessDuringFault": readiness_fault,
            "livenessDuringFault": live_status,
            "dbApiDuringFault": db_api_status,
            "dbApiLatencySeconds": round(elapsed, 3),
            "metricsBefore": mysql_metrics_before,
            "metricsDuringFault": prometheus_metrics(args),
            "expectedBehavior": "controlled 500/503 while liveness remains 200",
            "backendRestartRequired": False,
            "passed": True,
        }
    finally:
        if mysql_stopped:
            recovery_started = time.monotonic()
            compose(args, "start", "mysql")
            recovered_readiness = wait_for(readiness, {200})
            if mysql_result is not None:
                mysql_result["recoverySeconds"] = round(
                    time.monotonic() - recovery_started, 3
                )
                mysql_result["readinessAfterRecovery"] = recovered_readiness
                mysql_result["metricsAfterRecovery"] = prometheus_metrics(args)

    recovered_voice, recovered_payload = authenticated_result(args, token, voice_path)
    require_status(
        "DB-backed voice API after MySQL recovery",
        recovered_voice,
        {200},
    )
    if payload_sha256(recovered_payload) != baseline_voice_sha256:
        raise RuntimeError("DB-backed voice payload changed after MySQL recovery")
    mysql_result["recoveredVoiceApi"] = recovered_voice
    mysql_result["voicePayloadSha256"] = baseline_voice_sha256
    results.append(mysql_result)

    return {
        "type": "docker-fault-drill",
        "timestamp": datetime.now(UTC).isoformat(),
        "projectName": args.project_name,
        "passed": all(item["passed"] for item in results),
        "results": results,
    }


def main():
    parser = argparse.ArgumentParser(
        description="Fault drill for an isolated FCTTS Compose stack"
    )
    parser.add_argument("--project-name", required=True)
    parser.add_argument("--compose-file", default="docker-compose.yml")
    parser.add_argument("--app-url", default="http://127.0.0.1:8081")
    parser.add_argument("--management-url", default="http://127.0.0.1:9091")
    parser.add_argument("--request-timeout", type=int, default=15)
    parser.add_argument("--output")
    parser.add_argument("--confirm-isolated-stack", action="store_true")
    args = parser.parse_args()
    report = run(args)
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

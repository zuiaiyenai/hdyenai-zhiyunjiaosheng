import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from datetime import UTC, datetime
from pathlib import Path


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


def authenticated_status(args, token, path):
    return status(
        args.app_url + path,
        headers={"Authorization": "Bearer " + token},
        timeout=args.request_timeout,
    )


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
    require_status(
        "DB-backed voice API before faults",
        authenticated_status(args, token, voice_path),
        {200},
    )
    results = []

    redis_stopped = False
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
        results.append({
            "dependency": "redis",
            "componentHealthDuringFault": redis_fault,
            "readinessDuringFault": readiness_fault,
            "livenessDuringFault": live_status,
            "expectedBehavior": "readiness 503; login limiter falls back in-process",
            "fallbackLoginStatuses": login_statuses,
            "passed": True,
        })
    finally:
        if redis_stopped:
            compose(args, "start", "redis")
            wait_for(redis_health, {200})
            wait_for(readiness, {200})

    token = login_token(args.app_url, username, password, args.request_timeout)
    require_status(
        "DB-backed voice API after Redis recovery",
        authenticated_status(args, token, voice_path),
        {200},
    )
    results[0]["recoveredLogin"] = 200
    results[0]["recoveredVoiceApi"] = 200

    mysql_stopped = False
    try:
        compose(args, "stop", "mysql")
        mysql_stopped = True
        health_status = wait_for(health, {503})
        readiness_fault = wait_for(readiness, {503})
        live_status = wait_for(liveness, {200})
        started = time.monotonic()
        db_api_status = authenticated_status(args, token, voice_path)
        elapsed = time.monotonic() - started
        require_status("DB-backed voice API during MySQL fault", db_api_status, {500, 503})
        results.append({
            "dependency": "mysql",
            "healthDuringFault": health_status,
            "readinessDuringFault": readiness_fault,
            "livenessDuringFault": live_status,
            "dbApiDuringFault": db_api_status,
            "dbApiLatencySeconds": round(elapsed, 3),
            "expectedBehavior": "controlled 500/503 while liveness remains 200",
            "passed": True,
        })
    finally:
        if mysql_stopped:
            compose(args, "start", "mysql")
            wait_for(readiness, {200})

    recovered_voice = require_status(
        "DB-backed voice API after MySQL recovery",
        authenticated_status(args, token, voice_path),
        {200},
    )
    results[1]["recoveredVoiceApi"] = recovered_voice

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
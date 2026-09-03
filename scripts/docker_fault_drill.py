import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from datetime import UTC, datetime


def status(url, method="GET", body=None, timeout=10):
    data = body.encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, data=data, headers=headers, method=method),
            timeout=timeout,
        ) as response:
            response.read()
            return response.status
    except urllib.error.HTTPError as error:
        error.read()
        return error.code


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


def run(args):
    if not args.confirm_isolated_stack:
        raise SystemExit("Refusing fault injection without --confirm-isolated-stack")
    health = args.management_url + "/actuator/health"
    liveness = args.management_url + "/actuator/health/liveness"
    readiness = args.management_url + "/actuator/health/readiness"
    wait_for(readiness, {200})
    results = []

    redis_stopped = False
    try:
        compose(args, "stop", "redis")
        redis_stopped = True
        wait_for(health, {503})
        live_status = wait_for(liveness, {200})
        username = "phase9_fault_" + uuid.uuid4().hex
        login_statuses = [
            status(
                args.app_url + "/user/login",
                method="POST",
                body=json.dumps({"username": username, "password": "WrongPassword1!"}),
            )
            for _ in range(6)
        ]
        if login_statuses != [401, 401, 401, 401, 401, 429]:
            raise RuntimeError(f"Redis fallback mismatch: {login_statuses}")
        results.append({
            "dependency": "redis",
            "healthDuringFault": 503,
            "livenessDuringFault": live_status,
            "fallbackLoginStatuses": login_statuses,
            "passed": True,
        })
    finally:
        if redis_stopped:
            compose(args, "start", "redis")
            wait_for(readiness, {200})

    mysql_stopped = False
    try:
        compose(args, "stop", "mysql")
        mysql_stopped = True
        health_status = wait_for(health, {503})
        live_status = wait_for(liveness, {200})
        results.append({
            "dependency": "mysql",
            "healthDuringFault": health_status,
            "livenessDuringFault": live_status,
            "passed": True,
        })
    finally:
        if mysql_stopped:
            compose(args, "start", "mysql")
            wait_for(readiness, {200})

    return {
        "type": "docker-fault-drill",
        "timestamp": datetime.now(UTC).isoformat(),
        "projectName": args.project_name,
        "passed": all(item["passed"] for item in results),
        "results": results,
    }


def main():
    parser = argparse.ArgumentParser(description="Fault drill for an isolated FCTTS Compose stack")
    parser.add_argument("--project-name", required=True)
    parser.add_argument("--compose-file", default="docker-compose.yml")
    parser.add_argument("--app-url", default="http://127.0.0.1:8081")
    parser.add_argument("--management-url", default="http://127.0.0.1:9091")
    parser.add_argument("--confirm-isolated-stack", action="store_true")
    args = parser.parse_args()
    report = run(args)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

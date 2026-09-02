import argparse
import json
import math
import os
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor


def request(url, method="GET", body=None, token=None, timeout=10):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, data=data, headers=headers, method=method),
            timeout=timeout,
        ) as response:
            payload = response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        payload = error.read()
        status = error.code
    elapsed_ms = (time.perf_counter() - started) * 1000
    return status, payload, elapsed_ms


def login(base_url, username, password, timeout):
    status, payload, _ = request(
        f"{base_url}/user/login",
        method="POST",
        body={"username": username, "password": password},
        timeout=timeout,
    )
    if status != 200:
        raise RuntimeError(f"login setup failed with HTTP {status}")
    token = json.loads(payload)["token"]
    if not token:
        raise RuntimeError("login setup returned an empty token")
    return token


def percentile(values, percentage):
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * percentage) - 1)
    return round(ordered[index], 2)


def main():
    parser = argparse.ArgumentParser(description="FCTTS reproducible HTTP smoke load test")
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument(
        "--scenario", choices=("health", "login", "voices", "tasks", "task"),
        default="health",
    )
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--task-id")
    parser.add_argument("--timeout", type=int, default=10)
    args = parser.parse_args()

    if args.requests < 1 or args.concurrency < 1:
        parser.error("requests and concurrency must be positive")
    if args.scenario == "task" and not args.task_id:
        parser.error("--task-id is required for the task scenario")

    base_url = args.base_url.rstrip("/")
    username = os.getenv("LOAD_TEST_USERNAME")
    password = os.getenv("LOAD_TEST_PASSWORD")
    token = None
    if args.scenario != "health":
        if not username or not password:
            parser.error("LOAD_TEST_USERNAME and LOAD_TEST_PASSWORD are required")
        if args.scenario != "login":
            token = login(base_url, username, password, args.timeout)

    paths = {
        "health": "/actuator/health",
        "voices": "/voice_library/list?page=0&size=20",
        "tasks": "/api/tasks?page=0&size=20",
        "task": f"/api/tasks/{args.task_id}",
    }

    def invoke(_):
        if args.scenario == "login":
            return request(
                f"{base_url}/user/login",
                method="POST",
                body={"username": username, "password": password},
                timeout=args.timeout,
            )
        return request(
            base_url + paths[args.scenario], token=token, timeout=args.timeout
        )

    wall_started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        results = list(executor.map(invoke, range(args.requests)))
    wall_seconds = time.perf_counter() - wall_started

    statuses = Counter(status for status, _, _ in results)
    durations = [elapsed for _, _, elapsed in results]
    successful = sum(count for status, count in statuses.items() if 200 <= status < 300)
    report = {
        "scenario": args.scenario,
        "requests": args.requests,
        "concurrency": args.concurrency,
        "successRate": round(successful / args.requests, 4),
        "statusCounts": dict(sorted(statuses.items())),
        "wallSeconds": round(wall_seconds, 3),
        "requestsPerSecond": round(args.requests / wall_seconds, 2),
        "latencyMs": {
            "p50": percentile(durations, 0.50),
            "p95": percentile(durations, 0.95),
            "p99": percentile(durations, 0.99),
            "max": round(max(durations), 2),
        },
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if successful != args.requests:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

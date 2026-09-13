#!/usr/bin/env python3
"""Phase 16 direct-loopback benchmark for the real FunASR service."""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import math
import platform
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

import psutil


LEVELS = (1, 2, 4)
CREATE_NO_WINDOW = 0x08000000 if platform.system() == "Windows" else 0


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def percentile(values: list[float], percent: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percent / 100.0
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def gpu_snapshot() -> dict[str, float] | None:
    try:
        completed = subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=utilization.gpu,memory.used,memory.total",
                "--format=csv,noheader,nounits",
            ],
            check=True,
            capture_output=True,
            text=True,
            timeout=5,
            creationflags=CREATE_NO_WINDOW,
        )
        values = [float(value.strip()) for value in completed.stdout.splitlines()[0].split(",")]
        return {
            "utilization_percent": values[0],
            "memory_used_mib": values[1],
            "memory_total_mib": values[2],
        }
    except (FileNotFoundError, IndexError, ValueError, subprocess.SubprocessError):
        return None


class ResourceSampler:
    def __init__(self, pid: int, interval_seconds: float):
        self.process = psutil.Process(pid)
        self.interval_seconds = interval_seconds
        self.samples: list[dict[str, object]] = []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> None:
        self.process.cpu_percent(None)
        psutil.cpu_percent(None)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=self.interval_seconds + 6)

    def _run(self) -> None:
        while not self._stop.wait(self.interval_seconds):
            try:
                memory = self.process.memory_info()
                process_sample: dict[str, float | int | None] = {
                    "cpu_percent": self.process.cpu_percent(None),
                    "rss_mib": memory.rss / 1024 / 1024,
                    "thread_count": self.process.num_threads(),
                }
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                process_sample = {"cpu_percent": None, "rss_mib": None, "thread_count": None}
            virtual_memory = psutil.virtual_memory()
            self.samples.append(
                {
                    "timestamp": utc_now(),
                    "process": process_sample,
                    "host_cpu_percent": psutil.cpu_percent(None),
                    "host_available_memory_mib": virtual_memory.available / 1024 / 1024,
                    "gpu": gpu_snapshot(),
                }
            )


def summarize_resources(samples: list[dict[str, object]]) -> dict[str, float | int | None]:
    process = [sample["process"] for sample in samples if isinstance(sample.get("process"), dict)]
    process_cpu = [float(item["cpu_percent"]) for item in process if item.get("cpu_percent") is not None]
    process_rss = [float(item["rss_mib"]) for item in process if item.get("rss_mib") is not None]
    process_threads = [int(item["thread_count"]) for item in process if item.get("thread_count") is not None]
    host_cpu = [float(sample["host_cpu_percent"]) for sample in samples]
    host_memory = [float(sample["host_available_memory_mib"]) for sample in samples]
    gpu = [sample["gpu"] for sample in samples if isinstance(sample.get("gpu"), dict)]
    gpu_utilization = [float(item["utilization_percent"]) for item in gpu]
    gpu_memory = [float(item["memory_used_mib"]) for item in gpu]
    return {
        "sample_count": len(samples),
        "process_cpu_p95_percent": percentile(process_cpu, 95),
        "process_cpu_peak_percent": max(process_cpu, default=None),
        "process_rss_peak_mib": max(process_rss, default=None),
        "process_threads_peak": max(process_threads, default=None),
        "host_cpu_p95_percent": percentile(host_cpu, 95),
        "host_cpu_peak_percent": max(host_cpu, default=None),
        "host_available_memory_min_mib": min(host_memory, default=None),
        "gpu_utilization_p95_percent": percentile(gpu_utilization, 95),
        "gpu_utilization_peak_percent": max(gpu_utilization, default=None),
        "gpu_memory_peak_mib": max(gpu_memory, default=None),
    }


def multipart_body(audio: Path) -> tuple[bytes, str]:
    boundary = f"----fctts-phase16-{uuid.uuid4().hex}"
    body = bytearray()
    body.extend(f"--{boundary}\r\n".encode())
    body.extend(b'Content-Disposition: form-data; name="language"\r\n\r\nzh\r\n')
    body.extend(f"--{boundary}\r\n".encode())
    body.extend(
        f'Content-Disposition: form-data; name="file"; filename="{audio.name}"\r\n'.encode()
    )
    body.extend(b"Content-Type: audio/wav\r\n\r\n")
    body.extend(audio.read_bytes())
    body.extend(f"\r\n--{boundary}--\r\n".encode())
    return bytes(body), boundary


def operation(url: str, body: bytes, boundary: str, timeout_seconds: int):
    def invoke(index: int) -> dict[str, object]:
        started = time.perf_counter()
        try:
            request = urllib.request.Request(
                url,
                data=body,
                method="POST",
                headers={
                    "Content-Type": f"multipart/form-data; boundary={boundary}",
                    "Accept": "application/json",
                },
            )
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                payload = json.loads(response.read().decode("utf-8"))
                status = response.status
            text = str(payload.get("text") or "").strip()
            success = status == 200 and bool(text)
            return {
                "index": index,
                "success": success,
                "status": status,
                "latency_seconds": time.perf_counter() - started,
                "text_length": len(text),
                "segment_count": len(payload.get("segments") or []),
                "timed_out": False,
                "error": None if success else "response did not contain recognized text",
            }
        except Exception as error:
            return {
                "index": index,
                "success": False,
                "status": error.code if isinstance(error, urllib.error.HTTPError) else None,
                "latency_seconds": time.perf_counter() - started,
                "text_length": 0,
                "segment_count": 0,
                "timed_out": isinstance(error, TimeoutError),
                "error": f"{type(error).__name__}: {error}",
            }

    return invoke


def run_level(concurrency: int, jobs: int, invoke, pid: int, sample_interval: float) -> dict[str, object]:
    sampler = ResourceSampler(pid, sample_interval)
    started = time.perf_counter()
    sampler.start()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        results = list(executor.map(invoke, range(jobs)))
    wall_seconds = time.perf_counter() - started
    sampler.stop()
    successes = [result for result in results if result["success"]]
    latencies = [float(result["latency_seconds"]) for result in results]
    return {
        "concurrency": concurrency,
        "job_count": jobs,
        "success_count": len(successes),
        "failure_count": jobs - len(successes),
        "timeout_count": sum(1 for result in results if result["timed_out"]),
        "failure_rate": (jobs - len(successes)) / jobs,
        "wall_seconds": wall_seconds,
        "throughput_jobs_per_second": len(successes) / wall_seconds if wall_seconds else None,
        "latency_seconds": {
            "min": min(latencies, default=None),
            "p50": percentile(latencies, 50),
            "p95": percentile(latencies, 95),
            "p99": percentile(latencies, 99),
            "max": max(latencies, default=None),
        },
        "resources": summarize_resources(sampler.samples),
        "samples": sampler.samples,
        "jobs": results,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:9977/asr")
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--levels", nargs="+", type=int, default=list(LEVELS))
    parser.add_argument("--waves", type=int, default=3)
    parser.add_argument("--minimum-jobs", type=int, default=6)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--sample-interval", type=float, default=1.0)
    parser.add_argument("--results-dir", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if tuple(args.levels) != LEVELS:
        raise ValueError("Phase 16 FunASR evidence requires levels: 1 2 4")
    parsed_url = urllib.parse.urlparse(args.url)
    if parsed_url.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("FunASR benchmark is restricted to loopback addresses")
    audio = args.audio.resolve()
    if not audio.is_file():
        raise FileNotFoundError(audio)
    process = psutil.Process(args.pid)
    if not process.is_running():
        raise RuntimeError(f"FunASR process is not running: {args.pid}")

    project_root = Path(__file__).resolve().parent.parent
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    run_root = (args.results_dir or project_root / "target" / f"phase16-funasr-{timestamp}").resolve()
    target_root = (project_root / "target").resolve()
    if target_root not in run_root.parents:
        raise ValueError("results-dir must be a new directory under project target/")
    if run_root.exists() and any(run_root.iterdir()):
        raise ValueError(f"results-dir is not empty: {run_root}")
    run_root.mkdir(parents=True, exist_ok=True)

    body, boundary = multipart_body(audio)
    invoke = operation(args.url, body, boundary, args.timeout)
    evidence: dict[str, object] = {
        "phase": "16.4",
        "started_at": utc_now(),
        "host": {"platform": platform.platform(), "processor": platform.processor()},
        "service": {"url": args.url, "pid": args.pid, "command_line_recorded": False},
        "protocol": {
            "levels": list(args.levels),
            "waves": args.waves,
            "minimum_jobs": args.minimum_jobs,
            "timeout_seconds": args.timeout,
            "sample_interval_seconds": args.sample_interval,
            "scope": "direct loopback HTTP /asr with real FunASR models; excludes Spring and worker queue",
        },
        "input": {
            "name": audio.name,
            "bytes": audio.stat().st_size,
            "sha256": hashlib.sha256(audio.read_bytes()).hexdigest().upper(),
        },
        "warmup": invoke(-1),
        "levels": [],
    }
    if not evidence["warmup"]["success"]:
        raise RuntimeError(f"FunASR warmup failed: {evidence['warmup']['error']}")
    for level in args.levels:
        jobs = max(args.minimum_jobs, level * args.waves)
        evidence["levels"].append(run_level(level, jobs, invoke, args.pid, args.sample_interval))
    evidence["finished_at"] = utc_now()
    output = run_root / "phase16-funasr-raw-evidence.json"
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"results": str(output), "sha256": hashlib.sha256(output.read_bytes()).hexdigest().upper()}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

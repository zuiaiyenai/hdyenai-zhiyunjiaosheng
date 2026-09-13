#!/usr/bin/env python3
"""Phase 12 local heavy-media benchmark for GPT-SoVITS and FFmpeg.

The script deliberately targets only loopback GPT-SoVITS and writes raw evidence
under target/. It measures heavy services directly; it is not an end-to-end
video voice-swap benchmark.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import ctypes
import hashlib
import json
import math
import os
import platform
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


LEVELS = (1, 2, 4, 8)
CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0


class FILETIME(ctypes.Structure):
    _fields_ = [("low", ctypes.c_uint32), ("high", ctypes.c_uint32)]


class MEMORYSTATUSEX(ctypes.Structure):
    _fields_ = [
        ("length", ctypes.c_uint32),
        ("memory_load", ctypes.c_uint32),
        ("total_physical", ctypes.c_uint64),
        ("available_physical", ctypes.c_uint64),
        ("total_page_file", ctypes.c_uint64),
        ("available_page_file", ctypes.c_uint64),
        ("total_virtual", ctypes.c_uint64),
        ("available_virtual", ctypes.c_uint64),
        ("available_extended_virtual", ctypes.c_uint64),
    ]


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


def filetime_value(value: FILETIME) -> int:
    return (value.high << 32) + value.low


def windows_system_times() -> tuple[int, int] | None:
    if os.name != "nt":
        return None
    idle, kernel, user = FILETIME(), FILETIME(), FILETIME()
    if not ctypes.windll.kernel32.GetSystemTimes(
        ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)
    ):
        return None
    return filetime_value(idle), filetime_value(kernel) + filetime_value(user)


def available_memory_mib() -> float | None:
    if os.name != "nt":
        return None
    status = MEMORYSTATUSEX()
    status.length = ctypes.sizeof(status)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        return None
    return status.available_physical / 1024 / 1024


def gpu_snapshot() -> dict[str, float] | None:
    try:
        completed = subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw",
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
            "temperature_c": values[3],
            "power_w": values[4],
        }
    except (FileNotFoundError, IndexError, ValueError, subprocess.SubprocessError):
        return None


class ResourceSampler:
    def __init__(self, interval_seconds: float):
        self.interval_seconds = interval_seconds
        self.samples: list[dict[str, object]] = []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=self.interval_seconds + 6)

    def _run(self) -> None:
        previous = windows_system_times()
        while not self._stop.wait(self.interval_seconds):
            current = windows_system_times()
            cpu_percent = None
            if previous and current and current[1] > previous[1]:
                cpu_percent = 100.0 * (1.0 - (current[0] - previous[0]) / (current[1] - previous[1]))
                cpu_percent = max(0.0, min(100.0, cpu_percent))
            previous = current
            self.samples.append(
                {
                    "timestamp": utc_now(),
                    "cpu_percent": cpu_percent,
                    "available_memory_mib": available_memory_mib(),
                    "gpu": gpu_snapshot(),
                }
            )


def summarize_resources(samples: list[dict[str, object]]) -> dict[str, float | int | None]:
    cpus = [float(s["cpu_percent"]) for s in samples if s["cpu_percent"] is not None]
    memory = [float(s["available_memory_mib"]) for s in samples if s["available_memory_mib"] is not None]
    gpu_samples = [s["gpu"] for s in samples if isinstance(s.get("gpu"), dict)]
    gpu_util = [float(s["utilization_percent"]) for s in gpu_samples]
    gpu_memory = [float(s["memory_used_mib"]) for s in gpu_samples]
    return {
        "sample_count": len(samples),
        "cpu_p95_percent": percentile(cpus, 95),
        "cpu_peak_percent": max(cpus, default=None),
        "available_memory_min_mib": min(memory, default=None),
        "gpu_utilization_p95_percent": percentile(gpu_util, 95),
        "gpu_utilization_peak_percent": max(gpu_util, default=None),
        "gpu_memory_peak_mib": max(gpu_memory, default=None),
    }


def run_level(
    name: str,
    concurrency: int,
    job_count: int,
    operation: Callable[[int], dict[str, object]],
    sample_interval: float,
) -> dict[str, object]:
    sampler = ResourceSampler(sample_interval)
    started = time.perf_counter()
    sampler.start()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        results = list(executor.map(operation, range(job_count)))
    elapsed = time.perf_counter() - started
    sampler.stop()
    successes = [result for result in results if result["success"]]
    latencies = [float(result["latency_seconds"]) for result in results]
    return {
        "name": name,
        "concurrency": concurrency,
        "job_count": job_count,
        "success_count": len(successes),
        "failure_count": job_count - len(successes),
        "failure_rate": (job_count - len(successes)) / job_count,
        "wall_seconds": elapsed,
        "throughput_jobs_per_second": len(successes) / elapsed if elapsed else None,
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


def gpt_operation(url: str, request_body: bytes, timeout_seconds: int) -> Callable[[int], dict[str, object]]:
    def invoke(index: int) -> dict[str, object]:
        started = time.perf_counter()
        try:
            request = urllib.request.Request(
                url,
                data=request_body,
                method="POST",
                headers={"Content-Type": "application/json", "Accept": "audio/wav"},
            )
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                body = response.read()
                status = response.status
                content_type = response.headers.get("Content-Type", "")
            success = status == 200 and len(body) > 44 and body[:4] == b"RIFF"
            return {
                "index": index,
                "success": success,
                "status": status,
                "content_type": content_type,
                "response_bytes": len(body),
                "latency_seconds": time.perf_counter() - started,
                "error": None if success else "response was not a non-empty RIFF/WAV",
            }
        except urllib.error.HTTPError as error:
            message = error.read(512).decode("utf-8", errors="replace")
            return {
                "index": index,
                "success": False,
                "status": error.code,
                "response_bytes": 0,
                "latency_seconds": time.perf_counter() - started,
                "error": message,
            }
        except Exception as error:  # benchmark evidence must retain the concrete failure
            return {
                "index": index,
                "success": False,
                "status": None,
                "response_bytes": 0,
                "latency_seconds": time.perf_counter() - started,
                "error": f"{type(error).__name__}: {error}",
            }

    return invoke


def run_command(command: list[str], timeout_seconds: int) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        check=False,
        capture_output=True,
        text=True,
        timeout=timeout_seconds,
        creationflags=CREATE_NO_WINDOW,
    )


def require_command_success(command: list[str], description: str, timeout_seconds: int = 180) -> None:
    completed = run_command(command, timeout_seconds)
    if completed.returncode != 0:
        raise RuntimeError(f"{description} failed: {completed.stderr[-1000:]}")


def prepare_ffmpeg_input(ffmpeg: str, source: Path, run_root: Path, duration_seconds: int) -> tuple[Path, Path]:
    video = run_root / "ffmpeg-input.mp4"
    audio = run_root / "ffmpeg-input.wav"
    loops = max(1, math.ceil(duration_seconds / 5))
    require_command_success(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-stream_loop",
            str(loops - 1),
            "-i",
            str(source),
            "-t",
            str(duration_seconds),
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-movflags",
            "+faststart",
            str(video),
        ],
        "FFmpeg benchmark input preparation",
    )
    require_command_success(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(video), "-vn", "-c:a", "pcm_s16le", str(audio)],
        "FFmpeg benchmark audio extraction",
    )
    return video, audio


def ffmpeg_operation(ffmpeg: str, video: Path, audio: Path, output_dir: Path) -> Callable[[int], dict[str, object]]:
    output_dir.mkdir(parents=True, exist_ok=True)

    def invoke(index: int) -> dict[str, object]:
        output = output_dir / f"job-{index:03d}.mp4"
        command = [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(video),
            "-i",
            str(audio),
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-filter:a",
            "atempo=1.000000",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-shortest",
            "-movflags",
            "+faststart",
            str(output),
        ]
        started = time.perf_counter()
        try:
            completed = run_command(command, 600)
            size = output.stat().st_size if output.exists() else 0
            success = completed.returncode == 0 and size > 0
            return {
                "index": index,
                "success": success,
                "exit_code": completed.returncode,
                "output_bytes": size,
                "latency_seconds": time.perf_counter() - started,
                "error": None if success else completed.stderr[-1000:],
            }
        except Exception as error:
            return {
                "index": index,
                "success": False,
                "exit_code": None,
                "output_bytes": 0,
                "latency_seconds": time.perf_counter() - started,
                "error": f"{type(error).__name__}: {error}",
            }

    return invoke


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("all", "gpt", "ffmpeg"), default="all")
    parser.add_argument("--levels", nargs="+", type=int, default=list(LEVELS))
    parser.add_argument("--waves", type=int, default=3)
    parser.add_argument("--minimum-jobs", type=int, default=6)
    parser.add_argument("--sample-interval", type=float, default=1.0)
    parser.add_argument("--gpt-url", default="http://127.0.0.1:9880/tts")
    parser.add_argument("--gpt-ref-audio", type=Path)
    parser.add_argument("--gpt-timeout", type=int, default=600)
    parser.add_argument("--ffmpeg", default=r"C:\Program Files\ffmpeg\bin\ffmpeg.exe")
    parser.add_argument("--video-input", type=Path, default=Path("test-assets/video-voice-swap-chinese-5s.mp4"))
    parser.add_argument("--ffmpeg-duration", type=int, default=30)
    parser.add_argument("--results-dir", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if tuple(args.levels) != LEVELS:
        raise ValueError("Phase 12 evidence requires levels: 1 2 4 8")
    if args.waves < 1 or args.minimum_jobs < 1 or args.sample_interval <= 0:
        raise ValueError("waves, minimum-jobs and sample-interval must be positive")
    parsed_url = urllib.parse.urlparse(args.gpt_url)
    if parsed_url.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("GPT-SoVITS benchmark is restricted to loopback addresses")

    project_root = Path(__file__).resolve().parent.parent
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    run_root = (args.results_dir or project_root / "target" / f"phase12-live-{timestamp}").resolve()
    target_root = (project_root / "target").resolve()
    if target_root not in run_root.parents:
        raise ValueError("results-dir must be a new directory under project target/")
    if run_root.exists() and any(run_root.iterdir()):
        raise ValueError(f"results-dir is not empty: {run_root}")
    run_root.mkdir(parents=True, exist_ok=True)

    evidence: dict[str, object] = {
        "phase": 12,
        "started_at": utc_now(),
        "host": {"platform": platform.platform(), "processor": platform.processor()},
        "levels": list(args.levels),
        "waves": args.waves,
        "minimum_jobs": args.minimum_jobs,
        "sample_interval_seconds": args.sample_interval,
        "scope": {
            "gpt_sovits": "direct loopback HTTP /tts; excludes Spring/Tomcat and ASR",
            "ffmpeg": "direct audio replacement encode; excludes ASR, TTS and application worker queue",
        },
        "gpt_sovits": [],
        "ffmpeg": [],
    }

    if args.mode in {"all", "gpt"}:
        if args.gpt_ref_audio is None:
            raise ValueError("--gpt-ref-audio is required for GPT-SoVITS mode")
        reference = args.gpt_ref_audio.resolve()
        if not reference.is_file():
            raise FileNotFoundError(reference)
        request_body = json.dumps(
            {
                "text": "智韵教声重任务容量测试，正在验证语音合成服务的安全并发。",
                "text_lang": "zh",
                "ref_audio_path": str(reference).replace("\\", "/"),
                "aux_ref_audio_paths": [],
                "prompt_lang": "zh",
                "prompt_text": "红豆生南国，春来发几枝。",
                "top_k": 5,
                "top_p": 0.9,
                "temperature": 0.8,
                "text_split_method": "cut0",
                "batch_size": 1,
                "speed_factor": 1.0,
                "pitch": 1.0,
                "rhythm": 1.0,
                "media_type": "wav",
                "streaming_mode": False,
                "fragment_interval": 0.12,
                "parallel_infer": True,
                "repetition_penalty": 1.35,
            },
            ensure_ascii=False,
        ).encode("utf-8")
        operation = gpt_operation(args.gpt_url, request_body, args.gpt_timeout)
        warmup = operation(-1)
        evidence["gpt_sovits_warmup"] = warmup
        if not warmup["success"]:
            raise RuntimeError(f"GPT-SoVITS warmup failed: {warmup['error']}")
        for level in args.levels:
            count = max(args.minimum_jobs, level * args.waves)
            result = run_level("gpt_sovits", level, count, operation, args.sample_interval)
            evidence["gpt_sovits"].append(result)
            if result["failure_rate"] == 1.0:
                break

    if args.mode in {"all", "ffmpeg"}:
        ffmpeg = str(Path(args.ffmpeg).resolve())
        source = (project_root / args.video_input).resolve() if not args.video_input.is_absolute() else args.video_input.resolve()
        if not Path(ffmpeg).is_file():
            raise FileNotFoundError(ffmpeg)
        if not source.is_file():
            raise FileNotFoundError(source)
        prepared_video, prepared_audio = prepare_ffmpeg_input(ffmpeg, source, run_root, args.ffmpeg_duration)
        evidence["ffmpeg_input"] = {
            "source": str(source.relative_to(project_root)),
            "prepared_duration_seconds": args.ffmpeg_duration,
            "prepared_video_sha256": hashlib.sha256(prepared_video.read_bytes()).hexdigest(),
            "command_contract": "libx264/yuv420p + AAC + shortest + faststart, matching VideoVoiceSwapServiceImpl",
        }
        warmup_dir = run_root / "ffmpeg-warmup"
        warmup = ffmpeg_operation(ffmpeg, prepared_video, prepared_audio, warmup_dir)(-1)
        evidence["ffmpeg_warmup"] = warmup
        if not warmup["success"]:
            raise RuntimeError(f"FFmpeg warmup failed: {warmup['error']}")
        for level in args.levels:
            count = max(args.minimum_jobs, level * args.waves)
            operation = ffmpeg_operation(ffmpeg, prepared_video, prepared_audio, run_root / f"ffmpeg-c{level}")
            evidence["ffmpeg"].append(run_level("ffmpeg", level, count, operation, args.sample_interval))

    evidence["finished_at"] = utc_now()
    output = run_root / "phase12-raw-evidence.json"
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    digest = hashlib.sha256(output.read_bytes()).hexdigest().upper()
    print(json.dumps({"results": str(output), "sha256": digest}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

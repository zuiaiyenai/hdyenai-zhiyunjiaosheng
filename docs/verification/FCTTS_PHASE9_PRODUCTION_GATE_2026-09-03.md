# FCTTS Production Gate / Phase 9 Verification

- Date: 2026-09-03
- Repository: `D:\code\fctts-main5`
- Result: code, live GPT-SoVITS/FunASR, observability, the 10-minute stability gate, external-service fault injection, and MySQL 5.7 backup/restore passed. Linux Docker Compose and container Redis/MySQL fault injection remain release blockers because this host has no working Linux daemon or WSL.

## Scope

- Linux Docker Compose and access to host speech services.
- GPT-SoVITS/FunASR health and readiness integration.
- Actuator liveness/readiness, Prometheus metrics, and external-service gauges.
- Bounded stability load, fault injection, and MySQL backup/restore.
- CI, operational documentation, and reproducible scripts.

## Implementation

- The application and management ports are separated; Docker uses management port 9091.
- Readiness combines application state, database, Redis, and speech services. `EXTERNAL_SERVICES_REQUIRED` controls whether speech-service failure blocks readiness.
- GPT-SoVITS uses side-effect-free `HEAD /tts`. A parameterless GET returns 500, while HEAD returns 405 and proves the route is reachable. A 5xx still fails the check.
- FunASR uses `GET /health` and requires HTTP 200 with status UP.
- `fctts_external_service_up` gauges do not expose URLs, exception text, or credentials.
- Prometheus binds only to host 127.0.0.1. Linux Compose maps `host.docker.internal` with `host-gateway`.
- MySQL passwords use `MYSQL_PWD`; restore schemas require a dedicated prefix and safe identifiers.

## Live GPT-SoVITS and FunASR

- GPT-SoVITS listened on `127.0.0.1:9880`.
- Live synthesis returned HTTP 200 and produced an 821,804-byte PCM 16-bit, 32,000 Hz mono WAV lasting 12.84 seconds.
- FunASR health at `127.0.0.1:9977/health` returned UP.
- Recognition of the synthesized sample returned: `GPET soviy ITS` inside the otherwise recognizable Chinese sentence.
- Quality boundary: English acronym recognition is imperfect and needs a separate corpus and acceptance threshold.

## Observability probe

With application port 18081, management port 18091, and external services required:

- Overall health: HTTP 200.
- Readiness: HTTP 200.
- Liveness: HTTP 200.
- Prometheus: HTTP 200 with JVM and both external-service gauges.
- GPT-SoVITS: HEAD 405, reachable.
- FunASR: health 200/UP.
- `scripts/production_gate.py probe`: 5 of 5 checks passed.

## Stability gate

- Mode: nodb, authenticated read-only voices/tasks mix.
- Duration: 600.013 seconds.
- Concurrency: 20.
- Requests: 1,716,848.
- Success rate: 100%; error rate: 0%.
- Throughput: 2,861.35 requests/second.
- Latency: p50 6.7 ms, p95 9.88 ms, p99 11.75 ms, maximum 38.7 ms.
- Thresholds: at least 100,000 requests and at most 0.5% errors; passed.
- Boundary: this local nodb read-only result does not represent MySQL, Redis, upload, or GPU inference capacity.

## Fault injection

- GPT-SoVITS stopped: readiness 503 and liveness 200. After restart: HEAD 405 and readiness 200.
- FunASR stopped: readiness 503 and liveness 200. After restart: health 200 and readiness 200.
- `scripts/docker_fault_drill.py` implements Redis/MySQL stop, fallback checks, and finally-based recovery. It requires `--confirm-isolated-stack`.
- The Docker Redis/MySQL drill was not run locally because the Linux daemon is unavailable. The shared host mysqld was not used as a destructive substitute.

## MySQL backup and restore

- Server: local MySQL 5.7.26.
- Source schema: `tts_phase9_backup_source`, with one proof table and one row.
- Backup: 2,038 bytes plus SHA-256 manifest.
- Restore schema: `tts_restore_verify_phase9`.
- Restored table count: 1, matching the source; SHA-256 matched.
- Result: passed.
- Cleanup: source and restore schemas were removed in the finally path.
- A real MySQL 5.7 issue was found and fixed: the `COUNT(*)` header broke integer parsing, so the query now uses `--skip-column-names`.

## Automated verification

- Focused Java tests: 3 passed.
- Full Java suite: 97 tests, 0 failures, 0 errors, 5 skipped.
- Python suite: 9 passed.
- Maven package: BUILD SUCCESS.
- Compose configuration parsing: passed.

## Linux Docker status

- Docker Client: 29.7.2, windows/amd64.
- Context: `desktop-linux`.
- Linux daemon: unavailable; the `dockerDesktopLinuxEngine` pipe does not exist.
- WSL: not installed.
- CI now runs Compose build/up, backend health, Prometheus readiness, and image OS/architecture checks on Ubuntu.
- Do not mark Linux Docker as passed until the CI job succeeds or a local Linux daemon is installed and the gate is rerun.

## Remaining release blockers

1. No successful local or remote Linux Compose run is available yet.
2. Redis/MySQL container fault injection has not run.
3. The 10-minute load did not cover database mode, Redis, uploads, concurrent GPU TTS/ASR, or long-duration leak detection.
4. FunASR English acronym quality needs its own dataset and threshold.
5. Prometheus is integrated, but alert rules, Grafana dashboards, and external log aggregation are outside this phase.

## Release conditions

- Pass the Linux Compose CI job and retain its diagnostics artifact.
- Pass `docker_fault_drill.py` against an isolated Compose project.
- Run database/Redis/GPU capacity tests against production targets and set SLO/alert thresholds.
- Keep production credentials in environment variables or a secret manager; never commit local configuration.

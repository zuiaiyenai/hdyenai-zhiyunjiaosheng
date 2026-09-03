# FCTTS Phase 11 Final Production Qualification

- Date: 2026-09-03
- Base revision: `6be0a245b9d3a09119a2b50f8fc75128ece1a32e`
- Phase 11 commit message: `test(ops): close production qualification gaps`
- Repository: `D:\code\fctts-main5`

# Final Decision

**NO-GO**

Phase 11 closes the repository-side automation gaps, restores a real Vue 3/Vite source project from the user-provided `D:\vs`, and verifies that recovered project with a frozen lockfile. It does not create evidence that has not actually run: this revision has not run on an Ubuntu runner, the local Linux Docker daemon is unavailable, live GPT-SoVITS/FunASR endpoints are currently unavailable, and the recovered Vite source does not reproduce the frontend currently served by Spring Boot.

## Blocker Classification

| Blocker | Severity | Classification | Can Fix In Repo | Needs External Environment | Current Status |
| --- | --- | --- | --- | --- | --- |
| Linux Docker build / Compose boot | BLOCKER | ENVIRONMENT_REQUIRED | Automation only | Ubuntu runner or Linux daemon | `production-gate-linux` implemented; NOT RUN |
| Redis container outage / recovery | BLOCKER | ENVIRONMENT_REQUIRED | Fault drill implemented | Isolated Compose stack | Automation ready; NOT RUN |
| MySQL container outage / recovery | BLOCKER | ENVIRONMENT_REQUIRED | Fault drill implemented | Isolated Compose stack | Automation ready; NOT RUN |
| Redis-backed load / soak | BLOCKER | ENVIRONMENT_REQUIRED | `redis_mixed` implemented | Redis-enabled running stack | 180-second CI and 1800-second manual gates ready; NOT RUN |
| Complete persisted AI workflow | HIGH | EXTERNAL_CREDENTIAL_REQUIRED | Partial harness only | Real AI services and credentials | NOT RUN in Phase 11 |
| Live timeout / cancel | HIGH | ENVIRONMENT_REQUIRED | Existing task controls remain | Controllable slow external service | NOT RUN in Phase 11 |
| Frontend source / package / lockfile | BLOCKER | SOURCE_ASSET_MISSING for current served UI | Recovered candidate source added | Original current legacy source still required | Candidate build PASS; current public frontend remains non-reproducible |

## Closed Repository Gaps

| Gap | Before | After | Evidence |
| --- | --- | --- | --- |
| Linux production gate | Minimal Compose smoke | Dedicated `production-gate-linux` job | Build, boot, health, tools, smoke, faults, load, restart, logs, teardown |
| Fault injection | Health-only and incomplete business checks | Redis fallback and DB-backed API outage/recovery checks | `scripts/docker_fault_drill.py` and unit tests |
| Redis-backed load | No Redis-enabled scenario | `redis_mixed` with pre/post component health and login-path proof | `scripts/production_gate.py` and unit tests |
| Management access | Port 9091 not host-mapped | Loopback-only configurable mapping | `127.0.0.1:${MANAGEMENT_PORT:-9091}:9091` |
| Frontend build inputs | No source project in repository | Real `D:\vs` source, package manifest, pnpm lock and Vite config recovered | `pnpm install --frozen-lockfile`; `pnpm build` |
| CI diagnostics / cleanup | One compose log | Compose state, images, all logs, backend log and inspect JSON | Artifact upload runs with `if: always()`; teardown uses `down --volumes` |

## Linux

- Docker build: **NOT RUN** on this revision.
- Compose boot: **NOT RUN** on this revision.
- Health: CI checks MySQL, Redis, backend liveness/readiness/Redis component, Prometheus profile, FFmpeg and FFprobe.
- CI run: **NOT RUN** because Phase 11 is intentionally not pushed.
- Local limitation: `docker info` failed because `dockerDesktopLinuxEngine` does not exist.
- Automation: Ubuntu `production-gate-linux` is implemented in `.github/workflows/ci.yml`.
- Cleanup: diagnostics and `docker compose --profile observability down --volumes --remove-orphans` run under `if: always()`.

This is automation evidence, not Linux execution evidence.

## Fault Recovery

### Redis

The isolated drill now:

1. proves Redis component health and a DB-backed voice API before injection;
2. stops only the Compose Redis service;
3. requires Redis component health and readiness to become 503 while liveness remains 200;
4. performs six real login attempts and requires `401,401,401,401,401,429`, proving the designed in-process limiter fallback;
5. restarts Redis, requires health/readiness 200, logs in again, and requires the voice API to return 200.

Result on an isolated container stack: **NOT RUN**. Unit coverage of orchestration: **PASS**.

### MySQL

The isolated drill now:

1. proves the authenticated DB-backed voice API returns 200 before injection;
2. stops only MySQL without deleting its volume;
3. requires health/readiness 503 and liveness 200;
4. requires the authenticated voice API to return a bounded 500 or 503 rather than hang;
5. restarts MySQL, waits for readiness, and requires the same API to recover to 200.

Result on an isolated container stack: **NOT RUN**. Unit coverage of orchestration: **PASS**.

## Redis-backed Load

- Scenario: `redis_mixed`
- Mix: login 10%, voices 40%, tasks 30%, courseware 20%
- Redis enabled requirement: Redis actuator component must be 200 before and after load.
- Redis-backed path proof: successful login requests invoke `LoginRateLimiter`, which uses Redis when `REDIS_ENABLED=true`.
- Push / pull request duration: 180 seconds.
- Manual `workflow_dispatch` duration: 1800 seconds by default; input is configurable.
- Metrics: requests, errors, error rate, p50, p95, p99, maximum latency and throughput.
- Phase 11 actual duration / requests / errors / p95 / p99: **NOT RUN**.

The short CI gate must not be reported as a 30-minute production soak.

## AI E2E

- Required flow: request -> persisted task -> RUNNING -> real external processing -> SUCCESS -> persisted result -> backend restart -> result query.
- Current local probes on 2026-09-03:
  - backend `127.0.0.1:8081`: unavailable;
  - GPT-SoVITS `127.0.0.1:9880`: unavailable;
  - FunASR `127.0.0.1:9977`: unavailable.
- Workflow: **NOT RUN**
- Persistence: **NOT RUN**
- Restart: **NOT RUN**
- Result query: **NOT RUN**

Phase 10 live TTS/ASR evidence remains historical and is not relabeled as a complete persisted workflow.

## Timeout / Cancel

- Timeout state transition and Future cancellation: covered by existing Java service tests, but no Phase 11 live slow-service run.
- Cancel endpoint and cancellation state: implemented in the application, but no Phase 11 live running-task cancellation.
- External HTTP/process termination, temporary-file cleanup and worker-slot recovery: **NOT RUN live**.
- Final result: **NOT RUN / BLOCKER REMAINS**.

A controllable slow external service or available real service is required. No production-only test endpoint was added.

## Frontend

- Source found in `D:\vs`: **YES**, real Vue 3/Vite source.
- Historical source found in Git: **NO**; history search for `.vue`, manifests and lockfiles returned no commit.
- Repository source restored: **YES**, under `frontend/`.
- `package.json`: **YES**
- Lockfile: **YES**, pnpm lockfile version 9.0.
- Toolchain: pnpm 11.19.0, Vue 3.5.40, Vite 5.4.21.
- Frozen install: **PASS**
- Reproducible build: **PASS for the recovered candidate**.
- Modules transformed: 14.
- Recovered build hashes, identical to `D:\vs\dist`:
  - `index.html`: `0953E6B03588B45F50380087A22AC7659F5C1852B1796FF3FBDEFB7602A64D2D`
  - CSS: `41EE643D43B105F9E3F2B810AE3B1823249145F4300F2E91F1F971E17A0C0C6D`
  - JS: `C1E0C3D3CF5892B2B066792688654496FBAC3FB338C0BE558179E9104781FB14`

### Qualification Boundary

The current Spring entry point loads:

- `chunk-vendors.226fcf89.js`
- `app.362b5565.js`
- Phase 1-10 enhancement scripts for streaming, dialect, preview locking, courseware locking, video layout, recording and real report data.

The recovered project produces `index-CI4O7p4F.js` and `index-C81XVDe4.css`. It therefore reproduces `D:\vs\dist`, not the currently served legacy frontend. The current public frontend remains a `SOURCE_ASSET_MISSING` blocker. Phase 11 intentionally does not overwrite the Spring static entry point.

## Tests

| Check | Result | Evidence |
| --- | --- | --- |
| Java tests | PASS | 97 run, 0 failures, 0 errors, 5 skipped |
| Maven package | PASS | Spring Boot executable JAR repackaged |
| Python tests | PASS | 14 passed in 5.15s using the PyCharm interpreter |
| Python compileall | PASS | All scripts compiled |
| Frontend frozen install | PASS | pnpm 11.19.0, lockfile unchanged |
| Frontend build | PASS | Vite 5.4.21, 14 modules |
| Compose config | PASS | `docker compose --env-file .env.example config --quiet` |
| Linux Docker build / boot | NOT RUN | Local Linux daemon unavailable; CI not pushed |
| Security regression | PASS | Full Java suite includes auth, CORS, upload, traversal and task authorization coverage |
| Tracked-secret scan | PASS | No private-key, AWS access-key or long `sk-` credential pattern found outside ignored build/dependency directories |

## Backend Production Readiness

**NO-GO**

The repository now contains the repeatable Linux/fault/load qualification mechanism, but a mechanism without a successful immutable Ubuntu run is not production evidence. Live timeout/cancel and a complete persisted external AI workflow also remain unqualified.

## Full Product Production Readiness

**NO-GO**

In addition to backend execution gaps, the source that generates the currently served legacy frontend is still missing. The recovered Vue project is authentic and reproducible but functionally/build-wise different from the current public entry point.

## Remaining Blockers

1. Run `production-gate-linux` successfully on the immutable Phase 11 commit and retain its diagnostics artifact.
2. Complete the manual 1800-second Redis-backed qualification run and meet defined latency/error thresholds.
3. Execute one complete persisted real AI workflow through backend restart and result query with valid service credentials.
4. Execute live timeout and cancel against a controllable slow service and prove cleanup plus worker recovery.
5. Obtain the original source, manifest, lockfile and build configuration for the currently served legacy frontend, or migrate all current legacy/enhancement behavior into `frontend/` and complete browser acceptance.

## Git

- Phase 1-10 history: unchanged.
- Phase 11: one new commit only.
- Push: not performed.
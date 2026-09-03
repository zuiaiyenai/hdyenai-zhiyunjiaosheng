# FCTTS Phase 10 Final Production Qualification

- Date: 2026-09-03
- Repository: `D:\code\fctts-main5`
- Overall: **NO-GO**
- Backend Production Readiness: **NO-GO**
- Full Product Production Readiness: **NO-GO**

## 1. Executive Summary

The current revision is not qualified for public production. The Windows-hosted qualification proved the MySQL schema, stateful HTTP paths, restart recovery, backup/destroy/restore, selected live GPT-SoVITS/FunASR behavior, regression tests, and a 30-minute MySQL-backed mixed load. It did not prove a Linux image build or Compose boot, container MySQL/Redis failure and recovery, or any Redis-backed load. The repository also still lacks reproducible frontend source and a lockfile.

The 30-minute run is therefore `PARTIAL EVIDENCE`, not a production soak PASS: it used real MySQL 5.7, but local Redis was deliberately disabled because no safe acceptance credential was available. No shared host service was stopped for a destructive substitute.

## 2. Git Commits

| Phase | Commit | Message |
| --- | --- | --- |
| 1 | `00d8d18` | `fix(security): constrain voice library file access` |
| 2 | `181e2e8` | `feat(db): add reproducible database migrations` |
| 3 | `bfd16c0` | `feat(security): harden authentication cors and uploads` |
| 4 | `3190628` | `feat(courseware): persist project metadata` |
| 5 | `609a983` | `feat(tasks): add reliable async media processing` |
| 6 | `03a90c0` | `refactor(api): improve errors pagination and resource consistency` |
| 7 | `62e7e52` | `build: add reproducible local and ci environments` |
| 8 | `c442b18` | `test: add production readiness verification` |
| 9 | `962dbf4` | `feat(ops): add phase 9 production gate` |
| 10 | `SELF` | `test(ops): complete final production qualification` |

`SELF` denotes the commit containing this report. A Git commit cannot embed its own final hash without changing that hash; use `git log -1 --format=%H` for the immutable identifier.

## 3. Production Gate Matrix

| Gate | Result | Evidence |
| --- | --- | --- |
| Linux Docker image build | NOT RUN - LINUX DAEMON/CI RUN REQUIRED | Docker client 29.7.2 is present, but the `desktop-linux` daemon pipe is absent; WSL is not installed; the workflow is unpushed and has no run. |
| Compose configuration | PASS | `docker compose --env-file .env.example config --quiet` returned success. This is syntax/config evidence only. |
| Compose boot/persistence | NOT RUN - LINUX DAEMON REQUIRED | No container was started. |
| Redis container fault/recovery | NOT RUN - ISOLATED COMPOSE REQUIRED | Shared host Redis was not stopped; no safe Redis acceptance credential was available. |
| MySQL container fault/recovery | NOT RUN - ISOLATED COMPOSE REQUIRED | Shared host MySQL was not stopped. Backup/destroy/restore used only a dedicated schema. |
| Readiness semantics | PASS | External AI services are optional by default and publish bounded service gauges; required mode remains available. |
| Stateful load | PARTIAL PASS | Real MySQL login/voice/task/courseware paths passed; Redis was disabled. |
| AI pipeline | PARTIAL PASS | Live TTS and ASR, repeated TTS, small concurrency, dependency-down response, and recovery were observed; the complete upload/save/query/cancel/timeout matrix was not run. |
| 30-minute soak | PARTIAL PASS | 1,800.206 seconds, 766,371 requests, 0 errors, real MySQL, no Redis. |
| Backup/destroy/restore | PASS | Dedicated schema restored with matching SHA-256 and application/API verification. |
| Restart/persistence/recovery | PASS | Persisted records/files survived; a RUNNING task became FAILED after restart; pending cleanup resumed. |
| Security regression | PASS | Full Java suite and focused security tests passed; tracked-secret scan passed. |
| Observability | PARTIAL PASS | HTTP/JVM/Hikari/process/external-service and ASR scheduler metrics exist; async-task state and pending-cleanup gauges are absent. |
| Frontend reproducibility | FAIL - BLOCKER | Static bundles/assets exist, but no frontend source tree, `package.json`, or lockfile exists. |

## 4. Linux / Docker

- Current host: Windows, Docker client `29.7.2 windows/amd64`, context `desktop-linux`.
- Daemon result: failed to open `dockerDesktopLinuxEngine`.
- WSL result: not installed.
- Compose config: PASS.
- Docker build/up/health/persistence: NOT RUN.
- Ubuntu Actions: `.github/workflows/ci.yml` contains Java, Python, and Docker jobs, but those commits have not been pushed, so no run exists. The current Docker job also does not exercise the complete requested persistence and Redis/MySQL fault chain.

No Linux filesystem permissions, Linux FFmpeg/ffprobe installation, image OS/architecture, host-gateway behavior, container volume persistence, or container recovery claim is made.

## 5. Fault Injection

### AI services

- With GPT-SoVITS unavailable, 7/7 TTS requests returned stable HTTP 503 with `THIRD_PARTY_UNAVAILABLE`; unrelated application functions remained available under optional-dependency readiness semantics.
- After GPT-SoVITS restart, 5/5 sequential and 2/2 concurrent synthesis requests succeeded.
- FunASR health and recognition succeeded after restart.

### Redis and MySQL

- Redis down/recover: NOT RUN - isolated Compose stack required.
- MySQL down/recover: NOT RUN - isolated Compose stack required.
- The local services are shared host services and were not stopped. A schema-only destroy/restore is disaster-recovery evidence, not a database-server outage drill.

## 6. Performance

### Phase 9 stateless/read-only baseline

- nodb, authenticated read-only mix, 600.013 seconds, concurrency 20.
- 1,716,848 requests, 0 errors, 2,861.35 requests/s.
- p50 6.70 ms, p95 9.88 ms, p99 11.75 ms.

### Phase 10 stateful short runs

| Scenario | Duration | Concurrency | Requests | Errors | p50 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Login/MySQL | 15.225 s | 4 | 219 | 0 | 277.24 ms | 304.49 ms | 322.73 ms |
| Voice metadata/MySQL | 15.003 s | 8 | 39,986 | 0 | 2.90 ms | 3.99 ms | 4.64 ms |
| Async-task list/MySQL | 15.004 s | 8 | 38,396 | 0 | 3.03 ms | 4.32 ms | 5.07 ms |
| Mixed MySQL | 30.211 s | 10 | 12,830 | 0 | 1.02 ms | 214.43 ms | 222.33 ms |

The fixed mixed scheduler produced exactly login/voice/task/courseware = 10/40/30/20 percent. The earlier scheduler assigned a fixed scenario to each worker when concurrency equaled the scenario list length; that failed to prove the declared mix and was corrected with a lock-protected global request sequence plus a regression test.

No saturation point was established. The proven stateful mixed ceiling is concurrency 10 at approximately 425 requests/s on this host, without Redis.

### AI inference

- Five sequential TTS results: 6.982, 1.931, 2.188, 1.791, and 1.858 seconds; 5/5 success.
- Two concurrent TTS results: 2.531 and 4.759 seconds; 2/2 success.
- WAV outputs were 286,764 to 442,924 bytes.
- FunASR recognized the 4.76-second, PCM s16le, 32 kHz mono sample as `这是菲斯坦真实语音链路验证。`
- `Phase 10` becoming `菲斯坦` is a `MODEL QUALITY ISSUE`, not an infrastructure blocker. A full acronym corpus was not run.

## 7. Soak

- Classification: `PARTIAL EVIDENCE`.
- Dependencies: real local MySQL 5.7; Redis disabled; live AI services available but not part of every request.
- Duration: 1,800.206 seconds.
- Concurrency: 10.
- Requests: 766,371; 425.71 requests/s; 0 errors.
- Mix: login 76,638; voices 306,548; tasks 229,911; courseware 153,274.
- Latency: p50 0.86 ms, p95 217.45 ms, p99 223.89 ms, max 268.29 ms.
- Coarse latency drift versus the preceding 30-second mixed run: p95 +3.02 ms (+1.4%); p99 +1.56 ms (+0.7%).
- G1 Old Gen: 41.578 MB to 41.680 MB.
- JVM live threads: 78 to 78.
- Hikari pending: 0 to 0; observed active 0-1 and idle 9-10.
- ASR scheduler: active 0 to 0; queued 1 to 1. This is not the media-task executor.
- G1 young-GC delta: 867 pauses and 0.735 seconds total.
- Process CPU gauge: 30.05% to 30.19%; system CPU: 37.65% to 35.43%.
- End-of-run Java working set: 319,234,048 bytes. No comparable starting RSS was captured.
- D-drive used-space delta during the continuously sampled portion: +28,205,056 bytes; this includes temporary evidence logs and unrelated host writes.
- No `METRICS_ERROR` was recorded.

Monitoring limitation: the initial sampler captured one complete Prometheus snapshot at 19:18 and then blocked in the Windows `Get-PSDrive` call. It was replaced without restarting the load. There is an approximately ten-minute metrics gap, followed by 18 continuous one-minute metric+disk samples. Resource claims are bounded accordingly.

The harness stores aggregate latency only, so windowed latency drift is approximated against the immediately preceding mixed run rather than calculated from per-minute request samples.

## 8. Disaster Recovery

Dedicated source schema: `tts_phase10_qual_20260903`.

1. Created state through the application and verified user, voice, courseware project/revision, async tasks, files, and four Flyway migrations.
2. Stopped the backend and inserted one pending-cleanup proof row.
3. Backed up with `MYSQL_PWD`: 14,354 bytes.
4. SHA-256: `db4a5770406d96e4bc0437c551b4379163466d77d12aec819e2565610eeb5965`.
5. Dropped and recreated only the dedicated schema.
6. Restored the dump. Pre-start counts were user/voice/project/revision/task/cleanup/migration = `1/1/1/1/2/1/4`.
7. Started the application against the restored schema. Readiness, login, voice list, task list, and courseware list returned HTTP 200.
8. Both qualification files existed after restore/restart.
9. The cleanup worker consumed the restored missing-file row, changing cleanup count from 1 to 0.

The original `zhiyunjiaos` schema was not modified.

## 9. Restart and Recovery

- A directly inserted RUNNING task was present as RUNNING/progress 25 before restart.
- On the corrected dedicated-schema restart it became FAILED, retained progress 25, had an error message, and had a non-null `finished_at`.
- Existing user, voice, courseware project/revision, async tasks, and files survived restart and full schema restore.
- Flyway reported the restored schema at four successful migrations.
- Redis restart and full Compose restart remain NOT RUN.

## 10. Security Regression

- `mvn test`: 97 tests, 0 failures, 0 errors, 5 skipped.
- Covered path traversal, Windows/Linux absolute paths, voice ownership and unauthorized delete, courseware ownership, task ownership, upload size/quota, MIME/magic/decode, login enumeration/rate limiting, and credentialed CORS origin behavior.
- The five skipped tests are environment-gated live/MySQL integration tests; equivalent dedicated-schema migration, persistence, cleanup, and API checks were run manually in this phase.
- Tracked-file scan found no private-key header, AWS access-key pattern, or OpenAI-style `sk-` token.
- `config/application-local.yml` is ignored and not tracked.

## 11. Observability

Present:

- HTTP request counters/latency histograms.
- JVM heap/non-heap, GC, and thread metrics.
- Hikari active/idle/pending and acquire metrics.
- Process/system CPU and disk metrics.
- `fctts_external_service_up{service="funasr|gpt-sovits"}`.
- ASR WebSocket scheduler active/queued metrics.

Missing or incomplete:

- No dedicated async-task state counts.
- No dedicated media-task executor active/queue metrics.
- No pending-file-cleanup gauge.
- Redis error metrics could not be exercised with Redis disabled.
- Windows Micrometer output did not expose process RSS; the final RSS was captured from the OS only.

External-service labels use only the fixed `service` value; no username, task ID, file, request ID, or dynamic path label was found. Compose does not publish the backend management port to the host; Prometheus is published on loopback only. Production network policy must preserve that isolation.

## 12. Frontend Reproducibility

The repository contains HTML, JavaScript/CSS bundles, and manually maintained static assets, but no frontend source project, `package.json`, or dependency lockfile. The bundle was not reverse-engineered.

`BLOCKER FOR FULL PUBLIC PRODUCTION`.

## 13. Remaining Risks

### BLOCKER

1. No successful Linux Docker image build or Compose boot evidence.
2. No isolated Redis/MySQL container outage and automatic-recovery evidence.
3. No Redis-backed stateful load or soak.
4. Frontend source and reproducible dependency/build inputs are absent.

### HIGH

1. The 30-minute soak has a ten-minute resource-sampling gap and does not include Redis.
2. No production-target capacity/saturation test or SLO threshold exists.
3. The complete application-level upload -> validation -> transcode -> ASR -> persisted result -> TTS -> persisted/downloaded audio workflow, including timeout and cancellation, was not qualified end to end.

### MEDIUM

1. Async-task state, media-executor queue, pending cleanup, and Redis failure metrics are incomplete.
2. FunASR English acronym quality has no formal corpus or acceptance threshold.
3. Alert rules, dashboards, and centralized log aggregation remain outside the proven scope.

### LOW

1. Java test console output is mojibake under the current Windows terminal code page; pass/fail results remain machine-readable.

## 14. Production Constraints

If blockers are resolved and a limited deployment is later approved, the current architecture requires:

- `single backend instance`
- `external MySQL`
- `external Redis`
- `shared/persistent storage`
- `GPT-SoVITS endpoint` for TTS features
- `FunASR endpoint` for ASR features

`MULTI-INSTANCE ASYNC EXECUTION NOT SUPPORTED`.

GPT-SoVITS, FunASR, Moonshot, and Aliyun NLS are optional/degraded dependencies for overall application readiness unless `EXTERNAL_SERVICES_REQUIRED=true`; their feature endpoints must return explicit dependency-unavailable responses when down.

## 15. Final Verification

- `mvn test`: PASS, 97/0/0/5.
- `mvn compile`: PASS.
- `mvn package`: PASS; repeated 97/0/0/5.
- Clean Phase 10 venv dependency installation: PASS.
- `pytest -q scripts`: PASS, 11 tests.
- `python -m compileall -q scripts`: PASS.
- Compose config: PASS.
- Docker build/up: NOT RUN.
- Tracked-secret scan: PASS.

Temporary processes, schema, qualification files, and `target/phase10` are removed before the Phase 10 commit. The commit is not pushed.

## 16. Final Recommendation

**NO-GO**

Do not deploy this revision to public production. Re-evaluate only after an immutable revision passes real Ubuntu Docker build/boot, isolated Redis/MySQL fault recovery, Redis-backed stateful soak, the full persisted AI workflow, and a reproducible frontend build. No Phase 11 is created by this qualification.

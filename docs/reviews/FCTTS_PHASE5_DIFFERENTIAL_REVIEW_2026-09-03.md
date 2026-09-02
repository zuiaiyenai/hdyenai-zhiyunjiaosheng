# Phase 5 Differential Security Review

## 1. Executive Summary

| Severity | Count |
| --- | ---: |
| Critical | 0 |
| High | 0 |
| Medium | 2 |
| Low | 0 |

**Overall risk:** Medium

**Recommendation:** Conditional approval for the current single-instance deployment model.

Key metrics:

- Baseline: commit `3190628`
- Scope: 18 changed files, +1073/-41 lines
- Repository size: 120 Java source and test files; focused review strategy
- High-risk paths reviewed: task authorization, executor saturation, cancellation, timeout, subprocess handling
- Test gaps in changed high-risk functions: 0 identified
- Security regressions detected: 0

## 2. What Changed

| Area | Main files | Risk | Production callers |
| --- | --- | --- | ---: |
| Task submission and lifecycle | `AsyncTaskService`, task repositories | High | 3 |
| Task query and cancellation API | `TaskController` | High | 2 endpoints |
| Courseware async entry points | `CoursewareProjectController` | High | 3 endpoints |
| FFmpeg/ffprobe execution | `ExternalProcessRunner` | High | 4 |
| Database migration | `V3__async_task.sql` | Medium | 1 repository |

The previous synchronous courseware APIs remain available. New asynchronous optimize, audio, and
video endpoints return a task identifier. Task state is stored in MySQL in database mode and in the
existing nodb in-memory profile otherwise.

## 3. Findings

### Medium: Startup recovery assumes a single active application instance

**File:** `src/main/java/com/a09/tts/task/AsyncTaskService.java:80`

**Blast radius:** all persisted PENDING/RUNNING tasks

**Test coverage:** yes, single-instance restart recovery

At startup, the service marks all persisted PENDING and RUNNING tasks as failed. This correctly
prevents stale tasks after a single-instance restart, but a second application instance starting
against the same database would also mark tasks owned by a healthy first instance as failed.

**Attacker model:** an authenticated user cannot trigger this directly. Exploitability is hard and
requires deployment or operator access that starts another backend instance.

**Concrete impact:** task status can become FAILED while work is still running on another instance.

**Recommendation:** keep Phase 5 deployment single-instance. Before horizontal scaling, add worker
identity plus a lease/heartbeat and recover only expired leases.

### Medium: Legacy synchronous media endpoints remain callable

**File:** `src/main/java/com/a09/tts/controller/CoursewareProjectController.java:70`

**Blast radius:** existing clients of synchronous audio/video endpoints

**Test coverage:** yes for media completion; load behavior is covered later by Phase 8

Compatibility requirements retain the old synchronous endpoints. New clients can use the bounded
task endpoints, but an authenticated client can still hold servlet threads by repeatedly calling
the legacy endpoints. External calls and subprocesses now have finite timeouts, so this is bounded
rather than indefinite.

**Attacker model:** authenticated regular user with API access. Exploitability is medium because
login throttling and server request capacity still apply, but task per-user limits do not cover the
legacy synchronous calls.

**Concrete impact:** temporary servlet-thread pressure and elevated CPU use until configured
timeouts expire.

**Recommendation:** migrate the frontend to the async endpoints, measure usage, then deprecate or
rate-limit legacy synchronous media routes in a separately versioned API change.

## 4. Adversarial Analysis

| Attack sequence | Control verified | Result |
| --- | --- | --- |
| User floods async task creation | bounded queue, explicit rejection, per-user counter | bounded, HTTP 429 |
| User queries or cancels another user's task ID | repository query includes task ID and owner | denied |
| User double-clicks identical generation | owner/type/key active-task deduplication | existing task ID returned |
| FFmpeg hangs | timed wait, destroy, then destroyForcibly | process terminated |
| FFmpeg floods stdout/stderr | continuous drain with 1 MiB capture cap | no pipe deadlock or unbounded capture |
| User cancels a running FFmpeg task | Future interruption reaches process runner | process terminated and task CANCELLED |
| Backend restarts during work | startup recovery updates nonterminal rows | stale task becomes FAILED |

The review found one information-disclosure issue during implementation: raw task exceptions were
initially persisted and returned by the task API. It was corrected before commit; clients now see a
stable generic error while the server log retains the exception.

## 5. Test Coverage Analysis

Automated coverage includes:

- normal completion and failure
- timeout and cancellation
- queue saturation and per-user concurrency
- duplicate submission
- unauthorized task query and cancellation
- subprocess success and timeout termination
- MySQL V3 migration, owner scoping, persistence, and restart recovery
- existing courseware and video media regression tests

Final Phase 5 suite result: 82 tests, 0 failures, 0 errors, 4 environment-gated skips.
The gated MySQL task test and database-mode double-start test were also run separately against
dedicated temporary MySQL 5.7 schemas and passed.

## 6. Blast Radius Analysis

| Function | Production call sites | Classification |
| --- | ---: | --- |
| `AsyncTaskService.submit` | 3 | Low |
| `AsyncTaskService.get/cancel` | 2 | Low |
| `ExternalProcessRunner.run` | 4 | Low |
| `CoursewareProjectService.generateAudio` | 2 | Low |
| `CoursewareProjectService.generateVideo` | 2 | Low |

## 7. Historical Context

The removed unbounded `process.waitFor()` code originated in commits `185d532` and `d3630d7`.
Git history did not show that it had been introduced as part of a prior security fix, so replacing
it does not revert an earlier security invariant. Phase 1 path-boundary code was not removed or
weakened.

## 8. Recommendations

Before production:

- keep one active backend instance until task leases exist
- migrate browser callers to the asynchronous endpoints
- run the Phase 8 rapid-submission and load checks

Future:

- add worker leases before horizontal scaling
- deprecate synchronous heavy-media routes after compatibility telemetry

## 9. Methodology

The review used the differential-review focused strategy for a medium repository:

- inspected the complete staged diff and baseline versions
- checked Git history and blame for removed process code
- quantified one-hop production callers
- mapped public attacker entry points and owner checks
- verified test coverage for all changed high-risk functions
- modeled queue exhaustion, cross-user access, duplicate submission, timeout, and cancellation

Limitations:

- external cloud service implementations were not live-tested in this review
- multi-node task coordination is outside the implemented single-instance model

Confidence is high for the reviewed Phase 5 scope and medium for horizontally scaled deployments.

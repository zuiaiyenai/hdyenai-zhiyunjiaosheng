# FCTTS Phase 6 Differential Security Review

## Executive Summary

| Severity | Count |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 1 |
| Low | 0 |

**Overall risk:** Medium
**Recommendation:** APPROVE Phase 6; track the remaining scale risk before production load acceptance.

Key evidence:

- Reviewed all 37 Phase 6 implementation, migration, configuration, and test paths.
- Full Maven suite: 95 tests, 0 failures, 0 errors, 5 environment-gated skips.
- Real MySQL 5.7 verification: V1 through V4 migrated on a fresh dedicated schema; cleanup queue repository operations passed.
- No removed access-control check, path-boundary check, or prior Phase 1 security fix was reintroduced.

## What Changed

Baseline: `609a983 feat(tasks): add reliable async media processing`

The change set:

- adds stable API error codes and masks file, database, third-party, and unexpected error details;
- adds owner-scoped, bounded pagination for courseware projects and tasks, plus backward-compatible optional pagination for voices;
- changes voice deletion from file-first to database-first and schedules file cleanup only after transaction commit;
- persists failed file cleanup using relative storage keys and retries it through normalized root-bounded deletion;
- cleans courseware audio/video temporary files and atomically moves completed video output;
- adds focused unit, security, media, and real MySQL migration tests.

## Critical Findings

No Critical or High findings remain after review.

The review found and fixed before commit:

1. possible integer overflow when calculating an in-memory page window;
2. unstable pagination when timestamp sort keys were equal;
3. unsafe persisted cleanup records repeatedly occupying the retry batch;
4. repeatedly failing old cleanup records starving new entries.

## Medium Finding

### Voice DB pagination still materializes the visible result set

**File:** `src/main/java/com/a09/tts/controller/VoiceController.java:47` and `:58`
**Blast radius:** two voice list/search endpoints
**Test coverage:** pagination boundaries and owner visibility are covered; high-volume DB behavior is not.

When `page` or `size` is supplied, the response is bounded to at most 100 items, but the DB profile still calls the legacy service methods that load every visible matching voice before slicing in memory.

Attacker model:

- authenticated user with access to voice list/search;
- repeated requests against a database containing a very large voice catalog.

Concrete impact:

- response size remains bounded and no authorization bypass occurs;
- database work and JVM allocation can still grow with total visible rows, allowing resource amplification at scale.

Recommendation:

- keep the compatibility behavior in Phase 6;
- add Mapper-level bounded queries and verify them under Phase 8 load testing before declaring high-volume production readiness.

## Test Coverage Analysis

Covered behavior includes:

- stable error codes and masking of physical paths, JDBC details, and third-party messages;
- page/size validation, max size 100, empty pages, next-page detection, overflow protection, and owner isolation;
- DB deletion before cleanup, no cleanup on DB failure, and cleanup only after transaction commit;
- normalized root-boundary rejection for direct and persisted traversal keys;
- cleanup retry persistence, retry ordering, and MySQL repository operations;
- multi-chunk narration cleanup, slide cleanup, partial-video cleanup, and final media existence;
- real MySQL 5.7 V1-V4 migration.

Environment-gated tests remain explicit; the Phase 6 MySQL test was also run separately with its required environment and passed.

## Blast Radius Analysis

| Change | Entry points/callers | Risk | Result |
|---|---:|---|---|
| Global exception mapping | all MVC JSON failures | High | Responses masked; binary success APIs unchanged |
| Voice delete ordering | one delete endpoint | High | owner check remains in controller; after-commit cleanup tested |
| Pending cleanup worker | upload rollback and voice delete | High | only relative keys; all deletes use root-bounded helper |
| Project/task pagination | two new list endpoints | Medium | owner filtering occurs in repository query |
| Voice optional pagination | four DB/nodb list/search paths | Medium | response bounded; scale risk recorded above |

## Historical Context

`git blame` shows the root-bounded voice deletion helper was introduced by Phase 1 commit `00d8d18`. Phase 6 does not remove it: both immediate and retry cleanup continue through `UploadUtils.deleteWithin`.

The original file-first ordering came from the initial implementation. Phase 6 reverses the order to protect database/file consistency and adds a durable compensation queue instead of ignoring deletion failures.

## Recommendations

Before production:

- move DB voice pagination into bounded Mapper queries and include it in Phase 8 load tests;
- monitor cleanup queue depth, attempts, and oldest entry age;
- keep V4 immutable after this commit and add future schema changes only through V5+.

## Analysis Methodology

**Strategy:** Focused review for a medium-sized Java codebase.

Techniques:

- compared the working tree against `609a983`;
- reviewed every changed path, with deeper review of file deletion, exception handling, ownership, and external-service boundaries;
- inspected Git history and blame for the Phase 1 path-boundary code;
- counted direct source/test call sites and used the code graph where the committed baseline was indexed;
- modeled authenticated-user traversal, unauthorized access, error disclosure, queue starvation, and resource-amplification scenarios;
- ran targeted tests, full tests, compilation, packaging, diff checks, and real MySQL migration verification.

Limitations:

- the code graph does not include uncommitted symbols, so new-symbol caller checks used source call-site searches;
- no production-sized voice dataset was available; the remaining scale finding is therefore tracked for Phase 8.

**Confidence:** High for security and consistency behavior in the tested scope; Medium for high-volume voice-list performance.

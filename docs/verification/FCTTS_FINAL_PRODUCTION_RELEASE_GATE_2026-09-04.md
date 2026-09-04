# FCTTS Final Production Release Gate

- 日期：2026-09-04
- Phase 11 基线：`9ff50b32fc51a22dc3b606aac3eaa54cfda0abfd`
- 最终提交说明：`test(ops): complete final production release gate`
- 仓库：`D:\code\fctts-main5`

## Executive Summary

```text
Backend: NO-GO
Full Product: NO-GO
```

本轮已经关闭了本机能够安全执行的持久化 AI、真实 timeout/cancel、进程中断恢复、专用 schema 备份恢复、浏览器核心链路和可观测性缺口，并补齐了 Linux 门禁所需的资源采样、故障恢复证据和备份恢复步骤。

但生产放行标准仍未满足：当前提交尚未在 GitHub Ubuntu runner 上真实执行，因此 Linux image build、Compose boot、Redis/MySQL 容器故障恢复和 1800 秒 Redis-backed soak 均没有 PASS 证据。当前实际部署的 legacy 前端仍没有等价源码，而且浏览器 UI 没有接入异步课件语音 task polling。静态检查和本机 Windows 运行不能替代这些证据。

## Gate Matrix

| Gate | Result | Evidence |
| --- | --- | --- |
| Ubuntu CI | NOT RUN - BLOCKED | 本地提交未推送；只有真实 GitHub Ubuntu runner 成功才可改为 PASS。 |
| Linux image build | NOT RUN - BLOCKED | Dockerfile/CI 已静态检查；本机无可用 Linux Docker daemon。 |
| Compose boot | NOT RUN - BLOCKED | `docker compose config` PASS；未发生真实 Linux `build/up/ps`。 |
| Redis fault recovery | NOT RUN - BLOCKED | drill 与单元测试就绪；尚未在隔离 Compose 栈 stop/start Redis。 |
| MySQL fault recovery | NOT RUN - BLOCKED | drill 与单元测试就绪；尚未在隔离 Compose 栈 stop/start MySQL。 |
| 1800s Redis-backed load | NOT RUN - BLOCKED | CI 支持真实 Redis 混合负载、资源采样与 1800 秒手动门禁；当前提交未执行。 |
| AI live E2E | PARTIAL PASS | 本机真实 GPT-SoVITS 单次、连续 5 次、并发 2、并发 4 均产生有效 WAV；真实 FunASR 证据沿用 Phase 10。未形成 Ubuntu/生产环境完整一体化证明。 |
| Timeout | PASS - LOCAL LIVE | 专用 DB 中真实任务 `8b0ec604-...` 为 `TIMEOUT`，progress 10，错误为“任务执行超时”；随后工作线程可继续成功执行任务。 |
| Cancel | PASS - LOCAL LIVE | 专用 DB 中真实运行任务 `827c089d-...` 为 `CANCELLED`，progress 10，错误为“用户取消任务”；后续任务成功。 |
| Worker recovery | PASS - LOCAL LIVE | 强停 backend 后，任务 `f4a962f1-...` 从运行态恢复为 `FAILED`；课件项目也由 `PROCESSING` 恢复为 `FAILED`，无永久运行态。 |
| Backup restore | PASS - LOCAL LIVE | 仅操作专用 schema `tts_final_qual_20260904`；31,074 bytes，SHA-256 `1764cfc7f9c43176c9c3ac89797f464afccf4232f8b30cbff9c78dede41d1974`；恢复后数据/API/文件与 cleanup worker 已验证。 |
| Security regression | PASS - LOCAL | 完整 Java suite 覆盖 traversal、owner isolation、未授权删除、MIME/magic/decode、size/quota、登录枚举、rate limit/fallback、CORS；tracked-secret scan PASS。 |
| Frontend reproducibility | FAIL | 恢复的 Vue 3/Vite 工程 frozen install/build PASS 且与 `D:\vs\dist` 一致，但不生成 Spring 当前加载的 legacy bundle。 |
| Browser E2E | FAIL | fresh 隔离浏览器中 login/list/preview/upload/delete/PPTX/courseware/audio/download/refresh 均真实成功；legacy UI 仍调用同步 `/courseware/projects/{id}/audio`，没有使用 `/audio/tasks` 与 task polling。 |

## 1. Linux Production Gate

`production-gate-linux` 目前包含：checkout、Java/Python/frontend 构建、环境准备、Compose config/build/up、MySQL/Redis/backend 等待、liveness/readiness/management/Redis/FFmpeg/ffprobe 检查、API smoke、fault drill、Redis-backed load、backend restart 后持久化验证、专用 schema 备份/销毁/恢复/API 验证、诊断上传和 `down --volumes --remove-orphans` 清理。

本轮进一步补齐：

- 使用专用 schema `tts_restore_verify_phase11_ci`，销毁前有名称保护；
- MySQL/Redis 密码只通过 `MYSQL_PWD` / `REDISCLI_AUTH` 传递；
- 备份前后核对 user、voice、courseware、revision、async task、pending cleanup 和 Flyway 计数；
- 恢复后真实登录并访问 voice/task/courseware API；
- CI timeout 从 55 分钟提高到 75 分钟，以容纳默认 1800 秒手动门禁及恢复验证。

静态结果：YAML 可解析为 4 个 jobs；production gate 16 个 steps；6 段 bash 经 Git Bash 语法检查；Compose config PASS。

执行结果：`BLOCKED - REMOTE EXECUTION REQUIRED`。

## 2. Linux Docker Fault Drill

drill 现在会记录故障前/中/后的 Prometheus 指标、恢复耗时、业务响应 payload SHA-256，以及是否需要重启 backend。Redis 与 MySQL 恢复后必须返回与故障前一致的 voice payload；设计要求为 `backendRestartRequired=false`。

编排单元测试 PASS，但当前提交没有真实 Linux 容器 stop/start 结果：

- Redis：`NOT RUN - ISOLATED LINUX COMPOSE REQUIRED`
- MySQL：`NOT RUN - ISOLATED LINUX COMPOSE REQUIRED`

## 3. 1800 秒 Redis-backed Qualification

`production_gate.py` 已支持周期采样：

- Prometheus：process/system CPU、JVM heap/non-heap、GC、threads、executor active/queue、Hikari active/idle/pending、disk、Redis/Lettuce；
- 容器/OS：Docker CPU/memory、Java RSS；
- 文件/清理：upload bytes/count、temp count、pending cleanup；
- 日志：backend log bytes、Redis error/timeout 行数。

本机 nodb 运行已确认 `executor_active_threads{name="fctts.async.tasks"}` 与 `executor_queued_tasks{name="fctts.async.tasks"}` 可导出。这只是指标存在性检查，不是 stateful soak。

```text
duration: NOT RUN
requests: NOT RUN
errors: NOT RUN
p95: NOT RUN
p99: NOT RUN
resource trend: NOT RUN
```

Phase 10 的 1800.206 秒、766,371 请求、0 errors 是真实 MySQL 但 Redis disabled，只能保留为历史 PARTIAL EVIDENCE，不能作为本门禁 PASS。

## 4. AI Live E2E

本机可访问的 GPT-SoVITS/FunASR 被实际探测并运行，而非用 mock 代替：

- GPT-SoVITS：单任务、连续 5 次、并发 2、并发 4 均成功并生成 WAV；本轮证据文件大小约 228 KiB 至 388 KiB；
- FunASR：沿用 Phase 10 的真实 4.76 秒 PCM s16le/32 kHz/mono 识别成功证据；英文缩写识别质量问题仍存在，但不是服务可用性伪证；
- 课件：真实 PPTX 生成讲稿，并完成持久化课件语音任务；
- 浏览器最终同步课件音频下载为 55,413,804 bytes、`audio/wav`。

边界：这些证明本机真实服务链路可工作，但没有在待放行的 Linux/Compose 环境完成同一条全链路，也没有形成生产目标上的容量证明，因此整体为 `PARTIAL PASS`，不能抵消 Linux blocker。

## 5. Timeout / Cancel / Cleanup / Recovery

专用 schema 的持久化记录证明：

| Scenario | Final state | Evidence |
| --- | --- | --- |
| baseline async audio | SUCCESS | 两次真实课件语音任务完成，progress 100。 |
| timeout | TIMEOUT | progress 10，错误“任务执行超时”。 |
| cancel | CANCELLED | progress 10，错误“用户取消任务”。 |
| post-cancel recovery | SUCCESS | 取消后的新任务成功，证明 worker slot 可继续使用。 |
| backend crash | FAILED | 运行中强停 backend，重启后任务错误“应用重启导致任务中断”。 |
| project recovery | FAILED | 持久化 `PROCESSING` 课件项目在服务恢复时改为 FAILED 并重新持久化。 |

未发现残留 8081/9091/9880/9977 监听或本轮遗留子进程。临时输出与不完整文件在最终提交前清理。

## 6. Backup / Destroy / Restore

只使用专用 schema `tts_final_qual_20260904`，未修改 `zhiyunjiaos`。

1. 通过真实应用创建用户、音色、课件、修订和异步任务；
2. 插入一个仅用于恢复证明的 pending cleanup 行；
3. 使用 `MYSQL_PWD` 生成 31,074-byte dump；
4. SHA-256 为 `1764cfc7f9c43176c9c3ac89797f464afccf4232f8b30cbff9c78dede41d1974`；
5. 销毁并重建该专用 schema 后恢复；
6. 核对 1 user、1 voice、1 project、7 revisions、6 async tasks、1 pending cleanup、4 Flyway migrations；
7. backend 启动后 readiness、login、voice、task、courseware API 均成功；
8. cleanup worker 消费 pending cleanup 证明行，输出文件仍可读取。

结果：`PASS - LOCAL LIVE`。

## 7. Frontend and Browser

### Source status

- `frontend/package.json`、`pnpm-lock.yaml`、Vite/Vue 源码存在；
- frozen install/build PASS，14 modules；lock hash保持 `5B70DCDF62FFC4E90D7B5DA86C1074953A16E51CC46BC4035B777F0B1678BEE6`；
- build 与 `D:\vs\dist` 逐文件一致；
- Git 历史、refs/object、本机相关目录和 recovered tree 均未找到生成当前 `app.362b5565.js` / `chunk-vendors.226fcf89.js` 的完整源码。

因此 recovered Vue 工程是真实且可复现的候选工程，但当前部署 legacy UI 仍是 `SOURCE_ASSET_MISSING`。

### Browser E2E

使用隔离 Playwright 浏览器（不共享用户 Chrome 登录态）完成：fresh storage、login、voice list、preview、临时音色 upload/delete、真实 PPTX upload、讲稿生成、课件音频生成与下载、refresh 后认证/音色查询。浏览器直接 fetch `/api/tasks` 返回 HTTP 200 和 6 条终态任务。

失败点是实际 legacy UI 自身不展示异步 task 查询：它仍走同步 audio endpoint，没有 `/audio/tasks` 提交与 `/api/tasks` polling。故 Browser Gate 为 `FAIL`。预登录 401 是预期认证行为；favicon 500 为 LOW，不影响核心链路。

## 8. Security and Observability

安全回归：`PASS - LOCAL`。tracked 文件未发现 private key header、AWS `AKIA` key 或长 `sk-` token；`config/application-local.yml` 未跟踪。

可观测性：liveness/readiness/Prometheus、external service、JVM、process、HTTP、Hikari 和 async executor 指标均存在。新采样器只保留固定/低基数系列，不采集 userId、username、taskId、filename、requestId 或 physical path 标签。Compose management port 仅映射到 loopback。

## 9. Final Verification

| Check | Result |
| --- | --- |
| `mvn test` | PASS，99 run / 0 failures / 0 errors / 5 skipped |
| `mvn compile` | PASS |
| `mvn package` | PASS |
| Python tests | PASS，16 passed；受控一次性 venv |
| Python compileall | PASS |
| Full `requirements.txt` on host Python 3.13 | FAIL - ENVIRONMENT COMPATIBILITY；`numpy<2` 无 cp313 wheel 且主机无 C compiler；CI 使用 Python 3.12 |
| Frontend frozen install/build | PASS，14 modules，lockfile 未变 |
| Actions YAML | PASS，4 jobs；production gate 16 steps |
| Bash syntax | PASS，6 blocks |
| Compose config | PASS |
| `git diff --check` | PASS |
| tracked/staged secret scan | PASS |

Java 三命令与最终静态检查会在本报告落盘后重新执行；上表只在最终交付前全部通过时成立，任何复测失败都必须更新本报告并阻止提交。

## 10. Remaining Blockers and Decision

### Backend: NO-GO

1. Ubuntu CI / Linux image build / Compose boot 未真实运行；
2. Redis 与 MySQL 容器 fault/recovery 未真实运行；
3. 1800 秒 Redis-backed stateful soak 未真实运行；
4. 本机 AI 与 timeout/cancel/recovery 已提供真实证据，但尚不能替代上述 Linux 生产门禁。

### Full Product: NO-GO

除 Backend blockers 外：

1. 当前实际部署 legacy frontend 的等价源码仍缺失；
2. legacy UI 没有接入 async audio task submission/polling，Browser Gate 不通过。

## 11. Git / Remote Execution Boundary

- Phase 1-11 历史不修改、不 squash；
- 本轮只创建一个最终提交；
- 不直接 push `main`；
- 本地临时分支 `codex/phase11-production-qualification` 尚未推送；
- origin 文本 URL 为 `https://github.com/zuiaiyenai/fctts-main5.git`，GitHub 实际解析为公开仓库 `zuiaiyenai/hdyenai-zhiyunjiaosheng`；
- 在用户明确允许推送该临时分支到上述公开仓库前，不进行 push，也不把远端门禁标记为 PASS。

停止结论：当前真实生产资格证据仍不足，因此停止继续开发，保持 Backend `NO-GO`、Full Product `NO-GO`，不创建后续功能型 Phase。

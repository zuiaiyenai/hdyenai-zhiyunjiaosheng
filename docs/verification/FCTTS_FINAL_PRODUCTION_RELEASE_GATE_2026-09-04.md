# FCTTS Final Production Release Gate

- 日期：2026-09-04
- Phase 11 基线：`9ff50b32fc51a22dc3b606aac3eaa54cfda0abfd`
- 远端资格验证基线：`c5fd2e9044d510cb6a942ab644ecb4d7b21790d3`
- 最终 legacy polling 修复：位于本报告所在提交
- 临时分支：`final-production-gate`

## Final Decision

```text
Backend: CONDITIONAL GO
Full Product: NO-GO
```

Backend 的 Linux、Redis/MySQL、1800 秒、安全、备份和恢复硬门禁已闭环。条件是目标生产 GPT-SoVITS/FunASR 配置、模型和容量尚未验收。Full Product 仍为 NO-GO：polling blocker 已关闭，但 legacy bundle 等价源码仍缺失，不能声明完整可复现。

## Gate Matrix

| Gate | Result | Evidence |
| --- | --- | --- |
| Ubuntu CI | PASS | [run 33861538328](https://github.com/zuiaiyenai/hdyenai-zhiyunjiaosheng/actions/runs/33861538328)，4 jobs 全部 success |
| Linux image build | PASS | backend image、Compose services build success |
| Compose boot | PASS | boot、health、tools、API smoke success |
| Redis fault recovery | PASS | readiness/component 503；liveness 200；fallback 401×5 后 429；4.221 秒恢复；无需 backend restart |
| MySQL fault recovery | PASS | readiness/health 503；liveness 200；uncached API 500；3.015 秒有界失败；1.111 秒恢复；无需 backend restart |
| 1800s Redis-backed load | PASS | [run 33864075603](https://github.com/zuiaiyenai/hdyenai-zhiyunjiaosheng/actions/runs/33864075603)：1802.549 秒，199,851 requests，0 errors，110.87 req/s，p50 1.17 ms，p95 886.14 ms，p99 992.05 ms，max 1364.32 ms |
| AI live E2E | PARTIAL PASS | 本机真实 GPT-SoVITS 单次/连续 5/并发 2/并发 4 PASS；FunASR 有真实历史证据；目标生产 AI 未验收 |
| Timeout | PASS | 专用 MySQL 真实 TIMEOUT；UI 从真实 API 读取终态并停止 polling |
| Cancel | PASS | 专用 MySQL 真实 CANCELLED；UI 从真实 API 读取终态并停止 polling |
| Worker recovery | PASS | 本机 crash/restart 收口；浏览器断线恢复后自动完成 |
| Backup restore | PASS | 13,822-byte backup；7 张表恢复前后计数一致 |
| Frontend build | PASS | frozen install/build，14 modules |
| Legacy task polling | PASS | 三类异步提交、真实 task polling、全终态、网络恢复、防重复提交、pagehide 清理 |
| Legacy source reproducibility | FAIL | `LEGACY SOURCE NOT RECOVERED` |
| Browser E2E | PASS - COMPOSITE | 核心 polling 为真实 DB/API/TTS；Moonshot 401 的创建入口使用一次性 stub，未冒充全 live |
| Security regression | PASS | Java 99 tests；GitHub Java/Python gate；tracked/staged secret scan |

## Qualification and Resource Trend

普通门禁 run 33861538328 的 Java、Python、frontend、`production-gate-linux` 全部通过。Linux job 真实执行 Compose build/up、health、API、Redis/MySQL stop/start、restart/persistence、备份恢复和 teardown。

1800 秒门禁 run 33864075603 的关键 steps 全部 success。RSS 514,170,880 → 637,038,592 bytes，峰值 640,385,024；heap 结束 65,255,152，低于开始 107,309,824；threads 57 → 61；Hikari pending 与 async queue 始终 0；Redis error lines 10 → 10。RSS 上升约 117 MiB，作为后续观察基线；本次未显示明确 heap/thread/pool/queue/retry leak。

## Legacy Polling Browser Evidence

只修改 `courseware-lock.js` 和 `index.html` cache-bust。optimize/audio/video 提交 `/optimize/tasks`、`/audio/tasks`、`/video/tasks`，轮询 `/api/tasks/{id}`，处理 PENDING、RUNNING/progress、SUCCESS、FAILED、CANCELLED、TIMEOUT。网络失败继续查询，终态停止，最长 16 分钟；`pagehide` 清 timer；既有 `busy` 防重复提交。

隔离 Playwright 清空 storage 后登录。真实 `POST /audio/tasks` 返回 202；真实任务验证 RUNNING→FAILED 和 RUNNING→SUCCESS，SUCCESS 后真实刷新 project 为 `audioReady=true`，再等 5 秒 task GET 数不增长。backend 停止时 UI 提示继续查询，17 次 reset/refused 后恢复并自动完成。双击只新增一个 POST。pagehide 后计数稳定为 85。

Moonshot 当前返回 401，因此创建课件入口只做一次性 route stub；task/project/TTS 为真实后端/MySQL。CANCELLED/TIMEOUT 也只 stub 一次提交响应，task GET 为真实 API/MySQL。故结论是 composite/hybrid，不是全 live E2E。

## Source, Cleanup, and Regression

已搜索 Git refs/history、stash/tags、ignored 内容、`D:\vs`、`D:\code`、bundle/source map/归档、JetBrains recent paths 和 unreachable commits。当前 legacy bundle 等价源码未恢复：`LEGACY SOURCE NOT RECOVERED`。

专用 schema `tts_final_qual_20260904` 已删除，`information_schema` 计数为 0；`zhiyunjiaos` 未触碰。浏览器输出、本轮进程和一次性测试产物已清理。

- Java：`mvn test`、`mvn compile`、`mvn package` PASS；99 tests，0 failures，0 errors，5 skipped。
- Python：16 passed / 6.76 s；compileall PASS。
- Frontend：frozen install/build PASS，14 modules。
- Legacy JS：`node --check` PASS。
- Git：`git diff --check` 与 tracked/staged secret scan PASS。

## Commits and Boundary

远端修复提交：`0f2142e8`、`046ab44e`、`d94d6865`、`c5fd2e90`。legacy polling 与本报告位于本报告所在提交，并只推送到同一 `final-production-gate`。

Phase 1–11 历史未 rewrite/rebase/squash；`main` 未 push/merge；未创建 tag/release，未发布镜像，未部署生产。

停止结论：不创建新的 Phase，不继续泛化优化。

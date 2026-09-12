# Phase 5：持久化 Worker Queue 模型

## 1. 本阶段结论

Phase 5 将重型任务的可执行信息从提交请求中的 JVM lambda 改为 MySQL 中的 JSON payload。任一应用实例启动的 worker 都可以从同一张 `async_task` 表领取任务，因此任务提交与执行不再要求发生在同一个 JVM。

这证明的是 DB-backed worker queue 的正确性基础，不是吞吐容量证明。worker 数量、不同资源任务的隔离并发和队列背压仍分别属于 Phase 6、Phase 9 和真实压测阶段。

## 2. 状态与领取协议

任务主路径如下：

```text
POST -> INSERT PENDING -> 原子 claim -> RUNNING
                                  -> SUCCESS
                                  -> PENDING（可重试，带退避）
                                  -> FAILED（尝试耗尽）
                                  -> TIMEOUT / CANCELLED
```

V8 为 `async_task` 增加 `payload_json`、`attempts`、`max_attempts`、`available_at`、`heartbeat_at`、`worker_id`、`version` 和 `error_code`。`available_at`、`heartbeat_at` 使用 `TIMESTAMP(6)`，避免 MySQL 5.7 的整秒四舍五入把刚创建的任务短暂存成未来时间。

`JdbcTaskRepository.claimNext` 使用 MySQL 5.7 支持的单表 `UPDATE ... ORDER BY ... LIMIT 1` 原子领取最早可执行任务。每次领取生成唯一 `worker_id` token；心跳、成功、失败、超时、重排都必须同时匹配 `task_id + RUNNING + worker_id`，失去所有权的旧 worker 不能提交新终态。

`version` 在每次状态变化时递增，用于审计状态变化并为后续 CAS 扩展保留基础；本阶段的 worker fencing 由唯一 `worker_id` 条件完成。

## 3. 重试、失联恢复与关闭

- 普通失败按 `retry-base-delay * 2^(attempts-1)` 指数退避，并受 `retry-max-delay` 限制。
- heartbeat 超过 `stale-after` 未更新时，未耗尽任务重新进入 PENDING；已耗尽任务转 FAILED 并释放用户槽位。
- 超时任务由数据库条件更新为 TIMEOUT，再中断本地执行线程；旧执行即使稍后返回也不能覆盖终态。
- 关闭时先停止接收和领取新任务，给运行任务 `shutdown-grace` 完成时间，之后才中断仍未完成的工作。
- worker 在关闭竞态中已经领取但尚未执行的任务会释放 claim，并回退本次 attempts；已经开始且被强制中断的任务仍按正常尝试计数处理。

## 4. 交付语义与幂等边界

队列语义是 **at-least-once**，不是 exactly-once。数据库可以保证同一时刻只有一个有效 worker token，但无法把外部 ASR/TTS/GPT-SoVITS/FFmpeg 调用、对象存储写入和 MySQL 终态提交合并成一个分布式事务。worker 在外部副作用完成后、提交 SUCCESS 前失联时，任务可能再次执行。

| 任务类型 | `maxAttempts` | 当前处理 |
| --- | ---: | --- |
| ASR、视频字幕、视频换声、声音克隆、PPT 摘要 | 3 | 可重复计算；每次尝试写独立 `attempt-{n}-*` 结果键，失去所有权的旧 worker 只清理自己未提交的结果。 |
| 语音笔记、口语评测 | 1 | 当前包含持久业务副作用，未证明端到端幂等，因此禁用自动重试。 |
| 课件创建、优化、音频、视频 | 1 | 会更新课件状态或产物，未证明端到端幂等，因此禁用自动重试。 |

提交幂等仍由 Phase 2 的 active deduplication unique index 保证；它阻止同一 owner、task type、deduplication key 同时存在两个 PENDING/RUNNING 任务。它不等于外部副作用 exactly-once。

## 5. 所有权与对象清理

- payload 只保存对象键和业务标识，不保存 JVM 回调、临时绝对路径或长期凭证。
- dispatcher 始终使用任务记录中的 owner 访问对象和业务资源。
- PENDING/RUNNING 任务保留输入对象；仅终态后清理输入，避免重试或恢复任务失去源文件。
- 每次尝试使用独立结果对象键。只有成功 CAS 提交的结果保留；取消、超时或失去 claim 的旧 worker 返回结果时，清理该次未提交对象。
- 语音笔记使用 payload 中稳定的 `noteId`，但其完整外部副作用仍未达到可自动重试标准。

## 6. 配置

| 配置 | 默认值 | 作用 |
| --- | ---: | --- |
| `app.tasks.worker-count` | 2 | 当前实例 DB worker 数量；不是容量结论。 |
| `app.tasks.poll-interval` | 250ms | 空队列轮询间隔。 |
| `app.tasks.timeout` | 15m | 单次执行期限。 |
| `app.tasks.heartbeat-interval` | 10s | RUNNING 心跳周期。 |
| `app.tasks.stale-after` | 45s | worker 失联判断阈值，必须大于心跳周期。 |
| `app.tasks.recovery-interval` | 15s | stale 扫描周期。 |
| `app.tasks.retry-base-delay` | 2s | 首次重试退避。 |
| `app.tasks.retry-max-delay` | 1m | 最大退避。 |
| `app.tasks.shutdown-grace` | 10s | 关闭前等待运行任务完成的时间。 |
| `app.tasks.per-user-concurrency` | 2 | 跨实例共享的每用户活动任务槽位。 |

## 7. 验证范围

2026-09-13 在本机 MySQL 5.7.26 的专用 `tts_phase5_worker_verify_*` schema 中真实执行：

- Flyway V1 -> V5 -> V8 升级与旧活动任务终止；
- 两个独立 Repository 对同一任务并发 claim，仅一个成功；
- 跨 Repository 提交幂等、每用户槽位和原子终态竞争；
- retry/reclaim、heartbeat、stale requeue、attempts 耗尽失败；
- 未开始 claim 的释放不消耗尝试次数；
- 所有终态均释放用户槽位。

单元测试另覆盖多 `AsyncTaskService` worker、持久 payload 恢复执行、指数退避、超时、取消、结果清理和强制关闭重排。

未验证项：多主机进程实跑、worker crash kill -9、外部 AI/FFmpeg 真实重试安全性、长期运行稳定性、队列吞吐与安全并发。这些不能从本阶段测试推导为 VERIFIED capacity。

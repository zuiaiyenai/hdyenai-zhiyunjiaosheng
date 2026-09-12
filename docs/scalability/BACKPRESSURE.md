# Phase 9：持久化任务准入与 Backpressure

## 1. 本阶段结论

Phase 9 为 DB-backed worker queue 增加了跨实例全局准入上限。提交事务在 MySQL 中锁定唯一 admission row，随后对 `PENDING + RUNNING` 任务计数并插入，因此多个 backend 同时提交时不能通过“先查数量、再分别插入”的竞态突破上限。

现有 `async_task_user_slot` 继续提供每用户活动任务硬上限。因为活动任务集合包含全部 PENDING 任务，该限制同时构成更严格的 per-user pending 上限，不再复制一套易分歧的 Redis/JVM 计数。

本阶段证明的是任务准入正确性和明确拒绝语义，不是容量结论。默认 200 个全局活动任务、每用户 2 个活动任务和 5 秒 `Retry-After` 都是待 Phase 11–13 校准的保护初值。

## 2. 准入事务

V10 增加只有一行的 `async_task_admission_lock`。DB 模式的创建顺序是：

```text
BEGIN
  SELECT lock_id ... FOR UPDATE
  -> 查询活动 dedup task；存在则直接返回原 taskId
  -> COUNT status IN (PENDING, RUNNING)
  -> 达到 global-queue-limit：拒绝，不插入
  -> INSERT async_task
  -> INSERT IGNORE async_task_user_slot
  -> 用户 slot 已满：回滚 task 和本事务改动
COMMIT
```

关键语义：

- 全局限制计算数据库中真实活动任务，升级前已经存在的 PENDING/RUNNING 任务也会被计入。
- 所有新版本 backend 的创建事务使用同一 InnoDB 行锁，跨 JVM 的“计数+插入”被串行化。
- dedup 查询先于容量拒绝；同一幂等请求在队列满时仍返回已经接受的 taskId，不会错误返回 429。
- claim、retry、resource defer 和 stale recovery 只在 PENDING/RUNNING 之间迁移，不增加活动任务总数，因此无需重新准入，也不会让已接受任务因瞬时满载被丢弃。
- SUCCESS/FAILED/TIMEOUT/CANCELLED 不再被全局 COUNT 计入；原有终态事务仍删除用户 slot。

## 3. 全局与用户边界

| 保护 | 配置 | 默认 | 实现与范围 |
| --- | --- | ---: | --- |
| 全局活动任务 | `app.tasks.global-queue-limit` | 200 | MySQL admission row lock + `COUNT(PENDING,RUNNING)`；所有共享同一 DB 的新版本 backend。 |
| 每用户活动任务 | `app.tasks.per-user-concurrency` | 2 | `async_task_user_slot(owner_username, slot_number)` 唯一占位；所有共享同一 DB 的 backend。 |
| worker 执行并行度 | `app.tasks.worker-count` | 2/实例 | 消费速度，不是队列容量；不能通过调大线程数替代准入保护。 |
| 重型资源并行度 | `app.resources.*.max-concurrent` | 1/资源/实例 | Phase 6 bulkhead；不能替代 DB queue limit。 |

`global-queue-limit` 名称保留业务上的“队列保护”含义，但实现有意计算 PENDING+RUNNING，而不只计算等待任务。这比只限制 PENDING 更保守，也避免任务被 worker claim 后瞬间腾出 admission 空间、导致系统同时积累大量运行任务和等待任务。

所有 backend 必须使用相同的 `TASK_GLOBAL_QUEUE_LIMIT`。如果实例配置不同，仍不会产生数据库错误，但不同请求会按命中实例的阈值判断；Phase 10 部署校验必须阻止这种配置漂移。

## 4. 拒绝契约

| 原因 | HTTP | 稳定错误码 | 默认 Retry-After |
| --- | ---: | --- | ---: |
| 全局活动任务已满 | 429 | `TASK_GLOBAL_CAPACITY_EXCEEDED` | 5 秒 |
| 当前用户活动任务已满 | 429 | `TASK_USER_CAPACITY_EXCEEDED` | 5 秒 |
| 实例正在 graceful shutdown | 429 | `TASK_SERVICE_SHUTTING_DOWN` | 5 秒 |

`Retry-After` 由 `TASK_ADMISSION_RETRY_AFTER` 配置，并按毫秒向上取整为秒。它表示建议重试间隔，不承诺该时刻一定已有空位；客户端仍应使用有上限的退避和抖动，不能固定高频重试。

`fctts.task.admission.rejected{reason=global_capacity|user_capacity}` 记录准入拒绝。该指标不包含 username/taskId，避免敏感和高基数标签。

## 5. 为什么没有用 Redis 做最终配额

任务与终态都在 MySQL。若 Redis 先扣额度、MySQL 插入失败，或 MySQL 已完成但 Redis 释放失败，就会出现错误拒绝或超额接收。当前任务提交量远低于普通 API 流量，使用一个短 MySQL admission 临界区更简单且可恢复。

只有 Phase 11 证明这行锁成为提交吞吐瓶颈，才应考虑预分配全局 slot、分片配额或独立消息队列。即使以后 Redis 用于快速预筛，MySQL 仍必须保留最终原子保护。

## 6. nodb 边界

`InMemoryTaskRepository.create` 使用 `synchronized` 同时检查幂等、全局活动数和用户活动数，因此单 JVM nodb 模式也不会无界接受任务。但它不是多实例实现，重启会丢失任务，仅用于本地演示和单元测试。

## 7. 验证结果

2026-09-13 本地结果：

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| V1→V5→V10 迁移 | VERIFIED | 专用 MySQL 5.7 schema 完成 10 个迁移，旧 PENDING/RUNNING 任务按既有 V8 规则恢复，V10 lock row 为 1。 |
| 双 Repository 全局并发准入 | VERIFIED | 两个 Repository 同时以 global limit=1、不同 owner 提交；严格 1 CREATED、1 GLOBAL_CAPACITY_EXCEEDED。 |
| 每用户跨实例上限 | VERIFIED | 两个 Repository 同 owner、user limit=1 并发；严格 1 CREATED、1 USER_CAPACITY_EXCEEDED。 |
| 幂等优先于容量拒绝 | VERIFIED | 既有双 Repository dedup 并发回归仍严格 1 CREATED、1 DUPLICATE，返回相同 taskId。 |
| HTTP 错误契约 | VERIFIED | 单元测试覆盖稳定 error code、429、向上取整 Retry-After。 |
| nodb 全局拒绝和指标 | VERIFIED | global limit=1 时第二用户被拒绝，`global_capacity` counter=1。 |
| 专用 schema 清理 | VERIFIED | 测试后 `tts_phase9_backpressure_verify_* = 0`，业务 schema `zhiyunjiaos = 1`。 |
| 后端完整回归 | VERIFIED | Maven 共运行 128 个测试，0 failures、0 errors、7 skipped；真实 Redis 限流集成测试未跳过。 |
| 前端生产构建 | VERIFIED | Vite production build 成功，14 modules transformed。 |
| 任务提交锁等待与最大 submit/s | NOT VERIFIED | 尚未做并发级进压测；属于 Phase 11。 |
| 默认 200 是否为安全队列长度 | NOT VERIFIED | 尚未绑定任务体积、平均耗时、恢复时间目标和 DB 容量。 |
| 真实 10/100/200 并发业务流量 | NOT VERIFIED | 不能由两个线程的正确性测试推导。 |

## 8. 剩余风险

1. admission row 有意串行化任务创建；它保证正确性，也可能成为高提交速率下的锁等待热点。Phase 11 必须测 `task create` p95/p99 和 MySQL lock wait。
2. 全局上限是活动任务数量，不是 payload 总字节、预计运行时长或资源成本。一个短 ASR 与一个长视频当前各占一个名额；后续只能由真实任务分布驱动加权策略。
3. 429 不代表任务丢失：只有未被接收的请求返回 429；已经进入 MySQL 的任务继续由 worker retry/recovery。客户端必须用幂等键重试。
4. 滚动升级期间若旧版本 backend 仍接受任务，它不会获取 V10 admission lock。Phase 10 应采用先迁移、再切流到全新版本的部署方式，或在升级窗口停止旧版写入。
5. 本阶段没有加入 Kafka/RabbitMQ，因为当前没有压测证据证明 DB queue 已成为瓶颈。

# 智韵教声最终可扩展性与容量报告

> 报告状态：Phase 15 FINAL
>
> 报告日期：2026-09-13（Asia/Shanghai）
>
> 代码范围：`3abeff9`（容量基线）至本报告所在提交（含 `38290d9` Phase 15 报告及 completion-audit 纠正）
>
> 结论口径：`VERIFIED` 只表示指定提交、主机、拓扑、数据集和协议下的观测结果，不等于生产环境承诺。

## 1. 结论先行

“智韵教声”已经从依赖单 JVM 状态和节点本地文件的演示型结构，升级为可由两个 Spring Boot 实例共同工作的后端：Nginx 负责流量分配，MySQL 保存业务事实和持久任务队列，Redis 提供缓存与原子限流，Aliyun OSS 可作为跨节点对象存储，worker 使用数据库原子领取、心跳、重试和 stale recovery，重型资源由有界 bulkhead 隔离。

最终结论不是无条件的“支持 1000 并发”，而是以下分层结果：

| 结论 | 状态 | 能够准确声称的范围 |
| --- | --- | --- |
| 双实例业务正确性 | **VERIFIED** | 同一 Windows 主机上的两个真实 JVM，经 Nginx 共享 MySQL、Redis 和 OSS；JWT 跨节点、课件 CAS、任务幂等领取、缓存失效、文件跨节点读写和单节点切换已验证。 |
| 10/50/100/200 VU 非登录 L0 API | **VERIFIED** | 每档 3 轮、每轮 2 分钟预热 + 10 分钟稳态，错误率均为 0%；200 VU 非登录 endpoint 最坏 p95 为 27.929 ms。 |
| 完整 L0 SLO | **NOT MET** | 登录 BCrypt 路径在各档 p95 为 483.835–579.762 ms，超过既定 300 ms；不能用低总体 p95 掩盖该接口。 |
| 150 VU、30 分钟非登录 L0 稳定性 | **VERIFIED** | 74,938 次 steady 请求、0 业务错误；未观察到 heap、RSS、线程、handle、连接池、Redis 或 executor queue 持续增长。登录 p50/p95 仍未达标。 |
| GPT-SoVITS 安全并发 | **VERIFIED（隔离直连）** | 本机固定文本和参考音频下安全并发为 1，约 0.1193 job/s；不是完整 Spring/worker/OSS/ASR 链路容量。 |
| FFmpeg 安全并发 | **VERIFIED（隔离直连）** | 本机 30 秒固定输入、CPU 编码下整机安全并发为 2，约 1.2428 job/s；双 backend 同主机应各保留 1。 |
| Redis/MySQL/backend/worker 故障恢复 | **VERIFIED** | 真实隔离进程停启与 worker crash 已验证；GPT-SoVITS/FunASR 仅验证 HTTP 契约桩。 |
| 1000 注册用户、最多 200 活跃用户 | **ESTIMATED FOR PRODUCT / VERIFIED FOR NON-LOGIN L0 ONLY** | 1000 账号数据基线上的 200 VU 非登录 L0 已实测；若登录不是突发、媒体到达率不超过各资源实测服务能力，可作为部署规划起点。完整产品仍未验证。 |
| 1000 并发用户或完整媒体链路 | **NOT VERIFIED** | 没有 1000 VU、真实 FunASR、完整视频换声、OSS 吞吐、WebSocket/streaming、Linux/云主机或复合故障容量证据。 |

因此，当前发布判断是：**测得的双实例非登录核心 API 可作 CONDITIONAL GO；“全功能支持 1000 人同时使用”仍为 NO-GO。**

主要证据：[Phase 11 聚合证据](../performance/phase11-aggregate-evidence.json)、[Phase 12 聚合证据](../performance/phase12-aggregate-evidence.json)、[Phase 13 聚合证据](../performance/phase13-aggregate-evidence.json)、[Phase 14 聚合证据](../performance/phase14-aggregate-evidence.json)。

## 2. 证据等级与判定规则

| 标签 | 本报告中的含义 |
| --- | --- |
| `VERIFIED` | 在可追溯的代码、真实进程或真实负载中直接观察到，且结论不超出当时环境与协议。 |
| `VERIFIED_WITH_CONTRACT_STUB` | Spring 到依赖的 HTTP 超时、重试和错误语义已验证，但依赖本体是契约桩。 |
| `ESTIMATED` | 由实测点和明确假设推导，用于规划，不是承诺。 |
| `NOT MET` | 已执行测试，但没有达到预先定义的 SLO。 |
| `NOT VERIFIED` | 没有足够的当前实测证据。 |
| `NOT SUPPORTED` | 架构明确不提供该能力，例如已建立 WebSocket 连接跨节点无缝迁移。 |

普通 API SLO 为每个关键 endpoint 及聚合同时满足 p50 `< 100 ms`、p95 `< 300 ms`、p99 `< 800 ms`、错误率 `< 1%`。一个低频慢接口不能被大量快请求稀释。重任务安全并发要求零功能错误、没有稳定性硬失败、资源仍有余量，并且继续增加并发仍有合理吞吐收益。来源：[工作负载模型](WORKLOAD_MODEL.md)。

### 2.1 原始证据定位与摘要校验值

原始负载文件位于被 Git 忽略的 `target/`，提交到仓库的 aggregate JSON 保存协议、摘要和 SHA-256：

| Phase | raw 位置 | 关键 SHA-256 |
| --- | --- | --- |
| 11 | `target/phase11-live-20260913040849` | manifest `8F536B43EF5F9CF18E7E475648ACA81A67AF46EADE75C971027617816D74BDA5`；summary `126A8E0CFD9885E2AEC2D271C411AB878FA5165847E5CA3D82B8C5C2B6E00E68` |
| 12 GPT | `target/phase12-live-20260913074513/phase12-raw-evidence.json` | `736DFDE4F52BAB9C1787EA5D78C0A4029D8CA535FDC0BEDB6AD86A2FCCF7F7F3` |
| 12 FFmpeg | `target/phase12-live-20260913075311/phase12-raw-evidence.json` | `50C181DC7EE4507CDCCC5F412DA06B4727B8A0B9022EBCE5683F95860AF4725E` |
| 13 | `target/phase13-live-20260913081417` | manifest `1B72FE7F148BCF824F9D2D23ADFC1EEB3BFE3E17CE360779A054BCF46D635E80`；k6 summary `811515C9696A9B368B1BFF762BB7CBBD226C74F79323D44CA5C89D15805ACAA4`；resource samples `8BC85A043EF7EFED84E42A4BCA0935AE2D8819AD062568223DF4B5A0FF8C64A8` |
| 14 | `target/phase14-live-20260913101559/phase14-raw-evidence.json` | `D5765135F82D597135DF3771CDFD683631CA21F7DCEC5FFFE745C892C7F66069` |

这些路径用于复核本机原始结果；Git 中可长期审计的入口仍是 Phase 11–14 aggregate JSON。文件哈希只证明产物身份，不扩大其测试范围。

## 3. 原架构瓶颈

Phase 0 基线发现的首要问题不是线程数小，而是正确性和资源边界停留在单实例：

| 原问题 | 原行为 | 多实例/容量后果 | 后续处理 |
| --- | --- | --- | --- |
| JVM 内任务状态 | 去重、每用户活动数、锁和 `Future` 在 `ConcurrentHashMap`；数据库只是快照。 | 两节点可重复接受或执行任务，进程退出后不可恢复。 | Phase 2/5 改成 MySQL 原子状态和可恢复 worker queue。 |
| JVM 内口语趋势 | 明细写入 MySQL，但响应趋势和数据库异常 fallback 仍读取 `historyByUser`。 | 两节点返回不同趋势，重启后趋势丢失，数据库故障被节点本地成功掩盖。 | Completion audit 改为 DB 模式从 MySQL 最近 5 条记录重建趋势，DB 读写失败稳定返回 503；本地 Map 仅保留给 nodb 演示。 |
| 节点本地永久文件 | 课件、视频、字幕、笔记等直接使用 `Path/Files`。 | 请求换节点后文件不可见，节点损坏可能丢失资产。 | Phase 3 迁移到对象存储与元数据目录。 |
| 同步重任务占 HTTP 生命周期 | ASR、TTS、Moonshot、FFmpeg 等长调用与请求线程耦合。 | 慢依赖会放大 Tomcat、连接和 heap 压力。 | Phase 4 改为 `202 + taskId + polling`。 |
| 无 durable worker | 提交时捕获 JVM lambda，启动时将活动任务直接失败。 | 发布或崩溃会丢执行能力，另一节点无法接管。 | Phase 5 保存 JSON payload、claim token、heartbeat、retry、stale recovery。 |
| 重资源无隔离 | 多种任务共享同一执行资源，没有 TTS/ASR/FFmpeg 独立闸门。 | 某一慢资源会拖累其他任务和普通 API。 | Phase 6 增加按资源 bulkhead。 |
| 查询无界与 N+1 | 多个列表在 Java 切片，课件列表逐项目查 revisions。 | 数据量增大后 SQL、heap 和 OSS 调用随结果集增长。 | Phase 7 分页下推、批量 revision 查询、V9 索引。 |
| 限流与配额只在单 JVM | Redis 默认关闭或异常时依赖本机 Map，任务没有跨实例全局上限。 | 多实例可绕过限制，突发任务可无界积累。 | Phase 8/9 使用 Redis Lua 与 MySQL 原子 admission。 |
| 单后端入口 | Nginx 和 Compose 只指向一个 backend。 | 单节点故障即整体中断，无法横向分流。 | Phase 10 配置双 backend、least-conn 和失败切换。 |

来源：[容量基线](SCALABILITY_BASELINE.md)、[长任务 HTTP 模型](LONG_TASK_HTTP_MODEL.md)、[Worker Queue 模型](WORKER_QUEUE_MODEL.md)。

## 4. 修改前架构

```text
Browser / API
      |
      v
Nginx -> one Spring Boot JVM
                 |
                 +-- JVM Map / synchronized / captured lambda
                 +-- local filesystem as persistent artifact store
                 +-- synchronous GPT-SoVITS / FunASR / FFmpeg / Moonshot
                 +-- MySQL records selected business data and task snapshots
                 +-- optional Redis cache/session
```

该结构能演示功能，但节点本地状态同时承担了正确性、排队和文件事实源。增加第二个 JVM 不会自动增加可靠容量，反而会产生重复任务、状态分裂和跨节点文件 404。

## 5. 修改后架构

```text
Client
  |
  v
Nginx least_conn + retry
  |                         established WebSocket stays on selected node
  +------> backend-1 --------+
  |                           \
  +------> backend-2 ----------+--> shared MySQL
                               |      - business source of truth
                               |      - speaking history / trend
                               |      - atomic task admission
                               |      - durable worker queue / CAS
                               |
                               +--> shared Redis
                               |      - cache-aside
                               |      - Lua rate limits / dialogue TTL
                               |
                               +--> Aliyun OSS provider
                               |      - persistent inputs/results
                               |      - owner-scoped metadata
                               |
                               +--> bounded workers + resource bulkheads
                                      - GPT-SoVITS / FunASR
                                      - FFmpeg / courseware / cloud AI
```

节点本地目录现在只承担有界、可清理、可从对象存储重新 materialize 的工作副本，不再是跨实例业务事实源。来源：[多实例验证](MULTI_INSTANCE.md)、[共享对象存储模型](STORAGE_MODEL.md)、[资源隔离](RESOURCE_ISOLATION.md)。

## 6. 单实例问题如何解决

1. **任务正确性进入 MySQL。** 活动幂等唯一索引、每用户 slot、全局 admission lock、原子 claim 和带 `worker_id` 的终态条件更新取代 JVM Map 作为正确性来源。
2. **口语历史和趋势进入 MySQL。** DB 模式先写入 `speaking_history`，再按 `created_at DESC, history_id DESC` 有界读取最近 5 条并恢复为时间正序；DB 读写失败返回 `503 / SPEAKING_HISTORY_UNAVAILABLE`，不回退到节点 Map。
3. **课件写入使用数据库乐观锁。** `courseware_project.lock_version` 和 CAS 更新保证同一版本只有一个写者成功，冲突返回 409。
4. **永久文件进入对象存储。** 数据库记录 provider、bucket、key、大小、checksum、owner；业务不再依赖某个节点的绝对路径。
5. **长任务脱离 HTTP。** 请求只完成鉴权、校验、暂存和有界准入，返回 202；worker 在响应后执行并持久化结果。
6. **入口变为双实例。** Nginx `least_conn` 分配普通 HTTP 和 WebSocket 握手，并对连接失败、超时及 502/503/504 尝试另一 upstream。

当前仍是“同一主机双实例”，因此已经解决单 JVM 正确性，但没有证明跨可用区、跨主机网络或基础设施 HA。

## 7. 多实例如何保证正确性

| 正确性对象 | 机制 | 已验证范围 | 剩余边界 |
| --- | --- | --- | --- |
| JWT | 所有实例共享同一 secret，无 HTTP Session。 | 两节点互验 token。 | secret 轮换流程未做容量演练。 |
| 任务提交幂等 | `(owner, type, active_deduplication_key)` 唯一约束。 | 双 Repository 并发仅创建一条。 | 外部副作用仍是 at-least-once，不是 exactly-once。 |
| 每用户任务上限 | `async_task_user_slot(owner, slot)` 原子占位。 | 跨 Repository 严格限制。 | 目前是所有任务统一权重。 |
| 全局准入 | 单行 `async_task_admission_lock` 的 InnoDB 行锁串行化 count + insert。 | 并发 limit=1 时严格一收一拒。 | 高 submit/s 下可能成为锁热点。 |
| worker 所有权 | 原子 claim + 唯一 `worker_id` token + heartbeat。 | 双 worker 单次领取、worker crash 后 stale recovery。 | 外部调用完成后、SUCCESS 前崩溃可能重做。 |
| 课件写入 | `lock_version` CAS。 | 并发结果严格 200/409，revision 只加 1。 | 需要滚动升级时保证旧节点停止写入。 |
| 缓存 | MySQL 为事实源，Redis cache-aside；写路径驱逐缓存。 | 跨实例写后另一实例 miss 并看到新值。 | Redis 故障时回源压力需要生产容量验证。 |
| 口语历史/趋势 | DB 模式写入 MySQL 后有界查询同用户最近 5 条；失败不读取节点 Map。 | 5 项定向测试验证共享历史重建、DB 读写失败 503，以及原 ASR 失败语义。 | 本纠正未重新执行双节点 live 口语请求，只能声明代码与测试级 `VERIFIED`。 |
| WebSocket | 握手负载均衡，TCP 建立后自然固定在节点。 | 10 次握手 5/5。 | 节点故障时连接不能迁移，客户端必须重连。 |

来源：[Worker Queue 模型](WORKER_QUEUE_MODEL.md)、[Backpressure](BACKPRESSURE.md)、[多实例验证](MULTI_INSTANCE.md)、[Phase 14 故障报告](../performance/PHASE14_FAILURE_TESTS.md)。

## 8. 文件如何共享

生产/多实例模式选择 `app.storage.provider=aliyun-oss`。永久对象包括音色样本、课件源文件、生成音频、虚拟教师图片、课件视频、语音笔记音频和文本。`stored_object_metadata` 或相应业务表保存对象元数据，读取同时校验 task owner 与 object owner。

工作流程为：

```text
multipart upload
  -> size/type/magic/owner validation
  -> owner-scoped generated object key
  -> OSS staging object + metadata
  -> task admission
  -> worker downloads temporary local copy when required
  -> GPT/ASR/POI/FFmpeg
  -> OSS result object + metadata + terminal task state
  -> cleanup staging and temporary files
```

Phase 10 已真实验证 backend-2 上传、backend-1 读取、backend-1 删除、backend-2 随后 404。该结果证明共享与所有权闭环，不证明 OSS 高并发吞吐、区域网络延迟或故障恢复。仓库默认 provider 仍为 `local`，部署时必须显式选择 `aliyun-oss` 并通过环境或受保护配置注入凭证；禁止把长期 AccessKey 写入 Git。来源：[共享对象存储模型](STORAGE_MODEL.md)、[多实例验证](MULTI_INSTANCE.md)。

## 9. Task Queue 如何实现

`async_task` 是 MySQL-backed、at-least-once 的 worker queue，而不是内存线程池队列：

```text
POST
  -> admission transaction
  -> INSERT PENDING(payload_json, available_at, attempts=0)
  -> worker UPDATE ... ORDER BY available_at, created_at LIMIT 1
  -> RUNNING(worker_id, heartbeat_at, attempts+1)
       -> SUCCESS(result object key)
       -> PENDING(指数退避后重试)
       -> FAILED(max attempts exhausted)
       -> TIMEOUT / CANCELLED
```

V8 增加 `payload_json`、`attempts`、`max_attempts`、`available_at`、`heartbeat_at`、`worker_id`、`version`、`error_code`。心跳、成功、失败、重排都必须匹配 `task_id + RUNNING + worker_id`，旧 worker 不能覆盖新 owner 的状态。失联任务按 `stale-after` 恢复；Phase 14 已真实杀死运行任务所在 backend，并由另一 backend 将任务从 attempt 1 恢复为 SUCCESS/attempt 2。

该队列不承诺 exactly-once：外部服务、OSS 和 MySQL 不能处于同一个分布式事务。带持久业务副作用且未证明幂等的任务使用 `maxAttempts=1`；可重复计算任务以 attempt 独立结果 key 降低污染。来源：[Worker Queue 模型](WORKER_QUEUE_MODEL.md)、[Phase 14 聚合证据](../performance/phase14-aggregate-evidence.json)。

## 10. Backpressure

系统使用四层背压，而不是靠无限增加线程：

| 层 | 默认保护 | 满载行为 | 证据边界 |
| --- | --- | --- | --- |
| HTTP 用户接口 | Redis Lua，每用户每 controller method 120/min。 | 429 + `Retry-After`。 | 40 次并发调用、上限 10 时严格放行 10；默认 120 未由容量反推。 |
| 每用户活动任务 | MySQL slot，2 个 PENDING+RUNNING。 | 429 `TASK_USER_CAPACITY_EXCEEDED`。 | 跨实例原子性已验证。 |
| 全局活动任务 | MySQL admission lock，200 个 PENDING+RUNNING。 | 429 `TASK_GLOBAL_CAPACITY_EXCEEDED`，默认 Retry-After 5s。 | 原子性已验证；200 是否安全未验证。 |
| 重型资源 | TTS/ASR/FFmpeg/Courseware 各自 semaphore，默认 1/实例。 | 同步调用 503；worker 释放 claim、延后重领且不消耗 attempt。 | 单实例语义已验证；跨实例共享 GPT 全局准入未解决。 |

worker 使用固定 loop 与 `SynchronousQueue`，真正的等待保存在 MySQL，不再叠加不可见 JVM backlog。客户端轮询从 1 秒退避到 5 秒并设 16 分钟截止，避免零间隔 busy polling。

默认 200/2/120/min/5s 都是保护初值，不是容量证明。尤其全局 200 个任务若全部由约 0.1193 job/s 的 GPT-SoVITS 串行服务，简单排空时间可达到约 28 分钟；这是风险模型，不是可接受的队列 SLO。来源：[Backpressure](BACKPRESSURE.md)、[资源隔离](RESOURCE_ISOLATION.md)、[Phase 12 聚合证据](../performance/phase12-aggregate-evidence.json)。

## 11. Database indexes

Phase 7 将分页下推 SQL、把课件 revisions 的 `1 + N` 查询降为每页固定 2 次，并在 MySQL 5.7.26、代表性数据上对 V8 before/V9 after 执行真实 `EXPLAIN`。V9 只新增与当前访问模式对应的 4 个索引：

| 索引 | 服务的查询 | 观测结果 |
| --- | --- | --- |
| `idx_voice_owner_visibility_created(owner_username, public_visible, created_at, voice_id)` | 私有音色可见列表。 | 消除原 owner 单列索引后的 filesort。 |
| `idx_voice_visibility_created(public_visible, created_at, voice_id)` | 公共音色列表。 | 消除原 visibility 单列索引后的 filesort。 |
| `idx_async_task_status_heartbeat(status, heartbeat_at)` | stale RUNNING recovery。 | 估算候选由 1,250 降至 10。 |
| `idx_stored_object_owner_key(owner_username, object_key)` | owner 范围的对象 prefix 过滤。 | 估算候选由 30 降至 3，但仍有小集合 filesort。 |

已有 `idx_async_task_status_available_created` 支撑 claim，活动幂等生成列由唯一索引常量查找，课件 owner 分页、口语历史和 revision 主键已有匹配索引。没有为 `%keyword%` 搜索伪造 B-tree 收益，也没有添加不存在业务查询的索引。

当前边界是 offset 深分页、`%keyword%` 包含搜索和 voice-note 每页正文对象读取。`EXPLAIN rows` 是优化器估算，不是实际扫描行数或延迟。来源：[数据库容量报告](DATABASE_CAPACITY.md)、[V9 migration](../../src/main/resources/db/migration/V9__database_capacity_indexes.sql)。

## 12. Redis

Redis 的职责被限制在可重建或短生命周期状态，不承担任务/课件最终正确性：

| 能力 | 机制 | 故障行为 |
| --- | --- | --- |
| 音色缓存 | Cache-Aside；`voiceList` 10 分钟、`voiceById` 30 分钟。 | miss 回源 MySQL；不改变业务事实。 |
| 登录失败保护 | 按可信客户端 IP 哈希，Lua 原子计数、TTL 和锁定。 | 切换到有界 JVM fallback；多实例计数暂不统一。 |
| 用户接口限流 | JWT 后按 username + handler 哈希执行 fixed-window Lua。 | 有界 fallback；容量满时拒绝新桶。 |
| 对话会话 | Redis Hash + TTL + Lua advance。 | Redis 不可用时会话不可用，不伪装持久化。 |
| 任务配额/状态 | 不使用 Redis 作为事实源。 | MySQL 持续保证配额和状态。 |

已暴露 `cache.gets` hit/miss、限流 reject/degraded 指标，key/tag 不包含用户名、IP 或对象 key。Phase 14 中 Redis 停机时 readiness 503、liveness 200，降级登录限流返回 5×401 后 1×429，7.516 秒恢复。当前仍是单 Redis 进程，没有验证 Sentinel/Cluster、持久化、淘汰策略或生产故障切换。来源：[Redis 容量报告](REDIS_CAPACITY.md)、[Phase 14 聚合证据](../performance/phase14-aggregate-evidence.json)。

## 13. Workload model

容量模型严格区分用户数、并发请求和重任务并发：

| Lane | 代表行为 | 容量资源 | 本轮证据 |
| --- | --- | --- | --- |
| L0 CORE_API | login、voice list/search、courseware list、task create/status。 | Tomcat、JWT/BCrypt、MySQL、Redis、Hikari。 | 10→50→100→200 VU 阶梯和 150 VU soak。 |
| L1 FILE_IO | 上传、试听、课件/视频下载。 | Nginx、网络、OSS、临时盘、句柄。 | 正确性有验证，吞吐未测。 |
| L2 GPT_SOVITS_TTS | 合成、流式 TTS、声音克隆。 | GPU、模型、WebClient。 | 固定输入直连 1/2/4/8 并发。 |
| L3 ASR | FunASR 文件转写、实时 WebSocket。 | 模型、WebSocket、临时文件。 | HTTP 契约故障语义；真实容量未测。 |
| L4 FFMPEG_VIDEO | 音频提取、字幕、换声、课件视频。 | CPU、RAM、磁盘、进程。 | 30 秒固定输入直连 1/2/4/8 并发。 |
| L5 CLOUD_AI | Moonshot、阿里云 NLS/声音复刻。 | 外网、供应商 quota、成本。 | 未形成容量结果。 |

Phase 11 的账号基线是 1000 registered、200 active，每个活跃账号 10 个课件和 50 条历史任务，另有 200 条公开音色。每个 VU 独立登录、浏览、搜索、查看课件，并由 20% VU 提交任务和轮询；媒体执行被明确排除。RPS 是带 think time 的用户旅程自然结果，不是最大无停顿吞吐。

来源：[工作负载模型](WORKLOAD_MODEL.md)、[Phase 11 聚合证据](../performance/phase11-aggregate-evidence.json)。

## 14. Pressure-test result

### 14.1 L0 阶梯压测

| VU | 3 轮总请求 | steady RPS 范围 | overall p95 最大 | overall p99 最大 | 非登录 endpoint p95 最大 | login p95 最大 | error 最大 | CPU p95 最大 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 4,987 | 2.757–2.783 | 8.284 ms | 49.858 ms | 17.397 ms | 579.762 ms | 0% | 23.1% |
| 50 | 25,228 | 13.938–14.062 | 4.899 ms | 42.928 ms | 24.458 ms | 569.495 ms | 0% | 28.3% |
| 100 | 50,339 | 27.868–28.025 | 4.818 ms | 39.432 ms | 17.306 ms | 561.355 ms | 0% | 41.0% |
| 200 | 100,551 | 55.772–55.947 | 5.114 ms | 39.731 ms | 27.929 ms | 564.393 ms | 0% | 68.4% |

200 VU 最坏资源观察为 CPU peak 85.4%、可用内存最低 1,417 MiB、双 JVM heap 最大 327.0 MiB、Tomcat busy 最大 15、Hikari active/pending 最大 2/0、executor queue/rejection 0/0、MySQL connected/running 最大 21/2。它证明 measured mix 尚未出现这些资源的硬饱和，但 CPU 和主机内存已经是继续上探前必须关注的余量。

完整 L0 判定仍为 `NOT MET`，因为登录 endpoint p95 超过 300 ms。来源：[10 users](../performance/10_USERS.md)、[50 users](../performance/50_USERS.md)、[100 users](../performance/100_USERS.md)、[200 users](../performance/200_USERS.md)。

### 14.2 重任务压测

| 资源 | 并发 1 | 并发 2 | 并发 4 | 并发 8 | 安全点 |
| --- | --- | --- | --- | --- | --- |
| GPT-SoVITS | 0.1193 job/s，p95 9.64s | 0.1124 job/s，p95 19.40s | 0.1058 job/s，p95 41.05s | 0.1094 job/s，p95 85.80s，内存最低 364 MiB | **1** |
| FFmpeg | 0.8021 job/s，p95 1.36s | 1.2428 job/s，p95 1.71s，CPU p95 88.61% | 1.3051 job/s，p95 3.15s，CPU 100% | 1.5231 job/s，p95 5.83s，CPU 100% | **整机 2** |

GPT 提升并发没有增加吞吐，延迟近似随排队深度线性增加；FFmpeg 从 2 增到 4 只增加约 5.01% 吞吐且 CPU 饱和。48 次正式请求/任务在每种资源测试中均零失败，但“零失败”本身不等于安全并发。来源：[Phase 12 报告](../performance/PHASE12_HEAVY_TASKS.md)。

## 15. Soak-test result

150 VU 在同一 1000 registered/200 active 数据基线上完成 2 分钟 warmup、30 分钟 steady 和 5 分钟恢复尾窗：

| 指标 | 观测值 | 判断 |
| --- | ---: | --- |
| steady requests / RPS | 74,938 / 41.632 | `VERIFIED` |
| HTTP 状态 | 74,908×200；30×202 | 业务错误 0% |
| overall p50/p95/p99 | 2.873 / 6.249 / 51.156 ms | MET |
| login p50/p95/p99 | 546.556 / 589.455 / 617.450 ms | p50/p95 `NOT MET` |
| CPU avg/p95/peak | 24.572% / 85.49% / 99.854% | 有短峰值，未观察到持续饱和 |
| 可用内存最低 | 1,091 MiB | 未 OOM，余量偏窄 |
| 双 JVM heap 最大 / 尾窗末 | 363.8 / 148.3 MiB | GC 后回落 |
| Hikari active/pending 最大 | 1 / 0 | 未见池等待 |
| executor queued/rejected | 0 / 0 | 未见 backlog |
| Redis evictions / MySQL slow query 增量 | 0 / 0 | 未观察到该类增长 |

本次只运行一次 30 分钟 soak，因此可说“30 分钟内未观察到泄漏”，不能说长期绝无泄漏。测试期间存在一次已披露的约 85 秒本机汇总器 CPU/磁盘干扰；排除该窗后 CPU p95 仍为 85.49%，没有改变资源结论。媒体 worker、OSS、WebSocket 和 streaming 不在本次 soak 中。来源：[Phase 13 报告](../performance/PHASE13_SOAK.md)、[Phase 13 聚合证据](../performance/phase13-aggregate-evidence.json)。

## 16. Failure-test result

| 故障 | 故障期 | 恢复 | 证据状态 |
| --- | --- | --- | --- |
| Redis restart | readiness/health 503，liveness 200；fallback 登录 5×401、1×429。 | 7.516s 后恢复，业务查询 200。 | `VERIFIED` |
| MySQL restart | readiness/health 503，liveness 200；DB API 在 2.016s 返回 500。 | 1.859s 后 readiness 和同一查询恢复 200。 | `VERIFIED` |
| GPT-SoVITS unavailable | 连接拒绝 0.031s 返回 503；5s 慢响应在 2.015s 超时。 | stub 恢复后请求 200。 | `VERIFIED_WITH_CONTRACT_STUB` |
| FunASR unavailable | 先返回 202；3 次尝试后 2.094s 进入 FAILED。 | 新任务 1 次尝试 SUCCESS。 | `VERIFIED_WITH_CONTRACT_STUB` |
| backend-1 termination | 端口关闭；Nginx 的 20/20 次连续请求由 backend-2 成功处理。 | backend-1 19.000s 内重新 ready。 | `VERIFIED` |
| worker crash | RUNNING/attempt 1 时终止 backend-1。 | backend-2 stale recovery 后 SUCCESS/attempt 2，用时 18.891s。 | `VERIFIED` |

MySQL 故障窗出现 17 次 Hikari 约 2 秒获取超时、26 次 worker loop failure 和 8 次 stale recovery failure。连接等待是有界的，不能表述为“数据库停机时连接池仍可用”；100ms 测试 poll 放大了日志风暴，生产需要 DB 错误退避、抖动和日志限频。

一次故障演练不能建立恢复时间 p95/p99。Nginx 的 20 次请求总时长 23.390s 证明功能接管，不证明故障窗仍满足 300ms p95。真实模型进程、真实 OSS、Linux/云、磁盘满、网络分区和复合故障均未验证。来源：[Phase 14 报告](../performance/PHASE14_FAILURE_TESTS.md)、[Phase 14 聚合证据](../performance/phase14-aggregate-evidence.json)。

## 17. VERIFIED CAPACITY

以下是当前唯一允许作为“已验证容量”发布的口径：

1. **非登录 L0 API：**在一台 AMD Ryzen 9 7945HX Windows 主机、两个 512 MiB heap Spring Boot 实例、Nginx、共享 MySQL 5.7 和专用 Redis 的拓扑下，1000 账号数据基线上的 10/50/100/200 closed-model VU 各完成 3 轮 10 分钟 steady；200 VU 产生 55.772–55.947 req/s，非登录 endpoint 最坏 p95 27.929 ms，错误率 0%。
2. **30 分钟稳定性：**同类双实例拓扑下 150 VU 完成 30 分钟 steady，41.632 req/s、74,938 请求、0 业务错误；在观测窗内没有发现资源单调增长或池耗尽。
3. **隔离重资源：**固定输入直连 GPT-SoVITS 的本机安全并发为 1、约 0.1193 job/s；固定 30 秒输入的 CPU FFmpeg 整机安全并发为 2、约 1.2428 job/s。
4. **恢复正确性：**单 backend/worker crash、隔离 MySQL/Redis restart 可恢复；真实外部模型恢复不在此结论中。

`VERIFIED CAPACITY` **不包含登录 SLO、完整视频换声、真实 FunASR、真实 OSS 吞吐、WebSocket/streaming、1000 VU、Linux/云或生产网络。**

## 18. ESTIMATED CAPACITY

基于实测点，可形成以下部署规划估计，但必须保留假设：

| 场景 | 估计 | 必须成立的假设 | 最大不确定性 |
| --- | --- | --- | --- |
| 10 个同时活跃用户 | 非登录 L0 有充足实测余量；正常渐进登录可用。 | 请求构成接近 Phase 11；不同时触发大量媒体。 | 登录仍不满足 300ms p95；真实文件/媒体混合未测。 |
| 100 个同时活跃用户 | 非登录 L0 在本机双实例约 28 req/s 下可用。 | 账号、数据量、think time 与测试相近。 | 登录突发和媒体到达率。 |
| 1000 注册、最多 200 活跃 | 可作为非登录 L0 规划上限起点，不等于 1000 并发。 | 200 active、约 56 L0 req/s；登录分散；任务以 202 有界准入；媒体到达率不超过资源服务率。 | GPT 全局准入、FunASR、OSS、完整链路未验证。 |
| 纯 GPT-SoVITS 队列 | 长期到达率应严格低于约 0.119 job/s，并保留恢复余量。 | 输入长度与 Phase 12 固定样本接近，单模型实例。 | 文本/参考音频分布、模型冷启动和跨实例并发。 |
| 纯 FFmpeg 队列 | 双 backend 同主机最多各 1 个并行，整机约 1.24 job/s 测量点。 | 30 秒输入和相同 CPU 编码命令。 | 视频时长、分辨率、编码参数和其他 CPU 负载。 |

这里的“约 0.119/1.24 job/s”是隔离服务率，不是建议把到达率打到 100%。生产应预留故障、波动和交互延迟余量；没有队列等待 SLO前，不给出虚假的百分比利用率承诺。

### 18.1 真实来了 10、100、1000 用户时，请求如何流动

**10 个活跃用户：**用户经 Nginx 分散到两个 backend；登录执行 BCrypt，随后 JWT 请求经 Redis 用户接口限流。音色/课件读取优先使用有界分页与缓存，miss 回 MySQL。若提交媒体任务，上传先进入 OSS staging，MySQL 原子 admission 后返回 202，worker 再领取并通过资源 bulkhead 执行。此时最容易被感知的是单次登录约 0.5 秒，而非数据库或 Hikari。

**100 个活跃用户：**相同调用链自然产生约 28 L0 req/s。读请求仍主要由缓存和有界 SQL 处理，Phase 11 观察到 Hikari pending=0、Tomcat busy 最大 11。若媒体提交开始集中，瓶颈会从普通 API 转移到 GPT/ASR/FFmpeg service rate，任务在 MySQL 排队并以 429/Retry-After 限制继续涌入。

**1000 个注册用户：**账号行本身几乎不消耗在线资源。若其中 200 个同时活跃，实测 L0 约 56 req/s；请求按上述链路流动。若 1000 人同时登录，或 200 人同时触发 TTS/视频，则不再属于已测 workload：首先可能饱和的是 BCrypt/CPU 和 GPT-SoVITS，随后是未校准的 FunASR、任务等待时间和主机内存，而不是 MySQL 连接池。默认全局队列 200 会拒绝更多任务，但不能保证已接受任务在可接受时间内完成。

## 19. Remaining bottlenecks

按当前证据排序：

1. **登录 BCrypt 是已观测瓶颈。** 所有并发档及 soak 的 login p95 均超 300 ms；不能通过降低密码强度草率换取 PASS。需要认证路径容量隔离、CPU 预算、登录突发测试，并决定登录是否使用独立 SLO。
2. **共享 GPT-SoVITS 缺少跨实例全局准入。** 当前 semaphore 是每 JVM；两个 backend 各 1 仍可能向同一模型发 2 个并发，而实测安全值是整机/模型 1。
3. **真实 FunASR 与完整视频换声未压测。** 当前只能证明 HTTP 契约超时/重试，不知道 ASR safe concurrency，也不知道 ASR+TTS+FFmpeg+OSS 串联后的瓶颈和队列等待。
4. **OSS 只有正确性验证。** 没有 1/10/20/50 MiB 上传下载、慢客户端、吞吐、失败恢复或区域网络延迟证据；默认 provider 还是 local，部署配置必须显式选择 OSS。
5. **待清理文件队列没有原子领取。** `pending_file_cleanup` 由每个实例直接 `SELECT ... LIMIT` 后删除或增加 attempts；两个 scheduler 可能同时处理同一记录。对象删除通常幂等，但失败次数、日志和外部请求会重复，尚未达到多实例 worker 所有权语义。
6. **旧视频响应仍放大 JVM heap。** `VideoVoiceSwapServiceImpl.serveFile` 使用 `Files.readAllBytes` 返回 `ResponseEntity<byte[]>`；大视频或并发下载会产生大对象和 GC/OOM 风险，尚未改为流式响应或对象存储直签下载。
7. **基础设施仍是单点。** 两个 backend 共享同一 MySQL、Redis、主机和外部模型；未验证 Redis HA、MySQL HA、跨主机网络或可用区故障。
8. **登录/媒体未进入同一 soak。** 30 分钟测试关闭媒体 worker，只覆盖 L0；60 分钟以上、真实 streaming/WebSocket 和文件 IO 未验证。
9. **任务队列参数未按等待 SLO 校准。** 全局 200、每用户 2 和 admission 单行锁是正确性/保护机制，不是已证明的吞吐或等待目标。
10. **数据库故障会产生日志风暴。** 需要 worker DB 错误指数退避、jitter、熔断/短路和日志限频；还需在生产流量下验证池恢复。
11. **查询仍有数据规模边界。** offset 深分页、包含搜索、voice-note 正文 N 次对象读取和 revision 正文体积需要真实长期数据分布验证。
12. **故障样本不足。** 每类仅一次，无法给恢复时间 p95/p99；没有磁盘满、网络分区、半开连接和复合故障。

## 20. Scaling roadmap

| 优先级 | 工作 | 验收标准 |
| --- | --- | --- |
| P0 | 立即轮换已公开的 OSS 长期 AccessKey，改用最小权限 RAM/STS；部署只注入 secret。 | 旧 key 失效；新身份仅有目标 prefix 所需读写删权限；仓库与日志无密钥。 |
| P0 | 修复并重新定标登录路径。 | 10→50→100→200 三轮 login p50/p95/p99 达到重新确认的 SLO；保留 BCrypt 安全强度。 |
| P0 | 为共享 GPT-SoVITS 增加跨实例全局 permit/lease。 | 两 backend 并发提交时模型实际 active 永不超过 1；超限请求/任务有稳定 429/503、退避和指标。 |
| P0 | 校准 task queue。 | 按任务类型记录 arrival、queue wait、service time、完成率；全局/用户上限由等待 SLO 和恢复预算反推。 |
| P1 | 真实 FunASR、完整视频换声和课件媒体链路 1→2→4→8 压测。 | 分别给出 safe concurrency、吞吐、p50/p95/p99、失败/重试、CPU/RAM/GPU/磁盘和残留对象。 |
| P1 | L1 OSS/文件与 WebSocket/streaming 测试。 | 多文件大小、慢客户端、断连、active streams/connections、首包和清理均有原始证据。 |
| P1 | 为 `pending_file_cleanup` 增加原子 claim、owner token 和 stale recovery。 | 双 scheduler 对同一 cleanup 记录只有一个 owner；崩溃后可回收；状态更新必须匹配 owner。 |
| P1 | 移除视频 `readAllBytes` 返回路径。 | 大视频通过流式响应或对象存储直签 URL 下载；并发下载期间 heap 增量有界，并验证慢客户端和断连清理。 |
| P1 | 在目标 Linux/云主机重复 Phase 11–14。 | 绑定实例规格、网络、真实 MySQL/Redis/OSS/模型；至少 60 分钟 soak；每类故障多轮形成恢复分布。 |
| P1 | MySQL/Redis 故障退避与 HA。 | 无日志风暴；连接等待有界；主从/哨兵或托管 HA 切换后正确性和延迟达到明确 SLO。 |
| P2 | API 与媒体 worker 分离部署，按资源池扩容。 | API 扩容不放大共享 GPU 并发；worker 按 TTS/ASR/FFmpeg 队列独立扩容和限额。 |
| P2 | 数据增长触发式优化。 | 出现深分页/搜索/对象 N+1 热点后，再以真实 trace 决定 cursor、搜索服务或列表摘要表。 |

扩容顺序必须保持“先正确性与全局准入，再加实例”。直接增加 backend 会线性放大 Hikari 连接数和每 JVM semaphore，不会自动增加共享 GPU、MySQL 或 Redis 的安全容量。

## 21. Phase 0–15 交付映射

| Phase | commit | 主要结果 |
| ---: | --- | --- |
| 0 | `3abeff9` | 建立容量基线与阻断项。 |
| 1 | `e9bf732` | 固定 workload、SLO 和证据协议。 |
| 2 | `675b739` | 数据库原子任务状态与幂等。 |
| 3 | `a088542` | 永久产物共享对象存储。 |
| 4 | `8f64000` | 重任务从 HTTP 生命周期解耦。 |
| 5 | `95fa84e` | MySQL durable worker queue。 |
| 6 | `59c588e` | TTS/ASR/FFmpeg/Courseware 资源隔离。 |
| 7 | `31f2d64` | 分页、N+1 与容量敏感索引。 |
| 8 | `e138156` | Redis Lua 限流、缓存与指标。 |
| 9 | `8258bf9` | MySQL-backed 全局/用户 backpressure。 |
| 10 | `7e3c9ab` | 双实例运行与正确性验证。 |
| 11 | `109da5e` | 10/50/100/200 VU L0 阶梯压测。 |
| 12 | `cb9307f` | GPT-SoVITS 与 FFmpeg 安全并发。 |
| 13 | `3583e0e` | 150 VU、30 分钟 soak。 |
| 14 | `5bbef27` | 依赖、backend 与 worker 故障恢复。 |
| 15 | `38290d9` | 发布最终容量报告并给出分层结论。 |
| Completion audit | 本报告所在提交 | 口语历史/趋势移除 DB 模式下最后的节点 Map 正确性依赖，并补齐失败语义与定向测试。 |

这些提交构成一条可审计工程链，但每个历史阶段的 `VERIFIED` 都只适用于该阶段声明的范围。最终容量数字以 Phase 11–14 聚合 JSON 为准。

## 22. 最终声明校验

- [x] 没有把 1000 registered users 写成 1000 concurrent users。
- [x] 没有把总体 p95 代替 login endpoint SLO。
- [x] 没有把 0% failure 直接等同于重任务安全并发。
- [x] 没有把配置默认值或单元测试写成容量证明。
- [x] 没有把 GPT-SoVITS/FunASR 契约桩写成真实模型恢复。
- [x] 没有把单机 Windows 双实例写成跨主机、Linux 或云生产证明。
- [x] 没有把 OSS 跨节点 CRUD 写成 OSS 吞吐或可用性证明。
- [x] 没有把 30 分钟内未观察到泄漏写成长期绝无泄漏。
- [x] 所有 Phase 11–14 核心数值均来自可提交 aggregate JSON，并与对应阶段报告交叉核对。
- [x] 报告未包含 AccessKey、密码、JWT secret 或其他长期凭证。

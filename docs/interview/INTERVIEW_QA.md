# 智韵教声 Java 后端面试追问与标准回答

> 共 30 题。回答模板统一为：先给结论，再讲项目落点和原理，最后主动说明证据边界。

## 一、项目与架构

### 1. 这个项目到底解决什么问题？你的贡献是什么？

**标准回答：** 智韵教声把 TTS、ASR、声音复刻、课件和视频处理放到统一教学工作台。我负责的核心不是训练语音模型，而是 Java 后端工程化：用户认证、业务状态、持久异步任务、文件与对象归属、外部服务编排、资源隔离、可观测性和容量验证。

**项目落点与证据：** [`README_FINAL.md`](README_FINAL.md)、[`AsyncTaskService`](../../src/main/java/com/a09/tts/task/AsyncTaskService.java)、[最终容量报告](../scalability/FINAL_SCALABILITY_REPORT.md)。

**边界：** 项目使用真实外部模型，但不能把模型训练或算法研发写成个人成果。

### 2. 为什么是模块化单体，不是微服务？

**标准回答：** 当前规模没有证据证明拆成微服务能带来大于成本的收益。模块化单体减少部署、网络调用和分布式事务复杂度；Controller、Service、任务、存储和外部适配仍有清晰边界。未来只有在 worker 资源隔离或独立扩容需求被真实容量数据证明后，才考虑拆分。

**项目落点与证据：** [`ARCHITECTURE.md`](ARCHITECTURE.md)、`controller/service/task/storage` 包结构。

**边界：** 外部 GPT-SoVITS/FunASR 进程不等于本项目采用微服务架构。

### 3. 项目的核心调用链是什么？

**标准回答：** 典型链路是：JWT 认证 → 上传安全与 owner 校验 → 输入流式写对象存储 → MySQL 创建 `PENDING` 任务 → worker 原子 claim → bulkhead 保护下调用 ASR/TTS/FFmpeg → 结果写回对象存储 → 数据库提交 `SUCCESS` → 用户轮询并按 owner 流式下载。

**项目落点与证据：** [`MediaTaskService`](../../src/main/java/com/a09/tts/task/MediaTaskService.java)、[`TaskWorkDispatcher`](../../src/main/java/com/a09/tts/task/TaskWorkDispatcher.java)、[`TaskController`](../../src/main/java/com/a09/tts/controller/TaskController.java)。

**边界：** 不同业务任务会跳过某些外部步骤；这是一条代表性重任务链路。

## 二、异步任务与一致性

### 4. 为什么不能直接在 HTTP 请求线程里处理视频？

**标准回答：** 视频处理可能持续数秒到数分钟，并依赖模型、CPU 和大文件。同步执行会长期占用 Tomcat 线程和连接，客户端断开后状态也难管理。异步任务让 HTTP 快速返回 taskId，把进度、失败、取消、超时和结果变成可查询的持久状态。

**项目落点与证据：** [`AsyncTaskService.submit`](../../src/main/java/com/a09/tts/task/AsyncTaskService.java)、[`TaskStatus`](../../src/main/java/com/a09/tts/task/TaskStatus.java)。

**边界：** 异步只改变执行模型，不会自动提高 GPT-SoVITS、FunASR 或 FFmpeg 的物理吞吐。

### 5. 多个 worker 如何避免领取同一任务？

**标准回答：** `JdbcTaskRepository.claimNext` 使用一条条件 `UPDATE`，只从 `PENDING` 且已到 available time 的记录中选择一条，原子地改为 `RUNNING` 并写 workerId、attempts 和 heartbeat。更新行数为 1 才代表领取成功，避免 `SELECT → process` 的竞争窗口。

**项目落点与证据：** [`JdbcTaskRepository`](../../src/main/java/com/a09/tts/task/JdbcTaskRepository.java)、[`AsyncTaskMySqlIntegrationTest`](../../src/test/java/com/a09/tts/task/AsyncTaskMySqlIntegrationTest.java)、[Phase 16.7](../performance/PHASE16_MULTI_INSTANCE.md)。

**边界：** 证明的是数据库任务记录的唯一 claim，不等于外部服务副作用绝对只发生一次。

### 6. worker 执行到一半崩溃怎么办？

**标准回答：** worker 执行期间定时更新 heartbeat。恢复线程查找早于 stale threshold 的 `RUNNING` 任务：attempts 未耗尽则重新排队，耗尽则置 `FAILED` 并释放用户 slot。Phase 16 故障实验中，任务从 `RUNNING/attempts=1` 在另一实例恢复为 `SUCCESS/attempts=2`。

**项目落点与证据：** `AsyncTaskService.executeClaimed/recoverStaleSafely`、`JdbcTaskRepository.recoverStale`、[故障注入报告](../performance/PHASE16_FAILURE_INJECTION.md)。

**边界：** 14.563 秒是一次已测恢复结果，不是恢复时间 p95 或 SLA。

### 7. 项目如何做任务幂等？

**标准回答：** 提交时使用 owner、任务类型和 deduplication key 约束活跃任务，重复提交返回已有 taskId；数据库迁移建立 active dedup 唯一索引。执行和终态提交还使用 worker owner 条件，清理操作按 key 幂等。

**项目落点与证据：** [`V6__atomic_async_task_state.sql`](../../src/main/resources/db/migration/V6__atomic_async_task_state.sql)、`AsyncTaskService.submit`、[多实例报告](../performance/PHASE16_MULTI_INSTANCE.md)。

**边界：** dedup 只覆盖定义好的业务键和活跃状态；外部模型是否天然幂等仍取决于具体调用。

### 8. backpressure 是怎么做的？

**标准回答：** 在任务创建时，repository 同时检查每用户活动任务 slot 和全局活动任务上限。超限不继续入库排队，而是返回明确容量错误、指标和 Retry-After。这样把过载变成可控拒绝，而不是无限队列、长等待或 OOM。

**项目落点与证据：** [`AsyncTaskService.submit`](../../src/main/java/com/a09/tts/task/AsyncTaskService.java)、[`V10__task_admission_backpressure.sql`](../../src/main/resources/db/migration/V10__task_admission_backpressure.sql)、[`application.yml`](../../src/main/resources/application.yml)。

**边界：** 默认全局 200、每用户 2 是保护配置，不是“每秒能处理 200 个任务”的容量证明。

### 9. bulkhead 和线程池有什么区别？

**标准回答：** worker 线程池限制同时执行的任务总数，bulkhead 则按稀缺资源分别限制 TTS、ASR、FFmpeg 和课件处理。即使 worker 还有空闲，如果 ASR permit 用完，该类任务也会有界等待或拒绝，避免一种资源故障拖垮所有任务。

**项目落点与证据：** [`TaskResourceBulkheads`](../../src/main/java/com/a09/tts/task/TaskResourceBulkheads.java)、[`TaskResourceBulkheadsTest`](../../src/test/java/com/a09/tts/task/TaskResourceBulkheadsTest.java)。

**边界：** 当前 permit 是 JVM 内 `Semaphore`；多实例共享一个模型时缺少全局 permit。

### 10. 你能保证 exactly-once 吗？

**标准回答：** 不能笼统保证。项目能证明 MySQL 中的 claim 和终态转换由条件更新保护，重复提交有幂等键；但模型调用、OSS 写入和 MySQL 提交跨三个系统，没有分布式事务。当前通过幂等 key、owner token、未提交结果清理和 cleanup 补偿降低重复副作用。

**项目落点与证据：** `AsyncTaskService.executeClaimed`、`TaskWorkDispatcher.cleanupUncommittedResult`、[`PendingFileCleanupService`](../../src/main/java/com/a09/tts/cleanup/PendingFileCleanupService.java)。

**边界：** 面试中应说“可恢复、数据库原子 claim、最终补偿”，不能说端到端 exactly-once。

### 11. 重试策略如何避免重试风暴？

**标准回答：** 任务记录 attempts/maxAttempts，失败后按 retry base delay 指数退避并受 retry max delay 限制；超出最大次数进入 `FAILED`。外部调用本身还有超时，资源获取也有短超时，避免无限占用。

**项目落点与证据：** `AsyncTaskService.handleFailure/retryDelay`、[`application.yml`](../../src/main/resources/application.yml)。

**边界：** 当前没有证明大规模同时失败时带随机抖动的全局效果，不能声称已解决所有 retry storm。

### 12. 取消和超时如何处理？

**标准回答：** 用户只能取消属于自己的任务。运行任务会通过 repository 条件更新进入 `CANCELLED`，并中断本 JVM 持有的执行线程；每个已领取任务还会调度 deadline，超时后条件写入 `TIMEOUT` 并 interrupt。最终块取消 heartbeat/deadline，清理临时结果。

**项目落点与证据：** `AsyncTaskService.cancel/executeClaimed`、[`TaskController`](../../src/main/java/com/a09/tts/controller/TaskController.java)。

**边界：** Java interrupt 是协作式取消，外部 SDK/进程是否立即停止还取决于适配器的超时和中断响应。

### 13. 为什么用 MySQL 做任务队列，而不直接上 Kafka/RabbitMQ？

**标准回答：** 当前任务状态、幂等、用户 slot、全局 admission 和查询本来就在 MySQL。数据库原子更新能以较少组件解决持久化、领取和恢复，适合现阶段规模。引入 MQ 会增加投递一致性、消费幂等、部署和运维复杂度，应由高到达率或数据库队列瓶颈证据驱动。

**项目落点与证据：** `JdbcTaskRepository`、V3/V6/V8/V10 Flyway 迁移、[最终容量报告](../scalability/FINAL_SCALABILITY_REPORT.md)。

**边界：** 项目当前没有 MQ，简历不能写 Kafka/RabbitMQ。

## 三、认证、缓存与安全

### 14. JWT 为什么支持多实例？

**标准回答：** token 自包含用户身份和签名，不把登录会话只放在签发节点内存；多个实例使用一致的签名 secret 和校验逻辑即可验证。Phase 16 验证了 backend-1 签发 token 后直接访问 backend-2，以及 backend-1 下线后旧 token 经 Nginx 20/20 成功。

**项目落点与证据：** JWT 拦截配置、[`UserController`](../../src/main/java/com/a09/tts/controller/UserController.java)、[多实例报告](../performance/PHASE16_MULTI_INSTANCE.md)。

**边界：** 目标云的密钥分发和轮换尚未验证；JWT 也不让 WebSocket TCP 连接自动迁移。

### 15. Redis Lua 限流为什么比多条命令好？

**标准回答：** 固定窗口需要计数、首次设置 TTL、读取 TTL 和判断上限。如果客户端分多条命令执行，多实例并发时可能出现计数已有但 TTL 未设置等中间状态。Lua 脚本在 Redis 单线程执行模型中原子完成这些步骤并返回允许标志、计数和 TTL。

**项目落点与证据：** [`RedisConfig.fixedWindowRateLimitScript`](../../src/main/java/com/a09/tts/config/RedisConfig.java)、[`RedisRateLimitIntegrationTest`](../../src/test/java/com/a09/tts/security/RedisRateLimitIntegrationTest.java)。

**边界：** Redis 不可用时的进程内 fallback 只提供单实例降级保护，不是分布式一致限流。

### 16. 登录为什么慢？你是怎么优化的？

**标准回答：** 我先对 rate-limit、Redis、DB、BCrypt、JWT 和 total 加 Micrometer Timer。结果显示 BCrypt 占平均登录时间约 99.18%–99.47%，数据库约 0.82–1.50 ms、Redis约 1.75–2.07 ms、JWT约 0.07–0.33 ms。没有发现数百毫秒的重复查询或锁问题，因此不降低 BCrypt cost 12，而是建立认证专用 SLO。

**项目落点与证据：** [`LoginPerformanceMetrics`](../../src/main/java/com/a09/tts/observability/LoginPerformanceMetrics.java)、[`UserServiceImpl`](../../src/main/java/com/a09/tts/service/impl/UserServiceImpl.java)、[登录报告](../performance/PHASE16_LOGIN_PROFILE.md)。

**边界：** 这是 profiling 与合理 SLO，不应描述为把登录耗时“优化了 99%”。

### 17. 上传安全为什么不能只看文件后缀？

**标准回答：** 后缀和客户端 MIME 都可伪造。项目还检查允许 MIME、magic/header、媒体解码、文件大小和用户配额；文件名由服务端生成，并做重复 URL decode、路径规范化、root/real path 边界和 owner 隔离。

**项目落点与证据：** [`UploadSecurityService`](../../src/main/java/com/a09/tts/security/UploadSecurityService.java)、[`UploadSecurityServiceTest`](../../src/test/java/com/a09/tts/security/UploadSecurityServiceTest.java)。

**边界：** 这些措施降低常见上传风险，不等于替代杀毒、沙箱或内容安全平台。

## 四、存储与媒体

### 18. 为什么要做 local/OSS 存储抽象？

**标准回答：** local 方便单节点开发，但多实例永久文件存在“谁处理、谁能读”的节点耦合。统一 `ObjectStorageService` 让业务只使用 object key，`ManagedObjectStorageService` 再用数据库元数据校验 owner；生产样式拓扑选择 OSS，共享结果即可跨节点下载。

**项目落点与证据：** [`ObjectStorageService`](../../src/main/java/com/a09/tts/storage/ObjectStorageService.java)、[`ManagedObjectStorageService`](../../src/main/java/com/a09/tts/storage/ManagedObjectStorageService.java)、[多实例报告](../performance/PHASE16_MULTI_INSTANCE.md)。

**边界：** local storage 仍是 `PARTIAL`，不能用于多实例永久产物。

### 19. 如何避免大文件导致 JVM OOM？

**标准回答：** 上传和对象读写使用 `InputStream`/`Files.copy`，下载使用 `InputStreamResource` 或 `StreamingResponseBody`；FFmpeg 通过受控临时文件路径处理，不把完整视频装进 `byte[]`。独立 `-Xmx96m` 验证中，100/500 MiB 媒体搬运通过且 GC 增量为 0。

**项目落点与证据：** `LocalObjectStorageService`、`AliyunOssObjectStorageService.open`、`TaskController.result`、[媒体流式化报告](../performance/PHASE16_MEDIA_STREAMING.md)。

**边界：** 文本类小结果仍可能使用 byte 数组；该证据不等于 500 MiB FFmpeg 转码。

### 20. OSS 是怎么验证的？

**标准回答：** 使用真实阿里云 OSS 做 10、100、500 MiB 串行上传、下载、SHA-256 完整性和删除；还做了 4×10 MiB 并发功能检查，以及缺失对象、上传中断和错误凭证的有界失败。500 MiB 上传约 51.429 秒、下载约 24.661 秒，这是当时本机公网路径结果。

**项目落点与证据：** [`AliyunOssObjectStorageService`](../../src/main/java/com/a09/tts/storage/AliyunOssObjectStorageService.java)、[OSS 报告](../performance/PHASE16_OSS.md)。

**边界：** 4/4 成功不建立 safe concurrency；公网结果不能外推同区域云带宽或 SLA。真实长期凭证还必须轮换并改用 RAM/STS。

### 21. 完整视频换声链路有哪些步骤？

**标准回答：** 认证上传 → OSS → durable MySQL task → FFmpeg 提取音频 → FunASR 转写 → 使用识别或修订文本 → GPT-SoVITS 合成 → FFmpeg 混流和可选字幕 → OSS 结果 → 流式下载。临时输入和输出在 `finally` 清理。

**项目落点与证据：** [`VideoVoiceSwapServiceImpl`](../../src/main/java/com/a09/tts/service/impl/VideoVoiceSwapServiceImpl.java)、`TaskWorkDispatcher.executeVideoSwap`、[视频报告](../performance/PHASE16_VIDEO_PIPELINE.md)。

**边界：** 仅 small 5.291 秒、medium 10 秒两条串行样本成功，pipeline 状态仍为 `PARTIAL`。

## 五、性能、容量与可观测性

### 22. GPT-SoVITS 和 FunASR 的 safe concurrency 为什么都是 1？

**标准回答：** safe concurrency 不是“能并发成功的最大数字”，而是继续增加并发时吞吐收益与延迟/资源代价的平衡点。GPT-SoVITS 增加并发没有吞吐收益、延迟近似随排队增加；FunASR 从 1 到 2 吞吐只增 3.64%，p95 增 75.09%，所以当前固定输入和机器上都取 1。

**项目落点与证据：** [Phase 12 重任务](../performance/PHASE12_HEAVY_TASKS.md)、[FunASR 报告](../performance/PHASE16_FUNASR.md)。

**边界：** 该安全点依赖输入、模型、硬件和部署拓扑，换环境必须重测。

### 23. FFmpeg 为什么定为整机并发 2？

**标准回答：** 固定 30 秒输入逐档测试 1/2/4/8。并发 2 时约 1.2428 job/s、CPU p95 88.61%；再到 4 只增加约 5.01% 吞吐且 CPU 已接近饱和，因此取整机 2，避免用高并发换排队和资源抖动。

**项目落点与证据：** [Phase 12 重任务报告](../performance/PHASE12_HEAVY_TASKS.md)、`TaskResourceBulkheads`。

**边界：** “整机 2”不能解释为两个 backend 各自 2；输入编码、分辨率或云 CPU 变化后需重测。

### 24. 200 VU、61.85 req/s 到底说明什么？

**标准回答：** 它说明在同一已测 Windows 主机、双 Spring Boot、共享 MySQL/Redis/OSS、Nginx 和指定 closed-model mixed journey 下，200 VU 的 60 秒 steady 完成 3,711 次请求、0 业务错误，普通 API 最差 p95 50.50 ms。VU 包含 think time，不是同一时刻 200 个请求到达。

**项目落点与证据：** [多实例报告](../performance/PHASE16_MULTI_INSTANCE.md)及对应 aggregate JSON。

**边界：** 不能写成系统最大 QPS、200 个重任务并发、1,000 用户容量或云 SLA。

### 25. 为什么登录和普通 API 使用不同 SLO？

**标准回答：** 普通 API 主要是网络、序列化和数据库访问，登录还包含故意昂贵的 BCrypt。统一 p95 300 ms 会诱导降低密码安全参数。项目保留旧阈值失败事实，同时为认证采用 p95<750 ms、p99<1,000 ms、error<1% 的专用 SLO。

**项目落点与证据：** [登录报告](../performance/PHASE16_LOGIN_PROFILE.md)、[最终报告第 4 节](../scalability/FINAL_SCALABILITY_REPORT.md)。

**边界：** SLO 是本项目当前安全与体验取舍，不是行业通用标准。

### 26. 30 分钟 soak 得出了什么结论？

**标准回答：** 100 VU steady 完成 49,983 次 L0 请求和 6 次串行真实 TTS，0 业务错误；heap、连接、Redis key、executor queue 和进程 RSS没有持续增长。live threads 从 198 阶跃到 230 后形成平台，没有继续增长。

**项目落点与证据：** [Soak 报告](../performance/PHASE16_SOAK.md)及 aggregate evidence。

**边界：** 只跑一次 30 分钟，线程阶跃未用 thread dump 定因，ASR/FFmpeg/video/OSS 数据流和 WebSocket 未进入 workload，因此状态是 `PARTIAL`。

### 27. 故障注入覆盖了哪些场景？

**标准回答：** 顺序验证了 Redis restart、MySQL restart、GPT-SoVITS 不可用、FunASR 不可用、单 Nginx upstream 丢失和 worker/backend crash。对应恢复结果包括 Redis 8.079 秒、MySQL 1.875 秒、TTS 故障 0.031 秒返回 503、worker crash 后 14.563 秒恢复任务。

**项目落点与证据：** [故障注入报告](../performance/PHASE16_FAILURE_INJECTION.md)。

**边界：** 每类只有少量样本且没有与 100/200 VU 同时运行，不能给恢复 p95/p99，也不等于 HA。

### 28. 项目如何做可观测性？

**标准回答：** Actuator 暴露 health、liveness/readiness 和 metrics，Micrometer记录登录分段、任务 admission rejection、executor 和外部依赖指标，Prometheus负责采集。容量与 soak 同时采集 JVM heap/threads/GC、Hikari、Redis、队列、进程 RSS 和主机资源，用于解释瓶颈而不是只看 HTTP 平均值。

**项目落点与证据：** [`pom.xml`](../../pom.xml)、`observability` 包、[`application.yml`](../../src/main/resources/application.yml)、[Soak 报告](../performance/PHASE16_SOAK.md)。

**边界：** 有指标不等于已经配置生产告警、值班闭环或长期基线；这些仍属于 cloud 阻断项。

### 29. Flyway 在项目里解决什么问题？

**标准回答：** Flyway 把 async task、对象元数据、cleanup、索引和 admission 等 schema 变化按 V1–V12 版本化，应用启动时能校验和迁移，避免手工改库导致环境漂移。集成测试验证空 MySQL schema 可迁移并支持核心流程。

**项目落点与证据：** [`db/migration`](../../src/main/resources/db/migration/)、[`FlywayMySqlIntegrationTest`](../../src/test/java/com/a09/tts/FlywayMySqlIntegrationTest.java)、[`pom.xml`](../../pom.xml)。

**边界：** Flyway 管 schema，不等于数据库备份恢复；已有业务库迁移前仍需备份，不能靠清库解决 checksum 或漂移。

### 30. 为什么最终评级是 READY_WITH_LIMITS？下一步最重要的是什么？

**标准回答：** 核心双实例 HTTP、登录、JWT、任务 claim、cleanup、真实 OSS、GPT-SoVITS、FunASR 和 FFmpeg 有直接证据，所以不是 `NOT_READY`；但 MySQL/Redis/Nginx HA、WebSocket、负载中故障、60 分钟重复媒体 soak 和目标云均未完成，cloud 为 `BLOCKED`，所以不能评 `READY`。下一步若真要上线，优先轮换已暴露凭证、实现共享模型跨实例全局准入，并在明确云拓扑上重做容量、HA、故障和 soak。

**项目落点与证据：** [生产就绪矩阵](../scalability/PRODUCTION_READINESS_MATRIX.md)、[最终容量报告](../scalability/FINAL_SCALABILITY_REPORT.md)。

**边界：** 当前任务已停止生产架构扩展；这里是风险排序，不代表这些工作已经完成。

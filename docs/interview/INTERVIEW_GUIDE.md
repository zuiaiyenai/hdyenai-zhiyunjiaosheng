# 智韵教声 Java 后端面试讲解稿

## 1. 一句话定位

> 智韵教声是一个 AI 语音教学场景下的 Java 后端工程化项目：Spring Boot 负责用户、权限、任务、存储和业务状态，GPT-SoVITS、FunASR、Kimi、NLS 与 FFmpeg 作为外部能力接入；项目重点是高可靠异步任务、多实例正确性、资源隔离和证据化容量验证。

## 2. 1 分钟自我讲解稿

> 我在这个项目里主要做 Java 后端工程化，不把自己包装成语音算法工程师。业务包括 TTS、ASR、声音复刻、课件和视频处理，难点是这些操作时间长、占 CPU/GPU、会产生大文件，而且依赖外部服务。
>
> 我把原来偏单实例演示的处理方式改造成 MySQL 持久异步任务：请求入库后立即返回 taskId，worker 用数据库原子更新领取任务，执行期间维护 heartbeat，支持超时、重试、取消和 stale recovery。多实例共享 MySQL、Redis 和 OSS，JWT 不绑定节点；TTS、ASR、FFmpeg 使用独立 bulkhead，入口还有全局和每用户背压。
>
> 我没有只看测试是否通过，而是做了真实容量和故障验证。当前证据覆盖双实例 200 VU mixed HTTP、登录 BCrypt profiling、真实 GPT-SoVITS/FunASR/OSS/FFmpeg、两条完整视频链路和一次 30 分钟 mixed soak。最终评级是 `READY_WITH_LIMITS`，因为云环境、基础设施 HA、WebSocket 容量和长时媒体 soak 仍未完成。

## 3. 5 分钟项目讲解稿

### 第一部分：业务和技术矛盾

> 项目的用户是教师、学生和内容创作者，主要流程包括登录、上传音视频或 PPT、调用模型和 FFmpeg、轮询任务状态、播放或下载结果。这里真正的后端矛盾不是“接口能不能调通”，而是外部模型慢、大文件容易占 heap、节点重启会丢进程内任务、多 worker 可能重复处理，并发增加时模型和数据库先成为瓶颈。

### 第二部分：总体架构

> 我选择模块化单体而不是微服务。Spring MVC 承担 REST 和文件接口，WebSocket 承担流式 ASR；MySQL 保存长期业务事实和任务状态，Redis 做缓存与 Lua 原子限流，OSS 保存多实例共享的永久对象，本地目录只放临时副本。AI 模型和 FFmpeg 通过适配层调用，业务状态始终由 Java 后端管理。

### 第三部分：异步任务与正确性

> 耗时请求先流式保存输入，然后向 `async_task` 创建 `PENDING` 任务。创建过程同时检查幂等键、每用户活动任务数和全局活动任务数，超限直接有界拒绝。worker 用单条条件 UPDATE 原子把一条任务改成 `RUNNING`，写入 workerId、attempts 和 heartbeat。执行中定期续心跳，完成时只有持有 owner token 的 worker 能提交终态；崩溃后另一实例把 stale 任务重新排队。
>
> 这解决的是数据库状态的 at-most-one claim 和可恢复终态。我会明确说，外部模型、OSS 和 MySQL 之间没有分布式事务，所以不能宣称业务副作用 exactly-once；项目通过幂等 key、结果清理和补偿队列降低重复副作用风险。

### 第四部分：资源与文件保护

> 任务入口有背压，执行阶段还有 TTS、ASR、FFmpeg、courseware 独立的 Semaphore bulkhead。这样某一种重任务过载时会快速失败或等待有限时间，不会无限占满 worker。文件链路通过扩展名、MIME、magic、解码、大小、配额、规范化路径和 owner 检查；对象上传下载使用流，不把大视频读进 byte 数组。100 和 500 MiB 搬运在 `-Xmx96m` 下通过，说明 heap 不随文件大小近似线性增加。

### 第五部分：性能验证与取舍

> 我把普通 API、登录和重任务分开定标。双实例 mixed HTTP 在 200 VU 下完成 3,711 次请求、61.85 req/s、0 业务错误，普通 API 最差 p95 50.50 毫秒。登录 profiling 表明 BCrypt 占平均耗时约 99%，所以保留 cost 12，使用认证专用 p95 750 毫秒、p99 1,000 毫秒 SLO；四档周期负载共 7,083 次登录无错误。重任务的安全点是 GPT-SoVITS 1、FunASR 1、FFmpeg 整机 2，它们不能和 HTTP VU 混成一个“系统并发”。

### 第六部分：故障与最终边界

> Redis、MySQL、真实模型、单 upstream 和 worker crash 都做过故障恢复。worker 在 `RUNNING/attempts=1` 时被杀，另一实例 14.563 秒后恢复为 `SUCCESS/attempts=2`。30 分钟 soak 完成 49,983 次 L0 请求和 6 次真实 TTS，未观察到 heap、连接、Redis key 或队列持续增长，但线程出现有界阶跃且没有 thread dump 定因。
>
> 所以最终是 10 项 `VERIFIED`、8 项 `PARTIAL`、cloud 1 项 `BLOCKED`，评级 `READY_WITH_LIMITS`。我认为这个结果比直接写 READY 更能体现后端工程判断：知道代码实现了什么，也知道证据没有证明什么。

## 4. 按“基础—项目—原理—深入—面试”理解核心设计

### 4.1 持久异步任务

**基础：** HTTP 线程适合短请求。长任务如果同步执行，会占用连接和线程，客户端断开也难以表达任务状态。

**项目：** Controller/`MediaTaskService` 提交任务，`AsyncTaskService` 管理生命周期，`JdbcTaskRepository` 持久化和原子 claim，`TaskWorkDispatcher` 分派实际业务。

**原理：** 任务是数据库中的状态机；所有关键转换都带旧状态或 owner 条件，避免两个 worker 同时成功提交。

**深入：** heartbeat 是租约信号而不是锁。stale recovery 必须结合 attempts 和幂等清理，否则崩溃重试可能永久循环或遗留对象。

**面试表达：** “我没有说任务 exactly-once；我证明的是数据库 claim 唯一、终态有条件、崩溃后可恢复。”

### 4.2 背压与 bulkhead

**基础：** 线程池和队列不是越大越好；无界队列只会把过载变成更长等待和 OOM。

**项目：** 提交阶段限制每用户和全局活动任务；执行阶段为 TTS、ASR、FFmpeg、courseware 分配独立 permit。

**原理：** backpressure 在入口拒绝超过系统承载能力的工作，bulkhead 隔离不同资源，避免级联故障。

**深入：** 单 JVM `Semaphore` 在多实例下会按实例数放大，因此共享模型需要数据库/Redis lease 或独立调度层形成全局准入；当前尚未实现。

**面试表达：** “配置的队列上限是保护参数，不是已经证明的吞吐量。”

### 4.3 登录安全与性能

**基础：** BCrypt 故意消耗 CPU，降低密码爆破速度；普通 CRUD 的延迟目标不能直接套在密码校验上。

**项目：** 登录链路对 rate-limit、Redis、DB、BCrypt、JWT 和 total 分段计时；密码编码器使用 cost 12。

**原理：** profiling 先确认时间花在哪里，再决定优化。数据库和 Redis 只有毫秒级，降低 BCrypt 才能明显变快，但会降低安全性。

**深入：** 当前 workload 是闭环周期登录，最高约 6.55 login/s，不能代表同秒突发；更高 arrival rate 需要单独 burst 测试和 CPU 预算。

**面试表达：** “我接受了符合安全目标的认证 SLO，没有用伪优化换漂亮指标。”

### 4.4 对象存储和流式 I/O

**基础：** 把 500 MiB 文件读成 `byte[]` 会直接挤压 heap；多实例节点本地永久文件互相不可见。

**项目：** `ManagedObjectStorageService` 管理 owner 元数据，Local/OSS adapter 提供流式 `store/open/delete`，任务下载使用 `InputStreamResource`。

**原理：** 文件内容走流，业务归属走数据库元数据；临时文件适配 FFmpeg，永久结果写共享存储。

**深入：** MySQL 元数据和 OSS 不是一个事务，通过幂等 delete、未提交结果清理和 pending cleanup 补偿；不能说分布式事务已经解决。

**面试表达：** “500 MiB 证据证明媒体搬运的 heap 边界，不证明 500 MiB 转码性能。”

### 4.5 多实例与故障恢复

**基础：** 横向扩展要求节点不保存必须粘住的会话和永久文件。

**项目：** JWT 可跨节点，MySQL/Redis/OSS 共享，Nginx 分流；worker crash 后另一节点根据 stale heartbeat 恢复。

**原理：** shared-nothing API 加共享事实源可以扩普通请求，但共享数据库、缓存、模型和主机仍是容量/可用性边界。

**深入：** 单 backend 下线通过不等于 Nginx HA；MySQL/Redis restart 通过不等于主从、哨兵、RPO/RTO 达标。

**面试表达：** “多实例正确性已验证，云与基础设施高可用仍是 `PARTIAL/BLOCKED`。”

## 5. 项目亮点怎么讲

### 亮点一：从“线程里跑任务”升级为数据库事实源

先说问题：进程内任务遇到重启会丢失，多个实例不知道谁负责。再说方案：MySQL 状态机、原子 claim、heartbeat、retry、stale recovery。最后说证据：双 worker 只有一次 claim，worker crash 后另一实例完成；边界是外部副作用非 exactly-once。

### 亮点二：容量数字按工作类型拆开

普通 API 用 VU/RPS/p95，登录用 login/s 和 BCrypt 分段，重任务用 jobs/s、延迟、CPU/RAM 和 safe concurrency，soak 看资源趋势。这样避免用一个总 QPS 覆盖完全不同的瓶颈。

### 亮点三：文件安全不只是校验后缀

从服务端 owner、UUID key、路径规范化、root/real path 边界，到 MIME、magic、解码和配额，再到对象元数据校验和流式响应，形成上传—保存—处理—下载完整边界。

### 亮点四：结论可追溯且不过度承诺

每一项生产能力只有 `VERIFIED/PARTIAL/BLOCKED/FAILED`，报告保留旧登录阈值失败和线程阶跃，不用“测试很多”替代关键路径证明。

## 6. 项目难点怎么讲

### 难点一：原子领取和崩溃恢复同时成立

只做 `SELECT` 再处理会重复领取；只加锁又可能在 worker 崩溃后永久占用。最终用原子条件更新取得 owner，再用 heartbeat 和 stale 时间回收，并让 attempts 决定重排还是失败。

### 难点二：多实例共享状态与节点本地工具的冲突

永久产物必须在 OSS，FFmpeg 却需要本地路径。解决办法是对象流下载为临时副本、处理完流式写回、`finally` 清理，同时用数据库元数据保证 owner。

### 难点三：安全和性能的真实取舍

登录 p95 超过普通 API 阈值时，最容易做的是降低 BCrypt cost。profiling 证明瓶颈后，项目选择保留安全参数，单独定义认证 SLO，并明确未测 burst。

### 难点四：把“能跑”变成“知道边界”

外部模型和 FFmpeg 的并发增加未必提高吞吐，可能只增加排队和资源占用。必须固定输入、逐档提高并发、同时看吞吐、p95、错误和 CPU/RAM，选择安全点而不是最大数字。

## 7. 生产问题及解决方案

| 生产问题 | 根因 | 当前解决方案 | 如何验证 | 剩余风险 |
| --- | --- | --- | --- | --- |
| 任务重启丢失或永久 RUNNING | 状态只在 JVM、无租约恢复 | MySQL 持久任务、heartbeat、stale recovery | worker crash 后另一实例 14.563 秒完成 | 外部副作用非 exactly-once |
| cleanup 被两个 worker 重复领取 | `SELECT → process` 竞争窗口 | 原子 claim、claimed_by/claimed_at、owner 条件完成 | 双 worker 集成与 live 验证只有一次 claim | 大积压和 OSS 故障吞吐未测 |
| 大文件导致 heap 随大小增长 | 完整读取为 `byte[]` | InputStream/Files.copy/Resource/临时路径 | 100/500 MiB 在 `-Xmx96m` 搬运通过 | 真实 500 MiB 转码未测 |
| 登录 p95 不满足普通 API SLO | BCrypt cost 12 占主要耗时 | 分段 profiling，保留 cost，采用认证 SLO | 7,083 次 steady 登录 0 错误 | burst、高 arrival rate 未测 |
| 多实例结果文件不可见 | 永久文件留在节点本地 | local/OSS 抽象，OSS 共享永久对象 | 跨节点任务结果下载通过 | 凭证治理和 OSS 容灾未闭环 |
| 模型/FFmpeg 过载 | 稀缺资源无独立并发上限 | resource bulkhead、超时和有界 worker | TTS=1、ASR=1、FFmpeg 整机=2 | 跨实例全局 permit 缺失 |
| Redis/MySQL/模型不可用造成挂死 | 调用缺少有界失败和恢复协议 | timeout、health/readiness、重试与失败终态 | 六类故障路径有界恢复 | 未在 100/200 VU 负载中注入 |
| 长时运行潜在资源泄漏 | 堆、线程、连接、队列可能积累 | 指标采样与 30 分钟 soak | heap/连接/Redis/queue 无持续增长 | 线程阶跃未定因，未做重复 60 分钟 |

## 8. 面试时必须主动说出的证据边界

- 1,000 是注册账号数据基线，不是并发用户。
- 200 VU mixed HTTP 不等于 200 个媒体任务同时执行。
- 200 VU 登录 workload 约 6.55 login/s，不是 200 人同秒登录。
- OSS 4×10 MiB 并发成功不等于 safe concurrency=4。
- 视频 2/2 成功只证明两个固定样本的 happy path。
- restart recovery 不等于 MySQL、Redis 或 Nginx 高可用。
- 单机双实例不等于云、跨主机、容器或可用区验证。
- 一次 30 分钟 soak 不等于长期无泄漏。
- 当前项目没有 MQ、Kubernetes、微服务或外部副作用 exactly-once，不应在简历中添加。
- AI 模型是外部依赖，个人贡献是 Java 后端集成和工程验证。

## 9. 证据指路

- 架构和状态机：[ARCHITECTURE.md](ARCHITECTURE.md)
- 生产资格：[FINAL_SCALABILITY_REPORT.md](../scalability/FINAL_SCALABILITY_REPORT.md)
- 状态矩阵：[PRODUCTION_READINESS_MATRIX.md](../scalability/PRODUCTION_READINESS_MATRIX.md)
- 核心源码：[`AsyncTaskService`](../../src/main/java/com/a09/tts/task/AsyncTaskService.java)、[`JdbcTaskRepository`](../../src/main/java/com/a09/tts/task/JdbcTaskRepository.java)、[`TaskResourceBulkheads`](../../src/main/java/com/a09/tts/task/TaskResourceBulkheads.java)、[`ManagedObjectStorageService`](../../src/main/java/com/a09/tts/storage/ManagedObjectStorageService.java)、[`UploadSecurityService`](../../src/main/java/com/a09/tts/security/UploadSecurityService.java)
- 数据库迁移：[`db/migration`](../../src/main/resources/db/migration/)
- Phase 16 分项报告：[`docs/performance`](../performance/)

标准回答不要背数字堆砌。推荐顺序始终是：**问题是什么 → 为什么原方案不够 → 我怎么设计 → 如何验证 → 还没有证明什么**。

# 智韵教声容量基线审计

> 审计状态：**Phase 0 COMPLETE / CAPACITY NOT VERIFIED**
>
> 审计基线：`11d75d1 feat(storage): add Aliyun OSS voice storage`
>
> 审计时间：2026-09-12（Asia/Shanghai）

## 1. 结论先行

当前代码已经具备部分生产化基础：MySQL/Flyway 持久化、Redis 缓存与会话实现、JWT、有限队列、任务查询 API、上传限制、外部进程超时、Actuator/Prometheus，以及音色文件的对象存储实现。但它仍然不能被证明支持 10、100 或 1000 用户场景。

最关键的问题不是“线程数不够”，而是业务正确性和资源容量仍有单实例边界：

1. `AsyncTaskService` 的任务去重、每用户并发计数、取消句柄和状态锁均在单个 JVM 内；数据库只保存结果快照，不是可由多个 worker 原子抢占的队列。
2. 课件、视频换声、字幕、语音笔记、口语练习上传等链路仍直接使用本地文件系统；只有音色库的新上传闭环经过 `ObjectStorageService`。
3. 课件创建/优化/音频/视频、视频换声、ASR、GPT-SoVITS 与部分阿里云语音接口仍存在同步长请求；外部服务和 FFmpeg 没有全局或分资源 bulkhead。
4. Nginx 和 Compose 都只配置了一个后端实例；Tomcat、Hikari 的关键容量参数没有形成经过实测的预算。
5. 音色查询、管理员用户查询、语音笔记列表仍可无界返回，课件分页存在逐项目查询 revisions 的 N+1。

因此本阶段的容量结论是：

| 场景 | 当前结论 | 原因 |
|---|---|---|
| Stage A：10 个并发用户 | **NOT VERIFIED** | 当前没有运行中的后端和可复现的 10 用户混合负载结果；少量普通请求可能可用，但一旦混入同步 ASR/TTS/FFmpeg 就可能占满有限资源。 |
| Stage B：100 个并发用户 | **NOT VERIFIED** | 单 JVM 任务正确性、本地磁盘、无外部服务 bulkhead、未定标的 Tomcat/Hikari 均是阻断项。 |
| Stage C：1000 注册/活跃用户、200 并发活跃用户 | **NOT VERIFIED** | 不能把注册用户数等同于并发；当前也没有 200 活跃用户 workload、双实例和重任务安全并发证据。 |

本报告中的“10/100/1000 用户影响”均为基于源码和配置的风险推断，不是压测测量值。Phase 11–14 完成之前不得改写为 `VERIFIED` 或 `PROVEN`。

## 2. 证据边界

### 2.1 证据等级

| 等级 | 本报告中的含义 |
|---|---|
| 源码/配置事实 | 当前 Git `HEAD` 中可以直接定位到文件和行的实现或配置。 |
| 历史测试证据 | 旧报告或本地已有测试产物；只能证明当时、当提交、当环境的有限范围。 |
| 当前运行证据 | 2026-09-12 22:45 +08:00 对本机端口、进程和 HTTP 端点的只读检查。 |
| 真实容量/生产证明 | 必须绑定硬件、部署拓扑、数据规模、脚本、原始结果、Git SHA 和资源指标；当前不存在。 |

### 2.2 当前运行快照

- `netstat` 显示 MySQL `3306` 与 Redis `6379` 正在监听；本阶段未使用业务凭证执行数据库查询，也未把端口监听等同于完整健康。
- `8080`（Nginx）、`8081`（Spring Boot）、`9091`（management）、`9880`（GPT-SoVITS）、`9977`（FunASR）均未监听；对应 HTTP 探测无法连接。
- 当前 shell 找不到 `docker` 与 `nginx.exe`，所以本阶段没有 Compose 或 Nginx 运行时证据。
- 构建工具为 Maven 3.9.9，Maven 使用 Java 17.0.19；系统 `java` 命令为 Java 25.0.3，不能混为同一个运行时。FFmpeg 可执行文件存在。
- 本机 CPU 为 AMD Ryzen 9 7945HX、32 个逻辑处理器；内存总量未能通过当前权限读取。D 盘检查时约有 206 GiB 可用空间。硬件信息不完整，不能作为后续压测报告的最终环境清单。
- 本阶段于 2026-09-12 22:51 +08:00 运行完整 `mvn test`：104 tests、0 failures、0 errors、6 skipped、`BUILD SUCCESS`。它证明当前代码回归通过，不是容量测试；跳过项包含需要独立环境或真实凭证的集成测试。
- 被 Git 忽略的 `config/application-local.yml` 当前选择真实 MySQL、Redis 和 `aliyun-oss` provider；凭证没有被 Git 跟踪。密钥值不进入本报告。由于长期凭证曾在聊天中明文出现，应轮换为最小权限 RAM 凭证。

### 2.3 历史结果不能升级为当前容量结论

`docs/verification/FCTTS_FINAL_PRODUCTION_RELEASE_GATE_2026-09-04.md:27` 记录过一次历史 CI 混合负载：199,851 requests、110.87 req/s、p95 886.14 ms。该结果没有证明当前 `HEAD`、当前本机、双后端、真实 MySQL/Redis 数据规模、GPT-SoVITS/FunASR/FFmpeg 混合资源饱和度，也不满足本轮普通 API p95 < 300 ms 的 SLO。因此只保留为历史回归证据，不作为 Stage A/B/C 的 PASS。

## 3. 当前架构与容量相关组件

| 组件 | 当前行为（源码/配置事实） | 当前容量边界 |
|---|---|---|
| Nginx | `deploy/nginx/conf/nginx.conf:1-7` 使用 1 个 worker、1024 connections；`:60-97` 所有 API/WebSocket 都代理到 `127.0.0.1:8081`；长请求超时为 3600 秒。 | 没有 upstream 池、健康摘除、双实例或按接口超时；慢客户端和长媒体请求可长期占连接。 |
| Spring Boot / Tomcat | `src/main/resources/application.yml:38-39` 只显式配置端口。 | 未显式预算 `threads.max`、`max-connections`、`accept-count`、keep-alive；有效运行值和饱和点未测。 |
| HikariCP | `application.yml:14-20` 配置数据源和 3 秒 connection timeout。 | 未显式配置 maximum pool size、minimum idle、max lifetime、leak detection；有效池大小和等待分布未测。 |
| MySQL / Flyway | V1–V5 保存用户、音色、口语历史、课件、异步任务和待清理记录。 | `async_task` 不是可抢占 worker 队列；部分实际查询与索引不匹配；没有本轮 EXPLAIN/数据规模证据。 |
| Redis | `application.yml:7-13` 有 1/2 秒连接与命令超时；`RedisConfig.java:58-91` 提供缓存与对话会话 Lua；登录限流可使用 Redis。 | 默认 `app.redis.enabled=false`；Redis 故障时登录限流退化为各 JVM 独立 Map；没有 endpoint/task quota 和 hit/miss/reject 指标。 |
| Spring Cache | `VoiceServiceImpl.java:49-61,90-100,136-138` 缓存音色列表和详情；Redis TTL 为 10/30 分钟。 | 列表缓存的是无界结果；`allEntries=true` 是粗粒度失效；当前没有命中率和缓存击穿证据。 |
| async executor | `AsyncTaskService.java:53-85` 配置 core=2、max=4、queue=20、timeout=15m，并暴露 executor metrics。 | 所有任务类型共用一个池；去重、配额、锁、Future 都是进程内状态；不是多 worker 模型。 |
| task persistence | `JdbcTaskRepository.java:27-76` 通过 upsert 保存任务快照；启动时把所有 PENDING/RUNNING 标记失败。 | 没有 payload、attempts、available_at、heartbeat、worker_id、version、原子 claim、重试和 stale recovery。 |
| JVM 本地状态 | 任务、课件状态、WebSocket client、口语趋势、Redis fallback 限流，以及 nodb repositories 使用 Map。 | 多实例之间不共享，重启丢失或产生不同视图；部分直接参与业务正确性。 |
| 文件存储 | `ObjectStorageService` 已覆盖新音色上传/试听/删除；OSS client 有连接/读取/请求超时和最大连接数。 | 课件、视频、字幕、笔记、口语、ASR 临时文件仍直接使用 `Path/Files`；默认 provider 仍是 local。 |
| GPT-SoVITS | `TTSServiceImpl.java:54-120` 同步 block 最长 10 分钟，非流式结果聚合为 `byte[]`；最大内存 codec 为 50 MiB。 | 没有并发信号量、队列、GPU 容量或熔断；参考音色仍依赖节点本地路径。 |
| FunASR | `ASRServiceImpl.java:48-78` 从本地文件同步 multipart 调用，RestTemplate connect/read timeout 为 30/60 秒。 | 没有并发信号量、队列或异步任务；请求线程和本地临时文件生命周期耦合。 |
| FFmpeg / ffprobe | `ExternalProcessRunner.java:31-84` 有 10 分钟默认超时、强制终止和 1 MiB 输出上限。 | 每次调用新建进程和 reader thread；没有 CPU/RAM bulkhead 或全局进程上限。 |
| WebSocket | `StreamingAsrWebSocketHandler.java:25-235` 每连接在 JVM Map 保存会话，5 秒鉴权，1 MiB send buffer；ASR scheduler pool=1。 | 连接不需要跨节点迁移，但负载均衡行为、节点下线、连接上限与 ASR 外部并发未定义。 |
| HTTP streaming | GPT-SoVITS、阿里云 TTS 和声音克隆使用 `StreamingResponseBody`/流式写出。 | Nginx 1 小时 timeout；没有每接口并发限制、慢客户端预算、断连指标或验证过的线程模型。 |
| file upload/download | Servlet/Nginx 总请求上限分别为 50/60 MiB；按类型限制、MIME/magic 校验和 500 MiB 用户配额已经存在。 | 本地配额通过 `Files.walk` 全目录扫描；并发上传的 check-then-write 不原子；部分下载将整个视频读入堆。 |
| observability | Actuator 暴露 health/info/prometheus；HTTP histogram/SLO buckets、外部服务 up gauge、共享任务 executor metrics 已配置。 | 没有自定义 cache hit/miss、rate-limit reject、task lifecycle、每资源 executor/bulkhead、WebSocket、上传字节和外部调用 latency 指标。 |

## 4. 风险清单

严重度定义：`CRITICAL` 会阻止多实例正确性或可导致明显资源失控；`HIGH` 会阻止 100/200 活跃用户容量验收；`MEDIUM` 需要在压测前后治理或量化；`LOW` 是当前未形成主要瓶颈但应固定不变量。影响列均为架构推断。

| ID / 风险类别 | current behavior | bottleneck | 10-user impact | 100-user impact | 1000-user impact（按 200 active 解释） | severity | recommended solution |
|---|---|---|---|---|---|---|---|
| CAP-001 单实例入口 | Nginx 只有 `127.0.0.1:8081` 一个 upstream，Compose 也只有一个 backend service（`nginx.conf:60-97`; `docker-compose.yml:40-89`）。 | 无法负载均衡或摘除故障节点。 | 普通流量可能可用，但一次后端重启即整体中断。 | 单节点 CPU/heap/Tomcat 任一饱和即全站抖动。 | 200 active 没有横向扩容和故障转移路径。 | CRITICAL | Phase 10 配置 backend-1/backend-2 upstream、健康检查与失败切换，并做双实例验证。 |
| CAP-002 Tomcat 容量未预算 | 只设置 server port，未设置线程、连接、accept queue（`application.yml:38-39`）。 | 实际默认值与长请求占用共同决定排队，但当前不可见、未定标。 | 混入少量长请求即可放大尾延迟。 | 可能线程耗尽或 accept queue 拒绝。 | 无法从注册用户数推导安全 active users。 | HIGH | 先按 workload 测 Tomcat busy/current/max、connections 和 rejected，再设置有依据的边界；不得仅调大线程池。 |
| CAP-003 Hikari 容量未预算 | 只配置 3 秒 connection timeout（`application.yml:14-20`）。 | 池大小、MySQL max connections 与 Tomcat 并发没有联合预算。 | 短查询大概率可用，但尚无并发证据。 | DB 慢查询会导致连接等待和请求堆积。 | 多实例会把总连接数乘以实例数，可能打满 MySQL。 | HIGH | Phase 1 建连接预算；Phase 7 用真实 SQL/EXPLAIN 与 metrics 定标；Phase 10 校验两实例总池。 |
| CAP-004 默认 nodb / 进程内仓库 | 默认 profile 是 `nodb`（`application.yml:4-5`）；用户、音色、任务、课件、清理队列由内存实现接管。 | 数据随重启丢失，节点间不一致。 | 演示可用，重启会丢状态。 | 多节点产生彼此独立的数据世界。 | 完全不适合作为 200 active 场景。 | HIGH | 生产/压测显式使用 DB profile，并在启动时 fail-fast；nodb 仅保留开发演示。 |
| CAP-005 JVM 任务正确性状态 | `futures/timeouts/reservations/activeByOwner/activeDeduplication` 均为 `ConcurrentHashMap`（`AsyncTaskService.java:43-47`）。 | 去重、配额、取消和 active 数只在单 JVM 有效。 | 单实例内有基本保护。 | 两实例可对同一用户/幂等键重复执行。 | 重任务可被重复放大，突破用户和全局容量。 | CRITICAL | Phase 2/5 将正确性迁移到 MySQL 原子状态转换、唯一约束/version CAS；Map 只允许做非正确性缓存。 |
| CAP-006 本地锁代替分布式原子转换 | 任务状态通过 64 个对象锁和 `synchronized(lock(id))` 串行更新（`AsyncTaskService.java:169-224,259-261`）。 | 锁只保护本 JVM；DB upsert 没有 compare-and-set。 | 单实例竞争较低时可工作。 | 同节点 hash 碰撞产生无关任务串行；跨节点不互斥。 | worker 扩容后会发生重复 claim/覆盖终态。 | CRITICAL | 用 `UPDATE ... WHERE status='PENDING' AND version=?` 等原子 SQL claim/transition，并增加并发集成测试。 |
| CAP-007 数据库记录不是 worker queue | `async_task` 仅保存 11 个快照字段；动作是提交时捕获的 Java lambda；启动即把 PENDING/RUNNING 全部失败（V3; `JdbcTaskRepository.java:64-69`）。 | 任务不可由另一 worker 恢复、重试或接管。 | 重启会中断任务。 | 发布/故障期间大量任务失败。 | 无法支撑独立 worker 或水平扩展。 | CRITICAL | Phase 5 增加 payload、attempts、max_attempts、available_at、heartbeat_at、worker_id、version、error_code，并实现 claim/retry/stale recovery/idempotency。 |
| CAP-008 所有重任务共享一个池 | 课件优化/TTS/FFmpeg 共用 core 2、max 4、queue 20 的 `AsyncTaskService`（`CoursewareProjectController.java:94-126`; `application.yml:53-58`）。 | 一个慢 GPU/FFmpeg 类型会 head-of-line block 其他类型。 | 2–4 个重任务已可能排队。 | 队列很快满并返回 429。 | 无资源隔离时无法给普通 API 和各重任务独立 SLO。 | HIGH | Phase 6 按 CPU/IO/GPU 分类建立有界 executor/bulkhead，并以 Phase 12 实测决定并发，不先猜数字。 |
| CAP-009 同步长请求仍公开 | 课件 create/optimize/audio/video 同步端点仍存在；`/video_voice_swap/process` 同步串联 FFmpeg→ASR→TTS→FFmpeg（`CoursewareProjectController.java:47-91`; `VideoVoiceSwapController.java:37-65`）。 | 长时间占用 HTTP/异步响应资源，客户端断开与任务生命周期耦合。 | 只要多人同时做媒体任务就会明显排队。 | 可造成 Tomcat/外部服务/进程资源联动耗尽。 | 200 active 中即使少量重任务也可拖垮普通 API。 | CRITICAL | Phase 4 复用 taskId 模式，先淘汰危险同步端点；短 TTS 仅在 timeout+bulkhead+size limit 下保留。 |
| CAP-010 外部服务无限并发 | GPT-SoVITS、FunASR、Moonshot、阿里云 NLS 调用有部分 timeout，但无全局/每用户 semaphore 或 bulkhead。 | 请求可直接把 GPU、外部连接、供应商配额或本机服务压满。 | 10 人同时 TTS/ASR 即可能超过单 GPU 安全并发。 | 超时堆积并反向占满应用资源。 | 无法据注册用户数估计容量或成本。 | CRITICAL | Phase 6/9 增加可配置全局与每用户配额、排队上限、429/503+Retry-After；Phase 12 反推安全值。 |
| CAP-011 文件存储抽象不完整 | 新音色通过 `ObjectStorageService`；课件、视频、字幕、笔记、口语和参考音色仍直接 `Path/Files`（例如 `CoursewareProjectService.java:100-137,206-316`; `AccessibilityServiceImpl.java:74-143`）。 | 请求落到另一节点时看不到文件；节点损坏会丢资产。 | 单机可工作。 | 双实例产生 404/状态与文件不一致。 | 200 active 下磁盘容量、IO 和一致性都不可控。 | CRITICAL | Phase 3 把永久对象全部迁入 ObjectStorageService；DB 保存 key/bucket/content_type/size/checksum/owner；临时工作目录单独定义生命周期。 |
| CAP-012 本地目录配额非原子 | `ensureQuota(Path)` 每次 `Files.walk` 汇总，然后才写文件（`UploadSecurityService.java:143-150`）。 | O(file count) 扫描且 check-then-write 有并发竞态。 | 文件少时影响有限。 | 多文件用户上传时 IO/延迟上升，可能越过配额。 | 大量用户/对象下扫描不可持续。 | HIGH | 永久对象配额基于 DB 原子计数/事务；本地临时空间使用全局水位、每任务预算和定期清理。 |
| CAP-013 大对象进入 Java heap | GPT-SoVITS 非流式结果先保留 chunk list 再 join；视频响应 `Files.readAllBytes`；阿里云 TTS 使用 `ByteArrayOutputStream`（`TTSServiceImpl.java:54-81,231-239`; `VideoVoiceSwapServiceImpl.java:358-365`; `AliyunSpeechService.java:42-66`）。 | 同一响应可能有多份 `byte[]`，大视频/并发音频直接放大 heap 与 GC。 | 小音频通常可接受；视频已有风险。 | 并发大文件可能触发长 GC/OOM。 | 200 active 无法允许同量级大对象进入堆。 | CRITICAL | 下载与媒体结果统一流式/对象存储直传；限制响应大小；指标记录 bytes、heap、GC；移除视频 `readAllBytes`。 |
| CAP-014 HTTP streaming 缺少容量闸门 | `StreamingResponseBody` 用于 GPT-SoVITS、方言 TTS、声音克隆；Nginx read/send timeout 为 3600 秒（`TTSController.java:33-44`; `nginx.conf:70-96`）。 | 慢客户端和慢上游长期持有连接/执行资源。 | 少量流可用但未测断连。 | 连接与线程模型可能先于 CPU 饱和。 | 大量活跃流会占满 Nginx/Tomcat/上游连接。 | HIGH | 为流式接口单独并发限额和更短分阶段 timeout；验证断连取消；采集 active streams、duration、bytes、client abort。 |
| CAP-015 WebSocket 状态与容量 | 每连接状态在本机 `clients` Map；1 MiB send buffer；鉴权 scheduler pool=1（`StreamingAsrWebSocketHandler.java:29-50,113-121,223-234`; `AsrSchedulerConfig.java:9-15`）。 | 无连接上限、每用户上限、节点下线策略和跨实例说明。 | 10 条连接可能可用，未实测。 | scheduler/外部 ASR session/发送缓冲可能成为瓶颈。 | 200 active 长连接需要明确分布与故障语义。 | HIGH | Phase 10 明确 WebSocket 是节点本地长连接并做均衡；增加连接 quota/metrics/drain；只有确有需要才 sticky。 |
| CAP-016 Redis fallback 不具备集群一致性 | Redis 可保存对话状态和登录失败；故障时登录限流退化到本机 Map，Redis disabled 时对话也在本机（`LoginRateLimiter.java:37-104`; `InMemoryDialogueSessionStore.java:15-63`）。 | 故障或多实例下限流和会话行为不一致。 | 单实例 fallback 提供有限可用性。 | 攻击者可跨节点绕过计数；会话请求换节点失败。 | 200 active 时降级放大数据库/认证压力。 | HIGH | 生产强制 Redis；明确 Redis 故障时安全降级策略；限流使用 Lua；对话只以共享 store 为正确性来源。 |
| CAP-017 缓存与限流指标缺口 | 当前只有 HTTP、外部服务 up gauge 和共享 executor metrics；没有 cache hit/miss、rate-limit reject（`ExternalServicesHealthIndicator.java:42-55`; `AsyncTaskService.java:80-81`）。 | 无法判断缓存收益、拒绝率和排队原因。 | 调试依赖日志。 | 容量拐点不可解释。 | 无法形成可复核的扩容结论。 | HIGH | Phase 8/11 增加命中/未命中、限流拒绝、task 状态、外部 latency、queue、bulkhead、WS、上传与存储指标。 |
| CAP-018 SQL/索引与真实查询不完全匹配 | voice 可见列表使用 `public_visible=1 OR owner_username=?` 且无分页 SQL；LIKE 以 `%` 开头；task/courseware 排序索引不含 tie-break id；task 无幂等唯一约束（`VoiceMapper.java:36-59`; V1–V3）。 | OR/leading wildcard 易扫描，ORDER BY 可能 filesort，幂等只靠 JVM。 | 小表不可见。 | 数据增长后 p95/p99 与 DB CPU 上升。 | 1000 注册用户的数据累积会显著放大。 | HIGH | Phase 7 在专用 schema/脱敏数据上对实际 SQL 做 EXPLAIN；仅按证据加 composite index/改查询；幂等加可表达业务语义的唯一约束。 |
| CAP-019 课件列表 N+1 | 分页查项目后，`restore` 对每个项目调用一次 `findRevisions`（`CoursewareProjectService.java:143-152,534-560`）。 | 每页 1+N 次 SQL，revision MEDIUMTEXT 也全部加载。 | 默认小页影响有限。 | 并发列表增加 DB round trips 和连接占用。 | 数据增长后尾延迟和池等待显著。 | HIGH | 列表 DTO 不加载 revisions；详情按需分页/单独查；用 SQL 计数/摘要而非整批 MEDIUMTEXT。 |
| CAP-020 非分页/内存分页查询 | 音色 list/search 不传参数时无界返回，传分页也先查全表再内存 slice；管理员 `findAllUsers()` 无界；语音笔记读全目录和全文（`VoiceController.java:48-66`; `VoiceMapper.java:36-59`; `UserMapper.java:46-47`; `AccessibilityServiceImpl.java:119-148`）。 | 响应、heap、Redis cache value 与 DB/磁盘扫描随数据线性增长。 | 小数据可用。 | 100 用户累计数据后开始放大。 | 1000 用户长期数据会成为稳定性风险。 | HIGH | 所有 list API 强制有上限的 DB/object metadata pagination；拒绝缺省无界模式；笔记正文详情按需读取。 |
| CAP-021 DB 状态迁移非 CAS | task/courseware 通过通用 upsert 覆盖状态；除音色删除外没有明确事务/CAS；课件外部工作与状态写入分离（`JdbcTaskRepository.java:27-41`; `JdbcCoursewareProjectRepository.java:28-50`）。 | 并发更新可能丢失终态或旧状态覆盖新状态。 | 单实例锁暂时掩盖。 | 多实例或超时/取消竞争时出现错误终态。 | worker 扩容后正确性不可接受。 | CRITICAL | Phase 2 定义状态机并用受影响行数验证原子 transition；必要时 version 字段和短事务；外部调用不持有 DB 长事务。 |
| CAP-022 定时清理多实例重复执行 | 每个实例每 5 分钟扫描同一批 100 条清理记录，无 claim/lease（`PendingFileCleanupService.java:55-69`; `JdbcPendingFileCleanupRepository.java:112-142`）。 | 双实例可能同时删除、重复记失败或争用同一记录。 | 单实例通常可用。 | 双实例开始产生重复工作和状态竞争。 | 大 backlog 下重复扫描与写放大。 | HIGH | 把清理也纳入可 claim 的 DB worker，或使用原子 lease；加入 next_retry_at 和指数退避。 |
| CAP-023 外部进程/文件句柄风险 | 每次 FFmpeg/ffprobe 启动进程和 reader thread；流、文件遍历大多使用 try-with-resources，但没有全局进程上限（`ExternalProcessRunner.java:40-84`; `CoursewareProjectService.java:362-369`）。 | 并发进程消耗 PID、pipe、文件句柄、CPU、RAM；单个 timeout 不能限制总量。 | 1–2 个任务通常可控。 | 8+ 并发可能先耗尽 CPU/句柄，具体需实测。 | 200 active 必须通过队列隔离重任务。 | HIGH | Phase 6 添加 FFmpeg bulkhead；Phase 12 测 1/2/4/8；Phase 13 监视 process/thread/handle 泄漏。 |
| CAP-024 显式业务队列有界，但 scheduler admission 未定义 | 媒体 executor 使用 `ArrayBlockingQueue(20)` 和 AbortPolicy；timeout scheduler/ASR scheduler 没有业务级待调度数量上限（`AsyncTaskService.java:76-85`; `AsrSchedulerConfig.java:9-15`）。 | 不能把“未发现显式无界业务队列”误写成整个系统已有完整 backpressure。 | 当前 task queue 满会 429。 | scheduler/WS/同步接口仍可绕过 task queue。 | 多实例会把每节点队列容量相加，缺少全局队列水位。 | HIGH | Phase 9 在 DB source of truth 上实现 global/per-user pending limit；所有重任务统一入口；返回稳定 code 与 Retry-After。 |
| CAP-025 static mutable state | `DIALOGUE_SCENARIOS` 是 `static final LinkedHashMap`，启动后看起来只读但类型仍可变（`SpeakingPracticeServiceImpl.java:49-80`）。 | 当前没有发现运行期写入调用，主要是意外修改风险，不是现有容量主瓶颈。 | 无明显影响。 | 若被误改会全 JVM 共享错误。 | 节点可能出现不同场景配置。 | LOW | 构造完成后改为不可变 Map/数组防御性复制；不作为优先容量整改。 |
| CAP-026 口语趋势仍是 JVM 状态 | `historyByUser` 永久增长并用于返回趋势，即使明细同时写 MySQL（`SpeakingPracticeServiceImpl.java:46-47,102-148,305-327`）。 | 重启丢趋势，多实例不一致，活跃用户 key 无 TTL。 | 少量用户内存影响小。 | 用户数增长导致 Map 常驻和节点视图不同。 | 1000 用户长期运行存在未界定内存增长。 | HIGH | 从 MySQL 有界查询计算趋势或使用有 TTL 的 Redis performance cache；删除正确性对本地 Map 的依赖。 |

## 5. 20 类必查风险覆盖表

| 必查项 | 审计结果 | 对应发现 |
|---|---|---|
| 1. 单实例假设 | 存在 | CAP-001、004、005、011、015、022、026 |
| 2. JVM 进程内状态 | 存在且参与正确性 | CAP-004、005、015、016、026 |
| 3. 本地磁盘依赖 | 大量存在，音色库仅完成部分抽象 | CAP-011、012、023 |
| 4. synchronized | 任务和课件状态大量使用 | CAP-006、021 |
| 5. static mutable state | 发现一个低风险场景表 | CAP-025 |
| 6. ConcurrentHashMap | 任务、课件、WebSocket、口语、fallback、nodb 多处存在 | CAP-004、005、015、016、026 |
| 7. 本地锁 | 任务 64 stripes 与课件 ProjectState monitor | CAP-006、021 |
| 8. 同步长请求 | 存在 | CAP-009、010、014 |
| 9. 无界队列 | 显式媒体队列有界；scheduler/同步入口缺少统一 admission | CAP-024 |
| 10. DB transaction bottleneck | 未发现长外部调用包在显式事务内；状态 upsert 非 CAS 是主要正确性问题 | CAP-021 |
| 11. 缺失索引 | 从查询形态推断存在候选，尚未 EXPLAIN | CAP-018 |
| 12. N+1 查询 | 课件分页逐条加载 revisions | CAP-019 |
| 13. 非分页查询 | 音色、用户、语音笔记存在 | CAP-020 |
| 14. 外部服务无限并发 | 存在 | CAP-010 |
| 15. 文件句柄风险 | 存在于并发进程、pipe、流和目录遍历；多数单次流有关闭代码 | CAP-014、023 |
| 16. 内存加载大文件 | 存在 | CAP-012、020 |
| 17. byte[] 大对象 | GPT-SoVITS、阿里云 TTS、视频响应存在 | CAP-012 |
| 18. WebSocket 连接状态 | JVM 本地且无连接 quota | CAP-015 |
| 19. Hikari pool 配置 | 只有 connection timeout，预算不完整 | CAP-003 |
| 20. Tomcat thread 配置 | 未显式配置、未实测 | CAP-002 |

## 6. 正向基线：后续应保留的能力

- 媒体 task executor 是有界队列，满载会抛出容量异常并由全局异常处理返回稳定错误码 `TASK_CAPACITY_EXCEEDED` 和 HTTP 429（但尚无 `Retry-After`）。
- task 和 courseware 顶层列表已有 page/size 上限逻辑；问题是 voice 等其他列表仍无界，以及 courseware 的 N+1。
- 外部进程有超时、输出上限、interrupt 处理和强制终止；下一步是增加总量隔离，不是删除这些保护。
- Redis 命令/连接、RestTemplate、Moonshot、OSS、外部进程和 GPT-SoVITS 均有不同程度 timeout；下一步要统一预算并验证 fast-fail，而不是声称“完全没有超时”。
- WebSocket 有 5 秒鉴权、并发安全的发送装饰器、1 MiB 发送缓冲上限和关闭清理。
- 上传已经具备总请求、按类型大小、扩展名、MIME、magic、图像尺寸、PPT 页数、WAV 时长和路径穿越保护。
- 新音色上传已经通过对象存储抽象保存 object key/provider/bucket/content type/size/checksum；旧本地音色只保留兼容读取。
- Actuator/Prometheus 已经能提供 JVM、HTTP、Hikari 和 executor 的标准指标基础。

## 7. Phase 1–15 的整改优先级

1. **先定义 workload 与 SLO（Phase 1）**：把普通 API、数据库、Redis、TTS、ASR、GPT-SoVITS、FFmpeg、视频、文件流量分开建模。
2. **先修正确性再扩容（Phase 2、5）**：DB 原子状态机、可恢复 worker queue、多 worker 并发测试。
3. **完成共享存储（Phase 3）**：永久媒体对象全部抽象；临时空间明确节点本地、生命周期和水位。
4. **隔离长任务和资源（Phase 4、6、9）**：统一异步入口、分资源 bulkhead、全局与每用户 backpressure。
5. **以真实查询治理 DB/Redis（Phase 7、8）**：先 EXPLAIN 和指标，再添加有依据的索引/缓存/限流。
6. **双实例后再做容量证明（Phase 10–14）**：10→50→100→200 普通 workload；重任务 1/2/4/8；30–60 分钟 soak；真实故障恢复。
7. **最终只报告证据支持的结论（Phase 15）**：严格区分 `VERIFIED`、`ESTIMATED`、`NOT VERIFIED`。

## 8. Phase 0 验收清单

- [x] 覆盖 Nginx、Spring Boot/Tomcat、HikariCP、MySQL、Redis、Spring Cache、async executor、task persistence、本地状态、本地文件系统、GPT-SoVITS、FunASR、FFmpeg、WebSocket、HTTP streaming、上传下载和 observability。
- [x] 覆盖用户指定的 20 类风险，并为每个发现给出 current behavior、bottleneck、10/100/1000-user impact、severity、recommended solution。
- [x] 明确区分源码/配置、历史测试、当前运行和真实容量证明。
- [x] 没有修改应用代码，没有运行破坏性数据库操作，没有把线程池调大当成容量证明。
- [x] 完整 `mvn test`：104 tests、0 failures、0 errors、6 skipped、`BUILD SUCCESS`。
- [x] 当前 Stage A/B/C 均标记为 `NOT VERIFIED`。

# 智韵教声容量工作负载模型与 SLO

> 状态：Phase 1 workload model
>
> 基线提交：`3abeff9 docs(scalability): establish capacity baseline`
>
> 适用范围：后续 Phase 2–15 的设计、压测、容量结论和回归判定

## 1. 模型目标

本模型回答三个不同问题：

1. 10 个真实用户同时使用时，普通 API 和少量媒体行为是否稳定。
2. 100 个真实用户同时使用时，应用、MySQL、Redis 和外部依赖在哪里首先饱和。
3. 1000 个已注册/活跃用户中最多 200 个用户同时在线活动时，普通 API 是否满足 SLO，重任务是否被有界排队而不是拖垮系统。

本模型不把以下概念混为一谈：

| 概念 | 定义 |
|---|---|
| registered users | 数据库中可登录的账号总数，不代表同一时刻发请求。 |
| concurrent active users / VUs | 同一时间处于登录、浏览、思考、提交或轮询流程中的用户数。用户包含 think time，不会每毫秒持续发请求。 |
| concurrent requests | 某一瞬间正在服务器中执行或等待的 HTTP 请求数，通常小于 active users，但慢媒体请求可能让它快速累积。 |
| RPS | workload 在给定响应时间和 think time 下自然产生的每秒请求数，是结果而不是从用户数直接假定的常数。 |
| heavy-task concurrency | 同时实际占用 GPT-SoVITS/FunASR/FFmpeg/视频生成资源的任务数，必须独立于普通 API VUs 测量。 |

近似关系仅用于检查量级，不用于宣称容量：

```text
RPS ≈ concurrent active users / (平均响应时间 + 平均 think time)
```

## 2. 流量分层

所有测试必须标记 lane，禁止把不同资源成本的结果合并成一个“总 RPS”结论。

| Lane | 内容 | 主要资源 | 后续测试 |
|---|---|---|---|
| L0 CORE_API | login、voice list/metadata lookup、courseware list、task create、task status | Tomcat、JWT/BCrypt、MySQL、Redis、Hikari | Phase 11 主容量曲线 |
| L1 FILE_IO | 音色试听、课件/音频/视频下载、上传 | Nginx、网络、OSS、本地临时盘、文件句柄 | Phase 11 独立报告 |
| L2 GPT_SOVITS_TTS | `/voice/synthesize`、`/voice/stream`、本地声音克隆 | GPU/模型服务、WebClient、streaming | Phase 12，1/2/4/8 并发 |
| L3 ASR | FunASR 文件转写、实时 ASR WebSocket | CPU/GPU/模型服务、WebSocket、临时文件 | Phase 12，文件与实时连接分开 |
| L4 FFMPEG_VIDEO | 提取音频、拼接、字幕、视频换声、课件视频 | CPU、RAM、磁盘、子进程/句柄 | Phase 12，1/2/4/8 并发 |
| L5 CLOUD_AI | Moonshot、阿里云 NLS/声音复刻 | 外网、供应商 quota、成本 | 功能/故障/限流测试，不与本机模型 RPS 混合 |

普通用户旅程中可以“偶尔触发”媒体行为，但容量报告必须同时给出：

- 不含真实媒体执行的 L0 后端容量；
- 真实媒体任务的接收/排队响应；
- L2/L3/L4 实际 worker throughput、latency 和 safe concurrency。

## 3. 当前 API 到用户行为的映射

| 用户行为 | 当前 API | Lane | 说明 |
|---|---|---|---|
| login | `POST /user/login` | L0 | 每个 VU 使用独立账号；不复用一个共享用户。 |
| voice list | `GET /voice_library/list?page=1&size=20` | L0 | 必须显式分页；后续整改后分页应下推到 SQL。 |
| voice detail | 当前没有独立的 metadata-only detail API | L0 gap | Phase 11 暂用 `GET /voice_library/search?name={knownName}&page=1&size=5` 表示 metadata lookup；`/{id}/audio` 属于 L1，不能冒充普通详情。 |
| task create | `POST /courseware/projects/{id}/optimize/tasks`、`/{id}/audio/tasks`、`/{id}/video/tasks` | L0 admission + L2/L4 work | L0 只测持久化提交/背压响应；真实执行结果进入重任务报告。需预置归属该用户的 project。 |
| task status | `GET /api/tasks/{taskId}` | L0 | 轮询采用退避和截止时间，禁止零间隔 busy polling。 |
| courseware list | `GET /courseware/projects?page=1&size=20` | L0 | 用于暴露分页、N+1 与 Hikari 等问题。 |
| occasional TTS | `POST /voice/synthesize` 或 `/voice/stream` | L2 | 由用户旅程按低概率触发，但从 L0 latency/RPS 中分开统计。 |

`voice detail` 的 API gap 只影响压测映射，不授权新增普通业务功能。若后续不新增端点，就持续使用有界 search 作为 metadata lookup。

## 4. L0 普通 API 用户旅程

每个 VU 执行以下闭环，步骤间使用带随机抖动的 think time，避免所有用户整齐地同时敲击同一接口：

1. 登录一次，保存 JWT；失败则本轮用户终止，不能匿名继续并污染结果。
2. 浏览音色列表 1–3 次，每次间隔 1–3 秒。
3. 使用预置的已知音色名执行一次 metadata lookup，间隔 1–2 秒。
4. 浏览自己的课件列表 1–2 次，间隔 2–5 秒。
5. 约 20% 的用户会在一个已预置课件上提交一次任务；任务类型按 optimize/audio/video 分开打 tag。
6. 仅对本轮成功创建的 taskId 轮询：1s、2s、3s、5s、5s……，最长 60 秒；终态后立即停止。
7. 约 2% 的用户迭代触发一次 TTS 行为；该请求记入 L2，不计入 L0 SLO 聚合。
8. 休息 3–8 秒后进入下一轮；JWT 未过期时不重复登录。

为了既可复现又避免固定节奏，所有随机数由 `RUN_ID` 派生固定 seed；原始结果必须保存该 seed。

### 4.1 L0 请求构成检查

权重不是硬编码吞吐目标，而是验收每轮实际请求占比是否接近真实旅程：

| L0 请求类型 | 期望占比区间 |
|---|---:|
| login | 1%–5% |
| voice list | 20%–30% |
| voice metadata lookup | 10%–20% |
| courseware list | 15%–25% |
| task create/admission | 2%–8% |
| task status polling | 25%–40% |

若实际占比超出区间，必须先修脚本或说明原因，不能只发布漂亮的聚合 latency。

## 5. 三个容量场景

### Scenario A：10 concurrent users

| 项目 | 定义 |
|---|---|
| 账号池 | 10 个独立账号，10 个均活跃 |
| 并发模型 | closed model，10 constant VUs |
| 预热 | 2 分钟，不计入 SLO |
| 稳态 | 10 分钟，至少重复 3 次 |
| 数据基线 | 20 个公开音色；每用户 2 个课件、5 条历史 task |
| 媒体行为 | 低概率触发，但真实执行按 L2/L4 单独统计 |
| 目标 | 验证最小多人场景无功能错误、资源泄漏和明显排队 |

### Scenario B：100 concurrent users

| 项目 | 定义 |
|---|---|
| 账号池 | 100 个独立账号，100 个均活跃 |
| 并发模型 | closed model，逐级 10→50→100 VUs；不得从 10 直接跳到 100 |
| 每级预热/稳态 | 2 分钟预热 + 10 分钟稳态，至少重复 3 次 |
| 数据基线 | 100 个公开音色；每用户 5 个课件、20 条历史 task |
| 目标 | 找出 Tomcat、Hikari、MySQL、Redis 或应用 CPU/heap 的第一个饱和点 |

### Scenario C：1000 registered users / 200 concurrent active users

| 项目 | 定义 |
|---|---|
| 注册账号 | 1000 个独立账号 |
| 活跃账号 | 每轮从账号池确定性抽取 200 个，不允许 1000 VUs |
| 并发模型 | closed model，先通过 10/50/100，再运行 200 constant VUs |
| 预热/稳态 | 2 分钟预热 + 10 分钟稳态，至少重复 3 次；Phase 13 再做 30–60 分钟 soak |
| 数据基线 | 200 个公开音色；每个活跃用户 10 个课件、50 条历史 task；非活跃账号至少有登录记录所需的用户行 |
| 目标 | 证明或否定“1000 注册用户、峰值 200 活跃用户”假设，而不是声称支持 1000 concurrent requests |

数据量是本轮可复现的起始模型，不是对真实生产分布的永久假设。报告必须记录实际表行数、对象数量和总字节数。

## 6. SLO 与判定规则

### 6.1 普通 API SLO

对 L0 聚合和每个关键 endpoint tag 同时判定：

| 指标 | 阈值 |
|---|---:|
| p50 | `< 100 ms` |
| p95 | `< 300 ms` |
| p99 | `< 800 ms` |
| HTTP/业务错误率 | `< 1%` |

判定规则：

- 单个高频接口不能被其他快接口掩盖；聚合和各 endpoint 均要报告 p50/p95/p99/error rate。
- 测试内的 429/503 计入非成功响应。正常容量验收超过 1% 即 FAIL；专门的饱和/背压测试可以预期拒绝，但必须单列，不能据此宣称该并发级别 PASS。
- 认证失败、测试数据缺失、脚本断言失败计入错误；不能从结果中静默过滤。
- 客户端超时、连接失败、响应 schema 错误和错误 task owner 都计入错误。
- 三次重复运行全部满足才可将该场景标为 `VERIFIED`；只通过一次标为 `NOT VERIFIED`，并记录波动。

### 6.2 稳定性硬门槛

任何场景出现以下任一项都直接 FAIL：

- OOM 或进程被操作系统杀死；
- Tomcat thread exhaustion 或持续满载且无法恢复；
- Hikari connection pool exhaustion 或持续 pending；
- deadlock；
- executor queue 在停止施压后仍不回落；
- 任务永久停留在 RUNNING；
- 后端不可恢复、数据串用户、重复执行同一幂等任务；
- 未受控的磁盘/Redis/数据库持续增长。

### 6.3 重任务 SLO

Phase 1 不预设 GPT-SoVITS、FunASR、FFmpeg 的安全并发或 latency PASS 数字。Phase 12 对 1/2/4/8 concurrency 分别测量：

- accepted、started、success、failed、timeout、rejected；
- queue wait、execution latency、end-to-end latency、throughput；
- CPU、RAM、heap、GC；
- GPU utilization/VRAM（环境有 GPU 时）；
- 进程/线程/句柄、临时盘字节和清理结果。

safe concurrency 定义为：连续三轮无功能错误、无硬门槛失败、资源有余量且提高并发不再带来合理 throughput 收益之前的最高实测级别。最终 executor/semaphore 数字必须来自该结果。

## 7. 文件与流式负载模型

L1 不与 L0 混为一个 RPS：

| 行为 | 样本大小/模式 | 必测指标 |
|---|---|---|
| 音色上传 | 1 MiB、10 MiB、接近 20 MiB 上限 | upload latency、bytes/s、heap、临时盘/OSS、错误与清理 |
| PPT 上传 | 小型、50 页、接近 30 MiB/200 页边界 | validation time、heap、解析时间、拒绝是否有界 |
| 视频上传 | 10 MiB、接近 50 MiB 上限 | request body、临时盘、句柄、清理 |
| 音色试听 | 1–20 MiB object stream，包含慢客户端 | first byte、total latency、active streams、client abort |
| 课件/视频下载 | 实际生成对象；禁止构造超出业务上限的无限文件 | first byte、throughput、heap、断连释放 |

每种大小至少记录对象 provider、对象总字节数和是否经过 Java heap；不能只给 HTTP latency。

## 8. 测试数据与隔离

- 使用专用压测 schema、专用 Redis database/key prefix、专用 OSS object prefix，例如 `scalability/{RUN_ID}/...`。
- 禁止清空或重建现有业务数据库。涉及现有业务 schema 的迁移前必须先备份；Phase 1 本身不执行 schema 变更。
- 测试账号、课件、任务和对象必须带 `RUN_ID`，清理只按该精确前缀/主键范围执行。
- 1000 个账号预生成 BCrypt 密码哈希，seed 时间不计入压测；login 请求仍真实执行 BCrypt 校验。
- 每个 active user 使用自己的 JWT、projectId、taskId，禁止共享 token/owner 造成不真实的缓存和锁热点。
- voice metadata lookup 使用固定存在的数据；不存在查询另设 5% 子样本，避免所有请求都命中同一缓存键。
- task create 的幂等键每轮可控：正常流量唯一，另设 1% 重试相同请求以验证幂等。

## 9. 每轮必须采集的证据

### 9.1 请求结果

- Git SHA、RUN_ID、脚本 SHA、开始/结束时间、场景、VUs、seed；
- requests、RPS、p50、p95、p99、max、error rate；
- 每 endpoint 的同组指标；
- HTTP status、业务 error code、429/503 和 Retry-After；
- task accepted/reused/rejected/terminal 分布。

### 9.2 资源结果

- 主机：CPU、RAM、磁盘容量/IO、网络；
- JVM：heap/non-heap、allocation、GC count/pause、threads、deadlock；
- Tomcat：current/busy/max threads、connections；
- Hikari：active/idle/pending/max、acquire latency；
- MySQL：connections、threads running、slow query、buffer pool、实际表行数；
- Redis：command latency、connections、memory、evictions、key count；
- executors：active、pool、queued、completed、rejected；
- 外部服务：active、latency、timeout、failure、bulkhead rejected；
- WebSocket：active/authenticated/closed/error；
- 文件/OSS：bytes、latency、errors、临时对象/残留文件。

缺少 CPU/RAM/heap/GC/Tomcat/Hikari/MySQL/Redis/executor 指标的运行只能标为功能负载检查，不能标为容量证明。

## 10. 运行协议

1. 记录环境和依赖版本，确认数据/Redis/对象 prefix 与业务环境隔离。
2. 启动至少 5 分钟，确认 JVM warm-up、Flyway、缓存和连接池稳定。
3. 执行单用户 smoke，验证断言、测试账号、project/task 归属和清理标签。
4. 依次执行 10、50、100、200 VUs；上一级 FAIL 时不得跳级发布更高一级 PASS。
5. 每级 2 分钟预热、10 分钟稳态、至少 3 次；运行间恢复到同一数据基线并等待资源回落。
6. 保存原始 k6 JSON/summary、Prometheus snapshot/query、MySQL/Redis 状态和应用日志摘要。
7. 同时报告中位结果和最差一轮；SLO 判定使用最差一轮，不挑最好结果。
8. L2/L3/L4 按 1→2→4→8 独立执行；发现硬门槛失败立即停止升阶。
9. Phase 13 使用通过容量级别的 70%–80% 作为混合 soak 起点，而不是直接用饱和点。
10. 所有测试结束后精确清理 `RUN_ID` 资产并证明无业务数据被删除。

## 11. Phase 1 输出与后续实现约束

- Phase 11 的 k6 脚本必须直接实现本文用户旅程和 tags；偏离时先更新模型并独立说明原因。
- Phase 2–10 的改造不能通过扩大 Tomcat/Hikari/executor 数字来绕过本模型中的正确性和资源隔离要求。
- Phase 15 的 `VERIFIED CAPACITY` 必须引用满足本文运行协议的原始结果；推算 1000 registered users 时必须写出 active users、RPS、媒体到达率和重任务 safe concurrency 前提。
- 当前没有任何场景因为本文档而自动获得 PASS；Stage A/B/C 仍是 `NOT VERIFIED`。

## 12. Phase 1 验收清单

- [x] 定义 10、100、1000 registered/200 active 三个场景。
- [x] 区分普通 API、文件 IO、GPT-SoVITS、ASR、FFmpeg/视频和云服务。
- [x] 覆盖 login、voice list、voice metadata lookup、task create/status、courseware list、occasional TTS。
- [x] 固定普通 API p50/p95/p99/error rate SLO 与稳定性硬门槛。
- [x] 定义数据规模、think time、轮询退避、重复次数、原始证据和清理边界。
- [x] 没有提前假设重任务安全并发，没有声称支持 1000 concurrent requests。
- [x] 完整 `mvn test`：104 tests、0 failures、0 errors、6 skipped、`BUILD SUCCESS`。

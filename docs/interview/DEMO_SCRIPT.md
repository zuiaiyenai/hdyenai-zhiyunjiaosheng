# 智韵教声 5 分钟演示脚本

> 目标：让面试官在 5 分钟内看懂业务场景、Java 后端主链路、可靠性设计、真实验证和证据边界。
>
> 原则：演示功能是为了引出后端设计，不把页面效果当作容量证明，不临场跑长压测，不展示任何真实凭证。

## 1. 录制前准备

### 1.1 推荐演示方式

基础页面演示使用 Spring Boot 的 `nodb` 模式：

```powershell
Set-Location D:\code\fctts-main5
./launch.ps1 -Mode nodb
```

浏览器打开 <http://localhost:8081>，准备演示账号：

- `admin / admin123`
- `demo / demo123`

`nodb` 只证明当前进程内的界面与基础请求可以演示，不证明 MySQL durable task、Redis、OSS 或双实例正在实时运行。涉及这些能力时，应切到源码和已提交的 Phase 16 报告。

### 1.2 提前打开的页面或文件

按顺序准备 5 个标签页，避免录制时搜索：

1. 应用首页：<http://localhost:8081>
2. [`AsyncTaskService.java`](../../src/main/java/com/a09/tts/task/AsyncTaskService.java)
3. [`JdbcTaskRepository.java`](../../src/main/java/com/a09/tts/task/JdbcTaskRepository.java)
4. [`ARCHITECTURE.md`](ARCHITECTURE.md)
5. [`FINAL_SCALABILITY_REPORT.md`](../scalability/FINAL_SCALABILITY_REPORT.md) 与 [`PRODUCTION_READINESS_MATRIX.md`](../scalability/PRODUCTION_READINESS_MATRIX.md)

可选准备：Actuator health 页面和 Prometheus。如果当前不是数据库/外部服务完整模式，就不要展示绿色健康页并暗示全部依赖在线。

### 1.3 录制检查清单

- 服务端口可访问，页面能登录。
- 浏览器、终端和编辑器字号足够大。
- 清空终端中可能出现的密码、AccessKey、JWT 和本地绝对隐私路径。
- 不打开 `.env`、本地配置或真实 OSS 控制台凭证页。
- 演示一个稳定的短路径；视频和外部模型不可用时直接走备用方案。
- 将最终容量报告滚动到“双实例容量”“故障恢复”“30 分钟 soak”三处。

## 2. 五分钟时间轴与台词

### 0:00–0:30 项目定位

**画面：** 项目首页或 `README_FINAL.md` 顶部。

**台词：**

> 这个项目叫“智韵教声”，业务上做教学语音、课件和视频处理。我求职时把它定位为“AI 语音业务场景下的 Java 后端工程化与高可靠异步任务系统”。我的重点不是训练语音模型，而是用 Java 管理认证、数据库、缓存、对象存储、异步任务、外部模型和 FFmpeg，让重任务可恢复、可限流、可观测。当前评级是 `READY_WITH_LIMITS`，说明本机双实例和真实依赖有证据，但云生产还没有验证。

**必须说清：** 模块化单体，不是微服务；AI 是外部能力。

### 0:30–1:20 登录与工作台

**画面：** 登录，进入工作台，快速展示声音库、课件或视频入口。

**操作：**

1. 使用演示账号登录。
2. 打开声音库或课件列表。
3. 指出上传、提交任务、查询状态和下载结果的入口，不需要逐项演示所有功能。

**台词：**

> 登录链路是 rate limit、Redis、用户查询、BCrypt、JWT。用户身份由服务端从 JWT 解析，后续文件和任务都按 owner 隔离。Phase 16 profiling 发现 BCrypt 占登录耗时主导，所以我没有为了压测降低 cost 12，而是为认证单独定义了 SLO。200 VU 的周期登录负载约 6.55 login/s，不等于 200 人同秒登录。

### 1:20–2:15 异步任务主链路

**画面：** `ARCHITECTURE.md` 的任务时序图，再切 `AsyncTaskService.submit`。

**台词：**

> 视频、ASR、声音复刻和课件生成不能长期占用 HTTP 线程。请求上传后，输入流式写对象存储，再向 MySQL 创建 `PENDING` 任务并返回 taskId。创建时同时做幂等、每用户并发和全局活动任务上限判断，超限立即拒绝，不允许无界排队。

**代码指向：**

- `AsyncTaskService.submit`：载荷、幂等、每用户/全局 admission。
- `TaskController`：按当前用户查询、取消和流式获取结果。

### 2:15–3:05 原子领取、恢复与资源隔离

**画面：** `JdbcTaskRepository.claimNext`、`AsyncTaskService.executeClaimed`、`TaskResourceBulkheads`。

**台词：**

> worker 不是先 SELECT 再处理，而是用一条条件 UPDATE 原子领取，写入 workerId、attempts 和 heartbeat。执行时维护心跳和超时；worker 崩溃后，另一实例根据 stale heartbeat 重新排队。TTS、ASR、FFmpeg 和课件处理还有各自的 Semaphore bulkhead，避免一种重任务拖垮全部资源。这里能保证数据库单行状态转换，但我不宣称 MySQL、OSS 和外部模型之间 exactly-once。

**必须说清：** 单 JVM bulkhead 不是跨实例全局 permit。

### 3:05–3:45 视频与存储链路

**画面：** 视频流程 Mermaid，或应用内视频上传/任务状态页面。

**台词：**

> 视频流程是 OSS 输入、FFmpeg 提取音频、FunASR 转写、GPT-SoVITS 合成、FFmpeg 混流、结果写回 OSS。媒体搬运使用 InputStream、Files.copy 和临时文件，不把完整视频读进 JVM heap。100 和 500 MiB 搬运在 `-Xmx96m` 下通过；但这只证明搬运，不等于 500 MiB 真实转码。完整视频只跑了 5.291 秒和 10 秒两条串行样本，所以状态是 `PARTIAL`。

### 3:45–4:35 容量与故障证据

**画面：** 最终容量报告的 HTTP、故障和 soak 表格。

**台词：**

> 当前最清楚的容量边界是：双实例 mixed HTTP 在 200 VU、60 秒 steady 下完成 3,711 次请求，61.85 req/s，0 业务错误，普通 API 最差 p95 50.50 毫秒。重任务不能混在这个数字里：GPT-SoVITS 安全并发 1，FunASR 1，FFmpeg 整机 2。30 分钟 soak 是 100 VU、49,983 次 L0 请求和 6 次真实 TTS，0 业务错误。Redis、MySQL、模型、单 upstream 和 worker crash 都做过有界恢复，但没有在 100/200 VU 负载中注入，因此故障恢复仍是 `PARTIAL`。

### 4:35–5:00 评级与总结

**画面：** 生产就绪矩阵。

**台词：**

> 最终矩阵是 10 项 `VERIFIED`、8 项 `PARTIAL`、1 项 `BLOCKED`，cloud 是唯一 `BLOCKED`。所以我给 `READY_WITH_LIMITS`，而不是为了好看写 READY。这个项目最能代表我的能力，是把普通单实例演示项目改造成证据驱动的 Java 后端：任务状态持久、并发领取正确、媒体 I/O 可控、外部依赖有边界，而且每个结论都能回到源码或报告。

## 3. 演示失败备用方案

### 页面无法启动

不现场排查超过 30 秒。直接打开 `README_FINAL.md` 和 `ARCHITECTURE.md`：

> 当前本地运行环境没有在录制窗口内恢复，我用已提交架构图和证据继续讲。运行失败本身不影响下面的源码设计说明，但我不会说当前服务健康。

### GPT-SoVITS / FunASR 不可用

不要切 mock 后说“真实调用成功”。展示 Phase 16 报告中的真实执行记录：

> 外部模型当前没有启动，因此这次录制不现场调用。仓库里已有固定输入、并发档位、延迟和故障恢复证据；当前画面只是在展示历史验证，不是本次实时健康证明。

### 视频演示过慢

不等待完整处理。提交任务后展示 taskId 和状态页，再切视频证据表：

> 这是异步任务，5 分钟演示不等终态。已有 small/medium 两条真实全链路记录；它们证明功能链路，不证明并发容量。

### OSS 或网络不可用

不展示 AccessKey，不临时修改源码。切换到对象存储架构与真实 OSS 报告：

> 当前网络或凭证未就绪，所以不做实时 OSS 操作。已提交证据覆盖 10/100/500 MiB 上传、下载、SHA 和删除；生产凭证仍必须轮换并使用 RAM/STS。

## 4. 演示中的禁止话术

| 不要说 | 推荐说法 |
| --- | --- |
| “系统支持 1,000 并发” | “1,000 是注册数据基线；已测 HTTP 上限是当前协议下 200 VU” |
| “200 并发登录都通过” | “200 VU 每 30 秒周期登录，约 6.55 login/s，不是同秒突发” |
| “OSS 并发能力是 4” | “4×10 MiB 功能成功，但没有建立 safe concurrency” |
| “视频链路很稳定” | “small/medium 串行 2/2 成功，样本不足以给失败率” |
| “数据库和 Redis 高可用” | “restart recovery 通过，但 HA、切换和数据丢失未验证” |
| “任务 exactly-once” | “数据库 claim 和终态是原子条件更新；外部副作用不宣称 exactly-once” |
| “这是微服务项目” | “这是模块化单体，外部模型是独立依赖” |
| “我做了模型训练” | “我负责 Java 后端编排、可靠性、安全与容量验证” |
| “已经生产上线” | “本机双实例达到 `READY_WITH_LIMITS`，cloud 仍 `BLOCKED`” |

## 5. 结束后的追问引导

如果面试官希望深入，可以主动给出三个方向：

1. “我可以展开讲 MySQL 原子 claim、heartbeat 和 stale recovery 如何避免任务永久 `RUNNING`。”
2. “我可以讲登录 profiling 为什么选择保留 BCrypt cost 12，而不是为指标降低安全性。”
3. “我可以讲 200 VU、重任务安全并发和 30 分钟 soak 为什么必须分开解释。”

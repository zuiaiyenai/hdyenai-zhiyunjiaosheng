# Phase 6：重型资源隔离

## 1. 本阶段结论

Phase 6 为 TTS、ASR、FFmpeg 和课件任务增加了可配置的进程内 bulkhead。普通 HTTP 请求、DB worker 数量和重型资源并发不再由同一个无界线程池隐式决定；资源已满时，调用会在短暂等待后快速拒绝，持久化任务则释放 claim、延后重领且不消耗 attempts。

这证明的是单实例并发隔离和拒绝语义，不是安全并发容量。当前默认值均为待 Phase 12 在真实 GPT-SoVITS、FunASR 和 FFmpeg 环境中校准的保守起点。

## 2. 资源画像

| 任务类型 | TTS | ASR | FFmpeg | Courseware | 说明 |
| --- | :---: | :---: | :---: | :---: | --- |
| `ASR_TRANSCRIBE` |  | x |  |  | FunASR 转写。 |
| `VOICE_NOTE` |  | x |  |  | 保存后执行 FunASR。 |
| `VIDEO_SUBTITLES` |  | x | x |  | 音频提取、转写、时长探测。 |
| `VIDEO_VOICE_SWAP` | x | x | x |  | ASR 在未提供 transcript 时才实际调用；当前画像保守预留。 |
| `SOUND_CLONE` | x |  |  |  | GPT-SoVITS 流式输出。 |
| `SPEAKING_EVALUATION` |  | x |  |  | FunASR 口语识别。 |
| `PPT_SUMMARY` |  |  |  | x | PPT 解析及 Moonshot 调用。 |
| `COURSEWARE_CREATE` |  |  |  | x | PPT 处理及 Moonshot 调用。 |
| `COURSEWARE_OPTIMIZE` |  |  |  | x | Moonshot 优化。 |
| `COURSEWARE_AUDIO` | x |  |  | x | 分段 TTS 与课件产物更新。 |
| `COURSEWARE_VIDEO` |  |  | x | x | 使用已生成音频合成视频，不占 TTS。 |

同步/流式 GPT-SoVITS、FunASR、`ExternalProcessRunner` 和 Moonshot 客户端自身也受相应 bulkhead 保护，因此不经 DB worker 的兼容接口同样不能无限并发。worker 已持有资源时，底层同线程调用采用可重入 permit，避免重复获取自己持有的槽位。

复合任务按 `TaskResource` 的固定 enum 顺序一次性获取全部资源，失败时按逆序释放已获得的 permit，避免不同任务以不同顺序获取造成死锁。

## 3. 配置与当前证据边界

| 环境变量 | Spring 配置 | 默认值 | 证据状态 |
| --- | --- | ---: | --- |
| `RESOURCE_ACQUIRE_TIMEOUT` | `app.resources.acquire-timeout` | `50ms` | 仅为快速失败起点，未做容量实测。 |
| `TTS_MAX_CONCURRENT` | `app.resources.tts.max-concurrent` | `1` | Phase 12 本机 GPT-SoVITS 实测安全并发为 1；多实例共享 GPU 的全局准入仍未解决。 |
| `ASR_MAX_CONCURRENT` | `app.resources.asr.max-concurrent` | `1` | 未在真实 FunASR 负载下校准。 |
| `FFMPEG_MAX_CONCURRENT` | `app.resources.ffmpeg.max-concurrent` | `1` | Phase 12 本机整机安全并发为 2；双实例同主机保持每实例 1。 |
| `COURSEWARE_MAX_CONCURRENT` | `app.resources.courseware.max-concurrent` | `1` | 未按 Moonshot quota/延迟实测校准。 |

所有最大并发必须大于 0，等待时间不得为负。Phase 12 必须按 1/2/4/8 分级测量吞吐、延迟、失败率、CPU、RAM 和 GPU（可用时），再反推生产值；不能因为配置可调就声称已经支持对应并发。

## 4. 饱和、重试与关闭语义

- 非 worker 调用拿不到 permit 时抛出 `ResourceCapacityException`，由现有全局异常处理返回 503。
- DB worker 拿不到 permit 时把 RUNNING 任务还原为 PENDING，写入稳定错误码 `RESOURCE_SATURATED`，`available_at` 延后一个 poll interval，并回退本次 claim 增加的 attempts。
- worker 在等待资源时被关停，同样释放 claim、写入 `WORKER_SHUTDOWN` 且不消耗 attempts，因为业务执行尚未开始。
- 业务已经获得 permit 后发生的真实执行失败仍按 Phase 5 的普通重试与指数退避处理。
- worker 的内部执行器使用 `SynchronousQueue`：固定数量的常驻 worker loop 不再叠加一个不可见的 JVM 待执行队列；真正的等待状态保留在 MySQL。

## 5. 指标

每种 `resource` 标签（`tts`、`asr`、`ffmpeg`、`courseware`）暴露：

- `fctts.resource.bulkhead.active`：当前持有的物理 permit 数；同线程可重入不会重复计数。
- `fctts.resource.bulkhead.max`：本实例配置上限。
- `fctts.resource.bulkhead.rejected`：因等待超时而拒绝的累计次数。

这些指标用于 Phase 11/12 判断饱和点。它们是单 JVM 指标，多实例报告必须聚合所有实例，不能只看一个节点。

## 6. 已知边界

1. 当前 Semaphore 是单实例范围。Phase 12 已证实本机 GPT-SoVITS 安全并发为 1，但两个 backend 即使各配置 `TTS_MAX_CONCURRENT=1`，共享服务仍可能收到 2 个并发请求；生产部署必须按共享资源预算拆分，或引入真正的跨实例 admission control。FFmpeg 在双实例同主机时各保留 1，整机聚合为实测安全值 2。
2. 所有 DB worker 仍共享固定 worker pool。资源饱和任务会快速 defer，避免长期占住线程，但热门任务持续排在前面时仍可能形成短时 head-of-line；Phase 9 的全局/每用户 pending 上限和真实队列压测仍未完成。
3. 复合任务一次性持有其完整资源集合，因此某些资源会在任务的顺序阶段暂时空闲。这是避免死锁的保守选择；只有压测证明利用率成为瓶颈后，才应拆成阶段化 task，而不是先增加复杂度。
4. 已有 `app.tasks.per-user-concurrency=2` 是 MySQL-backed、跨实例共享的每用户 PENDING/RUNNING 总活动任务上限。尚未实现独立的 `video: 1 running per user` 配额；它属于后续 quota/backpressure 工作，当前不得声称已完成。
5. bulkhead 不替代外部调用超时、DB 队列容量、HTTP 429/Retry-After、供应商 quota 或多实例故障验证。

## 7. Phase 6 验证结果

2026-09-13 的本地验证结果：

- 单元测试覆盖资源间隔离、同线程可重入、复合资源固定顺序、饱和拒绝指标、worker defer 不消耗 attempts，以及等待资源时关停不消耗 attempts。
- Spring `nodb` context 启动通过，证明多构造器组件的唯一 `@Autowired` 注入入口和配置绑定可用。
- MySQL 5.7.26 专用 `tts_phase6_resource_verify_20260913_0427` schema 中，V1→V5→V8 迁移、双 Repository 原子领取、`RESOURCE_SATURATED` 持久化、attempts 回退和重新领取通过；测试后该 schema 已精确删除，业务 schema `zhiyunjiaos` 仍存在。
- 完整 Java 回归：116 tests，0 failures，0 errors，6 skipped；跳过项包含必须显式启用的真实外部服务和隔离 MySQL 集成测试，后者已按上一项单独运行通过。
- 前端 Vite 生产构建通过；`git diff --check` 通过。

以上实现验证仍只证明正确性和 MySQL 5.7 兼容性。Phase 12 已另行实测本机 GPT-SoVITS 与纯 FFmpeg 的 1/2/4/8 并发并写入 `docs/performance/PHASE12_HEAVY_TASKS.md`；FunASR、完整视频换声、跨 backend 的全局 TTS 准入、30–60 分钟 soak 和故障恢复仍为 `NOT VERIFIED`。

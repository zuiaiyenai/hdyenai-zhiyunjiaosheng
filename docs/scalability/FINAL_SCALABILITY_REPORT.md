# 智韵教声已经具备受限上线能力，但尚未完成云生产资格

> 报告状态：Phase 16 FINAL
>
> 证据截止：2026-09-14
>
> 代码范围：`3abeff9`（容量基线）至本报告所在提交；Phase 16.9 应用基线为 `e96383e`，当前报告前 HEAD 为 `bccb32f`
>
> 最终评级：`READY_WITH_LIMITS`
>
> 证据原则：`VERIFIED` 只适用于对应提交、主机、拓扑、数据集和测试协议，不自动等于 Linux、跨主机、云环境或真实生产流量承诺。

## 1. 结论先行

“智韵教声”已经从主要依赖单 JVM 状态和节点本地文件的演示型 Spring Boot 项目，升级为能够在同一 Windows 主机上由两个真实 Spring Boot 实例共同运行的后端。Nginx 分配请求，MySQL 保存业务事实与持久任务，Redis 提供缓存和原子限流，阿里云 OSS 可作为跨节点永久对象存储，worker 使用数据库原子领取、心跳、重试与 stale recovery，GPT-SoVITS、FunASR 和 FFmpeg 由有界资源闸门保护。

最终评级为 **`READY_WITH_LIMITS`**，依据是：

- 200 VU 的双实例 mixed HTTP 在 60 秒稳态完成 3,711 次请求、61.85 req/s、0 业务错误；普通 API 最差 p95 为 50.50 ms，登录 p95 为 511.21 ms，均满足各自 SLO。
- 登录在保留 BCrypt cost 12 的情况下完成 10/50/100/200 VU profiling；7,083 次 steady 登录无业务错误，最高 p95 563.67 ms，最高 p99 581.90 ms，满足认证专用 `p95 < 750 ms / p99 < 1000 ms / error < 1%`。
- 真实 GPT-SoVITS、真实 FunASR、真实阿里云 OSS、FFmpeg 和 small/medium 视频换声全链路已经执行，不再依赖 mock 或配置推断。
- cleanup 原子领取和视频大文件流式化已经修复；500 MiB 媒体搬运可在 `-Xmx96m` 下完成，真实 OSS 10/100/500 MiB 上传、下载、校验和删除全部通过。
- Redis、MySQL、真实 GPT-SoVITS、真实 FunASR、单个 Nginx upstream 和 worker/backend crash 的六条恢复路径均通过；100 VU mixed API 加 6 次真实 TTS 的 30 分钟 soak 未观察到 heap、连接、Redis key 或队列持续增长。

当前**不能评为 `READY`**，因为目标云主机、Linux/容器、跨主机网络、MySQL/Redis/Nginx HA、WebSocket 容量与连接迁移、负载中的故障注入、60 分钟以上重复 soak、真实 ASR/视频/OSS 混合 soak 均未完成。宿主机在 200 VU 双实例测试中最低可用内存仅约 370 MiB；GPT-SoVITS 与 FunASR 的本机安全并发均为 1，继续增加重任务会先触及模型与内存边界。

生产就绪逐项判定见 [生产就绪矩阵](PRODUCTION_READINESS_MATRIX.md)。

## 2. 评级适用范围与上线硬限制

`READY_WITH_LIMITS` 仅允许按以下边界部署：

1. 单个 GPT-SoVITS 服务实际并发限制为 **1**；两个 backend 不能各自独立放行 1 个请求后共同把模型并发放大到 2。
2. 单个 FunASR 服务按当前证据限制为 **1**；更长音频、不同编码和多个 backend 聚合访问需要重新定标。
3. 同一台已测主机的 FFmpeg 整机并发限制为 **2**；若双 backend 共机，建议各最多持有 1 个整机配额。
4. 不宣称支持 1000 simultaneous users，也不允许 1000 simultaneous heavy jobs。1000 只是注册账号数据基线和产品规划量级。
5. local storage 只可用于单节点或临时工作副本；多实例永久产物必须显式使用共享对象存储。
6. 对话中曾暴露的 OSS 长期凭证必须立即轮换；生产前改用限定 bucket/prefix 的最小权限 RAM 身份或短期 STS，并只通过 secret 注入。
7. 公网生产前必须在目标云拓扑重新执行容量、故障与 soak 验证，并完成 TLS、安全组、监控告警、备份恢复和 MySQL/Redis/Nginx 高可用验收。

任何一项硬限制无法落实时，本报告的发布结论降级为 `NOT_READY`。

## 3. 容量边界：用户数、请求和重任务不是同一指标

| 口径 | 当前结论 | 证据范围 | 不能外推为 |
| --- | --- | --- | --- |
| `REGISTERED USERS` | 1,000 | Phase 11/16 数据基线，账号、项目、历史任务和公开音色已预置 | 1,000 人同时在线或同时登录 |
| `ACTIVE USERS` | 200 VU 已短时验证 | 本机双实例，60 秒 mixed HTTP；另有 Phase 11 每档 3×10 分钟非登录 L0 | 200 人同时执行真实媒体任务 |
| `HTTP CONCURRENCY` | 200 VU | 100/200 VU 双实例 mixed HTTP；100 VU 30 分钟 soak | 200 个同一时刻到达的登录请求或 1,000 VU |
| `HTTP RPS` | 61.85 req/s mixed 短时；约 55.77–55.95 req/s 非登录 L0 长档；27.77 req/s 30 分钟 soak | closed-model、包含 think time 的实际用户旅程 | 无 think time 的最大吞吐、互联网端到端 RPS 或云 SLA |
| `LOGIN RATE` | 6.55 login/s 周期负载 | 200 VU 每 30 秒一次登录，10 分钟 steady | 200 人同秒突发登录 |
| `HEAVY TASK CONCURRENCY` | GPT-SoVITS=1；FunASR=1；FFmpeg 整机=2 | 固定输入、本机真实进程的独立容量测试 | ASR+TTS+FFmpeg+OSS 组合并发，或按 backend 实例线性相加 |
| `VIDEO PIPELINE` | small/medium 串行 2/2 成功 | 5.291s 与 10s 视频的真实 OSS→worker→FFmpeg→FunASR→GPT-SoVITS→FFmpeg→OSS 链路 | large、并发视频容量或失败率分布 |
| `OBJECT STORAGE` | 10/100/500 MiB 串行流式路径；4×10 MiB 并发功能成功 | 本机经公网访问真实阿里云 OSS | HTTP 业务端点 500 MiB、同区域云带宽、OSS safe concurrency 或长期 SLA |

因此：

```text
1000 registered users
!= 1000 active users
!= 1000 concurrent HTTP requests
!= 1000 simultaneous heavy jobs
```

## 4. HTTP、登录与双实例容量

### 4.1 普通 API

Phase 11 在 1,000 注册账号、200 活跃账号数据基线上，对 10/50/100/200 VU 各执行 3 轮、每轮 2 分钟 warmup 和 10 分钟 steady：

- 200 VU 的非登录 L0 为 55.772–55.947 req/s，错误率 0%。
- 非登录 endpoint 最坏 p95 为 27.929 ms。
- 该 workload 包含浏览、搜索、项目列表、任务提交/轮询和 think time；它不是无停顿吞吐测试。

Phase 16.7 在真实双实例、共享 MySQL/Redis/OSS 和 Nginx 下再次执行 mixed HTTP：

| VU | steady 请求 | RPS | error rate | login p95 | 普通 API 最差 p95 | 最低可用内存 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 1,892 | 31.53 | 0% | 497.43 ms | 61.29 ms | 417 MiB |
| 200 | 3,711 | 61.85 | 0% | 511.21 ms | 50.50 ms | 370 MiB |

两档分别产生 `200=1,890 / 202=2` 与 `200=3,707 / 202=4`，raw HTTP 点、自定义计数器和 summary count 完全一致。Nginx 增量 upstream 为 backend-1 3,351 次、backend-2 3,371 次，证明两个实例都承载了请求。

来源：[Phase 16.7 报告](../performance/PHASE16_MULTI_INSTANCE.md)、[聚合证据](../performance/phase16-multi-instance-aggregate-evidence.json)。

### 4.2 登录性能不是普通 API SLO

Phase 16.1 对链路 `HTTP → rate limit/Redis → user query → BCrypt → JWT → response` 增加分段 Micrometer Timer 后确认：BCrypt 占平均登录耗时约 99.18%–99.47%，数据库约 0.82–1.50 ms，Redis 约 1.75–2.07 ms，JWT 约 0.07–0.33 ms。没有证据支持通过重复查询、锁或序列化优化获得数百毫秒收益。

| VU | steady 登录 | login/s | p50 | p95 | p99 | error rate |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 197 | 0.328 | 488.34 ms | 514.42 ms | 537.32 ms | 0% |
| 50 | 986 | 1.643 | 505.02 ms | 559.10 ms | 568.77 ms | 0% |
| 100 | 1,969 | 3.282 | 531.15 ms | 563.67 ms | 574.02 ms | 0% |
| 200 | 3,931 | 6.552 | 529.70 ms | 561.97 ms | 581.90 ms | 0% |

原普通 API `p95 < 300 ms` 阈值对 BCrypt 登录仍为 `NOT MET`，但它不是适当的认证目标。最终采用认证专用 SLO：`p95 < 750 ms / p99 < 1000 ms / error < 1%`。四档全部通过，BCrypt cost 12 保留，结论为 **ACCEPTED 且矩阵状态为 VERIFIED**，不是通过降低密码强度实现的“优化”。

未验证 200 个同秒起始的突发登录，也未验证高于约 6.55 login/s 的 arrival rate。来源：[Phase 16.1 报告](../performance/PHASE16_LOGIN_PROFILE.md)、[聚合证据](../performance/phase16-login-aggregate-evidence.json)。

## 5. 重任务与完整媒体链路

### 5.1 安全并发

| 资源 | 测试结果 | 当前安全点 | 边界 |
| --- | --- | ---: | --- |
| GPT-SoVITS | 并发 1 时约 0.1193 job/s、p95 9.64s；提高并发没有带来吞吐收益，延迟近似随排队增加 | 1 | 固定文本/参考音频、本机直连；Phase 16.9 另有 6 次真实短 TTS |
| FunASR | 固定 5.29s 中文音频；并发 1/2/4 为 3.487/3.614/3.537 jobs/s，p95 0.318/0.557/1.156s | 1 | 并发 2 吞吐只增 3.64%，p95 增 75.09%；长音频与准确率未测 |
| FFmpeg | 30s 固定输入；并发 2 时约 1.2428 job/s、CPU p95 88.61%；并发 4 只再增约 5.01% 且 CPU 饱和 | 整机 2 | 视频时长、分辨率、编码参数变化需重新定标 |

来源：[Phase 12 重任务](../performance/PHASE12_HEAVY_TASKS.md)、[Phase 16.4 FunASR](../performance/PHASE16_FUNASR.md)。

### 5.2 视频大文件流式化

Phase 16.3 已移除视频结果、课程媒体和 PPT 上传路径中会把完整媒体装入 JVM `byte[]` 的实现。100 MiB 和 500 MiB 合成视频结果在最大堆 96 MiB 的独立 JVM 中通过真实 worker→本地对象存储文件流：

- 100 MiB 档 heap peak 增量 3 MiB。
- 500 MiB 档未采样到新增 heap 峰值。
- 两档 GC count/time 增量均为 0，结果大小一致，worker 临时输出已删除。

这证明 JVM 媒体搬运路径不再随文件大小近似线性占用 heap；不证明真实 500 MiB FFmpeg 转码或 500 MiB 业务上传。来源：[Phase 16.3 报告](../performance/PHASE16_MEDIA_STREAMING.md)、[聚合证据](../performance/phase16-media-streaming-evidence.json)。

### 5.3 真实视频全链路

Phase 16.5 执行了真实 `authenticated upload → OSS → durable MySQL task → FFmpeg extract → FunASR → GPT-SoVITS → FFmpeg output → OSS → streamed download`：

| case | 输入 | 结果 | E2E | 输出验证 | 临时文件 |
| --- | --- | --- | ---: | --- | --- |
| small | 5.291s / 178,606 B | `SUCCESS / attempts=1` | 15.755s | H.264 + AAC，可下载 | 最终清零 |
| medium | 10s / 278,290 B | `SUCCESS / attempts=1` | 23.246s | H.264 + AAC，可下载 | 最终清零 |

完成后重启 JVM，两条任务仍为 `SUCCESS`，结果仍可重新下载并保持 SHA-256 一致。两条串行 happy path 只能证明 small/medium 功能链路，不能产生并发容量或有统计意义的失败率；large 未运行。整体视频 pipeline 状态为 `PARTIAL`。来源：[Phase 16.5 报告](../performance/PHASE16_VIDEO_PIPELINE.md)、[聚合证据](../performance/phase16-video-aggregate-evidence.json)。

## 6. 存储、任务领取与多实例正确性

### 6.1 对象存储

Phase 16.6 使用真实阿里云 OSS：

| 对象 | 上传 | 上传吞吐 | 下载 | 下载吞吐 | heap peak delta（上/下） | 结果 |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 10 MiB | 4.330s | 2.310 MiB/s | 0.504s | 19.856 MiB/s | 2/6 MiB | 完整性与删除通过 |
| 100 MiB | 9.865s | 10.137 MiB/s | 17.218s | 5.808 MiB/s | 16/34 MiB | 完整性与删除通过 |
| 500 MiB | 51.429s | 9.722 MiB/s | 24.661s | 20.275 MiB/s | 14/16 MiB | 完整性与删除通过 |

4 路并发各上传 10 MiB 时 4/4 成功且下载校验一致；它只证明并发 4 可以工作，不建立 OSS safe concurrency 或吞吐扩展结论。缺失对象、上传中断和错误凭证均有界失败，所有已登记测试对象最终清零。

真实 OSS 功能与当前客户端吞吐状态为 `VERIFIED`；长期凭证治理、RAM/STS、同区域云带宽、SDK 多 JVM 聚合容量、真实限流/DNS/5xx/网络分区、生命周期和容灾仍未验证。来源：[Phase 16.6 报告](../performance/PHASE16_OSS.md)、[聚合证据](../performance/phase16-oss-aggregate-evidence.json)。

### 6.2 task claim 与 file cleanup

- 异步任务使用 MySQL 原子领取、owner token、heartbeat、重试和 stale recovery；多实例并发幂等提交只生成一条活跃任务。
- file cleanup 已从 `SELECT → process` 改为数据库原子 claim；双 worker 集成测试断言只有一个 owner，并覆盖 worker crash、stale claim recovery、retry 和幂等删除。
- Phase 16.7 的两个真实 worker 对同一 task 仅记录 1 次 claim、1 个 worker；两个 cleanup worker 对同一记录同样只记录 1 次 claim、1 个 worker。
- Phase 16.8 在任务 `RUNNING / attempts=1` 时杀死 backend，另一实例 stale recovery 后得到 `SUCCESS / attempts=2`，未留下永久 `RUNNING`。

这证明数据库状态和单行终态可恢复，但不等于外部模型、OSS 与 MySQL 之间的副作用 exactly-once。

### 6.3 JWT、缓存和节点切换

Phase 16.7 已验证：backend-1 签发的 JWT 可直接访问 backend-2；两个节点共享 MySQL/Redis；任务结果可跨节点经 OSS 查询和下载；backend-1 下线后旧 JWT 经 Nginx 20/20 请求成功，backend-1 恢复后 readiness 为 `UP` 且任务终态不变。

WebSocket 只验证过有限握手分流。已建立的 TCP 连接固定在所选节点，节点故障时不能迁移，必须由客户端重连；没有真实连接容量、消息吞吐或断线重连风暴证据。

## 7. 故障恢复

Phase 16.8 使用隔离 MySQL/Redis、双 Spring Boot、项目 Nginx 和真实本机模型，顺序执行六类故障：

| 故障 | 故障期间 | 恢复结果 | 状态 |
| --- | --- | --- | --- |
| Redis restart | health/readiness 503，liveness 200；fallback 登录有界返回 | 8.079s 后业务请求 200 | VERIFIED |
| MySQL restart | health/readiness 503；DB API 2.031s 返回 500 | 1.875s 后业务请求 200 | VERIFIED |
| GPT-SoVITS unavailable | 同步 TTS 0.031s 返回 503 | 真实模型恢复后 200 且 WAV 校验通过 | VERIFIED |
| FunASR unavailable | 任务 2.062s 后 `FAILED / attempts=3` | 新任务 `SUCCESS / attempts=1` | VERIFIED |
| 单个 Nginx upstream 丢失 | 旧 JWT 经 Nginx 20/20 成功 | backend-1 13.625s 内恢复 | VERIFIED |
| worker/backend crash | crash 前 `RUNNING / attempts=1` | 另一实例 14.563s 后 `SUCCESS / attempts=2` | VERIFIED |

六条具体恢复路径已验证，但故障窗口没有同时运行 100/200 VU，没有采集并发故障期间的 Hikari、executor 和线程曲线；也没有网络分区、磁盘满、OSS 故障、Nginx 进程故障、双 upstream 故障或多轮恢复分布。因此整体 failure recovery 为 `PARTIAL`。来源：[Phase 16.8 报告](../performance/PHASE16_FAILURE_INJECTION.md)、[聚合证据](../performance/phase16-failure-aggregate-evidence.json)。

## 8. 30 分钟 mixed soak

Phase 16.9 在本机双实例完成 60 秒稳定、2 分钟 warmup、30 分钟 steady 和 5 分钟 tail。100 VU steady 内完成 49,983 次 L0 请求和 6 次串行真实 TTS：

| 指标 | 结果 |
| --- | ---: |
| L0 requests / RPS / error | 49,983 / 27.768 / 0% |
| L0 p50/p95/p99 | 2.63 / 4.44 / 45.40 ms |
| login p50/p95/p99 | 481.51 / 494.32 / 503.36 ms |
| real TTS | 6/6 HTTP 200，6/6 WAV check |
| TTS p50/p95/p99 | 2.708 / 3.256 / 3.269s |
| samples / scrape failures | 443 / 0 |
| host CPU p95 / max | 11.89% / 28.79% |
| 可用内存最低 | 557 MiB |
| 双 backend RSS 最大 | 1,105.57 MiB |
| 双模型 RSS 最大 | 2,172.55 MiB |
| 双 JVM heap 最大 | 385.75 MiB |
| GC pause delta / count | 0.253s / 101 |
| Hikari pending / executor queue max | 0 / 0 |
| task rejection / Redis eviction / MySQL slow query delta | 0 / 0 / 0 |

heap 末值从首 5 分钟的 225.74 MiB 降到末 5 分钟 208.60 MiB，再降到 tail 181.86 MiB；MySQL 连接为 8→6→3，Redis keys 为 354→380→100。未观察到 heap、连接、Redis、队列或进程 RSS 持续增长。

JVM live threads 从首 5 分钟末的 198 阶跃到 230，峰值 232，最后约 4 分钟及 tail 保持平台但未回落；backend OS threads 为 279→312→310。这是**有界阶跃、未观察到继续增长，但根因未确认**。WebClient 延迟初始化线程池只是可能解释，没有 thread dump 不能作为结论。

k6 exit code 为 99，唯一原因是保留的旧 `login p95 < 300 ms` 阈值；当前认证专用 SLO 和所有其他分层 SLO 均通过。该 raw 阈值失败没有被隐藏。

整体 soak 为 `PARTIAL`：只执行一次 30 分钟本机 run，真实 TTS 仅 6 次且最大 in-flight=1，FunASR 只参与 readiness；ASR/FFmpeg/视频 worker/OSS 数据流、任务消费后的 queue drain、WebSocket、60 分钟以上、重复 run 和云环境均未覆盖。来源：[Phase 16.9 报告](../performance/PHASE16_SOAK.md)、[聚合证据](../performance/phase16-soak-aggregate-evidence.json)。

## 9. 仍然存在的生产风险

1. **云环境没有实际证据。** 当前结果来自同一台 Windows 主机，不能外推 Linux、容器、跨主机网络、云负载均衡、安全组、TLS、云盘或供应商配额。
2. **MySQL、Redis、Nginx 和模型服务仍是单点。** restart 证明同一进程可以恢复，不证明主从、哨兵、托管 HA 或负载均衡器自身切换。
3. **共享模型需要跨实例全局准入。** 单 JVM semaphore 不能保证两个 backend 聚合访问一个 GPT-SoVITS/FunASR 时仍保持模型并发 1。
4. **宿主机内存余量窄。** 200 VU 双实例最低可用内存约 370 MiB，30 分钟 soak 最低 557 MiB；不能在当前主机上继续叠加重媒体并发。
5. **视频证据样本很窄。** 只有 small/medium 各 1 条串行 happy path，没有 large、编码矩阵、组合并发、等待时间分布或统计失败率。
6. **真实媒体没有进入 soak。** ASR、FFmpeg、视频 worker、OSS 数据传输和任务实际消费均不在 Phase 16.9 workload 内。
7. **WebSocket 未定标。** 没有连接数、消息吞吐、慢客户端、节点故障重连和连接迁移测试。
8. **线程阶跃未归因。** 30 分钟末段虽形成平台，但没有 60 分钟重复 run 与 thread dump，不能关闭线程泄漏风险。
9. **故障样本与类型不足。** 每类一次，不能给恢复时间 p95/p99；负载中故障、网络分区、半开连接、磁盘满、OSS 限流/5xx 与复合故障未验证。
10. **凭证治理是生产硬阻断项。** 已暴露的 OSS 长期凭证在轮换、最小权限和 secret-only 注入完成前不得用于生产。
11. **任务等待 SLO 尚未校准。** 全局/用户队列上限和 admission 锁是正确性与保护机制，不是已经证明的重任务吞吐或等待时间承诺。
12. **长期数据规模边界仍存在。** 深分页、包含搜索、正文对象读取和 revision 体积需要用未来真实数据分布继续验证。

## 10. 生产扩展路线

| 优先级 | 工作 | 验收标准 |
| --- | --- | --- |
| P0 | 轮换已暴露 OSS 长期凭证并改用最小权限 RAM/短期 STS | 旧 key 失效；新身份仅有目标 prefix 所需权限；仓库、日志、报告无凭证 |
| P0 | 为 GPT-SoVITS/FunASR 增加跨实例全局 permit/lease | 两 backend 聚合提交时每个模型 active 永不超过 1；超限有稳定 429/503、退避和指标 |
| P0 | 绑定目标云拓扑与规格 | 明确 Linux/容器、CPU/RAM/GPU、网络、云盘、MySQL/Redis/OSS、Nginx/LB、TLS、安全组和供应商 quota |
| P0 | 建立 MySQL/Redis/Nginx HA 与备份恢复 | 实际切换时正确性、RTO/RPO 和延迟达到明确 SLO；告警可触发并闭环 |
| P1 | 在目标云执行 200 VU mixed 与 login burst | 普通 API、认证 SLO、资源余量和跨主机分流在目标环境通过 |
| P1 | 执行真实 ASR+TTS+FFmpeg+OSS worker 组合容量 | 按任务类型给出 arrival、queue wait、service time、完成率、资源和 safe concurrency |
| P1 | 执行 60 分钟以上、至少两轮 mixed media soak | heap、RSS、线程、连接、对象、临时盘和队列无持续增长；线程阶跃以 thread dump 归因 |
| P1 | 在 100/200 VU 下重复故障注入 | 连接池/线程池不耗尽；恢复时间形成分布；覆盖网络分区、磁盘满、OSS 与复合故障 |
| P1 | WebSocket/streaming 容量与恢复 | 定标连接数、消息吞吐、慢客户端、断连清理和节点故障重连 |
| P2 | API 与媒体 worker 分离资源池 | API 扩容不放大共享 GPU 并发；TTS/ASR/FFmpeg 按各自队列独立扩容和限额 |
| P2 | 数据增长触发式优化 | 由真实 trace 决定 cursor、搜索服务或列表摘要表，不提前做大规模架构重写 |

扩容顺序必须保持“先正确性和全局准入，再加实例”。直接增加 backend 会线性放大 JVM、Hikari 和单 JVM semaphore 数量，不会自动增加共享 GPU、MySQL、Redis 或主机内存的安全容量。

## 11. 最终生产就绪判定

完整矩阵见 [PRODUCTION_READINESS_MATRIX.md](PRODUCTION_READINESS_MATRIX.md)。摘要如下：

- `VERIFIED`：HTTP API、login、JWT、multi-instance、task claim、file cleanup、object storage、GPT-SoVITS、FunASR、FFmpeg。
- `PARTIAL`：MySQL、Redis、local storage、video pipeline、WebSocket、Nginx、failure recovery、soak。
- `BLOCKED`：cloud。
- `FAILED`：无。

最终评级：**`READY_WITH_LIMITS`**。

这不是“测试多所以可上线”，而是：本机双实例核心路径和多项真实外部依赖已经有受控实测，因此不是 `NOT_READY`；但云目标环境和关键 HA/长稳/组合容量证据缺失，因此不能给 `READY`。上线只能在第 2 节硬限制下进行；若目标是公网正式生产，必须先关闭矩阵中的 `cloud` 阻断项和 P0 安全/高可用条件。

## 12. 证据索引

| Phase | 主要证据 | raw / manifest SHA-256 |
| ---: | --- | --- |
| 11 | [L0 报告目录](../performance/) 与 `phase11-aggregate-evidence.json` | 见对应聚合 JSON |
| 12 | [重任务报告](../performance/PHASE12_HEAVY_TASKS.md) | 完整值见对应聚合 JSON 与 raw manifest |
| 13 | [30 分钟 L0 soak](../performance/PHASE13_SOAK.md) | 见 `phase13-aggregate-evidence.json` |
| 14 | [契约与基础设施故障](../performance/PHASE14_FAILURE_TESTS.md) | 见 `phase14-aggregate-evidence.json` |
| 16.1 | [登录 profiling](../performance/PHASE16_LOGIN_PROFILE.md) | manifest `77D4C9321814D7FF42CEAFD4ECC32D0CEFD90C60669942139D28F286927B43FC` |
| 16.3 | [媒体流式化](../performance/PHASE16_MEDIA_STREAMING.md) | 可提交受限堆逐档数据见聚合 JSON |
| 16.4 | [真实 FunASR](../performance/PHASE16_FUNASR.md) | `77795FE5731A0DCCC235EA7F82A1B2015F04704881B61016E33853198BD87636` |
| 16.5 | [真实视频全链路](../performance/PHASE16_VIDEO_PIPELINE.md) | `25BFC8DDC2075E30CAE300A6C158918A4E3D11B2496031E9AA8C0EF7FA916F75` |
| 16.6 | [真实 OSS](../performance/PHASE16_OSS.md) | `CFAEFFF5BA72BAB15D7E0CEC624B0AA646F27C2E1C24D83D8AE66C1BA395B3C2` |
| 16.7 | [双实例](../performance/PHASE16_MULTI_INSTANCE.md) | functional `F8EC792290F42C45BAE2046E970FCEF71AEC11A9BFF8EE5259B20E6E38899145` |
| 16.8 | [真实故障注入](../performance/PHASE16_FAILURE_INJECTION.md) | `4002AF7B8C7A8C164A71AFCF17F25AD7F113F01A4E0CB77A0624BA8E604CF807` |
| 16.9 | [mixed+TTS soak](../performance/PHASE16_SOAK.md) | manifest `9C067CC319E87E74BEE2DAD43EE02F6EFB8A3923EAD44DC5BD0CE91BF6AD6B21` |

Phase 16.9 的 k6 summary、resource samples、k6 raw 和生成摘要 SHA-256 分别为 `1860411360864D52A4E342CA66A4235C15310709255A702BCB0F42560C66AB86`、`551EE011CC6356F6EF9444423130BBC79F6EADEF24FB142AECEAFE1AE26A8891`、`92480A801EE9D7F51283419FDF34FDF11B664A75028C2540B1135BBAE5876D21`、`2BD93025ECADD5A9D6452E9B9E2FB6A0858978C0FF2244E3EFC56DA8B6FBE6D0`。

## 13. Phase 0–16 交付映射

| Phase | commit | 主要结果 |
| ---: | --- | --- |
| 0 | `3abeff9` | 建立容量基线与阻断项 |
| 1 | `e9bf732` | 固定 workload、SLO 和证据协议 |
| 2 | `675b739` | 数据库原子任务状态与幂等 |
| 3 | `a088542` | 永久产物共享对象存储 |
| 4 | `8f64000` | 重任务从 HTTP 生命周期解耦 |
| 5 | `95fa84e` | MySQL durable worker queue |
| 6 | `59c588e` | TTS/ASR/FFmpeg/Courseware 资源隔离 |
| 7 | `31f2d64` | 分页、N+1 与容量敏感索引 |
| 8 | `e138156` | Redis Lua 限流、缓存与指标 |
| 9 | `8258bf9` | MySQL-backed 全局/用户 backpressure |
| 10 | `7e3c9ab` | 双实例运行与正确性验证 |
| 11 | `109da5e` | 10/50/100/200 VU L0 阶梯压测 |
| 12 | `cb9307f` | GPT-SoVITS 与 FFmpeg 安全并发 |
| 13 | `3583e0e` | 150 VU、30 分钟 L0 soak |
| 14 | `5bbef27` | 依赖、backend 与 worker 故障恢复 |
| 15 | `38290d9` | 发布 Phase 15 容量报告 |
| 16.1 | `f9188c1`、`89c8b7b`、`7a6cec6` | 登录分段计时、Java runtime 修复与认证 SLO 证据 |
| 16.2 | `66e5f66` | cleanup 原子 claim、stale recovery 与幂等删除 |
| 16.3 | `738d3ef` | 媒体大文件流式化与受限堆验证 |
| 16.4 | `5984cd4` | 真实 FunASR 容量与安全并发 |
| 16.5 | `abd8e80` | small/medium 真实完整视频链路 |
| 16.6 | `b7a9cd5` | 真实阿里云 OSS 容量与失败行为 |
| 16.7 | `43501bf` | 双实例 shared-state 容量与功能验收 |
| 16.8 | `e96383e` | 真实依赖与 worker 故障注入 |
| 16.9 | `bccb32f` | 100 VU、30 分钟 mixed+真实 TTS soak |
| 16.10 | 本报告所在提交 | 最终容量边界、生产矩阵与 `READY_WITH_LIMITS` 评级 |

## 14. 最终声明校验

- [x] 没有把 1,000 registered users 写成 1,000 concurrent users。
- [x] 没有把 200 VU 写成 200 个同时执行媒体任务。
- [x] 没有把 6.55 login/s 写成 200 个同秒登录。
- [x] 没有用总体 p95 掩盖 login endpoint，并保留旧 k6 阈值失败事实。
- [x] 没有降低 BCrypt cost 12 来换取压测通过。
- [x] 没有把 mock、配置、测试数量或契约桩写成真实模型/生产证明。
- [x] 没有把 small/medium 串行视频写成 large 或并发视频容量。
- [x] 没有把 OSS 并发 4 功能成功写成 safe concurrency 或云内网吞吐保证。
- [x] 没有把单机 Windows 双实例写成跨主机、Linux、容器或云生产证明。
- [x] 没有把进程 restart 写成 MySQL/Redis/Nginx HA。
- [x] 没有把一次 30 分钟 soak 写成长期无泄漏证明。
- [x] 报告未包含 AccessKey、密码、JWT secret 或其他长期凭证。

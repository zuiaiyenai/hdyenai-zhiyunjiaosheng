# Phase 13：150 VU 混合 API Soak Test

## 结论

- 本机完成了 **2 分钟 warmup + 30 分钟 steady + 5 分钟恢复尾窗**。150 VU 在 steady 阶段完成 74,938 次 L0 请求，折算 41.63 req/s；74,908 次返回 200，30 次任务创建返回 202，业务错误率为 0。
- 总体延迟满足既定 SLO：p50 2.87 ms、p95 6.25 ms、p99 51.16 ms。但登录 p50 546.56 ms、p95 589.46 ms，未满足 100/300 ms 门槛，因此完整 L0 SLO 结论是 **NOT MET**，k6 按设计以退出码 99 结束。不能用总体低延迟掩盖登录瓶颈。
- 30 分钟内未观察到 JVM heap、进程 RSS、线程、Windows handle、Hikari pending、executor queue、Redis key/memory 或 MySQL 活跃连接持续增长。恢复尾窗结束时双 JVM 聚合 heap 为 148.3 MiB，低于负载结束时的 343.8 MiB；这支持“本次 30 分钟未观察到泄漏”，不等于证明系统在更长时间绝无泄漏。
- 该结果只覆盖 L0 混合 API。媒体 worker 的轮询间隔被设置为 24 小时，60 条待处理任务不会在本次运行中执行；GPT-SoVITS、FunASR、FFmpeg、视频生成、真实 OSS 上传下载与 HTTP streaming 均不在本次证据范围内。

## 环境与协议

- 时间：2026-09-13 16:16–16:54（Asia/Shanghai）。
- 应用提交：`cb9307fc28eddb9cbc483c9dbcbe01547cfcb748`。
- 主机：AMD Ryzen 9 7945HX，32 logical processors，Windows。
- 运行时：Java 25.0.3、MySQL 5.7.26、专用 Redis 3.2.100、k6 2.2.0。
- 拓扑：Nginx `127.0.0.1:8080` → 两个 Spring Boot backend `8081/8082` → 共享 MySQL 与专用 Redis `6380/DB 14`。
- 数据：1000 个注册账号、200 个活跃账号、每个活跃账号 10 个课件与 50 条历史成功任务、200 个公开音色元数据。
- 时序：启动后稳定 60 秒；2 分钟 warmup；150 VU、30 分钟 steady；停止 k6 后继续采集 5 分钟。
- 负载：登录、音色列表/搜索、课件列表、任务准入与状态轮询。JWT 登录后复用；20% VU 在每个 scenario 创建一次任务。
- 隔离：专用 `fctts_phase13_*` schema、无持久化的专用 Redis 进程；没有重启或清空日常 6379 Redis，也没有清理业务 schema。

## API 结果

| endpoint | requests | share | p50 ms | p95 ms | p99 ms | error rate | SLO |
|---|---:|---:|---:|---:|---:|---:|---|
| login | 150 | 0.20% | 546.56 | 589.46 | 617.45 | 0% | **NOT MET**：p50/p95 |
| voice_list | 32,999 | 44.04% | 2.04 | 3.98 | 46.60 | 0% | MET |
| voice_search | 16,505 | 22.02% | 2.93 | 4.92 | 46.93 | 0% | MET |
| courseware_list | 24,834 | 33.14% | 4.06 | 6.84 | 45.32 | 0% | MET |
| task_create | 30 | 0.04% | 9.65 | 21.13 | 29.55 | 0% | MET |
| task_status | 420 | 0.56% | 2.78 | 3.77 | 16.68 | 0% | MET |
| **overall** | **74,938** | **100%** | **2.87** | **6.25** | **51.16** | **0%** | 总体 MET；完整 L0 因登录 NOT MET |

41.63 req/s 使用 steady 请求数除以 1800 秒计算。k6 自带 counter rate 会用包含 warmup 的完整场景时间作分母，因此不作为 steady RPS。

## 资源结果

| 信号 | 30 分钟 steady 结果 | 判断 |
|---|---:|---|
| 主机 CPU | avg 24.57%，p95 85.49%，peak 99.85% | 存在短峰值；不是持续饱和 |
| 主机可用内存 | min 1091 MiB | 未 OOM，但最低余量偏窄 |
| 双 JVM RSS | max 1048.7 MiB | 后 5 分钟末为 813.1 MiB |
| 双 JVM heap | max 363.8 MiB；GC pause 0.289 s / 85 次 | 未见持续堆增长或长 GC pause |
| JVM live threads | 168 → 168，max 172 | 未见线程泄漏 |
| 两进程 Windows handles | 3090 → 3100，max 3138；尾窗末 3095 | 未见单调增长；Windows 下作为 fd 风险代理 |
| Tomcat | busy max 10，current max 20 | 未见线程耗尽 |
| Hikari | active max 1，pending max 0 | 未见连接池等待 |
| task executor | active max 2，queued max 0，rejected delta 0 | 未见进程内队列增长 |
| Redis | keys max 600（steady），memory max 1,529,944 bytes，evictions 0 | 未见 Redis 增长；尾窗 keys 降至 150 |
| MySQL | Threads_connected max 21，running max 2，slow query delta 0 | 未见活跃连接耗尽 |
| DB PENDING tasks | 30 → 60，max 60；尾窗末 60 | 每个 scenario 新增 30；worker 被禁用，数量随后稳定 |

`redis_ping_p95_ms=136.34` 是每个样本启动 `redis-cli`、建立连接、认证并执行 PING 的端到端探针耗时，包含 Windows 进程启动开销，不能解释成 Redis 纯网络 RTT 或服务端处理延迟。

MySQL `Connections` 是共享 MySQL 实例的全局累计计数，steady 增加 380；其中资源采集器每 5 秒主动新建一次 mysql CLI 连接，360 个 steady 样本已解释绝大部分增量。判断连接泄漏以 Hikari pending、当前 `Threads_connected` 和恢复尾窗为主，而不是用累计计数下结论。

## 前后趋势与恢复

| 信号 | steady 前 5 分钟 | steady 后 5 分钟 | 5 分钟尾窗 | 结论 |
|---|---:|---:|---:|---|
| 主机 CPU avg / p95 | 24.73% / 86.22% | 23.79% / 79.38% | 9.14% / 17.59% | 停流后回落 |
| 可用内存 min | 1237 MiB | 4205 MiB | 5178 MiB | 没有持续下降 |
| 双 JVM RSS | 起点 983.6 MiB，末 1045.0 MiB | 起点 869.6 MiB，末 813.1 MiB | 末 816.2 MiB | 没有持续增长 |
| 双 JVM private bytes | 起点 1220.0 MiB，末 1277.1 MiB | 起点 1280.2 MiB，末 1220.2 MiB | 末 1228.4 MiB | 基本稳定 |
| 双 JVM heap p95 | 356.5 MiB | 340.5 MiB | 末 148.3 MiB | GC 后回落 |
| 进程线程总数 | 250 → 247 | 246 → 248 | 末 246 | 没有线程泄漏 |
| Windows handles | 3090 → 3088 | 3098 → 3100 | 末 3095 | 没有单调增长 |
| JVM live threads | 168 → 168 | 168 → 168 | 168 → 168 | 稳定 |
| Redis keys | 600 → 547 | 576 → 569 | 571 → 150 | TTL 后回落 |
| DB PENDING tasks | 30 → 60 | 60 → 60 | 60 → 60 | 有界且符合 worker 禁用协议 |

## 已披露的测试干扰

在 16:46:03–16:47:28，执行了一次旧 Phase 11 原始证据的汇总器兼容回放，共占用约 70 CPU 秒。该时间窗覆盖 17 个资源样本，主机 CPU avg 34.67%、p95/peak 95.81%。排除这 17 个样本后，steady CPU avg 从 24.57% 变为 24.07%，p95 仍为 85.49%，peak 仍为 99.85%；因此它不是整段 CPU 峰值的唯一来源，也没有改变 p95/peak 结论。

这次重叠没有造成业务错误或进程退出，但 k6 汇总不能单独重建该 85 秒窗口的 endpoint 百分位，因此其延迟影响不能独立量化。当前运行可作为有真实桌面后台干扰的本机 soak 证据，不应表述为完全隔离的实验室 CPU 基准。

## 证据与清理

- raw 目录：`target/phase13-live-20260913081417`（被 Git 忽略）。
- `run-manifest.json` SHA-256：`1B72FE7F148BCF824F9D2D23ADFC1EEB3BFE3E17CE360779A054BCF46D635E80`。
- `150u-r1/k6-summary.json` SHA-256：`811515C9696A9B368B1BFF762BB7CBBD226C74F79323D44CA5C89D15805ACAA4`。
- `150u-r1/resource-samples.jsonl` SHA-256：`8BC85A043EF7EFED84E42A4BCA0935AE2D8819AD062568223DF4B5A0FF8C64A8`。
- `150u-r1/k6-raw.json` SHA-256：`3B332D89BD8E2ECBCADD7C5B618E9494DC035562D17EE13AAF0E102693F30F89`。
- `phase13-summary.json` SHA-256：`8742E9C6C0DA10F73C4CFD262BEA5CEAB183A4105285EF569BA6BF29091D4F9F`。
- 442 个资源样本，两个 Prometheus endpoint 均为 442/442 成功；collector stderr 为空，`collector.complete` 存在。
- 两个 backend stderr 仅包含 Java 25 对 native access 和 `Unsafe` 的兼容性警告，没有应用异常或退出。
- runner 结束后，6380、8080、8081、8082、9091、9092 的监听数均为 0，相关 Redis/k6/collector/backend/Nginx 进程均已退出。
- `fctts_phase13_20260913081417` schema 数量为 0；业务 `zhiyunjiaos` schema 仍存在。日常 MySQL 服务未停止或清空。

## 回归验证

- Phase 13 harness 与临时文件路径的针对性测试：13 tests，0 failures，0 errors，0 skipped。
- 完整 Maven 回归：137 tests，0 failures，0 errors，8 skipped。测试 JVM 的 `TEMP`、`TMP`、`java.io.tmpdir` 均限定到项目 `target/phase13-test-tmp`。
- 8 个跳过项仍需显式 MySQL/Redis/Testcontainers 或实时云服务条件，不能算作已执行证据。
- Phase 13 时长/协议护栏、seed schema 护栏、collector schema 护栏的负向测试全部通过；运行日志未发现 OOM、未捕获异常、启动失败或 collector fatal。

## 证据边界

`VERIFIED`：上述提交、主机和协议下，150 VU L0 混合 API 的 30 分钟 steady、74,938 次请求、HTTP 状态、延迟、零业务错误、资源样本和 5 分钟恢复行为。

`NOT MET`：登录 endpoint 的 p50 < 100 ms 与 p95 < 300 ms SLO。

`NOT OBSERVED IN 30 MINUTES`：OOM、进程退出、Prometheus scrape failure、线程/handle/连接/heap/Redis 的持续增长、Hikari pending、executor queue/rejection、Redis eviction 和 MySQL slow query 增量。

`NOT VERIFIED`：60 分钟或更长 soak；Linux/生产主机表现；1000 并发请求；媒体 worker 消费与 stale recovery；GPT-SoVITS、FunASR、FFmpeg、完整视频换声；真实 OSS 上传下载；WebSocket/streaming 长连接；故障恢复。它们必须由后续阶段或独立协议验证。

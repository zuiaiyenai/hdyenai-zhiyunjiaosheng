# Phase 16.9：30 分钟 Mixed Soak（含真实 TTS）

## 结论

- 本机双实例完成 60 秒稳定、2 分钟 warmup、30 分钟 steady 和 5 分钟恢复尾窗。100 VU 在 steady 内完成 49,983 次 L0 请求（27.77 req/s）和 6 次真实 GPT-SoVITS TTS；L0 与 TTS 业务错误率均为 0。
- L0 p50/p95/p99 为 2.63/4.44/45.40ms；登录 p95 494.32ms，满足 Phase 16.1 接受的独立 750ms SLO。真实 TTS p50/p95/p99 为 2.708/3.256/3.269s，6/6 返回 HTTP 200 且通过 WAV 载荷检查。
- 443 个资源样本无 Prometheus scrape failure、backend/model process exit、Hikari pending、executor queue/rejection、Redis eviction 或 MySQL slow query 增量。恢复尾窗结束时 heap、MySQL 连接和 Redis keys 均明显回落。
- JVM live threads 在 steady 首 5 分钟末为 198，随后分两次阶跃至 230，峰值 232，最后约 4 分钟及恢复尾窗保持平台但未回到基线。30 分钟内没有观察到持续线程增长，但该阶跃不能被写成“线程风险已完全消除”。
- Phase 16.9 在本机、100 VU、30 分钟、6 次串行真实 TTS 的明确范围内为 **VERIFIED_WITH_LIMITS**。它不证明 60 分钟以上、真实 ASR/视频 worker soak、并发 TTS、WebSocket 或云环境稳定性。

## 协议与环境

- 正式 run：`target/phase16-soak-live-20260913211423/`；应用基线 `e96383e5cfe4d8055c84f2683d393deb563c4203`。
- Java 17.0.19、MySQL 5.7.26、专用 Redis 3.2.100、k6 2.2.0，Windows 本机。
- Nginx 8080 → backend-1 8081/9091 + backend-2 8082/9092；两个 backend 各 `-Xmx512m`，共享隔离 schema、专用 Redis 6380/DB 14、真实 GPT-SoVITS 9880 与真实 FunASR 9977。
- 数据：1,000 注册账号、200 活跃账号、每活跃账号 10 个项目和 50 条历史任务、200 个公开音色。
- workload：login、voice list/search、courseware project list、task create/status；2% VU 在 warmup 和 steady 各创建一次任务。worker 轮询为 24 小时，所以 4 条任务只验证准入、持久化和轮询，不执行 Moonshot 或完整媒体链路。
- 一个指定 VU 每 300 秒调用一次 `/voice/synthesize`，因此 steady 内只有一个真实 TTS in-flight，遵守已经验证的 GPT-SoVITS 安全并发 1。FunASR 只参与 readiness，没有 ASR workload。

## HTTP 与 TTS 结果

| endpoint | requests | p50 ms | p95 ms | p99 ms | error rate | 结论 |
|---|---:|---:|---:|---:|---:|---|
| login | 100 | 481.51 | 494.32 | 503.36 | 0% | 独立 p95 < 750ms：MET |
| voice list | 22,181 | 1.93 | 2.88 | 40.71 | 0% | MET |
| voice search | 11,087 | 2.92 | 3.68 | 42.56 | 0% | MET |
| courseware list | 16,585 | 3.65 | 4.63 | 43.32 | 0% | MET |
| task create | 2 | 27.87 | 43.88 | 45.30 | 0% | MET |
| task status | 28 | 2.60 | 3.05 | 4.34 | 0% | MET |
| real TTS | 6 | 2,707.89 | 3,255.67 | 3,269.44 | 0% | 独立 p95/p99 < 30s：MET |

- L0 HTTP 状态：200=49,981、202=2；raw 的 L0 `http_reqs`、自定义 L0 counter 与 summary count 均为 49,983。
- TTS raw HTTP point、自定义 TTS counter 和 summary count 均为 6；HTTP 200=6，k6 checks rate=1，证明 6 次均通过状态与 WAV 头检查。
- Nginx 增量 upstream：backend-1=26,502、backend-2=26,878、other/retry log lines=2；两个实例都真实承载了流量。
- k6 退出码为 99，唯一越界是原脚本保留的 `endpoint:login p95 < 300ms`。本阶段没有静默修改旧阈值；报告同时保留 raw 阈值失败与独立登录 SLO 通过。

## 资源结果

全采集窗（warmup + steady + tail）：

| 指标 | 结果 |
|---|---:|
| samples / scrape failures | 443 / 0 |
| host CPU p95 / max | 11.89% / 28.79% |
| host available memory min | 557 MiB |
| two backend RSS max | 1,105.57 MiB |
| GPT-SoVITS + FunASR RSS max | 2,172.55 MiB |
| two JVM heap max | 385.75 MiB |
| GC pause delta / count | 0.253s / 101 |
| JVM live threads max | 232 |
| Tomcat busy/current max | 6 / 20 |
| Hikari active/pending max | 1 / 0 |
| executor active/queue max | 2 / 0 |
| task rejection delta | 0 |
| Redis keys/memory max | 405 / 1,286,456 B |
| Redis eviction delta | 0 |
| MySQL connected/running max | 21 / 2 |
| MySQL slow query delta | 0 |
| pending task max | 4 |

Redis ping p95 98.69ms 包含每次 `redis-cli.exe` 进程启动开销，不是 Redis 服务端命令延迟，不能据此宣称 Redis 本身为百毫秒级。

## 首尾趋势与恢复

| 指标 | steady 首 5m | steady 末 5m | 5m tail 末 |
|---|---:|---:|---:|
| host CPU average | 7.90% | 7.48% | 7.17% |
| available memory min | 653 MiB | 643 MiB | 946 MiB |
| backend RSS last | 1,054.08 MiB | 1,078.77 MiB | 1,082.63 MiB |
| model RSS last | 2,105.70 MiB | 2,158.99 MiB | 2,159.14 MiB |
| JVM heap p95 | 361.14 MiB | 315.94 MiB | 312.69 MiB |
| JVM heap last | 225.74 MiB | 208.60 MiB | 181.86 MiB |
| JVM live threads last | 198 | 230 | 230 |
| backend OS threads last | 279 | 312 | 310 |
| MySQL connections last | 8 | 6 | 3 |
| Redis keys last | 354 | 380 | 100 |

- heap、连接、Redis、queue 没有持续增长，且停止负载后回落；本轮 30 分钟内未观察到对应泄漏。
- backend RSS 首末相差约 24.7 MiB，model RSS 首末相差约 53.3 MiB，但末窗和 tail 已形成平台，没有与每个样本持续同向增长。模型缓存/allocator 保留是可能解释，不是本轮已证明的根因。
- JVM threads 增加 32 并在末段保持。TTS 调用链使用 WebClient，延迟初始化网络线程池是一种可能性，但本轮没有线程 dump，不能把可能性当作结论。生产前应做 60 分钟以上重复 soak，并在每次阶跃时保存两个 JVM 的 thread dump，按线程名前缀确认是否为有界池。

## 证据与清理

- `run-manifest.json` SHA-256：`9C067CC319E87E74BEE2DAD43EE02F6EFB8A3923EAD44DC5BD0CE91BF6AD6B21`
- `k6-summary.json` SHA-256：`1860411360864D52A4E342CA66A4235C15310709255A702BCB0F42560C66AB86`
- `resource-samples.jsonl` SHA-256：`551EE011CC6356F6EF9444423130BBC79F6EADEF24FB142AECEAFE1AE26A8891`
- `k6-raw.json` SHA-256：`92480A801EE9D7F51283419FDF34FDF11B664A75028C2540B1135BBAE5876D21`
- `phase16-summary.json` SHA-256：`2BD93025ECADD5A9D6452E9B9E2FB6A0858978C0FF2244E3EFC56DA8B6FBE6D0`
- 可提交摘要：`docs/performance/phase16-soak-aggregate-evidence.json`。
- runner 完成后隔离 schema 数量为 0；6380、8080、8081、8082、9091、9092 全部释放。日常 MySQL 3306、Redis 6379、GPT-SoVITS 9880、FunASR 9977 均保持可达。
- PowerShell 语法、k6 `inspect`、Phase 13/16.7 历史汇总兼容回放均通过；完整 Maven 为 150 tests、0 failures、0 errors、12 skipped，package 通过。12 个 skipped 仍是显式外部/环境集成测试，未当作成功证据。

## 边界

**VERIFIED：**本机双实例、100 VU、30 分钟 steady 的 mixed API；49,983 次 L0 请求与 6 次串行真实 TTS；当前分层 SLO；5 分钟恢复尾窗；本轮未观察到 heap、连接、Redis、queue、process-exit 或模型进程泄漏/耗尽。

**NOT VERIFIED：**60 分钟或更长、重复 run；200 VU soak；两个以上并发 TTS；真实 FunASR/FFmpeg/视频 worker 与 OSS 数据流 soak；任务实际消费后的 queue drain；WebSocket/HTTP streaming 长连接；线程阶跃根因；Linux、容器、跨主机、云规格和生产流量。

## 复现

确认 Java 17、MySQL 3306、真实 GPT-SoVITS 9880、真实 FunASR 9977 可用后执行：

```powershell
$env:JAVA_HOME = 'D:\BaiduNetdiskDownload\jdk-17.0.19+10'
.\performance\run_phase16_soak.ps1 -SteadyDuration 30m
```

runner 只使用唯一 `fctts_phase16_*` schema 和专用 Redis 6380/DB 14，完成后自动清理。任何命令、raw、日志、报告或提交文件都不得保存长期 OSS 或其他云凭证。

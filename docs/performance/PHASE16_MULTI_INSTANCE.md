# Phase 16.7：多实例真实验证

## 结论

- 两个真实 Spring Boot 实例通过 Nginx `least_conn` 共同连接隔离 MySQL、专用 Redis 和真实阿里云 OSS。100/200 VU 两档 mixed workload 均为 0% 业务错误，普通 API 和登录分别满足当前采用的 SLO。
- Nginx 增量 access log 记录 backend-1 3,351 次、backend-2 3,371 次 upstream 请求，证明两节点都实际承载了流量，不是只启动未使用。
- backend-1 登录签发的 JWT 可直接在 backend-2 使用；同一音频同时提交到两个节点只得到一个新任务和一个 duplicate 响应，MySQL 中只有一行。
- 两个活跃 worker 竞争该真实 ASR 任务时，审计触发器只记录一次 `PENDING → RUNNING`，任务以 `SUCCESS / attempts=1` 完成，并可从提交节点之外的实例查询和下载 OSS 结果。
- 两个 cleanup worker 竞争同一 `VOICE_OBJECT` 记录时只记录一次 claim；记录被删除，OSS 结果随后不可读取。backend-1 下线后，关闭前签发的 JWT 经 Nginx 连续请求 20/20 成功，backend-2 readiness 保持 `UP`；backend-1 重启后仍能读取该任务的 `SUCCESS / attempts=1`。
- Phase 16.7 功能结论为 **VERIFIED**。容量结论仅限本机双实例 HTTP 与一条真实 ASR 功能链路；200 VU 时主机可用内存最低约 370 MiB，因此该主机组合没有足够余量支撑更重并发，不能外推云规格或多机水平扩展能力。

## 正式环境与方法

- 正式 run：`p16_20260913194657`，代码基线 `b7a9cd5aabbdfdbac5cc9ce7aafec17440b1b069`。
- Java 17.0.19、MySQL 5.7.26、k6 2.2.0；Windows 本机运行真实 FunASR、GPT-SoVITS、两个 `-Xmx512m` 后端和项目 Nginx。
- 每轮创建唯一 `fctts_phase16_*` schema，准备 1,000 个注册账号、200 个活跃账号、每活跃账号 10 个项目和 50 条历史任务；Redis 使用独立 6380/DB 14 进程，关闭持久化。
- mixed workload 为登录、音色列表/搜索、课件列表、任务创建/轮询。2% VU 创建任务：100 VU 为 2 次创建和 28 次轮询，200 VU 为 4 次创建和 56 次轮询。
- 负载阶段 worker 每 24 小时轮询，只验证入队与查询，不把 Moonshot 等重任务混入 HTTP 容量。负载结束后删除尚未执行的隔离测试任务，重启双节点并启用每节点 1 个、250ms 轮询的 worker，执行一条真实 ASR 功能验收。
- readiness 显式要求真实 GPT-SoVITS 与 FunASR 可达。对象输入、结果和删除均走应用的真实 OSS service；raw/报告不保存 OSS endpoint、bucket 或凭证。
- 隔离 schema 内临时创建 task/cleanup claim 审计表和触发器；它们只记录状态领取事件，随 schema 一并删除，不进入生产迁移。

## HTTP 负载结果

| VU | steady 请求 | RPS | 错误率 | 总体 p50/p95/p99 | login p95 | 普通 API 最差 p95 |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 1,892 | 31.53 | 0% | 4.11 / 478.28 / 487.94 ms | 497.43 ms | 61.29 ms（task create） |
| 200 | 3,711 | 61.85 | 0% | 3.13 / 480.53 / 500.49 ms | 511.21 ms | 50.49 ms（task create） |

逐端点 p95（ms）：

| VU | voice list | voice search | courseware list | task create | task status |
|---:|---:|---:|---:|---:|---:|
| 100 | 5.36 | 5.50 | 6.67 | 61.29 | 9.05 |
| 200 | 3.27 | 4.15 | 5.15 | 50.49 | 3.23 |

`core-api.js` 保留原有统一 `p95 < 300ms` 阈值，所以 k6 对两档均返回 99，并明确只跨越总体与 `endpoint:login` 的阈值。本阶段没有静默修改它：普通 API 仍按 p95 300ms 评价并通过；登录按 Phase 16.1 接受的独立 p95 750ms SLO 评价，497.43/511.21ms 均通过。原始 k6 threshold 失败与当前分层 SLO 通过必须同时保留。

## 资源观测

| VU | host CPU p95 | 可用内存最低 | 两后端 RSS 峰值 | 两 JVM heap 峰值 | GC pause delta | Tomcat busy max |
|---:|---:|---:|---:|---:|---:|---:|
| 100 | 28.60% | 417 MiB | 1,018.84 MiB | 345.73 MiB | 0.016s / 3 次 | 6 |
| 200 | 44.34% | 370 MiB | 1,037.88 MiB | 362.72 MiB | 0.014s / 4 次 | 12 |

- 两档 Hikari pending max=0、executor queued max=0、executor rejected delta=0、MySQL slow query delta=0，两个 management endpoint 的 scrape failure=0。
- MySQL connected max 为 21/10，running max 均为 1；任务 PENDING 峰值 2/4，正好对应本轮只入队不执行的 task share。
- Redis CLI PING p95 为 107.81/114.78ms。采集实现每次启动 `redis-cli.exe` 并把进程启动计入秒表，因此它不是 Redis 服务端命令延迟，不能用于声明 Redis 亚毫秒或百毫秒容量。
- 200 VU 时整机可用内存最低只有约 370 MiB。测试未出现 OOM 或请求错误，但这是真实的宿主机余量告警；在 Phase 16.9 soak 前不应继续在同机提高重任务并发。

## 多实例功能验收

| 验收项 | 结果 | 证据 |
|---|---|---|
| 跨节点 JWT | VERIFIED | backend-1 登录，token 直接访问 backend-2 成功 |
| 并发幂等提交 | VERIFIED | 2 个节点同时提交同一文件：1 created、1 duplicate、1 DB row、相同 taskId |
| 双 worker task claim | VERIFIED | 1 claim event、1 distinct worker、`SUCCESS / attempts=1` |
| 跨节点任务与 OSS | VERIFIED | 从另一 backend 查询成功并下载 161 B 结果，SHA-256 已记录在 raw |
| 双 cleanup worker | VERIFIED | 1 claim event、1 distinct worker、cleanup row 删除；随后结果下载 HTTP 500 |
| backend-1 下线 | VERIFIED | 旧 token 经 Nginx 20/20 成功；backend-2 readiness=`UP` |
| backend-1 重启 | VERIFIED | readiness=`UP`；任务仍为 `SUCCESS / attempts=1` |

cleanup 后下载返回 HTTP 500，是因为 metadata 仍存在而底层 OSS key 已删除，`IOException` 被当前全局 handler 映射为 500。它证明对象已不可读和 cleanup 没有重复 claim，但也暴露了一个语义边界：对象缺失更适合返回 404；本阶段不为此扩大业务修改范围。

## 证据与清理

- 正式 raw 根目录：`target/phase16-multi-live-20260913194657/`（Git 忽略）。
- 聚合 raw：`phase16-summary.json`，SHA-256 `B52D38BBF228F439850E184D17031F5525BB5F393FDE8FD5C3C1E54F28965A35`。
- 功能 raw：`phase16-multi-functional-raw.json`，SHA-256 `F8EC792290F42C45BAE2046E970FCEF71AEC11A9BFF8EE5259B20E6E38899145`。
- 两档 raw 中 steady/L0 的 `http_reqs`、自定义请求点和 summary count 分别都为 1,892/3,711；状态分布分别为 `200=1,890, 202=2` 和 `200=3,707, 202=4`，交叉校验一致。
- 可提交摘要：`docs/performance/phase16-multi-instance-aggregate-evidence.json`。
- runner 最终已停止 Nginx、五个先后启动的后端进程和专用 Redis，删除正式隔离 schema；8080/8081/8082/9091/9092/6380 均无监听，业务 schema `zhiyunjiaos` 未修改。
- 一个更早的诊断 run 因 PowerShell 把字符串标量首字符转成 ASCII 49 而误报 DB row count，已拒绝作为证据；修复只涉及验收脚本的标量读取，正式 run 从新 schema 完整重跑。该诊断 run 的精确测试用户前缀下发现 1 个 OSS 遗留对象，已定向删除 1 个并复查剩余 0，没有列举或删除其他前缀。
- 本轮沿用了已经在对话中暴露的长期 OSS 凭证。即使功能通过，生产前仍必须立即轮换，并改用目标 bucket/prefix 最小权限的 RAM 角色或短期 STS；不得把长期密钥写入仓库、镜像、日志或报告。

## 已验证与未验证边界

**VERIFIED：**本机 Nginx 到双实例真实分流；无状态 JWT；共享 MySQL/Redis；跨 JVM 活跃任务幂等；双 worker 单次 claim；真实 ASR + OSS 结果跨节点访问；双 cleanup worker 单次 claim；单 backend 下线后的 Nginx 连续服务；任务终态跨 backend 重启保持；100/200 VU mixed HTTP 在分层 SLO 下通过。

**NOT VERIFIED：**跨物理机/容器/云主机；TLS；Nginx 自身高可用；WebSocket 跨实例与连接迁移；200 VU 以上；长时间连接复用；多条真实 ASR/TTS/视频任务并发；worker 处理中被杀；Redis/MySQL/模型/Nginx 故障注入；30/60 分钟 soak；云 OSS 同地域带宽；生产 RAM/STS、网络、安全组、监控告警与扩缩容。

下一阶段必须进入 Phase 16.8 故障注入；本阶段结果不能提前替代故障恢复、soak 或最终云生产资格结论。

## 复现

确认 MySQL、真实 FunASR、真实 GPT-SoVITS 可用，且被 Git 忽略的本地配置含有效 OSS 凭证后执行：

```powershell
.\performance\run_phase11.ps1 `
  -RunPhase phase16-multi `
  -UserLevels 100,200 `
  -Repetitions 1 `
  -WarmupDuration 10s `
  -SteadyDuration 60s `
  -StabilizationSeconds 0 `
  -CollectorTailSeconds 10 `
  -TaskSharePercent 2

.\performance\summarize_results.ps1 `
  -ResultsDirectory '<PHASE16_RESULTS 输出目录>'
```

运行前必须让 `JAVA_HOME` 指向 Java 17，并保持 8080/8081/8082/9091/9092/6380 空闲。脚本只接受 100/200 VU 的固定 Phase 16.7 参数组合，任何 raw、命令或提交文件都不得包含明文凭证。

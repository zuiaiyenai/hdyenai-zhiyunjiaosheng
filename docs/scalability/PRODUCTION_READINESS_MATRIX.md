# 智韵教声生产就绪矩阵

> 证据截止：2026-09-14
>
> 最终评级：`READY_WITH_LIMITS`
>
> 状态词限定：每项只使用 `VERIFIED`、`PARTIAL`、`BLOCKED` 或 `FAILED`。状态仅适用于“证据范围”，不能跨环境外推。

## 判定矩阵

| 项目 | 状态 | 已有证据 | 尚未覆盖 / 生产限制 |
| --- | --- | --- | --- |
| HTTP API | VERIFIED | 10/50/100/200 VU L0 多轮；Phase 16.7 双实例 200 VU 为 3,711 requests、61.85 req/s、0 error，普通 API 最差 p95 50.50ms | 200 VU 以上、无 think time 极限吞吐、互联网/云网络未测 |
| login | VERIFIED | BCrypt cost 12 保留；10/50/100/200 VU 共 7,083 次 steady 登录、0 error；最高 p95 563.67ms、p99 581.90ms，认证专用 SLO 全通过 | 200 同秒突发和高于 6.55 login/s 未测；旧普通 API 300ms 阈值不适用且仍会失败 |
| MySQL | PARTIAL | 双实例共享状态、原子 admission/claim、事务正确性、restart 后 1.875s 业务恢复；soak Hikari pending max=0 | 单点；未验证主从/托管 HA、数据丢失、网络分区、备份恢复、云磁盘与故障负载 |
| Redis | PARTIAL | 双实例缓存/限流共享；Lua 限流正确性；restart 后 8.079s 业务恢复；soak eviction delta=0 | 单点；未验证 Sentinel/Cluster/托管 HA、持久化、数据丢失、网络分区和故障负载 |
| JWT | VERIFIED | backend-1 登录签发 token 后直接访问 backend-2 成功；单 backend 下线时旧 token 经 Nginx 20/20 成功 | 密钥轮换与跨主机 secret 分发未在目标云验证 |
| multi-instance | VERIFIED | 同一 Windows 主机双 Java 17 实例经 Nginx 共享 MySQL/Redis/OSS；100/200 VU、跨节点 task/OSS、节点下线/恢复均通过 | 不代表跨物理机、容器、可用区或云负载均衡；200 VU 时内存余量约 370MiB |
| task claim | VERIFIED | 原子领取、幂等提交、双 worker 仅 1 claim；worker crash 后另一实例从 RUNNING/attempt 1 恢复到 SUCCESS/attempt 2 | 外部服务副作用 exactly-once 未证明；组合高到达率下的 queue wait 未定标 |
| file cleanup | VERIFIED | 数据库原子 claim、owner、stale recovery、retry、幂等 delete；双 worker live 验证只有 1 claim/1 owner | 云端大规模积压、OSS 限流/分区和长期清理吞吐未测 |
| local storage | PARTIAL | 100/500MiB 媒体搬运在 `-Xmx96m` 下完成；Path/InputStream 流式化和临时文件清理已验证 | 不是多实例永久共享存储；Linux 文件系统、磁盘满、慢盘、断连与跨节点访问未测 |
| object storage | VERIFIED | 真实阿里云 OSS 10/100/500MiB upload/download/SHA/delete 全通过；4×10MiB 并发 4/4；缺失、中断、错误凭证有界失败 | safe concurrency、同区域云带宽、multipart/resumable、限流/DNS/5xx、生命周期/容灾未测；长期凭证必须轮换 |
| GPT-SoVITS | VERIFIED | 真实模型独立容量、真实视频链路、6 次 soak TTS、进程中断 0.031s 返回 503 且恢复后真实 WAV 200 | 当前安全并发=1；长文本/音色分布、跨实例全局准入、长时 soak、Linux/云未测 |
| FunASR | VERIFIED | 真实模型 1/2/4 并发；固定 5.29s 音频 safe concurrency=1；真实视频链路；中断任务明确 FAILED、恢复后 SUCCESS | 长音频、编码/噪声/准确率、组合 worker soak、跨实例聚合和云未测 |
| FFmpeg | VERIFIED | 固定 30s 输入 1/2/4/8 容量测试；整机安全并发=2；small/medium 真实视频链路输出可解码 | 分辨率/编码矩阵、长视频、与模型/OSS 并发竞争、目标云 CPU 未测 |
| video pipeline | PARTIAL | small 5.291s 与 medium 10s 真实全链路 2/2 SUCCESS；E2E 15.755/23.246s；重启后可下载；临时文件清理 | large、并发任务、失败率分布、组合故障、媒体 soak、云环境未测 |
| WebSocket | PARTIAL | 有限握手可由 Nginx 分配至两个实例；协议与重连边界已定义 | 未测连接容量、消息吞吐、慢客户端、连接迁移、节点故障重连风暴和云 LB |
| Nginx | PARTIAL | 双 upstream 真实分流；单 upstream 丢失时旧 JWT 20/20 成功；backend 可恢复 | Nginx 自身仍是单点；未验证进程故障、双 upstream 故障、TLS、云 LB 和长连接容量 |
| failure recovery | PARTIAL | Redis、MySQL、真实 GPT-SoVITS、真实 FunASR、单 upstream、worker/backend crash 六条单探针路径通过；无永久 RUNNING | 故障时无 100/200 VU；无池/线程曲线；无网络分区、磁盘满、OSS、复合故障与多轮恢复分布 |
| soak | PARTIAL | 100 VU、30m steady、49,983 L0、6 次真实 TTS、0 业务错误；heap/连接/Redis/queue 未持续增长 | 未达 60m、未重复；ASR/FFmpeg/video/OSS/worker 消费/WebSocket 未入 workload；线程阶跃未归因 |
| cloud | BLOCKED | 当前只有本机 Windows、真实公网 OSS 和本机模型证据 | 未绑定/验证目标 Linux/容器/跨主机拓扑、规格、TLS、安全组、HA、备份恢复、监控告警、供应商 quota；凭证轮换未完成 |

## 最终评级

**`READY_WITH_LIMITS`**

判定逻辑：

- 不是 `NOT_READY`：核心双实例 HTTP、登录专用 SLO、共享状态正确性、任务/cleanup 原子领取、真实 OSS、真实 GPT-SoVITS、真实 FunASR、FFmpeg、small/medium 视频、真实依赖恢复和一次 30 分钟 mixed+TTS soak 已有直接证据。
- 不是 `READY`：`cloud` 仍为 `BLOCKED`，八项为 `PARTIAL`；基础设施 HA、WebSocket、负载中故障、长时重复媒体 soak 和目标云容量仍缺失。

## 允许部署的限制

- GPT-SoVITS 单服务并发不得超过 1。
- FunASR 单服务按当前证据并发不得超过 1。
- 同一已测主机的 FFmpeg 整机并发不得超过 2。
- 多实例永久文件必须使用共享对象存储，local storage 只保存临时工作副本。
- 不允许宣称 1,000 concurrent users 或 1,000 simultaneous heavy jobs。
- 已暴露的 OSS 长期凭证必须立即轮换，并改用最小权限 RAM 或短期 STS。
- 公网生产前必须先关闭 `cloud` 阻断项，并完成目标环境 HA、TLS、安全组、监控告警与至少 60 分钟重复媒体 soak。

详细数值、提交映射、证据哈希和风险说明见 [最终可扩展性与容量报告](FINAL_SCALABILITY_REPORT.md)。

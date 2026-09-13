# Phase 11：10 并发用户普通 API 压测

## 结论

- 执行证据：`VERIFIED`。3 轮均完成 2 分钟预热、10 分钟稳态和资源尾窗，错误率均为 0%。
- 非登录普通 API：`VERIFIED`。最坏 endpoint p95 为 17.40 ms，满足 p95 < 300 ms。
- 完整 L0 SLO：`NOT MET`。登录 p95 为 547.47–579.76 ms，超过 300 ms；因此不能把本档写成“所有普通 API 均 PASS”。
- 重媒体与文件吞吐：`NOT RUN`。本结果不证明 TTS、ASR、GPT-SoVITS、FFmpeg、视频生成或 OSS 上传下载容量。

## 协议与环境

- 数据：1000 个注册账号、200 个活跃账号、每个活跃账号 10 个课件和 50 条历史任务、200 条公开音色。
- 拓扑：Nginx 8080 -> 两个 Spring Boot 实例 -> 共享 MySQL；压测专用 Redis 6380/DB 14。
- 每轮：10 closed-model VU；2 分钟 warmup；10 分钟 steady；20% 用户执行任务创建和状态轮询。
- 应用提交：`7e3c9abc901aac72c024c96edd88ca965d81fbb6`；k6 v2.2.0；Java 25.0.3；MySQL 5.7.26。

## 三轮结果

| run | requests | RPS | p50 ms | p95 ms | p99 ms | error | login p95 ms | 非登录最坏 p95 ms | CPU p95 | 双 JVM heap used 最大 | Tomcat busy 最大 | Hikari active/pending 最大 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10u-r1 | 1670 | 2.783 | 4.885 | 8.284 | 49.858 | 0% | 547.475 | 13.996 | 14.42% | 365.36 MiB | 2 | 1 / 0 |
| 10u-r2 | 1663 | 2.772 | 4.220 | 6.704 | 40.641 | 0% | 579.762 | 17.397 | 18.39% | 352.51 MiB | 2 | 0 / 0 |
| 10u-r3 | 1654 | 2.757 | 3.586 | 5.567 | 37.005 | 0% | 551.797 | 9.181 | 23.09% | 355.23 MiB | 0 | 0 / 0 |

RPS 是包含真实 think time 的用户旅程吞吐，不是无停顿极限吞吐。

## Endpoint 最坏值

| endpoint | 三轮请求数 | p95 最大 ms | p99 最大 ms | error 最大 | SLO |
|---|---:|---:|---:|---:|---|
| login | 30 | 579.762 | 584.375 | 0% | `NOT MET` |
| voice_list | 2144 | 6.207 | 35.107 | 0% | `VERIFIED` |
| voice_search | 1088 | 7.247 | 39.491 | 0% | `VERIFIED` |
| courseware_list | 1635 | 8.547 | 44.245 | 0% | `VERIFIED` |
| task_create | 6 | 17.397 | 17.688 | 0% | `VERIFIED` |
| task_status | 84 | 5.830 | 6.618 | 0% | `VERIFIED` |

## 资源边界

三轮共 485 个资源样本。最坏观察值：主机 CPU p95 23.1%、CPU 峰值 36.3%、可用内存最低 3182 MiB、双 JVM heap used 最大 365.4 MiB、GC pause 增量最大 0.081 秒、JVM live threads 最大 170、Tomcat busy 最大 2、Hikari active/pending 最大 1/0、executor queued/rejected 0/0、MySQL connected/running 最大 21/2、slow query 增量 0。

Redis PING p95 最大 93.7 ms 包含 Windows 上启动 `redis-cli.exe` 的进程开销，只能作为采集往返上界，不能冒充纯 Redis 服务端处理延迟。

原始证据位于被 Git 忽略的 `target/phase11-live-20260913040849`；可提交聚合证据见 `phase11-aggregate-evidence.json`。

# Phase 11：200 并发活跃用户普通 API 压测

## 结论

- 执行证据：`VERIFIED`。在 1000 注册用户数据基线上，3 轮 200 closed-model VU 均完整执行，错误率均为 0%。
- 非登录普通 API：`VERIFIED`。最坏 endpoint p95 为 27.93 ms，满足 p95 < 300 ms；总体 p95 最大 5.11 ms、p99 最大 39.73 ms。
- 完整 L0 SLO：`NOT MET`。登录 p95 为 494.55–564.39 ms，持续超过 300 ms。
- 容量措辞：可以证明“200 并发活跃用户的非登录 L0 API 在本机双实例环境满足既定延迟/错误率 SLO”；不能写成“所有普通 API PASS”，更不能写成“支持 1000 人同时使用”。
- 重媒体、文件吞吐、30/60 分钟 soak 和故障注入：`NOT RUN`，分别留给 Phase 12–14。

## 协议与环境

- 1000 个注册账号，前 200 个为活跃账号；每个活跃账号 10 个课件、50 条历史成功任务；200 条公开音色。
- Nginx 8080 -> backend-1:8081/backend-2:8082 -> 共享 MySQL 与专用 Redis 6380/DB 14。
- 每轮 2 分钟 warmup + 10 分钟 steady；200 VU 中 40 名用户执行任务创建和状态轮询；worker 24 小时轮询，媒体不执行。
- 3 轮之间清除本轮 PENDING 测试任务并恢复 30 秒；资源采样覆盖施压和 90 秒尾窗。

## 三轮结果

| run | requests | RPS | p50 ms | p95 ms | p99 ms | error | login p95 ms | 非登录最坏 p95 ms | CPU p95 | 双 JVM heap used 最大 | Tomcat busy 最大 | Hikari active/pending 最大 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 200u-r1 | 33568 | 55.947 | 2.355 | 4.382 | 37.669 | 0% | 503.393 | 13.561 | 35.15% | 326.96 MiB | 11 | 1 / 0 |
| 200u-r2 | 33520 | 55.867 | 2.514 | 4.910 | 39.731 | 0% | 494.550 | 22.537 | 54.21% | 321.28 MiB | 13 | 2 / 0 |
| 200u-r3 | 33463 | 55.772 | 2.543 | 5.114 | 39.432 | 0% | 564.393 | 27.929 | 68.43% | 325.88 MiB | 15 | 1 / 0 |

## Endpoint 最坏值

| endpoint | 三轮请求数 | p95 最大 ms | p99 最大 ms | error 最大 | SLO |
|---|---:|---:|---:|---:|---|
| login | 600 | 564.393 | 583.546 | 0% | `NOT MET` |
| voice_list | 43612 | 2.814 | 28.755 | 0% | `VERIFIED` |
| voice_search | 21840 | 4.079 | 31.478 | 0% | `VERIFIED` |
| courseware_list | 32699 | 5.523 | 29.035 | 0% | `VERIFIED` |
| task_create | 120 | 27.929 | 41.221 | 0% | `VERIFIED` |
| task_status | 1680 | 3.172 | 35.607 | 0% | `VERIFIED` |

## 资源边界

三轮共 484 个资源样本。最坏观察值：CPU p95 68.4%、CPU 峰值 85.4%、可用内存最低 1417 MiB、双 JVM heap used 最大 327.0 MiB、GC pause 增量最大 0.104 秒、live threads 最大 179、Tomcat busy 最大 15、Hikari active/pending 最大 2/0、executor active/queued 最大 2/0、task rejected 增量 0、MySQL connected/running 最大 21/2、slow query 增量 0、PENDING 测试任务最大 80。

Redis keys 最大 880；Redis PING p95 最大 101.4 ms 含 `redis-cli.exe` 进程启动成本，只作采集往返上界。

## 已证明与未证明

`VERIFIED`：双实例普通 API 路径、共享 MySQL/Redis、200 VU 下非登录 endpoint 延迟和 0% 错误率、资源未出现 Hikari pending/executor queue/rejection。

`NOT MET`：登录 p95 < 300 ms。当前 BCrypt strength 12 是首要可见瓶颈，不能通过降低密码强度来草率换取 PASS；后续应先分析认证容量隔离、CPU 预算和登录 SLO 是否合理。

`NOT RUN`：GPT-SoVITS、FunASR、FFmpeg、视频、OSS 吞吐、长时间 soak、依赖故障恢复。

# Phase 11：100 并发用户普通 API 压测

## 结论

- 执行证据：`VERIFIED`。3 轮完整执行，错误率均为 0%。
- 非登录普通 API：`VERIFIED`。最坏 endpoint p95 为 17.31 ms，满足 p95 < 300 ms。
- 完整 L0 SLO：`NOT MET`。登录 p95 为 483.83–561.36 ms，超过 300 ms。
- 重媒体与文件吞吐：`NOT RUN`。

## 协议与环境

使用相同的 1000 注册/200 活跃数据集和双后端拓扑。每轮 100 closed-model VU，2 分钟预热、10 分钟稳态；20 名用户执行任务创建/状态轮询。应用提交为 `7e3c9abc901aac72c024c96edd88ca965d81fbb6`。

## 三轮结果

| run | requests | RPS | p50 ms | p95 ms | p99 ms | error | login p95 ms | 非登录最坏 p95 ms | CPU p95 | 双 JVM heap used 最大 | Tomcat busy 最大 | Hikari active/pending 最大 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100u-r1 | 16815 | 28.025 | 2.664 | 4.818 | 39.432 | 0% | 549.908 | 16.018 | 39.30% | 346.10 MiB | 7 | 1 / 0 |
| 100u-r2 | 16721 | 27.868 | 2.576 | 4.701 | 36.716 | 0% | 483.835 | 16.398 | 40.98% | 330.31 MiB | 11 | 1 / 0 |
| 100u-r3 | 16803 | 28.005 | 2.525 | 4.582 | 38.657 | 0% | 561.355 | 17.306 | 31.10% | 327.17 MiB | 8 | 1 / 0 |

## Endpoint 最坏值

| endpoint | 三轮请求数 | p95 最大 ms | p99 最大 ms | error 最大 | SLO |
|---|---:|---:|---:|---:|---|
| login | 300 | 561.355 | 571.712 | 0% | `NOT MET` |
| voice_list | 21824 | 2.753 | 28.742 | 0% | `VERIFIED` |
| voice_search | 10905 | 3.891 | 30.984 | 0% | `VERIFIED` |
| courseware_list | 16410 | 5.117 | 29.286 | 0% | `VERIFIED` |
| task_create | 60 | 17.306 | 32.953 | 0% | `VERIFIED` |
| task_status | 840 | 16.398 | 32.632 | 0% | `VERIFIED` |

## 资源边界

三轮共 485 个资源样本。最坏观察值：CPU p95 41.0%、CPU 峰值 63.0%、可用内存最低 1791 MiB、双 JVM heap used 最大 346.1 MiB、GC pause 增量最大 0.068 秒、live threads 最大 170、Tomcat busy 最大 11、Hikari active/pending 最大 1/0、executor queued/rejected 0/0、MySQL connected/running 最大 21/2、slow query 增量 0。

100 VU 下持续读/API 路径仍有明显资源余量，但这不是 100 个重型媒体任务的证明。

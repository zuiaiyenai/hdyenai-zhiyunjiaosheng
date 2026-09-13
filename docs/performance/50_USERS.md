# Phase 11：50 并发用户普通 API 压测

## 结论

- 执行证据：`VERIFIED`。3 轮完整执行，错误率均为 0%。
- 非登录普通 API：`VERIFIED`。最坏 endpoint p95 为 24.46 ms，满足 p95 < 300 ms。
- 完整 L0 SLO：`NOT MET`。登录 p95 为 494.32–569.49 ms，超过 300 ms。
- 重媒体与文件吞吐：`NOT RUN`。

## 协议与环境

使用与 10 用户档相同的 1000 注册/200 活跃数据集和双后端拓扑。每轮 50 closed-model VU，2 分钟预热、10 分钟稳态；10 名用户执行任务创建/状态轮询。应用提交为 `7e3c9abc901aac72c024c96edd88ca965d81fbb6`。

## 三轮结果

| run | requests | RPS | p50 ms | p95 ms | p99 ms | error | login p95 ms | 非登录最坏 p95 ms | CPU p95 | 双 JVM heap used 最大 | Tomcat busy 最大 | Hikari active/pending 最大 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50u-r1 | 8363 | 13.938 | 2.942 | 4.899 | 37.288 | 0% | 494.318 | 15.932 | 24.73% | 352.71 MiB | 5 | 1 / 0 |
| 50u-r2 | 8428 | 14.047 | 2.824 | 4.670 | 37.849 | 0% | 569.495 | 24.458 | 27.62% | 347.73 MiB | 4 | 1 / 0 |
| 50u-r3 | 8437 | 14.062 | 2.841 | 4.856 | 42.928 | 0% | 558.186 | 13.203 | 28.32% | 348.02 MiB | 4 | 1 / 0 |

## Endpoint 最坏值

| endpoint | 三轮请求数 | p95 最大 ms | p99 最大 ms | error 最大 | SLO |
|---|---:|---:|---:|---:|---|
| login | 150 | 569.495 | 573.755 | 0% | `NOT MET` |
| voice_list | 10930 | 3.021 | 33.252 | 0% | `VERIFIED` |
| voice_search | 5471 | 3.998 | 34.153 | 0% | `VERIFIED` |
| courseware_list | 8227 | 5.189 | 35.520 | 0% | `VERIFIED` |
| task_create | 30 | 24.458 | 29.672 | 0% | `VERIFIED` |
| task_status | 420 | 3.065 | 23.008 | 0% | `VERIFIED` |

## 资源边界

三轮共 486 个资源样本。最坏观察值：CPU p95 28.3%、CPU 峰值 51.9%、可用内存最低 2642 MiB、双 JVM heap used 最大 352.7 MiB、GC pause 增量最大 0.052 秒、live threads 最大 172、Tomcat busy 最大 5、Hikari active/pending 最大 1/0、executor queued/rejected 0/0、MySQL connected/running 最大 21/2、slow query 增量 0。

原始证据与 Redis PING 口径同 `10_USERS.md`；本结果不外推媒体任务容量。

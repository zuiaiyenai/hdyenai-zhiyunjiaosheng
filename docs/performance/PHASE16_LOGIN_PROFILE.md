# Phase 16.1：登录链路 Profiling

## 结论

- 结论：**ACCEPTED**，不是 `FIXED`。本阶段没有降低 BCrypt 强度，也没有发现值得优化的重复数据库查询、Redis 往返、锁竞争或序列化热点。
- 原先套用普通 API 的 `p50 < 100 ms / p95 < 300 ms` SLO 仍然 **NOT MET**，但该目标不适合包含 BCrypt 密码校验的登录端点。登录改用认证专用 SLO：在不降低 BCrypt cost 12 的前提下，`error rate < 1%`、`p95 < 750 ms`、`p99 < 1000 ms`。10/50/100/200 VU 四档全部满足。
- 四档 10 分钟 steady 共完成 7,083 次登录，业务错误率均为 0。最高 p95 为 563.67 ms，最高 p99 为 581.90 ms，最大单次为 671.08 ms。
- BCrypt 平均耗时为 485.90–525.14 ms，占控制器总耗时 99.18%–99.47%，是明确主瓶颈。DB 查询、Redis rate-limit 查询与 JWT 生成均不是主因。
- 这里的 200 VU 是 200 个活跃虚拟用户，每个 VU 每 30 秒登录一次并随机错峰；实测为 6.55 login/s。它不等于 200 个请求在同一毫秒开始，也不能外推为 200 个并行 BCrypt 作业或 1000 并发登录。

## 环境与协议

- 时间：2026-09-13 22:29–23:26（Asia/Shanghai）。
- 应用提交：`89c8b7b1819424ba7d9f81c20f2c7db76cc0c437`。
- 运行时：Eclipse Temurin Java 17.0.19；Maven 与两个后端均显式使用同一个 `JAVA_HOME`，manifest 记录了实际 Java 可执行文件。
- 主机：Windows，32 logical processors。
- 拓扑：Nginx `8080` → 两个 Spring Boot backend `8081/8082` → 共享 MySQL 5.7.26 与专用 Redis `6381/DB 13`。
- 数据：隔离的 1000 注册账号基线；每档 2 分钟 warmup + 10 分钟 steady + 90 秒 collector tail；每档 1 次。
- 行为：每个 VU 每 30 秒登录一次，账号按 VU 稳定映射，场景切换保留 30 秒 graceful stop，warmup 与 steady 指标分开标记。
- 隔离：专用 `fctts_phase16_*` schema、无持久化的专用 Redis 进程；没有修改或清理业务 schema 与日常 Redis。

## HTTP 结果

| VU | steady requests | login/s | p50 ms | p95 ms | p99 ms | max ms | error rate | 认证 SLO |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 10 | 197 | 0.33 | 488.34 | 514.42 | 537.32 | 565.68 | 0% | MET |
| 50 | 986 | 1.64 | 505.02 | 559.10 | 568.77 | 599.88 | 0% | MET |
| 100 | 1,969 | 3.28 | 531.15 | 563.67 | 574.02 | 613.90 | 0% | MET |
| 200 | 3,931 | 6.55 | 529.70 | 561.97 | 581.90 | 671.08 | 0% | MET |

`login/s` 使用 steady 请求数除以 600 秒计算。k6 counter 自带的 rate 使用包含 warmup 的场景总时间作分母，因此不作为 steady 吞吐量。

## 分段结果

以下为 collector 的 10 分钟 wall-clock steady 窗口内，两个 backend 的 Micrometer Timer `count/sum` 增量计算出的平均值。场景边界与 5 秒采样边界最多存在一个采样间隔的偏差，因此 count 与 k6 的严格 steady 标签可能相差少量请求；HTTP 百分位与错误率以 k6 标签结果为准。

| VU | total avg ms | rate limit avg ms | Redis avg ms | DB avg ms | BCrypt avg ms | JWT avg ms | BCrypt / total |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 489.94 | 1.94 | 1.84 | 1.50 | 485.90 | 0.33 | 99.18% |
| 50 | 505.63 | 2.12 | 2.07 | 1.20 | 502.03 | 0.16 | 99.29% |
| 100 | 528.07 | 1.77 | 1.75 | 0.96 | 525.14 | 0.12 | 99.44% |
| 200 | 526.57 | 1.84 | 1.82 | 0.82 | 523.78 | 0.07 | 99.47% |

`redis` 是 `rate_limit` 内部的实际 Spring Redis 调用，两者是嵌套关系，不能相加。独立 `redis-cli PING` 的 p95 为 110.94–119.86 ms，其中包含 Windows 进程启动、连接与认证开销，不能解释为 Redis 服务端 RTT。

## 资源结果

| VU | host CPU avg / p95 / max | 双 JVM RSS max | 双 JVM heap max | GC pause / count | MySQL connected / running max | Hikari pending max | Tomcat busy max | 可用内存 min |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 5.13% / 9.16% / 46.56% | 957.9 MiB | 361.7 MiB | 0.029 s / 8 | 4 / 1 | 0 | 2 | 4437 MiB |
| 50 | 11.73% / 20.88% / 38.69% | 1001.6 MiB | 360.8 MiB | 0.032 s / 8 | 5 / 1 | 0 | 4 | 2588 MiB |
| 100 | 14.03% / 18.58% / 29.26% | 1015.8 MiB | 370.2 MiB | 0.044 s / 10 | 9 / 1 | 0 | 4 | 2536 MiB |
| 200 | 23.04% / 42.91% / 69.40% | 916.6 MiB | 368.3 MiB | 0.030 s / 10 | 14 / 1 | 0 | 7 | 1126 MiB |

200 VU 时未观察到 CPU、Tomcat、Hikari 或 GC 饱和，但本机最低可用内存只有 1126 MiB，属于容量风险信号。它不否定本次登录测试，却说明生产部署不能照搬本机余量，仍需按目标主机重新验证。

## Security vs performance

- 当前 `PasswordEncoder` 明确使用 `BCryptPasswordEncoder(12)`。cost 12 的计算开销是主动安全控制：它同时提高正常登录成本与离线破解成本。
- 降低 cost 会直接改善压测数字，也会降低密码哈希抵抗能力；本阶段禁止以这种方式达标，因此保持 12。
- BCrypt 是 CPU 密集型且不可缓存的用户秘密校验。容量规划应以登录速率单独建模，并通过登录限流、足够 CPU、横向实例和真实峰值压测承载，而不是把普通读 API 的毫秒级 SLO 强加给登录。
- 本次只证明当前主机、双实例与 6.55 login/s 的周期登录负载。若业务需要突发批量重新登录，应另做 arrival-rate 阶梯测试并设置独立认证容量门槛。

## 证据完整性与边界

- raw 目录：`target/phase16-login-live-20260913142802`（Git 忽略）。
- 四档 `k6_exit_code=0`、`collector_complete=true`；每档 summary、raw、resource samples 与 Prometheus 首尾快照均存在。
- backend、collector 与 Redis stderr 均为空；日志未发现 OOM、fatal、未捕获异常或应用 ERROR。
- 运行结束后 `6381/8080/8081/8082/9091/9092` 无监听；临时 schema 数量为 0，业务 `zhiyunjiaos` schema 仍存在。
- 完整回归在 instrumentation 提交前通过 142 tests、0 failures、0 errors、8 skipped；随后 Java 版本采集修复的聚焦测试 4/4 与 Maven package 通过。最终全量回归仍会在 Phase 16 收口时再次执行。
- `VERIFIED`：当前提交、主机、数据和协议下的四档登录结果、分段平均耗时、资源样本和环境回收。
- `ACCEPTED`：保留 BCrypt cost 12 后，以认证专用 `p95 < 750 ms / p99 < 1000 ms / error < 1%` SLO 接受 10–200 VU 周期登录负载。
- `NOT VERIFIED`：生产/Linux 主机；200 个同时起始的登录；高于 6.55 login/s 的 arrival-rate 容量；更长持续时间；云负载均衡与跨地域网络。

完整 SHA-256、逐档机器可读结果和阶段均值见 `phase16-login-aggregate-evidence.json`。

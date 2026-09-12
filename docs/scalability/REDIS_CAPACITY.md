# Phase 8：Redis 限流、缓存指标与降级边界

## 1. 本阶段结论

Phase 8 已把登录失败计数改为 Redis Lua 原子操作，并在 JWT 验证之后增加“每用户、每接口”的 Redis fixed-window 限流。缓存管理器已开启本地统计，Actuator/Micrometer 可以导出 cache hit/miss；登录 IP 和用户接口的拒绝、Redis 降级也有独立 counter。

本阶段 `VERIFIED` 的是：Lua 在本机真实、需要认证的 Redis 上可执行；40 个并发调用同一用户接口桶时，配置上限为 10，实际严格放行 10；登录失败锁、缓存 hit/miss meter、HTTP 429 和 `Retry-After` 均通过测试。它不是 API 吞吐或容量证明，10/100/200 并发与 1000 注册用户场景仍是 `NOT VERIFIED`。

## 2. Redis 职责边界

| 能力 | 最终事实源 | 当前实现 |
| --- | --- | --- |
| 音色缓存 | MySQL | Redis Cache-Aside；`voiceList` TTL 10 分钟，`voiceById` TTL 30 分钟。Redis 丢失只造成回源，不改变业务事实。 |
| 登录限流 | Redis；故障时本 JVM 有界降级 | 按可信客户端 IP 统计失败次数，Lua 原子完成计数、首写 TTL、达到阈值后加锁和删除失败计数。 |
| 用户接口限流 | Redis；故障时本 JVM 有界降级 | JWT 验证后，以已验证用户名和控制器方法作为桶，Lua 原子执行 `INCR + 首写 PEXPIRE + 判定`。 |
| 对话会话 | Redis | 既有 Hash + TTL + Lua advance；Redis 故障时对话会话不可用，不伪装为持久业务数据。 |
| 每用户活动任务配额 | **MySQL** | 继续使用 `async_task_user_slot` 的 owner+slot 原子占位。Redis 不复制这份 correctness 状态，避免双写和配额分歧。 |
| 异步任务状态 | **MySQL** | `async_task` 仍是唯一 source of truth；Redis 不决定任务状态迁移。 |

## 3. 登录 IP 限流

- key namespace：`zjys:rate:login:fail:{hash}` 与 `zjys:rate:login:lock:{hash}`；只存 SHA-256 后的 IP，不把原始 IP 放入 Redis key。
- 默认失败窗口 10 分钟、5 次失败、锁定 15 分钟；这些是安全保护初值，不是压测反推值。
- 计数维度已从旧的 `IP + username` 改为 IP，避免攻击者轮换用户名绕开同源限制。
- 登录成功不会清除整个 IP 的失败记录。否则同一来源只需穿插一次成功登录，就能清空对其他账号的攻击计数。
- 两个 Lua key 使用相同 Redis Cluster hash tag；即使以后迁移 Redis Cluster，同一次脚本的 key 仍落在同一 slot。

### 可信客户端 IP

应用只在 TCP 对端位于 `app.security.trusted-proxies` 时读取 `X-Real-IP`。默认只信任 `127.0.0.1,::1`，与当前同机 Nginx 代理方式匹配；直接访问后端的客户端无法用该 header 伪造 IP。

生产若把 Nginx 放到另一台机器或容器网络，必须把**明确的代理地址**加入 `TRUSTED_PROXIES`。不能配置任意来源，也不能默认相信 `X-Forwarded-For`。

## 4. 用户接口限流

请求链为：

```text
HTTP request
  -> JwtAuthenticationInterceptor 验证 token 并写入 username
  -> UserRateLimitInterceptor 使用已验证 username
  -> Redis Lua 判定 username + HTTP method + Controller#method
  -> allowed: controller
  -> rejected: HTTP 429 + USER_ENDPOINT_RATE_LIMITED + Retry-After
```

- 默认每个用户、每个控制器方法 120 次/分钟，最大值与窗口均可通过环境变量调整。
- key 使用用户名和稳定 handler 标识的 SHA-256，不使用包含 taskId/voiceId 的原始 URL，因此不会因路径参数产生无限高基数的桶。
- `Retry-After` 按剩余毫秒向上取整为秒，避免客户端在窗口真正结束前重试。
- fixed window 在相邻窗口边界可能出现短时双倍突发。这一算法简单、单次一个 Lua、适合作为当前保护层；只有 Phase 11 证明边界突发构成问题时才升级 sliding window/token bucket。

## 5. Redis 故障降级

Redis 调用异常时，两类限流都会切到本 JVM 的有界 `ConcurrentHashMap`，默认最多 10,000 个桶，并记录 `fctts.rate.limit.degraded`：

- 用户接口 fallback 满时拒绝新的桶，保护 JVM，不继续无界增长。
- 登录 fallback 满时临时进入全局 fail-closed 窗口，优先阻止认证保护失效和内存增长；代价是 Redis 故障且桶已满时登录可用性下降。
- fallback 只能保证单 JVM，不是多实例统一限流。多实例运行时 Redis 正常才有全局一致的 rate limit；Phase 10/14 必须验证 Redis 重启和两后端行为。
- 当前 Redis connect timeout 1 秒、command timeout 2 秒。故障时每次首次访问仍可能承担该超时，Phase 14 需要观察是否应增加短路器，而不是根据代码直接声称 fast fail 已满足。

## 6. 指标

| Micrometer meter | 关键 tag | 含义 |
| --- | --- | --- |
| `cache.gets` | `cache=voiceList/voiceById`, `result=hit/miss` | Redis cache hit/miss；由 `RedisCacheManager.enableStatistics()` 提供并由 Actuator 绑定。 |
| `fctts.rate.limit.rejected` | `scope=login_ip/user_endpoint`, `backend=redis/local_fallback/local_fallback_capacity` | 被限流拒绝的请求数。 |
| `fctts.rate.limit.degraded` | `scope=login_ip/user_endpoint` | Redis 异常导致切换到 JVM fallback 的次数。 |

Prometheus 会按命名约定转换为下划线并给 counter 增加 `_total`。指标不带用户名、IP、URL 或对象 key，避免敏感信息与高基数标签。

cache hit ratio 只能在产生真实 cache 调用后计算；进程刚启动或 cache 尚未访问时没有足够样本。Phase 11 应同时观察 hit/miss、Redis latency 和 MySQL 回源量，不能只看命中率。

## 7. 配置

| 环境变量 | Spring 配置 | 默认值 | 说明 |
| --- | --- | ---: | --- |
| `LOGIN_MAX_FAILURES` | `app.security.login-rate-limit.max-failures` | 5 | 每 IP 失败阈值。 |
| `LOGIN_FAILURE_WINDOW` | `app.security.login-rate-limit.window` | 10m | 失败计数窗口。 |
| `LOGIN_LOCK_DURATION` | `app.security.login-rate-limit.lock-duration` | 15m | IP 锁定时长。 |
| `LOGIN_RATE_LIMIT_FALLBACK_MAX_ENTRIES` | `...fallback-max-entries` | 10000 | 登录本机降级桶上限。 |
| `ENDPOINT_RATE_LIMIT_ENABLED` | `app.security.endpoint-rate-limit.enabled` | true | 用户接口保护开关。 |
| `ENDPOINT_RATE_LIMIT_MAX_REQUESTS` | `...max-requests` | 120 | 每用户每接口窗口上限。 |
| `ENDPOINT_RATE_LIMIT_WINDOW` | `...window` | 1m | 用户接口窗口。 |
| `ENDPOINT_RATE_LIMIT_FALLBACK_MAX_ENTRIES` | `...fallback-max-entries` | 10000 | 用户接口本机降级桶上限。 |
| `TRUSTED_PROXIES` | `app.security.trusted-proxies` | 127.0.0.1,::1 | 允许提供 `X-Real-IP` 的精确代理地址。 |

所有数量与时长在 Spring 启动时验证为正数。Redis host、port、database 和 password 仍沿用既有环境变量；仓库不保存密码或 AccessKey。

## 8. 验证结果与证据边界

2026-09-13 本地结果：

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| 登录/用户接口 Lua 真实执行 | VERIFIED | 本机认证 Redis，127.0.0.1:6379；专项 Spring 集成测试通过。Redis 版本未采集。 |
| 用户接口 Lua 并发原子性 | VERIFIED | 12 测试线程提交 40 次同桶调用，上限 10，严格 10 次 allowed。 |
| 登录 IP 锁 | VERIFIED | 阈值 3，真实 Redis 连续记录 3 次失败后 `isBlocked=true`。 |
| cache hit/miss meter | VERIFIED | 真实 Redis put 后分别制造 hit/miss，读取到 `cache.gets` 两个 `FunctionCounter`。 |
| JWT 后 handler 级限流与 HTTP 响应 | VERIFIED | 单元测试验证已认证用户名、429、稳定错误码和向上取整的 `Retry-After`。 |
| 可信代理与 header 防伪 | VERIFIED | 单元测试覆盖可信 loopback 接收、非可信来源忽略、非法 header 忽略。 |
| 本机 fallback 有界与 fail-closed | VERIFIED | 单元测试覆盖桶满拒绝和窗口恢复逻辑。 |
| 完整 Java 回归 | VERIFIED | 127 tests，0 failures，0 errors，7 skipped；本次 Redis 集成测试未跳过。 |
| 前端生产构建 | VERIFIED | Vite 5.4.21，14 modules transformed。 |
| Redis 故障恢复、延迟和最大 ops/s | NOT VERIFIED | 属于 Phase 11/14；本阶段未重启 Redis，也未做吞吐压测。 |
| 两个 backend 的共享限流与缓存失效 | NOT VERIFIED | 属于 Phase 10。 |
| 10/100/200 并发普通 API SLO | NOT VERIFIED | 属于 Phase 11，不能由 Lua 并发单测推导。 |
| 1000 注册用户容量 | NOT VERIFIED | 尚无工作负载、soak 和故障证据。 |

测试使用 UUID 隔离 key，窗口/锁 TTL 为 5 秒，不扫描、不清空 Redis 数据库，也未修改业务数据库 `zhiyunjiaos`。

## 9. 剩余风险

1. 当前 Redis 是单节点；持久化、主从/哨兵/Cluster、内存淘汰策略和故障切换尚未验证。
2. Cache-Aside 的跨实例失效依赖所有写路径正确执行 eviction；Phase 10 需真实双实例检查，不能用单实例 cache hit/miss 代替。
3. 登录 IP 限流在 NAT/校园网场景会让多个用户共享失败预算；真实业务数据到位后应在安全和误伤率之间校准，不能简单调大阈值。
4. 用户接口默认 120/min 不是压测结果；任务状态轮询频率、页面并发请求数和真实 RPS 必须在 Phase 11 中测量后调整。
5. 每用户任务配额虽然由 MySQL 跨实例保证，但当前只是活动任务总数配额；全局队列上限、pending 上限和 `Retry-After` 属于 Phase 9。

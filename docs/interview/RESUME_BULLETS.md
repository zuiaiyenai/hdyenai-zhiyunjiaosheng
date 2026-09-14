# 智韵教声简历项目描述

## 推荐项目标题

**智韵教声｜AI 语音业务场景下的 Java 后端工程化与高可靠异步任务系统**

技术栈：Java 17、Spring Boot 3.4.1、Spring MVC/WebFlux/WebSocket、MyBatis、MySQL、Flyway、Redis、JWT、BCrypt、阿里云 OSS、GPT-SoVITS、FunASR、FFmpeg、Actuator、Micrometer、Prometheus、Docker Compose、Nginx。

## 可直接放入简历的 7 条描述

1. 针对视频、ASR、声音复刻与课件生成等长耗时任务，设计 MySQL 持久任务状态机，采用原子 claim、owner token、heartbeat、超时、指数退避重试与 stale recovery；双 worker 验证单任务仅一次领取，worker 崩溃后由另一实例恢复至成功终态。

2. 建立任务入口背压与资源隔离：在数据库事务边界执行幂等提交、每用户活动任务 slot 和全局容量控制，并以公平 `Semaphore` 分隔 TTS、ASR、FFmpeg、课件资源，超限返回可观测的有界拒绝，避免无界队列拖垮 API。

3. 抽象 Local/阿里云 OSS 对象存储并实现用户归属校验、流式上传下载和临时文件清理；完成真实 OSS 10/100/500 MiB 上传、下载、SHA-256 校验与删除，100/500 MiB 媒体搬运在 `-Xmx96m` 下通过受限堆验证。

4. 完善 JWT、BCrypt cost 12、Redis Lua 登录限流和上传安全链路；通过 Micrometer 分段 profiling 确认 BCrypt 为主要耗时，在不降低密码强度的前提下，10/50/100/200 VU 周期负载共完成 7,083 次 steady 登录、0 业务错误。

5. 完成同机双 Spring Boot 实例共享 MySQL、Redis、OSS 的正确性与容量验证：backend-1 签发 JWT 可跨节点使用，Nginx 双 upstream 均实际承载请求，单节点下线后旧 token 请求 20/20 成功，任务结果可跨节点查询与下载。

6. 建立分层容量与故障验证体系，区分普通 API、登录和重任务口径；在 200 VU 双实例 mixed HTTP 中记录 3,711 requests、61.85 req/s、0 业务错误及普通 API 最差 p95 50.50 ms，并完成 100 VU、30 分钟、49,983 次 L0 请求与 6/6 真实 TTS 的 soak。

7. 编排真实 OSS→FFmpeg→FunASR→GPT-SoVITS→FFmpeg→OSS 视频换声链路，small/medium 两个固定样本均以 `SUCCESS/attempts=1` 完成且重启后结果可下载；同时明确该结果仅为串行 happy path，未包装为并发视频容量或统计失败率。

## 逐条证据映射

| 条目 | 代码证据 | 测试/报告证据 | 可说到哪里 |
| ---: | --- | --- | --- |
| 1 | [`AsyncTaskService`](../../src/main/java/com/a09/tts/task/AsyncTaskService.java)、[`JdbcTaskRepository`](../../src/main/java/com/a09/tts/task/JdbcTaskRepository.java)、V6/V8 migration | [`AsyncTaskMySqlIntegrationTest`](../../src/test/java/com/a09/tts/task/AsyncTaskMySqlIntegrationTest.java)、[Phase 16.8](../performance/PHASE16_FAILURE_INJECTION.md) | 数据库 claim 和 crash recovery 已证实；不说外部副作用 exactly-once |
| 2 | `AsyncTaskService.submit`、[`TaskResourceBulkheads`](../../src/main/java/com/a09/tts/task/TaskResourceBulkheads.java)、[`V10__task_admission_backpressure.sql`](../../src/main/resources/db/migration/V10__task_admission_backpressure.sql) | `TaskResourceBulkheadsTest`、[最终容量报告](../scalability/FINAL_SCALABILITY_REPORT.md) | 保护机制已实现；配置上限不是吞吐承诺 |
| 3 | [`ManagedObjectStorageService`](../../src/main/java/com/a09/tts/storage/ManagedObjectStorageService.java)、[`AliyunOssObjectStorageService`](../../src/main/java/com/a09/tts/storage/AliyunOssObjectStorageService.java)、`TaskController.result` | [Phase 16.3](../performance/PHASE16_MEDIA_STREAMING.md)、[Phase 16.6](../performance/PHASE16_OSS.md) | 流式搬运和真实 OSS 已证实；不说 500 MiB 转码或 OSS safe concurrency |
| 4 | [`LoginRateLimiter`](../../src/main/java/com/a09/tts/security/LoginRateLimiter.java)、`RedisConfig.fixedWindowRateLimitScript`、`UserServiceImpl.login` | [`RedisRateLimitIntegrationTest`](../../src/test/java/com/a09/tts/security/RedisRateLimitIntegrationTest.java)、[Phase 16.1](../performance/PHASE16_LOGIN_PROFILE.md) | 周期登录约 6.55 login/s；不说 200 人同秒登录 |
| 5 | JWT 认证链路、对象存储 owner 元数据、MySQL task repository | [`MultiInstanceDeploymentConfigTest`](../../src/test/java/com/a09/tts/MultiInstanceDeploymentConfigTest.java)、[Phase 16.7](../performance/PHASE16_MULTI_INSTANCE.md) | 同一 Windows 主机双实例；不说跨主机或云 HA |
| 6 | Actuator/Micrometer、任务与资源指标、压测脚本 | [Phase 16.7](../performance/PHASE16_MULTI_INSTANCE.md)、[Phase 16.9](../performance/PHASE16_SOAK.md) | 指定 workload 的实测值；不说系统最大 QPS或长期无泄漏 |
| 7 | [`TaskWorkDispatcher`](../../src/main/java/com/a09/tts/task/TaskWorkDispatcher.java)、[`VideoVoiceSwapServiceImpl`](../../src/main/java/com/a09/tts/service/impl/VideoVoiceSwapServiceImpl.java) | [Phase 16.5](../performance/PHASE16_VIDEO_PIPELINE.md) | small/medium 串行 2/2；pipeline 总体仍 `PARTIAL` |

## 面试口径说明

这 7 条遵循“问题 → 技术方案 → 可验证结果 → 证据边界”，可以直接解释到代码或报告。简历空间不足时优先保留 1、2、3、4、5、6；第 7 条用于突出具体业务编排。

不要替换成以下夸大描述：

- “支撑 1,000 并发用户”——1,000 只是注册账号数据基线。
- “实现分布式 exactly-once”——没有跨 MySQL、OSS、外部模型的分布式事务证据。
- “引入 Kafka/RabbitMQ 构建消息系统”——当前项目没有 MQ。
- “Kubernetes 微服务生产部署”——当前是模块化单体，cloud 为 `BLOCKED`。
- “OSS 并发能力 4”——4 路只完成了功能成功验证。
- “长期稳定运行无内存/线程泄漏”——只完成一次 30 分钟 soak，线程阶跃未定因。

## 安全与真实性检查

- 简历、作品集和截图中不得出现真实 AccessKey、密码、JWT secret 或本地配置。
- `READY_WITH_LIMITS` 只用于说明工程验证结果，不改写为“已正式生产上线”。
- 面试官追问数字时，主动给出环境、时长、VU/arrival 模型、输入样本和未覆盖项。
- 所有数据以当前[最终容量报告](../scalability/FINAL_SCALABILITY_REPORT.md)和[生产就绪矩阵](../scalability/PRODUCTION_READINESS_MATRIX.md)为准。

# Phase 14：故障恢复演练

## 结论

- 2026-09-13 在 Windows 本机完成了一次双实例故障演练。独立 MySQL、独立 Redis、两个 Spring Boot 实例和项目 Nginx 均为真实进程；Redis/MySQL 重启、backend 终止和 Nginx 上游切换均为真实进程级故障。
- Redis、MySQL、GPT-SoVITS HTTP 端点、FunASR HTTP 端点、Nginx 上游、worker 和 backend 重启共 6 组测试全部通过。最终 raw evidence 的 `passed=true`，9 个测试端口全部关闭，日常 3306/6379 服务在演练前后均可达。
- GPT-SoVITS 与 FunASR 只使用了独立 loopback HTTP 契约桩。应用的连接拒绝、慢响应超时、重试、失败终态与恢复已验证；真实模型进程、GPU、模型加载和模型进程重启恢复均为 **NOT VERIFIED**。
- MySQL 停机期间 Hikari 获取连接在约 2 秒内超时，数据库 API 返回 500，liveness 仍为 200；MySQL 恢复后 readiness 和相同数据库查询恢复为 200。证据支持“等待有界并可恢复”，不支持“数据库停机时连接池仍可用”。
- 完整 Maven 回归为 138 tests、0 failures、0 errors、8 skipped。

## 环境与隔离

- 时间：2026-09-13 18:15:59–18:18:59（Asia/Shanghai）。
- 基线提交：`3583e0ebe61d076bc874c1849b14f44c9e817e15`；演练执行的是该提交之上的 Phase 14 working tree，具体文件 SHA-256 已写入聚合证据。
- Java：25.0.3；隔离 MySQL：8.0.41、`127.0.0.1:3307`；隔离 Redis：3.2.100、`127.0.0.1:6381`、DB 15；Nginx：1.22.0。
- 拓扑：Nginx `127.0.0.1:8080` → backend-1 `8081/9091` 与 backend-2 `8082/9092` → 独立 MySQL/Redis；TTS stub `9880`，ASR stub `9977`。
- MySQL 使用一次性 `fctts_phase14_*` schema 和 `target/phase14-live-*/mysql-data`；Redis 禁用 RDB/AOF；对象存储使用 `target/phase14-live-*/objects`。清理时隔离 MySQL 数据目录和随机 Redis 配置均被删除。
- 日常 MySQL 3306 与 Redis 6379 没有被停止、重启、清空或改配置。演练前后端口均可达；外部端口复核中 PID 保持为 MySQL 7412、Redis 6204。

## 故障结果

| 故障 | 故障期行为 | 恢复行为 | 结论 |
|---|---|---|---|
| Redis restart | Redis health/readiness 503，liveness 200；降级登录限流依次返回 5×401、1×429 | 7.516s 后 Redis health 与 readiness 恢复，数据库查询 200 | VERIFIED |
| MySQL restart | health/readiness 503，liveness 200；DB API 2.016s 内返回 500 | 1.859s 后 readiness 与同一查询恢复 200，响应摘要不变 | VERIFIED |
| GPT-SoVITS unavailable | 连接拒绝 0.031s 返回 503；5s 慢响应在 2.015s 返回 503 | 契约桩恢复后 TTS 200，音频摘要不变 | VERIFIED_WITH_CONTRACT_STUB |
| FunASR unavailable | 请求先以 202 入持久队列，2.094s 后经 3 次尝试进入 FAILED，错误码 `TASK_EXECUTION_FAILED` | 新任务 1 次尝试进入 SUCCESS | VERIFIED_WITH_CONTRACT_STUB |
| Nginx upstream failure | backend-1 端口关闭；经 Nginx 连续 20 次请求全部 200，总计 23.390s | backend-1 在 19.000s 内重新就绪 | VERIFIED |
| worker crash + backend restart | 任务在 RUNNING/attempt 1 时终止 backend-1 | backend-2 将 stale task 恢复为 SUCCESS/attempt 2，用时 18.891s；backend-1 另行重启 19.078s；Nginx 查询 200 | VERIFIED |

Nginx 的 20 次请求是连续同步请求，总时长不能解释成单请求延迟分位数。它证明功能接管，没有证明故障窗口仍满足普通 API 的 300ms p95 SLO。

## fast fail、bounded timeout 与 recover

- `TTSServiceImpl` 新增可配置 `tts.api.timeout`，同步与 streaming 两条调用链都应用 Reactor timeout；默认 30s，演练设置为 2s。默认行为之外，通用 RestTemplate 的 connect/read timeout 也改为可配置，默认仍为 30s/60s。
- TTS 连接拒绝 31ms、慢响应 2.015s、MySQL API 2.016s，均为有界返回，没有无限挂起。
- Redis/MySQL readiness 在依赖不可用时变为 503，liveness 保持 200；依赖恢复后 readiness 自动回到 200。
- FunASR 不可用时任务没有永久停在 RUNNING，而是在重试耗尽后进入 FAILED；worker 进程崩溃留下的 RUNNING 任务被另一实例重新领取并成功完成。
- JWT 在 backend 切换/重启后仍可验证，数据库响应摘要在 Redis/MySQL/节点故障前后保持一致。

## 观察到的限制与风险

- 为缩短演练时间，本次把 worker poll interval 设置为 100ms、stale-after 设置为 3s。这不是生产推荐值，也使 MySQL 停机窗口内两实例记录了 26 次 worker 循环失败、8 次 stale-recovery 失败和 17 次 Hikari 约 2s 获取连接超时。等待是有界的，但存在明显日志风暴；生产化仍应为 DB worker 错误增加退避、抖动和日志限频。
- Redis 重启恢复为 7.516s，Nginx 20 次故障窗口请求总计 23.390s；它们是本机单次结果，不是恢复时间 SLO 的统计分位数。
- 演练只有 1 次最终有效重复；没有覆盖 Linux、容器编排、跨主机网络分区、磁盘满、MySQL 主从切换、Redis Sentinel/Cluster、依赖半开连接或多故障叠加。
- TTS/ASR 契约桩的 BrokenPipe/ConnectionReset 日志是客户端超时和 worker 强杀的预期结果，不代表真实模型进程行为。
- 本次对象存储为共享本地测试目录；真实阿里云 OSS 的故障、权限、吞吐、超时和恢复没有验证。

## 校准运行披露

最终有效运行之前有 5 次不计入 PASS 的 harness 校准：初始 MySQL root TCP 授权与清理回退、Redis 3.2 CLI 认证兼容、Windows Nginx 输出管道继承、ASR chunked multipart 契约处理，以及一次 worker 恢复计时范围错误。每次都在隔离端口上停止，后续运行前确认 3307/6381/8080/8081/8082/9091/9092/9880/9977 无监听；除第一次需按已验证 PID 手工终止隔离 3307 进程并删除其临时数据目录外，其余运行由 finally 自动完成清理。最终结论只取 `phase14-live-20260913101559`。

## 证据与清理

- raw 目录：`target/phase14-live-20260913101559`（Git 忽略）。
- `phase14-raw-evidence.json` SHA-256：`D5765135F82D597135DF3771CDFD683631CA21F7DCEC5FFFE745C892C7F66069`。
- `fault_dependency_stub.py` SHA-256：`04108BE4602003F62C392A32B1A3524D370113B4661C56B080CDFAF56C637782`。
- `phase14_failure_drill.py` SHA-256：`D5214629A38078A6BC531D5F64E821A43F8C0071423ECB080F512A258FA6C66C`。
- raw evidence 记录 6/6 故障项 passed、9/9 测试端口关闭、隔离 MySQL 数据删除、随机 Redis 配置删除、日常服务前后可达性不变。
- Python `py_compile`、两个脚本 `--help`、TTS GET 启动探针和 ASR chunked body smoke 均通过。
- 定向 TTS 测试：7 tests、0 failures、0 errors、0 skipped。
- 完整 Maven：138 tests、0 failures、0 errors、8 skipped；`TEMP`、`TMP`、`java.io.tmpdir` 与 Surefire `argLine` 均指向项目 `target/phase14-test-tmp`。重复配置提示不影响实际测试结果。

## 证据边界

`VERIFIED`：上述 Windows 拓扑中真实 MySQL/Redis/Nginx/Spring Boot 进程的停启、健康状态、DB API 有界失败与恢复、Nginx 单上游接管、跨实例 JWT/数据可用性、worker crash 后 stale task 恢复，以及清理护栏。

`VERIFIED_WITH_CONTRACT_STUB`：应用面对 GPT-SoVITS/FunASR HTTP 连接拒绝、慢响应、重试耗尽、终态迁移和端点恢复的行为。

`NOT VERIFIED`：真实 GPT-SoVITS/FunASR 模型进程恢复；GPU/模型加载；真实阿里云 OSS；Linux/容器/云主机；重复故障统计；跨主机网络分区；Redis HA、MySQL HA；故障窗口延迟 SLO；生产流量下无连接池耗尽。

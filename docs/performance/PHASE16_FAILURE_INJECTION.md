# Phase 16.8：真实依赖故障注入

## 结论

- 正式 run `target/phase16-failure-live-20260913203928/` 在隔离 MySQL/Redis、双 Spring Boot、项目 Nginx、真实本机 GPT-SoVITS 和真实本机 FunASR 上完成，六类故障项均通过 runner 断言。
- Redis、MySQL、GPT-SoVITS、FunASR、单个 Nginx upstream 和正在执行任务的 worker/backend 均实际停止并恢复，不是 mock 故障。健康与 readiness 在依赖中断时降为 503，liveness 保持 200；恢复后业务探针重新成功。
- 一条真实 ASR 任务在 `RUNNING / attempts=1` 时杀死其 backend，另一实例接管后以 `SUCCESS / attempts=2` 结束，恢复用时 14.563 秒；未留下永久 `RUNNING`。这证明数据库任务状态可恢复，不等于外部副作用 exactly-once。
- 本轮是顺序单探针故障恢复验证，没有在故障窗口同时运行 100/200 VU mixed workload。因此六条具体恢复路径为 **VERIFIED**，Phase 16.8 的“负载下故障恢复”整体状态为 **PARTIAL**，不能替代 Phase 16.9 soak 或目标云环境演练。

## 正式环境与隔离

- 应用基线：`43501bf2662fbd053d762aad53da1e81922828de`。
- 正式时间：2026-09-14 04:39:28 至 04:44:02（Asia/Shanghai）。
- 隔离 MySQL 8.0.41：127.0.0.1:3307，唯一 schema `fctts_phase14_20260913203928`；结束后 data directory 已删除。
- 隔离 Redis 3.2.100：127.0.0.1:6381，DB 15、无持久化、run 内随机密码。
- 两个应用实例：8081/9091 与 8082/9092；Nginx：8080。
- GPT-SoVITS：真实本机进程 9880；FunASR：真实本机模型进程 9977。
- 日常 MySQL 3306 与 Redis 6379 在运行前后均可达，业务 schema `zhiyunjiaos` 未被本 runner 使用或修改。

## 故障结果

| 故障 | 故障期间行为 | 恢复证据 | 结论 |
|---|---|---|---|
| Redis stop/restart | Redis health/readiness 503，liveness 200；无 Redis 时错误登录依次 401×5、429 | 8.079 秒后 voice API 200 | VERIFIED |
| MySQL stop/restart | health/readiness 503，liveness 200；DB API 2.031 秒内返回 500 | 1.875 秒后 voice API 200 | VERIFIED |
| GPT-SoVITS unavailable | external health/readiness 503；同步 TTS 0.031 秒返回 503 | 重启真实模型后返回 200 且载荷为 WAV | VERIFIED |
| FunASR unavailable | 请求仍以 202 入队，2.062 秒后 `FAILED / attempts=3`，错误码 `TASK_EXECUTION_FAILED` | 重启真实模型后新任务 `SUCCESS / attempts=1` | VERIFIED |
| Nginx upstream loss | backend-1 端口确认关闭；旧 JWT 经 Nginx 20/20 请求为 200；backend-2 readiness 200 | backend-1 13.625 秒内恢复 | VERIFIED |
| worker/backend crash | 任务杀进程前为 `RUNNING / attempts=1` | backend-2 接管后 `SUCCESS / attempts=2`，14.563 秒；Nginx 200 | VERIFIED |

Nginx 的 20 次串行请求共用时 18.016 秒。该值包含每次对失效 upstream 的连接/重试成本，只能作为本机故障窗口的观测，不能解释为统一的“故障检测时间”或生产 SLO。

## 关键不变量

- 没有永久等待：依赖故障期间的同步请求有界返回，异步 FunASR 任务进入明确 `FAILED` 终态。
- 没有永久耗尽证据：故障恢复后 readiness 与业务请求恢复。但本轮没有并发采集 Hikari pending、线程池 active/queue 或线程数曲线，因此不能把它扩展成负载下资源池不耗尽证明。
- 没有永久 `RUNNING`：worker 被杀的同一逻辑任务由另一 worker 恢复为单行 `SUCCESS / attempts=2`。
- 没有“重复成功任务”的可见结果：该逻辑任务只观测到一个终态成功。但 runner 没有对真实 ASR 外部调用建立幂等审计，不能宣称崩溃窗口中的外部副作用 exactly-once。
- 真实 TTS 基线与恢复结果都通过 WAV 头校验；两次 SHA-256 不同是生成模型非确定性的正常边界，不能用字节相等作为恢复条件。

## 证据完整性与清理

- 原始证据：`target/phase16-failure-live-20260913203928/phase16-failure-raw-evidence.json`（Git 忽略）。
- 原始证据 SHA-256：`4002AF7B8C7A8C164A71AFCF17F25AD7F113F01A4E0CB77A0624BA8E604CF807`。
- 可提交摘要：`docs/performance/phase16-failure-aggregate-evidence.json`。
- runner 结束时 3307、6381、8080、8081、8082、9091、9092、9880、9977 全部关闭；隔离 MySQL data 与 Redis 配置均删除。
- runner 清理后，已按项目 `launch.ps1` 的既有命令恢复日常模型服务：GPT-SoVITS `HEAD /tts` 返回 405（路由可达），FunASR `/health` 返回 `UP`，9880/9977 均可连接。
- 两个更早的启动失败目录不作为通过证据：它们未进入后端或故障项执行，且 finally 已释放隔离端口和临时数据。

## 已验证与未验证边界

**VERIFIED：**本机隔离环境中 Redis/MySQL 的健康降级与恢复；真实 GPT-SoVITS 进程中断、快速失败和重新生成 WAV；真实 FunASR 进程中断后的明确失败与恢复后成功；一个 Nginx upstream 下线后的 20/20 连续服务；正在运行的 worker/backend 被杀后由另一实例恢复任务终态。

**NOT VERIFIED：**故障期间的 100/200 VU mixed workload；连接池/线程池在并发故障下是否耗尽；Redis/MySQL 数据丢失、主从切换或网络分区；Nginx 进程自身失败；两个 upstream 同时失败；GPT-SoVITS/FunASR 慢响应与半开连接；OSS/DNS/限流/5xx 故障；WebSocket 连接迁移；多次重复演练的恢复时间分布；Linux、容器、跨主机、云环境和生产告警联动。

下一阶段进入 Phase 16.9：在不超过当前宿主机内存边界的前提下执行至少 30 分钟 mixed soak，并加入少量真实 TTS；该 soak 仍需单独报告其实际覆盖范围。

## 复现

确认日常 3306/6379 可达、测试端口空闲，且真实模型安装目录存在后执行：

```powershell
& 'C:\Users\65374\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' `
  performance\phase14_failure_drill.py `
  --real-models `
  --java-exe 'D:\BaiduNetdiskDownload\jdk-17.0.19+10\bin\java.exe' `
  --python-exe 'D:\BaiduNetdiskDownload\GPT-SoVITS-v2-240821\runtime\python.exe'
```

不得把 OSS 或其他长期凭证写入命令、raw、日志、报告或仓库。对话中曾暴露的 OSS 长期密钥必须立即轮换，并改用限定 bucket/prefix、最小权限的 RAM 角色或短期 STS。

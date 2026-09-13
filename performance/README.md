# Phase 11 普通 API 压测

本目录实现 `docs/scalability/WORKLOAD_MODEL.md` 的 L0 CORE_API 场景。它只测试登录、音色列表/搜索、课件列表、任务持久化准入和任务状态轮询；GPT-SoVITS、FunASR、FFmpeg、视频生成和文件吞吐必须在 Phase 12 或独立 L1 场景中测试，不能混入本结果的 RPS。

## 安全边界

- 只允许使用名称匹配 `fctts_phase11_*` 的专用 MySQL schema；脚本显式拒绝 `zhiyunjiaos`。
- 压测脚本在本机 6380 自启无持久化的隔离 Redis，并使用专用 DB 14；不会重启、清空或复用日常开发的 6379 Redis。
- OSS、数据库、JWT 凭证仅从被 Git 忽略的 `config/application-local.yml` 读取；隔离 Redis 和压测账号使用运行时随机密码。凭证不写入报告或结果文件，临时 Redis 配置在清理阶段删除。
- 运行时账号密码随机生成，只通过进程环境传递；数据库只保存 BCrypt strength 12 哈希。
- 两个 task worker 的轮询间隔临时设为 24 小时，压测产生的任务保持 `PENDING`。因此 task create/status 是 L0 准入测试，不是重媒体吞吐证明。
- 原始 k6 JSON、应用日志和逐 5 秒资源样本保存在被忽略的 `target/phase11-live-*`。

## 工作负载

- 1000 个独立注册账号。
- 前 200 个活跃账号各有 10 个课件、50 条历史成功任务。
- 200 个公开音色元数据。
- closed model，依次 10、50、100、200 VUs。
- 每档 2 分钟预热、10 分钟稳态、3 次重复。
- 每个 VU 首次登录后复用 JWT；请求前有确定性随机抖动。
- 20% 的用户每个 k6 scenario 提交一次课件优化任务，并按 1/2/3/5 秒退避轮询最长 60 秒。

## 运行

先把 Grafana 官方 Windows portable k6 解压到：

```text
target/tools/k6-v2.2.0-windows-amd64/k6.exe
```

然后在仓库根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File performance/run_phase11.ps1
```

完整运行至少需要约 144 分钟的施压时间，另加 5 分钟稳定期、运行间恢复和启动/清理时间。不要通过缩短默认时长后仍把结果称为容量证明。

运行结束后汇总：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File performance/summarize_results.ps1 `
  -ResultsDirectory target/phase11-live-<UTC timestamp>
```

## 采集证据

每轮保存：

- k6 summary 与逐请求 raw JSON；
- RPS、p50、p95、p99、错误率、HTTP 状态与 endpoint 请求占比；
- 主机 CPU/可用内存、两个 Java 进程 CPU/RSS/线程/句柄；
- JVM heap、GC pause、live threads；
- Tomcat busy/current/max threads；
- Hikari active/idle/pending/max；
- Redis PING latency、memory、connections、key count；
- MySQL connections/running/slow query 与专用 schema 数据量；
- executor active/queued/completed 与 task/bulkhead rejected。

k6 的 closed-model scenario、request tags、thresholds、custom metrics 和 `handleSummary` 均按 Grafana 官方文档实现：

- <https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/>
- <https://grafana.com/docs/k6/latest/using-k6/scenarios/advanced-examples/>
- <https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/>

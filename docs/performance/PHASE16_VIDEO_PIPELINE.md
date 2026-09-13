# Phase 16.5：真实完整视频链路验证

## 结论

- small（5.291 秒）和 medium（10.000 秒）各完成 1 次真实链路，2/2 进入 `SUCCESS`，0 失败、0 timeout，均为 `attempts=1`。
- 实际路径为：认证上传 → 阿里云 OSS 输入对象 → MySQL 持久任务 → FFmpeg 抽音 → 真实 FunASR → 真实 GPT-SoVITS → 字幕/FFmpeg 合并 → 阿里云 OSS 结果对象 → 流式下载。没有使用 mock 或本地对象存储替代 OSS。
- small/medium happy path 与完成后 JVM 重启持久性标记为 **VERIFIED**。正在处理任务时杀死 worker、外部模型中断后的恢复仍为 **NOT VERIFIED**，留给 Phase 16.8，不能由本次成功样本推导。
- large 为 **NOT RUN**：当前默认上传上限为 50 MiB、GPT-SoVITS HTTP timeout 为 30 秒，且本机同时运行两个真实模型和后端后的内存余量不适合无边界放大输入。本阶段不临时放宽生产保护值来制造“通过”结果。
- 因此，本阶段总体证据状态为 **PARTIAL**：最小要求的两档真实完整流程已验证，但大文件、并发容量、运行中故障恢复和云环境仍未验证。

## 环境与协议

- 时间：2026-09-14（Asia/Shanghai），Windows 本机。
- 后端使用 Java 17，监听 8081；management 监听 9091；真实 FunASR 监听 9977；真实 GPT-SoVITS 监听 9880。
- 后端连接隔离 schema `fctts_phase16_video_202609140159`，Flyway v12。没有清空、baseline 或修改业务 schema `zhiyunjiaos`。
- 存储使用真实阿里云 OSS；凭证仅来自被 Git 忽略的本地配置或进程环境，raw/聚合证据均不记录凭证。
- `voiceType=longxiao`，`includeSubtitles=true`，任务轮询间隔 1 秒，基准客户端总 timeout 900 秒。服务自己的 GPT-SoVITS timeout 仍为默认 30 秒。
- 每个 case 采集后端、FunASR、GPT-SoVITS 进程 CPU/RSS/线程，以及 JVM heap、GC、匹配的系统临时文件和 D 盘剩余空间。Windows 进程 CPU 可超过 100%，约 300% 表示约三个逻辑 CPU 的计算量。

## 结果

| case | 输入 | 任务结果 | upload | E2E | 输出 | heap peak | GC pause | 临时文件 before/peak/after |
|---|---:|---|---:|---:|---:|---:|---:|---:|
| small | 5.291s / 178,606 B | SUCCESS / attempt 1 | 1.726s | 15.755s | 5.268s / 157,408 B | 91.02 MiB | 1 / 0.003s | 0 / 178,606 / 0 B |
| medium | 10.000s / 278,290 B | SUCCESS / attempt 1 | 0.408s | 23.246s | 9.985s / 280,482 B | 94.10 MiB | 3 / 0.009s | 0 / 278,290 / 0 B |

两个输出均包含 H.264 video 与 AAC audio。输出 SHA-256：

- small：`F49789169A917B599A39AD5DD17095F89640B4AE54B50379B21FA8BE8B267C30`
- medium：`97EF1A6DD72457F0691BA387AF51BB5B167AA2A1AEB5833E6FEB551FB3D720A8`

| case | backend CPU p95 / peak | backend RSS peak | FunASR CPU p95 / peak | FunASR RSS peak | GPT-SoVITS CPU p95 / peak | GPT-SoVITS RSS peak |
|---|---:|---:|---:|---:|---:|---:|
| small | 31.21% / 31.66% | 402.23 MiB | 1.22% / 1.23% | 951.18 MiB | 326.32% / 336.44% | 2,303.17 MiB |
| medium | 38.22% / 40.40% | 410.17 MiB | 25.17% / 121.03% | 956.39 MiB | 322.04% / 322.78% | 2,385.05 MiB |

样本量只有两个串行任务，`0/2` 失败只证明这两次 happy path，不是稳定 failure-rate 或并发容量估计。E2E 包含上传、队列轮询、全部模型/FFmpeg 阶段和结果下载前的任务完成时间；`upload` 只表示 202 响应时间。

## 持久性、清理与路径边界

- 原后端进程退出后，以 Java 17 启动新后端并连接同一隔离 schema；Flyway 验证 12 个迁移且 schema 保持 v12，readiness 为 `UP`。
- 重启后两个任务仍为 `SUCCESS`、`attempts=1`。再次下载 small 结果得到 157,408 bytes，SHA-256 与重启前完全一致，证明已完成任务和结果对象不依赖原 JVM 内存。
- 两个 OSS 输入对象均已删除；两个结果对象按产品语义保留用于下载；pending cleanup 队列为 0。结果对象将在 Phase 16.6 OSS delete 验证中删除，不把遗留测试对象当作清理成功。
- 本轮任务专属工作目录 `video-1ae8e64c-f901-4038-af81-bb8cacd16d07` 与 `video-4d4ee3ac-c83f-40ab-8f7d-d1cefe7e744d` 已删除。`uploads/output` 下 2026-09-02 的历史目录不属于本轮，未擅自删除。
- raw 的 `temporary_bytes_peak` 只覆盖系统临时目录中匹配 `fctts-*` / `video-voice-swap-*` 的文件；它没有测量任务专属 `uploads/output` 目录的峰值。因此“临时文件最终清理”已验证，但“完整临时磁盘峰值”仍是部分观测。
- 上传调用链会先执行 `UploadSecurityService.validate`，对象 key 由服务端生成并经 `ObjectStorageKeys.requireValid` 校验；结果下载同时验证任务 owner 和对象 metadata owner，并用 `InputStreamResource` 流式返回。现有 traversal、跨 owner 和对象 key 单测提供代码级回归证据；本次没有对真实视频端点执行恶意文件名攻击，所以不把路径安全标成独立 live penetration test。

## 证据

- 正式 raw：`target/phase16-video-20260913180903/phase16-video-raw-evidence.json`（Git 忽略）。
- raw SHA-256：`25BFC8DDC2075E30CAE300A6C158918A4E3D11B2496031E9AA8C0EF7FA916F75`。
- 重启日志与复下载结果：`target/phase16-video-live/`（Git 忽略）。
- 可提交摘要：`docs/performance/phase16-video-aggregate-evidence.json`。
- 可复现基准：`performance/video_pipeline_benchmark.ps1`。脚本要求结果目录位于仓库 `target`、服务 URL 为 loopback，并从环境变量读取测试账号，不记录密码或 OSS 凭证。

## 已验证与未验证边界

**VERIFIED：**small/medium 真实全链路；输入上传与结果流式下载；任务持久化终态；真实 FunASR/GPT-SoVITS/FFmpeg/OSS；输出可解码；本轮临时文件最终清理；完成后 JVM 重启仍可查询与下载。

**NOT VERIFIED：**large；两个或更多视频任务并发；长视频与不同编码矩阵；各阶段独立耗时；任务处理中 backend/worker kill；FunASR/GPT-SoVITS 中断及恢复；OSS 限流/超时/断网；临时磁盘完整峰值；Linux、容器、Nginx、多实例、云主机、30/60 分钟真实媒体 soak。

## 复现

```powershell
$env:VIDEO_TEST_USERNAME = '<existing-test-user>'
$env:VIDEO_TEST_PASSWORD = '<secret>'
.\performance\video_pipeline_benchmark.ps1 `
  -BackendPid <JAVA17_PID> -AsrPid <FUNASR_PID> -TtsPid <GPT_SOVITS_PID> `
  -Video @('test-assets\video-voice-swap-chinese-5s.mp4', '<medium-video>')
```

复现前必须让后端连接专用验证 schema 和真实 OSS，并确认 FunASR/GPT-SoVITS 都已加载真实模型。命令中的占位符不得替换为提交到仓库的明文凭证。

# Phase 12：GPT-SoVITS 与 FFmpeg 重任务压测

## 结论

- GPT-SoVITS：本机安全并发为 **1**。1/2/4/8 档共 48 次正式请求全部成功，但吞吐始终约 0.11 次/秒；并发从 1 提高到 2 后吞吐反而下降，p95 从 9.64 秒增至 19.40 秒。8 并发时 p95 为 85.80 秒、主机可用内存最低仅 364 MiB，因此“8 并发零失败”不等于“8 是安全并发”。
- FFmpeg：本机整机安全并发为 **2**。2 并发相对 1 并发把吞吐从 0.80 提高到 1.24 次/秒，p95 为 1.71 秒，CPU p95 为 88.61%；4 并发吞吐只再提高 5.01%，CPU 已持续触顶 100%，不再具备合理资源余量。
- 当前双 backend、同一主机的部署建议保持 `FFMPEG_MAX_CONCURRENT=1`/实例，整机聚合上限为 2；不要把单实例默认值改成 2 后再部署两个实例，否则会把整机放大到已证实 CPU 饱和的 4 并发。
- `TTS_MAX_CONCURRENT=1` 只能限制单 JVM。两个 backend 共享同一个 GPT-SoVITS 时，当前实现仍可能聚合为 2；“跨实例全局 TTS 并发=1”尚未由分布式准入机制保证，状态为 `NOT VERIFIED`，不能因为本次测试而提高 TTS 或 worker 数量。
- 本报告不是完整视频换声容量证明。完整链路还包含对象存储、FunASR、GPT-SoVITS、FFmpeg、DB worker 和结果上传；本次只分别证明模型服务直连和纯 FFmpeg 合并能力。

## 环境与协议

- 时间：2026-09-13（Asia/Shanghai）。
- 主机：AMD Ryzen 9 7945HX，32 logical processors；NVIDIA GeForce RTX 4060 Laptop GPU，8188 MiB，driver 566.26；Windows 10.0.26200。
- GPT-SoVITS：本机 CUDA/v2 配置，直连 `127.0.0.1:9880/tts`，固定中文文本与 `红豆生南国.m4a` 参考音频，非流式 WAV；排除 Spring、Tomcat、MySQL、Redis 和 ASR。
- FFmpeg：`2025-03-03-git-d21ed2298e`；将仓库 5 秒视频循环准备为 30 秒输入，再执行与 `VideoVoiceSwapServiceImpl.buildMergeCommand` 一致的 `libx264/yuv420p + AAC + atempo + shortest + faststart` 音轨替换；排除 ASR、TTS、worker 与 OSS。
- 每档至少 3 个连续并发波次且不少于 6 个正式任务：1/2/4/8 并发分别执行 6/6/12/24 次。每类各有 1 次 warmup，不计入正式统计。
- 每秒采集整机 CPU、可用内存和 `nvidia-smi` GPU 指标；逐任务记录端到端 latency、状态、输出字节与错误。原始 JSON 保存在被 Git 忽略的 `target/phase12-live-*`。

## GPT-SoVITS 结果

| 并发 | jobs | throughput jobs/s | p50 s | p95 s | p99 s | failure | CPU p95 / peak | 可用内存最低 | GPU p95 / peak | VRAM peak |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 6 | 0.1193 | 8.71 | 9.64 | 9.86 | 0% | 20.28% / 23.35% | 2820 MiB | 39% / 99% | 3120 MiB |
| 2 | 6 | 0.1124 | 17.26 | 19.40 | 19.73 | 0% | 28.04% / 29.72% | 2700 MiB | 40.6% / 42% | 3258 MiB |
| 4 | 12 | 0.1058 | 35.69 | 41.05 | 41.14 | 0% | 29.22% / 41.55% | 1346 MiB | 45% / 74% | 3409 MiB |
| 8 | 24 | 0.1094 | 70.46 | 85.80 | 86.19 | 0% | 62.42% / 91.85% | 364 MiB | 42% / 78% | 3236 MiB |

warmup 为 16.18 秒、588844 bytes，已排除。1 并发档的 GPU 99% 是单个峰值样本，p95 为 39%；判断使用完整采样分布而不是只取该峰值。

并发提高没有带来吞吐收益，latency 近似按排队深度线性增长，4/8 档还显著压缩主机内存。按“零功能错误、资源有余量、继续增加并发仍有合理吞吐收益”的既定定义，安全并发只能取 1。

## FFmpeg 结果

| 并发 | jobs | throughput jobs/s | p50 s | p95 s | p99 s | failure | CPU p95 / peak | 可用内存最低 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 6 | 0.8021 | 1.22 | 1.36 | 1.39 | 0% | 52.04% / 55.03% | 3772 MiB |
| 2 | 6 | 1.2428 | 1.59 | 1.71 | 1.71 | 0% | 88.61% / 90.50% | 3483 MiB |
| 4 | 12 | 1.3051 | 3.09 | 3.15 | 3.16 | 0% | 100% / 100% | 2143 MiB |
| 8 | 24 | 1.5231 | 5.00 | 5.83 | 5.98 | 0% | 100% / 100% | 1007 MiB |

4 并发相对 2 并发只增加约 5.01% 吞吐，p95 增加约 84.37%，同时 CPU 饱和；因此整机安全并发取 2。8 并发虽然仍为 0% 失败，但 CPU 饱和、p95 继续上升且可用内存逼近 1 GiB，不属于安全运行点。

该 FFmpeg 命令使用 CPU `libx264`，没有启用 NVIDIA 硬件编码；表外观察到的 GPU 活动来自整机其他桌面进程，不能归因于 FFmpeg。

## 部署反推

| 资源 | 实测整机安全并发 | 当前单实例默认 | 双实例同主机建议 | 状态 |
|---|---:|---:|---:|---|
| GPT-SoVITS | 1 | `TTS_MAX_CONCURRENT=1` | 全部实例合计必须为 1 | 单实例值正确；跨实例全局准入 `NOT VERIFIED` |
| FFmpeg | 2 | `FFMPEG_MAX_CONCURRENT=1` | 每实例 1，整机合计 2 | 与当前双实例拓扑匹配 |

`TASK_WORKER_COUNT=2` 已足以占满本机 FFmpeg 安全并发，增加 worker 不会提高 GPT-SoVITS 吞吐。若未来每个 backend 迁移到不同主机，或每个实例拥有独立 GPU 服务，必须在新拓扑重新压测，不能复用本机数字。

## 证据与边界

- GPT raw：`target/phase12-live-20260913074513/phase12-raw-evidence.json`，SHA-256 `736DFDE4F52BAB9C1787EA5D78C0A4029D8CA535FDC0BEDB6AD86A2FCCF7F7F3`。
- FFmpeg raw：`target/phase12-live-20260913075311/phase12-raw-evidence.json`，SHA-256 `50C181DC7EE4507CDCCC5F412DA06B4727B8A0B9022EBCE5683F95860AF4725E`。
- FFmpeg 30 秒准备输入 SHA-256：`415C8F289FD680F2512534E1EBC0393A218DE14A52C2383109FD88DAAABB368C`。
- 可提交的摘要证据：`docs/performance/phase12-aggregate-evidence.json`。

`VERIFIED`：上述固定文本 GPT-SoVITS 直连负载和固定 30 秒输入的纯 FFmpeg 命令，在本机 1/2/4/8 档的实际吞吐、端到端延迟、零失败与整机资源采样。

`NOT RUN`：FunASR、完整视频换声、Spring bulkhead 拒绝/排队、DB worker queue wait、OSS 输入输出、不同文本长度/音频时长、流式首包、30/60 分钟 soak、故障注入。

`NOT OBSERVABLE`：直连外部进程没有 JVM heap/GC/Tomcat/Hikari 指标，GPT-SoVITS 内部 queue wait 与 execution time 也未分别暴露。本报告只把外部可见端到端 latency 用于容量判断。

## 回归验证

- `heavy_media_benchmark.py` 通过 Python 语法编译与 `--help` 启动检查；非回环 GPT URL 的安全拒绝检查通过。
- 可提交 aggregate 的各档 concurrency、jobs、throughput、p95 和关键资源值已逐项与两份 raw JSON 比对，结果一致。
- 完整 Maven 回归：136 tests，0 failures，0 errors，8 skipped。跳过项仍是需要显式 Testcontainers/MySQL/Redis 或实时阿里云服务的条件测试，不能算作已执行证据。
- 压测结束后 GPT-SoVITS 已正常关闭，9880 无监听；本阶段没有启动 Spring backend、MySQL、Redis 或 Nginx，也没有执行 OSS 上传。

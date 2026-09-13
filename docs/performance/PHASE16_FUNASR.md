# Phase 16.4：真实 FunASR 容量验证

## 结论

- 当前本机真实 FunASR 服务已完成 1/2/4 并发验证，正式请求共 24 次，24 次成功，0 次失败，0 次 timeout。
- 对固定 5.29 秒中文音频，1/2/4 并发的吞吐分别为 3.487、3.614、3.537 jobs/s，p95 分别为 0.318、0.557、1.156 秒。
- 从 1 提高到 2 并发只增加 3.64% 吞吐，p95 增加 75.09%；提高到 4 并发后吞吐低于 2 并发，p95 是 1 并发的 3.63 倍。因此本机 **safe concurrency = 1**，不提高当前 `ASR_MAX_CONCURRENT=1`。
- 状态为 **VERIFIED**，但只适用于本机、当前模型和固定短中文音频的直连 HTTP `/asr`。长音频、不同编码、Spring worker 排队、多实例共享服务、故障恢复和云主机均不在本结论内。

## 环境与启动诊断

- 时间：2026-09-14（Asia/Shanghai）。
- 主机：Windows，AMD Ryzen 9 7945HX，32 logical processors；NVIDIA GeForce RTX 4060 Laptop GPU，8188 MiB。
- Python 3.9.13；FunASR 1.0.27；PyTorch 2.0.0+cu118；ModelScope 1.10.0；ONNX Runtime 1.18.1。
- 模型：Paraformer large ASR、FSMN VAD、CT-Transformer punctuation，均从本机真实 `model.pt` 加载；服务入口为仓库 `scripts/asr_server.py`。
- 初次启动在 `funasr -> librosa -> numba` 导入阶段停留于 Numba 缓存临时文件创建。当前验证环境对外部 Python 安装目录只读，导致该路径持续重试；把 `NUMBA_CACHE_DIR`、`MODELSCOPE_CACHE` 和 `MPLCONFIGDIR` 定向到仓库忽略的 `target` 可写目录后，真实服务正常启动并返回 `GET /health -> {"status":"UP"}`。
- 该处理没有修改模型、外部 Python 安装或推理代码，也没有使用 mock。生产部署必须预先提供可写缓存目录，并把冷启动纳入 readiness/发布流程。

## 协议

- 输入由 `test-assets/video-voice-swap-chinese-5s.mp4` 提取为 PCM signed 16-bit little-endian、16 kHz、单声道 WAV。
- 输入时长 5.290687 秒、169380 bytes；SHA-256 `683C5E1760E979DD704A10C1E61CCEE39C11A542C6C2110A9408421C442C1C5B`。
- 直连 `http://127.0.0.1:9977/asr`，multipart 字段与 Java `ASRServiceImpl` 契约一致；排除 Spring、Tomcat、MySQL、Redis、worker queue、对象存储和视频/TTS 阶段。
- 先执行 1 次 warmup；1/2/4 并发分别执行 6/6/12 次，即每档至少三个并发波次。
- 单请求 timeout 120 秒；每 0.5 秒采集 FunASR 进程 CPU/RSS/线程、整机 CPU/可用内存和 GPU。
- safe concurrency 采用既定口径：无功能错误和 timeout、资源仍有余量，且继续提高并发仍能获得合理吞吐收益的最高实测档。

## 结果

warmup 成功，latency 0.382 秒，返回非空识别文本（40 个字符）。所有正式响应均为 HTTP 200 且包含非空识别文本。

| 并发 | jobs | 成功/失败/timeout | throughput jobs/s | p50 s | p95 s | p99 s | FunASR CPU p95/peak | RSS peak | host CPU p95/peak | 可用内存最低 | GPU p95/peak | VRAM peak |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 6 | 6/0/0 | 3.4869 | 0.2749 | 0.3184 | 0.3219 | 379.26% / 380.30% | 972.54 MiB | 21.76% / 22.90% | 2195.95 MiB | 19.90% / 20.00% | 2679 MiB |
| 2 | 6 | 6/0/0 | 3.6137 | 0.5463 | 0.5574 | 0.5578 | 371.46% / 371.60% | 972.56 MiB | 24.04% / 25.30% | 2192.92 MiB | 26.75% / 27.00% | 2679 MiB |
| 4 | 12 | 12/0/0 | 3.5370 | 1.1048 | 1.1559 | 1.1611 | 404.66% / 410.00% | 972.70 MiB | 27.28% / 27.80% | 2159.02 MiB | 60.60% / 67.00% | 2672 MiB |

Windows/psutil 的进程 CPU 可超过 100%，约 400% 表示约四个逻辑 CPU 的计算量；不能把它和整机 CPU 百分比直接相加。1/2 并发档持续时间较短，各只有两个整机资源样本，因此资源分位数仅作容量余量参考，吞吐和逐请求 latency 来自全部请求。

## 证据与边界

- 最终 raw：`target/phase16-funasr-20260913173405/phase16-funasr-raw-evidence.json`（Git 忽略）。
- raw SHA-256：`77795FE5731A0DCCC235EA7F82A1B2015F04704881B61016E33853198BD87636`。
- 可提交摘要：`docs/performance/phase16-funasr-aggregate-evidence.json`。
- 第一轮 `target/phase16-funasr-20260913173248` 用于发现并修复首个 host CPU 样本为 0 的采样器问题，不作为最终数据。

**VERIFIED：**真实模型加载；当前进程健康；固定短中文 WAV 在 1/2/4 并发下的识别成功、latency、throughput、timeout 和资源观测；本机安全并发为 1。

**NOT VERIFIED：**一分钟及以上长音频、不同语言/噪声/编码、识别准确率基准、Spring/worker queue wait、两个 backend 聚合访问同一 FunASR、进程 kill/restart 后模型恢复时间、30/60 分钟真实 ASR soak、Linux/容器/云主机和生产流量。

## 复现

在本机真实模型与 Python 运行时存在、且缓存目录可写时：

```powershell
$env:NUMBA_CACHE_DIR = (Resolve-Path 'target').Path + '\funasr-cache\numba'
$env:MODELSCOPE_CACHE = (Resolve-Path 'target').Path + '\funasr-cache\modelscope'
$env:MPLCONFIGDIR = (Resolve-Path 'target').Path + '\funasr-cache\matplotlib'
# 启动 scripts/asr_server.py --model-root <REAL_MODEL_ROOT> --host 127.0.0.1 --port 9977
<FUNASR_PYTHON> performance/funasr_benchmark.py --audio <PCM_WAV> --pid <FUNASR_PID> --levels 1 2 4 --waves 3 --minimum-jobs 6 --sample-interval 0.5
```

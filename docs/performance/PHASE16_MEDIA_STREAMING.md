# Phase 16.3：媒体大文件流式化

## 结论

- 结论：**VERIFIED（限定为 JVM 媒体搬运链路）**。视频任务的输入落盘、TTS 音频生成、FFmpeg 输出、对象存储写入和任务结果下载都不再要求把完整视频或生成音频放入单个 JVM `byte[]`。
- 100 MiB 与 500 MiB 的合成视频结果在最大堆仅 96 MiB 的测试 JVM 中均成功经过 `TaskWorkDispatcher → ManagedObjectStorageService → LocalObjectStorageService`。100 MiB 档 heap peak 相对 before 增加 3 MiB；500 MiB 档采样不到新增 heap 峰值；两档测试期 GC count/time 增量均为 0。
- 视频服务现在接收调用方拥有的输出 `Path`，FFmpeg 直接写该路径；worker 使用 `storeFile` 以 `InputStream` 上传并在 `finally` 中删除临时输出。已有 `/api/tasks/{id}/result` 继续以 `InputStreamResource` 返回，不改变 202 提交、任务轮询和结果下载协议。
- 视频链路内的 GPT-SoVITS 输出改用 `TTSService.stream(..., OutputStream)` 逐块写临时 WAV，速度、音高、节奏参数也沿原调用传递；不再调用聚合完整音频的同步 `tts(...)`。
- 课件讲稿的分段音频同样改为直接流入 `narration-*.wav`，不再为每段先构造完整音频 `byte[]`。
- PPT 的旧同步入口从 `MultipartFile.getBytes()` 改为 `MultipartFile.getResource()`，消除了显式的整文件复制。异步 PPT 路径原本已经使用 `FileSystemResource(Path)`。

## 真实调用链

```text
POST /video_voice_swap/process
  → MediaTaskService（上传流式存储，返回 202）
  → TaskWorkDispatcher
  → objectStorage.withTemporaryCopy（输入对象流式落临时文件）
  → VideoVoiceSwapService.processVideo(..., outputPath)
      → FFmpeg 读取视频路径
      → TTSService.stream → cloned_audio.wav
      → FFmpeg → caller-owned result.mp4
  → ManagedObjectStorageService.storeFile（流式哈希 + 流式存储）
  → finally 删除 worker 临时输出
  → GET /api/tasks/{id}/result（InputStreamResource）
```

## 受限堆验证

执行命令：

```powershell
mvn '-Dtest=MediaStreamingMemoryIntegrationTest' `
    '-Dfctts.it.media-streaming=true' `
    '-DargLine=-Xmx96m' test
```

测试使用稀疏文件模拟 FFmpeg 输出，避免测试代码自身分配 100/500 MiB 数组；随后走真实 worker 调度、SHA-256 文件流、真实本地对象存储文件流、元数据校验和临时输出清理。每档结束后删除结果对象。

| logical result | JVM max heap | heap before | heap peak | peak growth | heap after GC | GC count delta | GC time delta |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 MiB | 96 MiB | 16.29 MiB | 19.29 MiB | 3.00 MiB | 16.42 MiB | 0 | 0 ms |
| 500 MiB | 96 MiB | 16.43 MiB | 16.43 MiB | 0 MiB sampled | 16.44 MiB | 0 | 0 ms |

两档均校验对象元数据大小严格等于输入逻辑大小，并断言 worker 临时输出已删除。`500 MiB > 5 × max heap`，因此旧的完整 `byte[]` 方案在同一堆限制下不可能完成；当前通过结果与源码保护共同证明文件大小未近似线性转化为 JVM heap。

## 全量扫描后的分类

| 位置 | 判定 | 处理 |
|---|---|---|
| 完整视频输出 | 原高风险 | 已移除 `Files.readAllBytes`、`ResponseEntity<byte[]>` 和视频 `storeBytes` |
| 视频内生成音频 | 原中高风险 | 已改用 `TTSService.stream` 写文件；只保留逐块缓冲 |
| 课件讲稿生成音频 | 原中风险 | 每个讲稿分段直接流入文件，再由 FFmpeg 连接；不再逐段聚合字节数组 |
| 视频上传/对象下载 | 已是流式 | `MultipartFile.getInputStream`、`ObjectStorageService.open`、`Files.copy` |
| 任务结果下载 | 已是流式 | `InputStreamResource` + 已知 content length |
| PPT 上传到 Moonshot | 原整文件复制 | `MultipartFile.getBytes()` 改为 `Resource`；Path 入口继续使用 `FileSystemResource` |
| 文本朗读与 UTF-8 校验 | 有界内存 | 仍会物化内容，但配置上限为 2 MiB，且业务响应本身需要文本；不属于大媒体搬运 |
| 同步 TTS API | 有界但仍聚合 | 文本上限 5000 字；兼容接口仍返回 `byte[]`，流式端点已经存在；视频链路不再使用它 |
| PPT 解析校验 | 有界但非流式结构解析 | 上限 30 MiB / 200 页；Apache POI 仍会构建文档结构，尚未做大 PPT heap 实测 |

## 自动化验证

- 聚焦回归：19 tests，0 failures，0 errors，0 skipped。
- 受限堆集成测试：1 test，0 failures，0 errors，0 skipped。
- 全量 Maven：148 tests，0 failures，0 errors，10 skipped；其中新增的受限堆测试默认跳过，只有显式属性开启时才执行。
- `MediaStreamingArchitectureTest` 防止视频重新出现 `ResponseEntity<byte[]>`、`Files.readAllBytes`、视频 `storeBytes`，防止视频/课件音频重新调用聚合式 TTS，并防止 PPT 重新使用 `MultipartFile.getBytes()`。

## 证据边界

- **VERIFIED**：当前 Windows/Java 17 环境；100/500 MiB 合成结果；96 MiB 最大堆；worker 到本地对象存储的文件流；大小校验；成功路径临时输出清理；源码与自动化防回退。
- **NOT VERIFIED**：500 MiB HTTP 上传（当前配置上限为 50 MiB）；真实 500 MiB 视频的 FFmpeg 转码；真实 GPT-SoVITS/FunASR 完整链路；真实 Moonshot 大 PPT 请求内存；阿里云 OSS 大文件吞吐和失败恢复；生产/Linux 文件系统行为。
- 稀疏输出验证的是 JVM 复制/哈希/存储路径，不验证视频容器合法性、FFmpeg 编码性能或外部 AI 容量。完整视频链路归 Phase 16.5，OSS 归 Phase 16.6。

机器可读数据见 `phase16-media-streaming-evidence.json`。

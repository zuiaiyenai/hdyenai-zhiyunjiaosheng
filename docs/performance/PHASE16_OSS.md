# Phase 16.6：真实阿里云 OSS 容量验证

## 结论

- 真实阿里云 OSS 的 10/100/500 MiB 串行 upload → download → SHA-256 → delete 全部成功，3/3 对象内容一致，删除后均不存在。
- 500 MiB 上传 51.429 秒、9.722 MiB/s；下载 24.661 秒、20.275 MiB/s。默认 2 分钟 OSS request timeout 未被放宽，该次传输在默认边界内完成。
- 文件从稀疏磁盘文件通过 `InputStream` 和 1 MiB 缓冲传输。500 MiB 上传/下载 heap peak delta 分别为 14/16 MiB，明显没有随对象大小接近线性增长，因此大对象存储流式路径标记为 **VERIFIED**。
- 4 路并发各上传 10 MiB，4/4 成功且下载校验一致；总墙钟 6.344 秒，聚合吞吐 6.305 MiB/s，heap peak delta 8 MiB。该单次结果证明并发 4 可工作，但吞吐低于 100/500 MiB 串行档约 10 MiB/s 的上传速率，不能据此声称并发扩展或给出 OSS safe concurrency。
- 缺失对象在 0.043 秒内返回 not found；读取到 2 MiB 后主动中断的 10 MiB 上传在 1.260 秒内失败且没有可见残留对象；错误凭证在 0.149 秒内被拒绝。
- 本轮容量测试的 8 个已登记对象和 Phase 16.5 保留的 2 个结果对象均已删除、逐一确认不存在。状态为 **VERIFIED**，但仅限本机经公网访问当前 OSS bucket 的应用存储客户端路径。

## 范围与方法

- 时间：2026-09-14（Asia/Shanghai）。运行进程为 Java 17.0.19。
- 调用的是项目实际 `AliyunOssObjectStorageService`，不是 mock、MinIO 或手写的替代协议；SDK 配置沿用应用默认连接 5 秒、socket 30 秒、request 2 分钟、max connections 64。
- 每个串行输入都是指定逻辑大小的稀疏零文件；虽然本地不预占同等物理磁盘，上传和下载仍实际传输全部 Content-Length，并对源文件和下载流计算 SHA-256。
- 上传使用 `Files.newInputStream`；下载以 1 MiB buffer 循环读取并更新 digest。测试实现没有对 10/100/500 MiB 对象调用 `readAllBytes`。
- 每个 upload/download 期间每 100ms 采集测试 JVM heap，并记录 GC 和进程 CPU。该指标是直接调用应用 OSS service 的测试 JVM，不是 8081 HTTP controller 的 heap；Phase 16.5 已另行验证 small/medium HTTP 完整链路。
- 500 MiB 超过应用默认 50 MiB 视频上传上限，因此它证明对象存储客户端的流式能力，不证明用户可以通过当前业务上传 API 提交 500 MiB 文件。
- raw 与报告都不记录 endpoint、bucket 或凭证；对象使用每轮唯一测试前缀。

## 串行结果

| 大小 | 上传 latency | 上传吞吐 | 上传 heap delta | 下载 latency | 下载吞吐 | 下载 heap delta | delete | integrity |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 10 MiB | 4.330s | 2.310 MiB/s | 2 MiB | 0.504s | 19.856 MiB/s | 6 MiB | 0.040s | VERIFIED |
| 100 MiB | 9.865s | 10.137 MiB/s | 16 MiB | 17.218s | 5.808 MiB/s | 34 MiB | 0.039s | VERIFIED |
| 500 MiB | 51.429s | 9.722 MiB/s | 14 MiB | 24.661s | 20.275 MiB/s | 16 MiB | 0.039s | VERIFIED |

10 MiB 是本轮第一个真实容量对象，包含连接/客户端冷态影响；100 MiB 下载明显慢于 10/500 MiB，说明公网单样本抖动较大。上述速率是观测值，不是带宽 SLO 或长期保证。heap delta 在 100 MiB 下载档达到 34 MiB 后，在 500 MiB 下载档回到 16 MiB，未呈文件大小线性趋势。

## 并发与失败行为

4 路并发 10 MiB：

- individual latency：6.343 / 0.988 / 5.970 / 5.923 秒。
- 4/4 上传成功，4/4 下载 SHA-256 与源一致。
- 墙钟 6.344 秒，聚合 6.305 MiB/s；heap before/peak/after 为 112.79/120.79/120.79 MiB，无 GC。

失败演练：

| 场景 | 行为 | latency | 结论 |
|---|---|---:|---|
| GET 随机不存在 key | `FileNotFoundException` | 0.043s | BOUNDED_NOT_FOUND |
| PUT 声明 10 MiB、输入流在 2 MiB 主动失败 | 上传失败、对象不存在 | 1.260s | BOUNDED_NO_OBJECT |
| 无效 AccessKey | 请求被 OSS 拒绝 | 0.149s | BOUNDED_REJECTION |

这三项证明当前样本没有无限等待或半成品可见；没有注入真实网络分区、限速、服务端 5xx 或 SDK 重试风暴。

## 清理与凭证边界

- 容量测试登记 8 个 key：3 个串行对象、4 个并发对象、1 个中断上传 key；最终 delete failures=0、remaining objects=0。
- Phase 16.5 的两个持久结果对象在本阶段通过显式白名单清理，执行前 2/2 存在，执行后 0/2 存在。
- 测试只删除本轮唯一前缀和显式列出的 Phase 16.5 key，没有列举或批量删除 bucket 其他内容。
- 用于本次验证的是已在对话中暴露的长期凭证。即使功能与容量测试通过，凭证治理仍为生产阻断项：应立即轮换当前凭证，并迁移到仅允许目标 bucket/prefix 所需动作的 RAM 角色或短期 STS，不得把长期密钥写入仓库、镜像、日志或报告。

## 证据

- 正式 raw：`target/phase16-oss-20260913185412/phase16-oss-raw-evidence.json`（Git 忽略）。
- raw SHA-256：`CFAEFFF5BA72BAB15D7E0CEC624B0AA646F27C2E1C24D83D8AE66C1BA395B3C2`。
- Phase 16.5 显式清理 raw：`target/phase16-oss-explicit-cleanup.json`（Git 忽略）。
- 清理 raw SHA-256：`FFFA7F52AFD745948A50C69E042083E722DF65DA0B1A3E1498DEF7898F3E89DF`。
- 可提交摘要：`docs/performance/phase16-oss-aggregate-evidence.json`。
- 可复现测试：`AliyunOssCapacityIntegrationTest` 与 `AliyunOssExplicitCleanupIntegrationTest`，均需显式环境开关，普通回归不会访问 OSS。

## 未验证边界

**NOT VERIFIED：**应用 HTTP 端点 100/500 MiB（默认配置明确拒绝）；multipart/resumable upload；同区域云主机网络；多个 backend 共享 SDK 连接池的聚合容量；真实网络中断、限速、DNS、OSS 5xx 与重试恢复；跨区域/公网长期带宽；OSS 生命周期、版本控制、服务端加密、RAM policy、STS、审计、跨地域容灾和 SLA；30/60 分钟对象存储 soak。

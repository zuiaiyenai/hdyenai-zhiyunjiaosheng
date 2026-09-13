# Phase 10：双实例运行与正确性验证

## 1. 本阶段结论

Phase 10 已在同一台 Windows 主机上真实启动两个独立 Spring Boot 进程，并通过 Nginx 组成双实例后端：

```text
browser / API client
        |
        v
Nginx 127.0.0.1:8080
        |
        +--> backend-1 127.0.0.1:8081  management 9091
        |
        +--> backend-2 127.0.0.1:8082  management 9092
                  |
                  +--> shared MySQL schema
                  +--> shared Redis DB
                  +--> shared Aliyun OSS bucket
```

当前代码不再依赖 JVM 课件 Map 保证正确性。课件项目每次从 MySQL 读取，写入使用 `lock_version` CAS；两个实例同时修改同一版本时，只有一个更新成功，另一个返回 HTTP 409 和稳定错误码 `COURSEWARE_CONCURRENT_UPDATE`。

本阶段证明的是双实例部署与业务正确性，不是 10、100 或 200 并发容量结论。吞吐、延迟和安全并发仍由 Phase 11–13 的真实压测决定。

## 2. 共享状态与节点本地状态

| 状态/资源 | 多实例策略 | 正确性边界 |
| --- | --- | --- |
| JWT | 两个 backend 使用同一 `JWT_SECRET` | 任一节点签发的 token 可由另一节点验证，不使用 HTTP Session。 |
| 用户、课件、任务 | MySQL 是 source of truth | 任务 claim 使用原子 SQL；课件写入使用 version CAS。 |
| 缓存、限流 | 两节点使用同一 Redis database | Redis 不是最终业务数据源；缓存 miss 回源 MySQL。 |
| 文件 | production provider 为 Aliyun OSS | DB 保存 object key/provider/bucket/size/checksum/owner；节点本地目录只存执行期临时文件。 |
| WebSocket | Nginx 在握手时负载均衡 | 不配置 `ip_hash`/cookie sticky；连接建立后由 TCP 自然固定在该节点，连接中途不迁移。 |
| 课件工作目录 | backend-1/backend-2 独立目录或 volume | 可从 OSS 重新 materialize，不作为跨实例 source of truth。 |

## 3. 课件跨实例修复

原实现把 `ProjectState` 缓存在 JVM `ConcurrentHashMap`，因此一个节点更新后，另一个节点可能继续返回旧脚本；进程内 `synchronized` 也不能阻止两个 JVM 同时写。

本阶段做了以下最小修复：

1. V11 为 `courseware_project` 增加 `lock_version`。
2. `CoursewareProjectRepository.save` 返回新版本；JDBC 实现使用
   `WHERE project_id = ? AND owner_username = ? AND lock_version = ?` 更新并递增版本。
3. 删除 `CoursewareProjectService` 的 JVM 项目 Map，每次请求从 Repository 读取最新状态。
4. 同一版本 CAS 失败返回 HTTP 409；已处于 `PROCESSING` 的项目返回 `COURSEWARE_BUSY`。
5. 恢复出的课件在生成音频/视频/头像/打包时按需创建节点本地临时目录，并从对象存储重新取文件。
6. 课件任务进入失败、取消或超时终态时，dispatcher 将仍为 `PENDING/PROCESSING` 的项目标记为失败，避免 worker 异常后永久卡住。

进程内 `synchronized (state)` 仍用于一个请求内部的对象修改顺序，但跨实例正确性由 MySQL CAS 保证。

## 4. Nginx 与 Compose 配置

- Nginx `backend_cluster` 使用 `least_conn`，上游为 8081/8082，并记录 `$upstream_addr`、上游状态和耗时。
- 普通 HTTP 在连接错误、超时、502、503、504 时最多尝试两个 upstream。
- WebSocket 保留 Upgrade/Connection，并传递 X-Forwarded 头；本地 8080 Origin 统一按已有同源策略重写。
- 未配置 sticky session。单条 WebSocket 连接不跨节点迁移；客户端断线重连时可进入任一健康节点。
- Compose 定义 `backend-1`、`backend-2`，共享 DB/Redis/JWT/OSS 配置，使用独立工作 volume。
- Prometheus 同时抓取 `backend-1:9091` 与 `backend-2:9091`。

## 5. 2026-09-13 真实验证

运行环境：

- Windows 本地主机
- 两个真实 Spring Boot/JVM 进程
- Windows Nginx 1.22
- 本地 MySQL 5.7，专用 schema `tts_phase10_multi_instance_verify_*`
- 本地带认证 Redis，专用 DB 14
- 真实 Aliyun OSS provider
- backend-1/backend-2 各 1 个 DB worker

| 验证项 | 状态 | 本轮证据 |
| --- | --- | --- |
| 双实例启动 | VERIFIED | 8081/9091 与 8082/9092 同时运行；两个 readiness 均为 UP，DB/Redis 均为 UP。 |
| 并发 Flyway 启动 | VERIFIED | 空专用 schema 在两个实例并发启动下完成 V1→V11，最终 schema version 为 11。 |
| HTTP 负载均衡 | VERIFIED | 经 Nginx 连续 20 次请求，backend-1/backend-2 为 10/10。 |
| JWT 跨节点 | VERIFIED | backend-1 token 访问 backend-2 为 200；backend-2 token 访问 backend-1 为 200。 |
| HTTP Session 独立性 | VERIFIED | 认证只依赖共享 JWT；Actuator 的 Tomcat active session 为 0。 |
| 课件即时可见 | VERIFIED | backend-1 先读 `initial script`，backend-2 更新后，backend-1 立即读到 `updated by backend 2`。 |
| 课件并发 CAS | VERIFIED | MySQL 行锁确保两请求先读同一版本后同时放行，结果严格为 409/200；错误码 `COURSEWARE_CONCURRENT_UPDATE`，revision 只增加 1。 |
| Redis 跨实例缓存失效 | VERIFIED | backend-1 先产生 voiceList hit；backend-2 上传后，backend-1 miss 从 1 增至 2并立即看见新 voice。 |
| OSS 跨实例文件 | VERIFIED | backend-2 上传；backend-1 预览 200；backend-1 删除 200；backend-2 随后预览 404。 |
| 双 worker 幂等 | VERIFIED | 两节点同时提交同一课件视频任务，返回同一 taskId；duplicate 恰好为 False/True；终态仅 `attempts=1`。 |
| WebSocket 握手分流 | VERIFIED | 10 次真实 Upgrade 均返回 101，backend-1/backend-2 为 5/5；无 sticky 配置。 |
| 单节点 HTTP 故障切换 | VERIFIED | 停止 backend-1 后，经 Nginx 的 10/10 个认证请求继续返回 200。 |
| 节点恢复加入 | VERIFIED | backend-1 readiness 恢复并经过 Nginx fail timeout 后，20 次请求重新为 10/10。 |
| 专用 MySQL CAS 集成测试 | VERIFIED | V1→V6→V11，首次 save 成功、陈旧版本 save 抛 optimistic locking failure；测试后 schema 精确删除。 |
| 针对性单元/配置测试 | VERIFIED | 15 tests，0 failures，0 errors；包含 dispatcher 终态清理、409、课件跨实例服务语义、Nginx/Compose/Prometheus 配置。 |
| 后端完整回归 | VERIFIED | 133 tests，0 failures、0 errors、7 skipped；真实 Redis 限流测试已运行，条件式 MySQL/外部服务 live 测试未伪装成通过。 |
| 前端生产构建 | VERIFIED | Vite build 成功，14 modules transformed。 |
| Nginx 配置语法 | VERIFIED | Windows Nginx 1.22 `-t` 通过。 |
| 专用资源清理 | VERIFIED | 两个 Java 进程和项目 Nginx 已停止；Redis DB 14 为 0 keys；专用 schema 已删除；`zhiyunjiaos` 仍为 1。 |

课件视频任务因测试记录没有音频而按预期进入 `FAILED`；本项只验证跨实例幂等领取和一次执行，不把媒体生成成功率作为本阶段结论。

## 6. 明确未验证

| 项目 | 状态 | 原因/后续 |
| --- | --- | --- |
| Docker Compose 双 backend 实际启动 | NOT VERIFIED | 当前本地没有可用 Docker daemon；本轮只由 SnakeYAML 测试验证 Compose 结构。 |
| WebSocket 实际 ASR 音频流 | NOT VERIFIED | 只验证 101、分流和连接归属；FunASR 未运行。 |
| WebSocket 节点中断无缝迁移 | NOT SUPPORTED | 已建立 TCP 连接无法迁移；客户端必须重连，重连可落到另一节点。 |
| GPT-SoVITS/FunASR 多实例并发 | NOT VERIFIED | 外部服务未参与 Phase 10；归入 Phase 12 重任务压测。 |
| FFmpeg/视频生成成功 | NOT VERIFIED | 双 worker 测试在进入 FFmpeg 前因缺少音频按预期失败。 |
| OSS 高并发吞吐与失败恢复 | NOT VERIFIED | 只验证跨节点 create/read/delete 正确性；容量与网络故障属于 Phase 11/14。 |
| MySQL/Redis/OSS 跨主机网络 | NOT VERIFIED | 两个 backend 与基础设施在同一 Windows 主机；不是目标云拓扑。 |
| 10/50/100/200 并发 SLO | NOT VERIFIED | 尚未运行 Phase 11 k6/Gatling 逐级压测。 |
| 1000 注册/活跃用户容量 | NOT VERIFIED | 不能由双实例正确性测试推导。 |

## 7. 部署约束

1. 所有 backend 必须使用完全相同的 DB、Redis database、JWT secret、对象存储 bucket 和任务准入上限。
2. V11 必须先完成，再让新版本节点接收课件写请求；旧版本不理解 `lock_version`。
3. 本地文件 volume 不得在业务层当共享文件系统使用。需要跨节点读取的文件必须先写对象存储并持久化元数据。
4. Nginx 只负责健康节点选择，不替代任务幂等、数据库 CAS 或客户端重试。
5. WebSocket 客户端必须实现有上限的重连；重连后重新认证，不能假设回到原节点。
6. OSS、JWT、数据库和 Redis 凭证只通过本地忽略配置或运行环境注入，不进入 Git。

## 8. 下一阶段

Phase 11 使用 k6 建立 10→50→100→200 并发阶梯，只压普通 HTTP/API workload，并同时采集：

- RPS、p50、p95、p99、错误率；
- CPU、RAM、heap、GC、Tomcat threads；
- Hikari active/pending、MySQL connections、Redis latency；
- executor active/queued、task rejected。

重型 TTS/ASR/GPT-SoVITS/FFmpeg/视频任务继续单独进入 Phase 12，不能和普通 API RPS 合并成一个“支持 1000 用户”的结论。

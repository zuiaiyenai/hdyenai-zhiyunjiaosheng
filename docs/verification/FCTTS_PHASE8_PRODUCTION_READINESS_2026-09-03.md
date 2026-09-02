# Production Readiness Remediation Report

## 1. Executive Summary

本轮 Phase 1–8 已完成可在当前环境执行的整改与真实验证。建议等级为 **D. 可部署 Staging**，尚不应宣称可公网生产。

主要依据：MySQL 5.7 空库 migration 和重启持久化通过；Redis 真实路径与不可用 fallback 通过；核心 API、隔离浏览器 E2E、Moonshot 与阿里云 NLS live test 通过；Java/Python 测试与构建通过。主要缺口是前端源码不在仓库、本机 Docker Linux daemon 无法启动、GPT-SoVITS/FunASR 模型服务未完成 live 验证，以及未执行长时稳定性和容量压测。

## 2. Git 提交

| Phase | Commit | Message |
| --- | --- | --- |
| 1 | `00d8d18` | `fix(security): constrain voice library file access` |
| 2 | `181e2e8` | `feat(db): add reproducible database migrations` |
| 3 | `bfd16c0` | `feat(security): harden authentication cors and uploads` |
| 4 | `3190628` | `feat(courseware): persist project metadata` |
| 5 | `609a983` | `feat(tasks): add reliable async media processing` |
| 6 | `03a90c0` | `refactor(api): improve errors pagination and resource consistency` |
| 7 | `62e7e52` | `build: add reproducible local and ci environments` |
| 8 | 当前 Phase 8 提交 | `test: add production readiness verification` |

所有 Phase 均保持独立 commit，未 push。

## 3. 已解决问题

| 级别 | 状态 | 证据 |
| --- | --- | --- |
| P0 声音库任意读取/删除 | FIXED | 客户端不再提供物理路径；owner 查询；路径 normalize 与根边界检查；路径穿越和越权测试。 |
| P1 数据库不可复现 | FIXED | 4 个 Flyway migration，MySQL 空库首启/二启通过。 |
| P1 认证、CORS、上传安全 | FIXED | 统一登录错误、限流、密码策略、Origin 白名单、MIME/magic/decode/配额验证。 |
| P1 课件/任务仅内存保存 | FIXED | 课件项目、修订和异步任务持久化，重启后可重新查询。 |
| P1 长耗时请求可靠性 | FIXED | 有界线程池/队列、超时、取消、单用户并发限制、重复提交抑制和外部进程清理。 |
| P2 API/异常/分页/文件一致性 | FIXED | 稳定错误码、不泄露物理路径的错误、有界分页和 commit 后删文件/待清理补偿。 |
| P2 可复现工程环境 | PARTIALLY FIXED | Java/Python/Compose/CI 已建立；前端只有 bundle，没有可复现源码构建。 |
| P3 持续容量验证 | DEFERRED | 仅完成短时本机负载冒烟，未执行 30 分钟稳定性、资源拐点和多实例测试。 |

## 4. 数据库

- Flyway：`V1` 初始 schema、`V2` 课件项目、`V3` 异步任务、`V4` 待清理文件。
- 7 张业务表：`user`、`voice`、`speaking_history`、`courseware_project`、`courseware_project_revision`、`async_task`、`pending_file_cleanup`；另有 Flyway 历史表。
- MySQL 5.7 上使用 4 个专用空库执行 Flyway、课件、Task 和文件清理 live integration test，全部通过。
- E2E schema `tts_phase8_e2e` 证明用户、声音、课件和 Task 在应用重启后仍存在。Mapper 未发生表/字段不存在错误。

## 5. Security

- Path traversal：服务端存储键、`normalize()` 和根路径 `startsWith` 检查，覆盖 Windows/Linux 路径形式。
- 权限：声音、课件和 Task 均执行 owner scope，含越权查询/删除/取消测试。
- CORS：带凭据请求仅允许显式 Origin 白名单。
- 登录：不区分用户不存在/密码错误；限流 key 包含 IP + username；Redis 和内存 fallback 均已验证。
- 密码：服务端执行可配置长度与复杂度策略，现有 BCrypt 用户兼容。
- 上传：校验大小、MIME、magic number 和可解码性；原始文件名不作为存储路径；存在用户配额。
- 密钥：受跟踪配置使用环境变量；本地真实配置被 Git 忽略；最终扫描不得发现真实密钥。

## 6. Reliability

- Task 状态：`PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/TIMEOUT`。
- 有界线程池/队列，可配置超时与单用户并发数；取消/超时终止 `Future` 和注册 Process。
- FFmpeg 同时消费输出、有界等待、超时 `destroy`/`destroyForcibly`、校验 exit code，finally 清理。
- 10 次相同任务请求均返回 HTTP 202，仅生成 1 个唯一 taskId；`RUNNING` 任务在重启时被恢复为终态。

## 7. Persistence

| 数据 | 存储 | 重启结果 |
| --- | --- | --- |
| User | MySQL | PASS |
| Voice metadata / media | MySQL + 安全文件存储 | PASS |
| Courseware project/revision | MySQL + 安全文件存储 | PASS |
| Async task / pending cleanup | MySQL | PASS |
| Dialogue session | Redis；关闭时为进程内存 | Redis PASS；fallback 重启后不保留（预期限制） |

## 8. Tests

| 类别 | 结果 | 说明 |
| --- | --- | --- |
| Unit/component/mock integration | PASS | Maven：95 tests，0 failures，0 errors，5 个显式环境门禁 skip。 |
| MySQL Live Integration | PASS | 4 个专用空库、4 个 live test class。 |
| Aliyun NLS Live Integration | PASS | 自动获取 Token 并合成音频，1 test。 |
| Python | PASS | 全新 venv 安装，pytest 3/3，`asr_server` import PASS。 |
| E2E API | PASS | 注册、登录、上传、列表、试听、课件、Task 查询/取消、重启持久化。 |
| Browser E2E | PASS WITH NOTE | 隔离 Playwright 完成登录、声音列表/试听/删除、PPTX 上传和 Moonshot 生成。既存 `favicon.ico` 500 不影响核心链路。 |
| Load smoke | PASS | 3 组本机短时负载，成功率 100%。 |

Mock 测试不计入 Live Integration。

## 9. External Services

| Service | Mock Test | Live Test | Result |
| --- | --- | --- | --- |
| MySQL 5.7 | 是 | 是 | PASS |
| Redis | 是 | 是 | PASS（真实限流键及未监听端口 fallback） |
| FFmpeg/ffprobe | 是 | 是 | PASS |
| Moonshot | 是 | 是 | PASS（真实课件讲稿生成） |
| Aliyun NLS | 是 | 是 | PASS（Token 自动刷新 + TTS） |
| GPT-SoVITS | 是 | 否 | NOT RUN - EXTERNAL SERVICE REQUIRED（`9880` 未监听） |
| FunASR | 是 | 部分 | 安装、pytest 和 import PASS；NOT RUN - MODEL REQUIRED |

## 10. Build

| 项目 | 结果 |
| --- | --- |
| Java test / compile / package | PASS |
| Python clean install / pytest / import / py_compile | PASS |
| Frontend | NOT RUN - SOURCE REQUIRED；只有已构建 bundle |
| Docker Compose config | PASS |
| Docker backend image | NOT RUN - LOCAL WSL/DOCKER LINUX DAEMON REQUIRED |

Docker 未构建不代表 Dockerfile 已经运行验收。当前 Windows 主机没有可用 WSL，Docker Desktop Linux daemon 无法启动。

## 11. Performance

环境：Windows 本机、单实例 Spring Boot、MySQL 5.7、Redis、HTTP loopback；短时冒烟，无持续稳定性阶段。

| Scenario | Requests | Concurrency | Success | p50 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| login | 50 | 5 | 100% | 251.06 ms | 378.45 ms | 386.88 ms |
| voices | 200 | 20 | 100% | 7.37 ms | 11.79 ms | 12.82 ms |
| task status | 200 | 20 | 100% | 7.59 ms | 14.70 ms | 28.95 ms |

上述数字不能外推生产容量或 SLA。

## 12. Remaining Risks

### BLOCKER（阻止公网生产）

- 前端源码、锁文件和可复现构建链缺失。
- 尚未在可用 Linux Docker daemon 上真实构建并启动镜像。
- 未完成长时稳定性、资源拐点、故障注入、备份恢复和多实例验证。

### HIGH

- GPT-SoVITS 服务未运行，本地 TTS/声音克隆未做 live E2E。
- FunASR 缺少完整模型目录，只验证了安装、单测和模块加载。
- 进程内 Redis fallback 在多实例间不共享，不等价于生产级分布式限流。

### MEDIUM

- 异步任务仍为单节点执行模型，没有多节点租约/协调。
- 压测不包含高并发媒体转码、外部 AI 与 CPU/内存/磁盘指标。
- 待清理队列需在 Staging 进行长时失败重试和配额告警验证。

### LOW

- 既存 `favicon.ico` 返回 500，未影响本轮核心 E2E，Phase 8 未扩大范围修复。

## 13. Deployment Recommendation

**D. 可部署 Staging**。

进入 Staging 前应在 Linux Docker/Compose 环境构建并启动，配置独立 MySQL/Redis/存储，执行备份恢复与至少 30 分钟稳定性压测，并补齐 GPT-SoVITS/FunASR 真实链路。恢复前端源码、锁文件和 CI 构建是进入公网生产审批的必要条件。

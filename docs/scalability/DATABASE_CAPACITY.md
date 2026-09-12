# Phase 7：数据库容量与分页治理

## 1. 本阶段结论

Phase 7 已把随数据量增长的音色、语音笔记和口语历史列表改为有界分页，把课件列表的 revision 查询从 `1 + N` 次降为每页固定 2 次，并让任务幂等查询直接使用已有的活动幂等生成列。V9 仅增加 4 个由真实访问模式和 MySQL `EXPLAIN` 支持的索引，同时删除 2 个已被复合索引覆盖的 voice 单列索引。

本阶段的 `VERIFIED` 范围是：当前 SQL 在本机 MySQL 5.7.26 上可执行，V1→V9 迁移兼容，代表性数据分布下优化器自然选择预期索引，列表结果有明确上限。它不是吞吐、延迟或并发容量证明；Stage A/B/C 容量仍是 `NOT VERIFIED`，必须等待 Phase 11–14 的真实压测、soak 和故障测试。

## 2. 验证环境与证据边界

- 数据库：本机真实 MySQL 5.7.26。
- schema：专用 `tts_phase7_capacity_verify_20260913_0510`，测试前后由 Flyway clean 清空；不使用业务 schema `zhiyunjiaos`。
- 代表性数据：3,000 voice、2,000 courseware project、5,000 async task、3,000 object metadata、3,000 speaking history，以及少量 courseware revisions。
- 计划采集：先迁移到 V8 并插入数据，记录 before `EXPLAIN`；再迁移 V9、执行 `ANALYZE TABLE` 并记录 after `EXPLAIN`。
- MySQL 5.7 不支持 MySQL 8 的 `EXPLAIN ANALYZE`。本阶段实际执行的是 `EXPLAIN`，因此 `rows` 是优化器估算值，不是实际扫描行数或耗时。
- 测试没有使用 `FORCE INDEX`；索引断言要求优化器自然选择，避免把“索引存在”伪装成“索引有效”。

## 3. 真实 SQL 访问模式审计

| 数据域 | 真实读取模式 | 有界性与索引结论 |
| --- | --- | --- |
| user | 按 `username` 查询用户、密码和更新密码。 | `uk_user_username` 唯一索引覆盖；未使用的管理员 `findAllUsers()` 已删除，不再保留无界入口。 |
| voice | 按主键详情；按 owner 汇总字节；公共音色与当前用户私有音色分别排序后 `UNION ALL`；可选名称包含搜索。 | list/search 使用 SQL `LIMIT/OFFSET`，两个分支各最多读取 `offset + limit`；V9 两个 visibility/order 复合索引消除分支 filesort。`%keyword%` 仍不能使用普通 B-tree 做前缀定位。 |
| speaking_history | 按 user，或 session+user，按 `created_at/history_id` 倒序。 | 新增 page/size 下推，最大 100；已有两个复合索引因 InnoDB 二级索引隐式携带主键，可自然满足稳定排序，无需新索引。 |
| courseware_project | 按 project_id+owner 详情；按 owner、updated_at/project_id 倒序分页。 | 已有 `idx_courseware_project_owner_updated` 自然满足分页排序；不追加冗余 project_id 列。 |
| courseware_project_revision | 详情按 project_id 查询；列表页对最多 100 个 project_id 做一次 `IN (...)` 批量查询。 | 主键 `(project_id, revision_number)` 同时支持单项目和批量读取；列表从 `1 + N` 次 SQL 降为项目页查询 + revision 批量查询。 |
| async_task | 主键/owner 查询、owner 列表、活动幂等键、PENDING claim、RUNNING heartbeat recovery、worker 查询与原子状态更新。 | owner list、claim、worker、活动幂等唯一索引已存在；V9 只为 stale recovery 增加 `(status, heartbeat_at)`。幂等读取改为 `active_deduplication_key`，与唯一约束使用同一访问路径。 |
| async_task_user_slot | owner+slot 原子占位，task_id 唯一查找/删除。 | 主键和唯一索引已覆盖；没有新增索引。 |
| pending_file_cleanup | 按 attempts、updated_at、cleanup_id 取固定 batch；按主键更新/删除。 | `idx_pending_file_cleanup_retry` 与排序一致；没有 status/next_retry_at 字段，也没有 outbox 表，不能虚构相应查询或索引。多实例重复领取仍是后续正确性边界。 |
| stored_object_metadata | object_key+owner 详情/删除；按 owner 汇总大小；按 owner+prefix+suffix 分页列出 note metadata。 | V9 增加 owner+object_key，减少 prefix 候选；suffix `%/note.txt` 仍需剩余条件过滤。正文只读取当前页，不读取 lookahead 对象。 |

## 4. 分页与 N+1 整改

### 4.1 音色列表与搜索

- `/voice_library/list` 与 `/voice_library/search` 的 DB 模式不再先查全表再由 Java 切片。
- 显式提供 page/size 时返回 `PageResult`；page 从 0 开始，默认 size 20，最大 100，通过多取 1 条计算 `hasNext`。
- 为兼容旧前端，不传 page/size 时仍返回数组，但默认最多 20 条，不再无界。
- 公共与私有可见性不再使用一个 `OR` 扫描。两个分支分别按自己的索引取 `offset + limit` 条，再合并排序并应用外层分页，保证合并结果正确。
- nodb 模式也复用相同上限，避免默认响应随 JVM 数据线性增长。

### 4.2 语音笔记

- `/accessibility/voice-notes` 接受可选 page/size，metadata 查询同时带 owner、prefix、suffix、`LIMIT/OFFSET`。
- 查询多取 1 条 metadata 仅用于 `hasNext`，服务不会打开这条 lookahead 对象；每页最多读取 size 个 `note.txt`。
- 这仍是一个有界 N+1：每页 1 次 metadata SQL + 最多 N 次 OSS/object open。未来若列表只需标题/摘要，应把列表字段写入 metadata/DB，正文放到详情接口。

### 4.3 口语历史

- `/speaking_practice/history` 接受可选 page/size；两种筛选都使用 `LIMIT size+1 OFFSET offset`。
- 排序增加 `history_id` 作为相同时间戳的稳定 tie-break；已有索引可用，不新增索引。
- nodb 的趋势对象本身只保留最近 5 次，但仍是 JVM 本地状态；多实例一致性不在本阶段证明范围内。

### 4.4 课件 revisions

- `CoursewareProjectService.list` 先取一个有界 project window，再用一个参数化 `IN (...)` 查询批量加载 revisions。
- 列表不再逐项目调用 `findRevisions`。详情路径仍按单个 project_id 查询，保持原行为。
- revision 的 `script` 是 MEDIUMTEXT；当前仍会加载本页全部 revision 正文。若真实数据证明 revision 数或脚本体积成为瓶颈，应把列表 DTO 改为 revision count/latest summary，而不是先加缓存掩盖读取量。

## 5. V9 索引决策

### 5.1 新增

| 索引 | 查询依据 | 观察结果 |
| --- | --- | --- |
| `idx_voice_owner_visibility_created (owner_username, public_visible, created_at, voice_id)` | 当前用户私有音色分支。 | before 使用 owner 单列索引且 filesort；after 使用新索引，无 filesort。 |
| `idx_voice_visibility_created (public_visible, created_at, voice_id)` | 公共音色分支。 | before 使用 visibility 单列索引且 filesort；after 使用新索引，无 filesort。 |
| `idx_async_task_status_heartbeat (status, heartbeat_at)` | stale RUNNING recovery。 | 代表“失联任务占 RUNNING 少数”的数据分布下，估算候选从 1,250 降为 10。 |
| `idx_stored_object_owner_key (owner_username, object_key)` | owner 范围内按 voice-note prefix 过滤。 | 估算候选从 30 降为 3，但仍有 filesort；这是减少对象类别过滤量的取舍，不是零排序成本。 |

### 5.2 删除

- `idx_voice_owner_username` 被新 `(owner_username, public_visible, created_at, voice_id)` 的左前缀覆盖。
- `idx_voice_public_visible` 被新 `(public_visible, created_at, voice_id)` 的左前缀覆盖。

### 5.3 明确拒绝新增

- 不给 `idx_async_task_owner_created` 和 `idx_courseware_project_owner_updated` 显式追加 task_id/project_id。真实 MySQL 5.7 计划已无 filesort；InnoDB 二级索引隐式携带主键。
- 不为 task claim 再建索引。`idx_async_task_status_available_created` 已自然用于 `status + available_at + created_at + task_id` 的领取顺序。
- 不为 `%keyword%` 音色搜索加普通 B-tree。前导通配符不能获得期望定位能力；需要全文检索或搜索服务时必须由真实搜索规模和中文分词需求驱动。
- 不为 courseware revision 批量查询加索引。复合主键已以 project_id 开头。
- 不为不存在的 outbox/status/next_retry_at 查询臆造索引。

## 6. Before / After EXPLAIN

以下 `rows` 均为 MySQL 5.7 优化器估算；`VERIFIED` 的是计划形态，不是耗时 SLO。

| 查询 | V8 before | V9 after |
| --- | --- | --- |
| public voice page | `idx_voice_public_visible`, rows 600, `Using filesort` | `idx_voice_visibility_created`, rows 600, `Using where; Using index` |
| private voice page | `idx_voice_owner_username`, rows 30, `Using filesort` | `idx_voice_owner_visibility_created`, rows 30, `Using where; Using index` |
| task owner page | `idx_async_task_owner_created`, rows 50, no filesort | 同一旧索引，rows 50, no filesort；因此未加冗余索引 |
| stale RUNNING recovery | `idx_async_task_status_available_created`, rows 1,250, `Using where` | `idx_async_task_status_heartbeat`, rows 10, `Using index condition; Using where` |
| voice-note metadata | `idx_stored_object_owner_created`, rows 30, no filesort | `idx_stored_object_owner_key`, rows 3, `Using index condition; Using filesort` |
| courseware owner page | 已有复合索引 | `idx_courseware_project_owner_updated`, rows 20, no filesort |
| task claim | 已有复合索引 | `idx_async_task_status_available_created`, range, rows 1,251, no filesort |
| active deduplication | 已有唯一索引，但旧代码没有直接查询生成列 | `uq_async_task_active_deduplication`, const, rows 1 |
| speaking owner/session page | 已有复合索引 | 对应已有索引，rows 30, no filesort |

对象 metadata 的 after 计划不是单向“更好”：它减少 prefix 候选但引入小集合排序。Phase 11 若显示用户对象分布以 voice note 为主、filesort 成为热点，应基于真实 trace 重新评估；当前不再追加第五个猜测索引。

## 7. 深分页与剩余边界

1. 当前使用 offset pagination。即使每个查询有上限，深页仍需扫描/跳过前面的记录；voice 的两个分支还各需读取 `offset + limit`。若真实访问出现高 page 值，应改为基于 `(created_at, id)` 的 seek/cursor pagination。
2. 音色名称包含搜索仍可能扫描 visibility/owner 范围内大量候选，普通 B-tree 无法解决前导 `%`。
3. voice-note suffix 条件不能用 owner+object_key 索引的连续前缀定位，且最终排序仍为 filesort；当前只证明候选集有界和分页可执行。
4. voice-note 列表仍会读取当前页正文，最多 N 次对象存储请求；外部 OSS 延迟会直接影响该接口。
5. `total` 是当前页成功返回数，不是额外执行 `COUNT(*)` 得到的全量总数；系统使用 `hasNext` 避免每次列表都增加 count 查询。
6. 本阶段没有测 Hikari 等待、MySQL CPU/IO、锁等待、buffer pool 命中、p50/p95/p99 或并发写放大。
7. 代表性测试数据用于计划回归，不代表生产数据分布。尤其 heartbeat 索引的价值建立在“stale 是 RUNNING 少数”的正常运行假设上，Phase 11/13 必须观察真实分布。

## 8. 验证状态

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| V1→V9 empty-schema 与重启迁移 | VERIFIED | 真实 MySQL 5.7.26 专用 schema 启动两次通过。 |
| Phase 4/5/6 MySQL 持久化和原子任务回归至 V9 | VERIFIED | 课件、清理队列、双 Repository 任务并发测试通过。 |
| 代表性数据与 EXPLAIN | VERIFIED | `DatabaseCapacityMySqlIntegrationTest` 非跳过执行通过。 |
| MyBatis voice union 参数绑定 | VERIFIED | 真实 Spring/MyBatis/MySQL 集成测试调用 list 与 search。 |
| revision batch 与 metadata suffix SQL | VERIFIED | 真实 JDBC 路径执行并断言结果。 |
| 单元与 Spring nodb 回归 | VERIFIED | 完整 Maven：120 tests，0 failures，0 errors，7 skipped；被跳过的 MySQL 容量相关测试已另行非跳过执行。 |
| 前端生产构建 | VERIFIED | `frontend` 下 Vite 5.4.21 build 通过，14 modules transformed。 |
| 普通 API SLO / 10–200 并发 | NOT VERIFIED | 属于 Phase 11。 |
| MySQL 最大吞吐、安全连接数与生产数据分布 | NOT VERIFIED | 尚无绑定硬件和原始指标的负载结果。 |
| 1000 注册用户 / 200 active 场景 | NOT VERIFIED | 不能由索引和单元测试推导。 |

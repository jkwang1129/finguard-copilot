# Phase 1 只读事实闭环验收记录

日期：2026-08-26

## 结论

Tasks 1–9 的实现、针对性测试和独立审查已完成。Task 10 的仓库级验证通过；实现边界
满足“Core 是事实源、Copilot 只读、证据不足时拒答”的 Phase 1 目标。

本次没有创建新的长期基础设施。仓库目前没有用于启动 Core、Copilot、Copilot MySQL 和
RabbitMQ 的隔离 Compose/Dockerfile 或一键 HTTP 验收脚本，因此没有声称完成未实际运行的
“两服务进程级验收”；现有真实 MySQL/RabbitMQ/Testcontainers 集成测试证据如下。

## 新鲜验证证据

| 范围 | 命令/方式 | 结果 |
|---|---|---|
| Core | `mvn -B -ntp -q test`，进程内注入现有 MySQL/RabbitMQ/Redis 测试参数 | 388 tests, 0 failures, 0 errors, 0 skipped |
| Copilot Java | `./mvnw.cmd -B -ntp -q test` | 172 tests, 0 failures, 0 errors, 0 skipped |
| Copilot unified | `./scripts/verify.ps1` | Java 172/172；Python 2/2；前端 lint、test 1/1、build 通过；npm audit 0 vulnerabilities |
| Schema/消息/恢复 | Task 8 focused + smoke + full suite | focused 20/20；smoke 2/2；全量结果已包含在 172/172 |
| 保留期/清理 | Task 9 focused + full suite | focused 12/12；全量结果已包含在 172/172 |

Core 的默认 worktree 没有 `mvnw.cmd`，所以本次用 PATH 中已安装的 Maven 执行等价命令；
测试所需的密码只存在于当前进程环境，没有写入仓库。

## 已覆盖的 Phase 1 边界

- Core `/api/auth/me` 与 `/api/review-tasks/{id}/context` 覆盖成功、未认证、无权限、找不到和超时/上游错误语义。
- Copilot 只接受 `REVIEWER`，跨用户查询不泄露分析存在性；创建响应在运行、快照、幂等保留和 Outbox 同一事务提交后才返回 `202`。
- JWT 只在创建请求期间转发给 Core，快照、消息、模型输入、持久化和 API 响应不保存 JWT。
- 快照 checksum、事实一致性和 JSON Schema 校验失败会拒绝结果；Phase 1 Stub 只能产生 `INSUFFICIENT_EVIDENCE`。
- RabbitMQ 重复、乱序、停机恢复、发布确认/返回失败、坏 payload、重试/DLQ、worker 重投和崩溃后重投均有测试覆盖。
- 快照、幂等键和已发布 Outbox 的时钟控制清理任务不会删除活动数据；快照删除后保留不可复现标记。
- Core 客户端仅有两个固定 GET 路径；源码扫描未发现 Copilot 对 Core 的写请求实现。

## 清理与限制

本次未创建隔离容器、网络或卷，因此没有执行删除；常驻的 `finguard-*` 开发容器和卷未被
停止或删除。Copilot Testcontainers 由 Ryuk 在测试结束时回收。Task 9 留有一个非阻塞的
测试改进项：snapshot GET 的保留期断言目前是结构性标记断言，后续可改为直接选择快照列。

以下仍属于后续阶段：RAG/知识索引、真实模型、Agent Graph/Tool Calling/MCP、SSE/UI
业务页面、评测闭环、可观测性集成和 production-like 镜像/Compose 部署。

# P4-4 可观测性本地工程验收

日期：2026-11-02；基线提交 `c30b92342a222ae9b579ec32ea90856dc883284c`，本次改动未提交。设计与任务见[设计](../superpowers/specs/2026-11-02-p4-4-observability-design.md)、[计划](../superpowers/plans/2026-11-02-p4-4-observability.md)，使用入口见[运行说明](../operations/phase4-4-observability.md)。

## 本次结论

P4-4 的本地工程范围已验收：显式 OpenTelemetry 阶段 Trace、V11 创建事务关联、Outbox/Relay/Worker 上下文、脱敏及有界导出、固定标签指标、预算状态快照、实际 Prometheus/Grafana 和本地 Langfuse 接收。7天演示清理通过公开 API 执行，已验证过期样本删除、近期样本保留；未购买或启用 Langfuse Enterprise 保留功能。

本工作包没有新增付费模型调用，业务链路使用 local-test-stub。没有改写 P4-1/P4-2/P4-3 的历史冻结报告。真实人工校准、真实模型质量、更换 Embedding、生产高可用和 Phase 5 工具能力不由本次验收替代。

## 实现和安全边界

- 显式埋点12个阶段：API_CREATE、CORE_CONTEXT、SNAPSHOT、OUTBOX_PUBLISH、WORKER、RETRIEVAL、CONTEXT、PREFLIGHT、MODEL、VALIDATION、ACCOUNTING、PERSISTENCE。V11 只新增 trace_id；关联和 Outbox 在创建事务中持久化。旧消息/无效追踪字段继续按原业务处理。
- 每个出口最多1000条 span，包含排队和实际未完成的导出；队列TTL 60秒，批次64，HTTP期限2秒、不重试、不落盘。排队满、过期、失败、停机均有固定原因计数。导出等待超时仍保留实际未完成请求的容量占用。
- 输出重新构造白名单快照，清除自由 name、属性、events、links、resource、scope、tracestate 和 status description。只保留受控元数据；模型及 Prompt 的声明版本指纹不等于远端权重或正文哈希。
- 指标不再带 model_configuration_version 等动态标签；provider/outcome 均归一为有限枚举。HTTP尝试、进程用量观察与 MySQL 账本状态分别计量，UNKNOWN_HELD 不解释成实际0费用。数据库采样最多一个后台任务，故障保留旧值并暴露新鲜度。
- 管理端点从主端口分离到 loopback 9091；实际验证主端口 `/actuator/prometheus` 为404，独立端点200，Prometheus target UP。
- Core 读取阶段和向 Core 的 traceparent 传播在 Copilot 侧实现；未修改或证明 Core/供应商内部 Span。Graph、模型工具执行、SSE、面向审核员的完整页面仍在后续工作包。

## 进程和服务证据

| 场景 | 证据与结果 |
| --- | --- |
| 当前构建正常链路 + 实际 Langfuse | run `20261102220740`，26/26检查；真实 Core/Copilot HTTP、MySQL、Outbox/RabbitMQ、Qdrant/Redis，模型为本地替身。Langfuse v4 API 读到同一 Trace 的16条观测，含2个 Worker span；初次成功、重复 NOOP，实际模型 HTTP 共1次。 |
| 当前构建导出不可达 | run `20261102221052`，26/26检查；Langfuse 出口指向 loopback 不可达端口，EXPORT_FAILED=20，业务仍成功，重复投递后模型 HTTP 仍1次。通用 OTLP 出口独立工作。 |
| 较早正常/政策拒绝补充验证 | runs `20261102203514` / `20261102203851`，各26/26；政策拒绝 Trace 定位 VALIDATION / FACT_VALIDATION_FAILED。这两次构建早于 v4 实时导出头修正，保留为补充证据，不合并冒充当前构建的全部场景。 |
| Prometheus / Grafana | 实际 target UP、6个预置面板通过 API 读取，主端口管理隔离与固定业务标签检查通过。业务临时服务关闭后 target DOWN 属正常停机状态。 |
| 本地 Langfuse | web/worker、独立 PostgreSQL、ClickHouse、Redis、MinIO 已启动；Web 健康200，初始化项目和 API key 身份一致；所有宿主机端口绑定 loopback。 |
| 演示保留 | 通过 OTel 发送8天前与近期各1条合成 Trace；预演只选中过期项，公开 DELETE 接受后另行轮询 v4 API，证实旧项消失、近期项保留。后台任务每日执行，失败5分钟后重试；受分页上限、异步删除和停机影响，不是严格到秒的过期保证。 |

逐项脱敏报告位于[p4-4 证据目录](p4-4/langfuse-ingestion.json)、[导出故障](p4-4/export-down-acceptance.json)、[保留验证](p4-4/retention-acceptance.json)、[管理隔离](p4-4/management-acceptance.json)。四次业务报告分别保留原 JAR/脚本哈希；当前构建与源文件快照见[冻结基线](2026-11-02-p4-4-baseline.json)。当前 JAR SHA-256 为 `aefcf22b1742e01163ae7abae2edcf990ecf8fdd3dd3c88c8b28b42e21991802`；已核对其213个 class 与编译树逐字节相同。

## 测试与复核

Java 首轮 `mvn -q '-Dspring.test.context.cache.maxSize=1' test` 跑391项，390通过、1失败：旧 MigrationTest 的列清单未包含 V11 trace_id。只修正该断言后，MigrationTest 定向复测退出0；Langfuse v4 头的 OtlpExportTest 先失败再修复、复测退出0。当前84份 Surefire 报告汇总391 tests、0 failures、0 errors、0 skipped；**没有把定向复测描述为另一次全套命令退出0**。日志保存在忽略目录 `target/p4-4-full-java.log`、`target/p4-4/migration-fixed.log`、`target/p4-4/langfuse-v4-green-package.log`。

Python 最终全套：`uv run --project tools/agent-evaluation pytest tools/agent-evaluation/tests -q`，183 passed，21.67秒。前端 lint、Vitest（1/1）、构建均通过；没有将后端 Grafana API 检查描述成浏览器视觉验收。

独立复核提出两项 Important，均有 RED→GREEN：指标/Tracer 异常不能覆盖已成功的业务/模型结果；超过2秒仍未完成的导出不能提前释放容量。相关保护也覆盖已提交用量后的指标失败。另对本地清理新增代理禁用、删除结果未知、项目身份、cursor重复/去重、近期活动保护和启动凭据丢失保护验证。没有把网络后端接受请求当成删除完成。

## 操作和清理状态

`./scripts/start-observability.ps1 -Langfuse` 启动本地服务；凭据在 Git 忽略的 `.env.langfuse.local` / `.env.observability.local`，从未写入验收文件。丢失凭据时禁止为已有资源重建数据库或加密密钥。Langfuse Web 初次启动发生 Node 默认堆上限错误，依据日志将堆设1024MB、容器设1536MB后验证健康；这是本机演示配置，不是生产容量结果。

留存 Langfuse、Prometheus、Grafana 和其数据卷以供使用；临时业务 Core/Copilot、模型替身、OTLP接收器、隔离 Qdrant/Redis 已按记录的PID/容器名核对后停止。共享 finguard-mysql/rabbitmq/redis 未停止，隔离数据库/vhost及停止的容器仍保留。没有执行提交、推送、合并、生产部署或删除卷。

过期样本可用 `scripts/e2e/RetentionSeed.java` 复现（从本机 env 导入项目两把 key，使用 Maven dependency:build-classpath 生成的 classpath）；它只向固定 loopback Langfuse 发送无业务正文的两个合成 Span。随后运行 `scripts/e2e/langfuse_retention.py` 先预演，确认候选后显式 `--apply`，并通过 v4 observations API 另行核对结果。

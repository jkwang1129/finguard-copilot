# P4-4 Observability Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement task by task; use test-driven-development and verification-before-completion.

**Goal:** 按 analysisId 关联受控阶段、运行版本及账本指标，在遥测后端故障时保持业务处理语义。

**Architecture:** 显式 OTel span + 白名单脱敏导出；Outbox 持久传播上下文；Micrometer 固定标签与 MySQL 预算快照；可选 OTLP/Langfuse 及本地 Prometheus/Grafana。

**Tech Stack:** Java 17 / Boot 3.5.8 / managed OpenTelemetry SDK+OTLP / Micrometer / MySQL / Docker Compose。

**Spec:** `docs/superpowers/specs/2026-11-02-p4-4-observability-design.md`

## Global Constraints

- 不变更已有迁移；仅 V11 增加 trace_id。
- 不记录原始输入、输出、身份、自由异常、URL；不启用自动 Agent。
- Span 队列每目的地最多1000/60秒，网络2秒、不重试；遥测失败不影响业务。
- 不新增付费请求、模型备用或 P4-5 的事件/SSE 能力。
- 本轮采用当前独立 Phase 4 工作树；提交/推送不由计划中的示例命令自动授权。

## Review Focus

- 无效或旧 traceparent 不能改变消息处置或注入额外字段。
- 回滚、重投、NOOP 不得被当作新模型调用或再次结算。
- Span 的 resource/scope/status/events 也不能携带敏感数据。
- 指标刷新失败不能把未知费用伪装为零或无限增加标签。
- 遥测队列满、后端卡住、停机都不能拖住业务线程。

### Task 1: 受控 span、脱敏和有界导出

**Files:** `observability/AnalysisTelemetry.java`, `SafeSpanData.java`, `BoundedSpanProcessor.java`, `TelemetryConfiguration.java`, `TelemetryProperties.java`; tests 同名；pom.xml。

**Interfaces:** `AnalysisTelemetry.start(Stage, UUID, String)` -> AutoCloseable scope；`call(Stage, Supplier<T>)`；`traceparent()` -> String/null；脱敏器只接收 SpanData。

- [x] 写上下文关联/线程恢复、恶意属性/events/status、队列满/到期/失败的测试，运行并确认 RED。
- [x] 最小实现显式 SDK、枚举、脱敏快照、独立出口队列及配置验证。
- [x] 运行 `mvn -q -Dtest='*TelemetryTest,SafeSpanDataTest,BoundedSpanProcessorTest' test`，Expected: PASS。

### Task 2: API / Outbox / Worker / 模型阶段关联

**Files:** CreateAnalysisService、AnalysisCreationStore、AnalysisRequestedMessage、OutboxRelay、AnalysisWorker、ModelAnalysisOrchestrator、RealModelConfiguration、HttpCoreClient；V11；定向集成测试。

**Interfaces:** 使用 Task 1 telemetry scope；OutboxRecord 与消息增加可选 traceparent 和旧构造器；trace_id 在创建事务中更新。

- [x] 添加旧消息、创建事务、Relay→Worker 父子关联和模型拒绝阶段测试，确认 RED。
- [x] 在现有边界封装 scope，不改变业务异常/重试或提交顺序；Core 只传播有效 traceparent。
- [x] 运行新增测试及现有 Worker/Create/Relay/Model 测试，Expected: PASS；迁移列表更新到 V11。

### Task 3: 固定标签与账本状态指标

**Files:** ModelUsageRecorder、observability/LedgerMetrics.java、TelemetryMetricsTest、LedgerMetricsIntegrationTest。

**Interfaces:** Task 1 阶段计时；账本每30秒刷新为有限状态快照，查询超时2秒，缓存不可用指标明确可见。

- [x] 测试恶意 provider/outcome/version 不增加标签、未知费用/未发送/已结算分开、刷新失败保留状态；确认 RED。
- [x] 移除版本指标标签，归一 provider/outcome；添加账本快照 gauges。
- [x] 运行定向指标与用量集成测试，Expected: PASS。

### Task 4: 运维配置与进程证据

**Files:** `ops/observability/` Compose、Prometheus、Grafana provisioning/dashboard；运行手册；验收脚本及报告。

**Interfaces:** 独立 loopback management 端口；通用 OTLP 与 Langfuse 单独配置，只从环境读密钥。

- [x] 检查 Compose 与面板配置；用本地接收器证明协议、脱敏及后端断开不改变业务。
- [x] 运行 Prometheus/Grafana 并查询健康性、抓取目标和预置面板；记录真实 Langfuse 接收是否已执行。
- [x] 跑相关 Java/Python/前端全套并核对 git diff --check；独立复核本轮差异后更新任务状态。

每项结果记录到本计划专属 ledger；没有执行的进程/外部服务验收保持未勾选。

## 执行补充与结果

用户无已有 Langfuse 实例，增加 `ops/langfuse/compose.yml`、`scripts/start-observability.ps1`、专用演示保留任务与 `RetentionSeed.java`。固定镜像digest、独立凭据/卷和loopback端口；保留任务禁用代理/重定向、验证项目、v4 cursor分页与近期活动，异步结果不冒充删除完成。已验证过期样本删除、近期样本保留。

受控阶段、队列、消息与指标均有RED→GREEN证据；独立复核两项Important已修复，本地清理复核的代理与凭据丢失保护也已修复。后续v4分页适配由执行者自行核验并通过单元及真实实例测试，未宣称另一次独立复核。

Java首轮全套391项有1个旧迁移断言失败，修正后MigrationTest及受影响OTLP定向复测通过；84份最新报告391项0失败/错误/跳过，并未重新跑一遍整套。Python最终183项，前端lint/Vitest/build通过。完整范围和未覆盖条件见 `docs/acceptance/2026-11-02-p4-4-observability.md`。

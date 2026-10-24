# 2026-10-19 Core / Copilot 真实业务链路验收

## 结果

真实进程验收通过，15 项断言全部通过。Core 与 Copilot 各自运行独立 Java 进程，使用真实 MySQL、RabbitMQ、Redis、Qdrant 和千问 `qwen-plus`；审核输入为独立数据库中的合成案例。

- runId：`20261019151956`
- analysisId：`b0be86f4-c0ac-41c2-827d-490a949136ab`
- 分析状态：`SUCCEEDED`
- 模型建议：`CONFIRMED`；Core 最终审核状态保持 `PENDING`、版本保持 `0`。
- 政策引用：1 条，来自 `e2e-large-amount-policy` / `v1`。
- 已发布索引：`published-20261019151956`，Qdrant 实际检索命中后从 MySQL 回取正文并校验 checksum。
- 成功调用用量：输入 1558 Token、输出 521 Token、模型延迟 12185 ms。
- 成功验收中的模型用量账本：1 条 `SUCCESS`；Outbox 发布记录及 Worker receipt 各 1 条。
- 结束后检查：本次 vhost 的请求队列、重试队列、DLQ 均无待处理或未确认消息。

原始脱敏报告位于 `target/business-e2e/20261019151956/report.json`，服务日志位于同目录；该目录被 Git 忽略。日志和报告未保存供应商密钥、JWT 或模型原文。

## 验证过程

1. Core 与 Copilot 健康检查均为 UP，数据库分别完成原有 Flyway 迁移。
2. CLI 使用现有知识登记、构建、评测和发布服务创建 PUBLISHED 索引；使用与运行时一致的 `fixed-v1` Embedding。
3. 真实登录取得 REVIEWER JWT，Core `/api/auth/me` 和审核任务上下文返回实际身份及脱敏事实。
4. Copilot 未认证请求返回 401，仅具 ADMIN 的请求返回 403；合法创建返回 202。
5. 重放相同幂等键返回相同 analysisId，没有创建第二次分析。
6. Worker 通过实际 RabbitMQ 消息处理分析；模型输出通过 JSON、事实来源路径、金额及 ID、精确政策引用校验后持久化。
7. Core 的账户、交易、导入、对账、风险、审核和审计表 checksum 前后一致。

链路修复后全量 Java 回归于 2026-10-19 15:28（UTC+08:00）完成：260 项测试，0 失败、0 错误、0 跳过，`BUILD SUCCESS`；日志为 `target/business-e2e-final-regression.log`。本轮未重跑 Python 和前端验证。

## 本次发现并修复

- Qdrant Bean 的条件注册依赖 Spring 自动配置顺序，导致真实 profile 启动缺少 VectorStore；已改为明确依赖，并补充使用 Boot 自动配置 ObjectMapper 的回归测试。
- Java 模型上下文遗漏本次 analysisId；已显式传入并验证输出 ID 匹配。
- 政策片段原先以混合文本表示；已改为可直接复制的引用 JSON，完整保留多行与引号，精确匹配校验继续生效。
- Java 请求原先只通过供应商扩展参数传递 Schema；现将完整 Schema 注入系统提示词，千问使用通用 JSON Object 模式，服务端继续校验完整结构。
- 模型未被告知事实来源路径白名单；已同步到提示词及公开输出 Schema。
- 模型返回后被事实校验拒绝时用量记录遗漏；已补充 `FAILURE` 账本路径及回归测试。

排障及成功验收共执行 6 次真实模型请求；每个分析的调用预算均为 1，未自动重试。较早的事实校验失败请求受上述用量遗漏影响，不能使用旧账本合计推断供应商总用量。

## 范围

这是单个合成审核案例的真实进程验收。Core 的案例由 SQL fixture 准备，本轮没有通过 HTTP 重新验收 CSV 上传和对账全过程。固定 Embedding、单文档单案例的索引评测只证明基础设施和发布链路，不证明语义召回或生产审核质量；2026-09-02 的 Golden Dataset 历史报告没有在本轮重测。

验收结束已停止本次 Core、Copilot 进程和 Qdrant 容器，保留独立数据库、RabbitMQ vhost 和停止的 Qdrant 容器作为核查证据。原有中间件继续运行，原有业务库没有用于本次种子写入。

复跑方式见 [运行手册](business-e2e-runbook.md)。

## 2026-10-21 收尾核对

只读复核原报告、Surefire XML 和保留的数据库：15 项业务断言通过，Java 测试合计 260 项且无失败、错误或跳过；数据库仍为 `SUCCEEDED` / `PUBLISHED`，成功用量、已发布 Outbox 和 Worker receipt 各 1 条，Core 审核仍为 `PENDING` / 版本 `0`。RabbitMQ 对应 vhost 所有队列均无待处理或未确认消息。本次没有追加真实模型调用。

验收进程已停止，但本机 8080 当前由另一工作区 `finguard-core-interactive-live-demo` 的 Core 占用；未停止或修改该进程。复跑需先确认 8080、8081、6333 空闲。原有 MySQL、RabbitMQ、Redis 继续健康运行。

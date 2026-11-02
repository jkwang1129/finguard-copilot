# P4-1 隔离政策发布与召回验证

`LargeAmountPolicyFlowIntegrationTest` 使用 Testcontainers 独立启动 MySQL 8.4.10 和 Qdrant 1.15.5，执行 Flyway 迁移，通过应用实际服务注册项目大额政策、建索引、运行单样本检索检查并发布，再从已发布索引检索 MySQL 正文及校验值。测试数据和索引仅存在于隔离容器中，测试结束由 Testcontainers 清理；不修改现有运行环境的知识索引。

分析编排使用真实知识检索和合成 Core 快照、可控模型替身：达到阈值且引用政策的 `CONFIRMED` 被接受；`IGNORED`、历史阈值高于交易金额、缺失风险事实均被拒绝。第二个索引只发布另一份政策，用来验证大额政策缺失时方向性建议被拒绝。安全定向回归同时覆盖已有越权、恶意知识、URL/秘密准入和工具调用响应测试。

2026-10-28 定向测试 `LargeAmountPolicyFlowIntegrationTest,ReviewAnalysisControllerTest,GetAnalysisServiceTest,KnowledgeDynamicFactRejectionTest,CompatibleChatModelGatewayTest,UntrustedInstructionGuardTest`：35 项通过、0 失败/错误/跳过。随后单独重跑更新后的 `LargeAmountPolicyFlowIntegrationTest`：1 项通过；模型完整引用了另一份实际召回的政策后，仍由大额政策护栏拒绝。`scripts/run-business-e2e.ps1` 的政策 ID 断言已与新的发布文档 ID 对齐；该脚本本轮未执行。

该验证没有启动 Core 业务服务、HTTP/Outbox/Worker 全链路，也没有调用真实模型。单样本召回与固定向量只验证接线，不能作为语义检索质量证明。项目政策尚未发布到任何实际运行环境；此前 2026-10-22 的真实模型 E2E 是旧政策和旧代码的历史证据，不能替代当前版本验收。

## 2026-10-31 全链路增量

使用 `scripts/run-business-e2e.ps1 -LocalModelStub -CoreRoot C:\dev\finguard-core` 执行真实 Core、Copilot HTTP、隔离 MySQL 数据库、RabbitMQ vhost、独立 Redis/Qdrant、Outbox 和 Worker。模型通过本机回环 HTTP 替身提供合成结果，无付费模型调用；测试任务以 SQL 合成，不代表真实业务数据。三次测试分别得到：

- 正确建议：`20261031094534`，`SUCCEEDED`，准确引用已发布政策。
- `-ExpectPolicyRejection`：`20261031094637`，错误 `IGNORED` 以 `FACT_VALIDATION_FAILED` 结束，结果表为空，失败用量与消息回执存在。
- `-ExpectFactRejection`：`20261031094741`，交易金额与风险命中金额不一致，`FACT_VALIDATION_FAILED`，结果表为空，失败用量与消息回执存在。

三次均验证 401、ADMIN 403、幂等重放、Core 复核任务状态与业务/审计表校验和不变。每次报告在 `target/business-e2e/<runId>/report.json`；测试进程及独立 Redis/Qdrant 已停止，测试数据库和 RabbitMQ vhost 按脚本设计保留。首次运行 `20261031094429` 因替身不支持 HTTP chunked 请求失败，修复后才完成上述验证。

2026-10-31 又以 `-LocalModelStub -ExpectMissingPolicy` 运行 `20261031094955`：隔离索引发布另一份政策，模型替身准确引用该政策；大额政策护栏仍以 `FACT_VALIDATION_FAILED` 拒绝，结果表为空，失败用量为 `FAILURE / 10 / 5`，消息回执存在，Core 状态和业务/审计表校验和未改变。Core 数据库约束本身不允许大额风险命中缺失阈值或低于阈值；缺失阈值由隔离编排测试覆盖。

安全及数值定向回归 `UntrustedInstructionGuardTest,ModelAnalysisOrchestratorTest,ReviewAnalysisControllerTest,GetAnalysisServiceTest,KnowledgeDynamicFactRejectionTest,CompatibleChatModelGatewayTest,NumericThresholdGuardTest`：60 项通过、0 失败/错误/跳过。覆盖范围及当前 reviewerInput 非公共 API、检测模式有限等限制见 [安全增量记录](2026-10-27-phase4-security-diagnostics.md)。模型工具调用响应被拒绝；完整工具参数治理仍属后续阶段。未进行真实模型、生产环境或泛化注入攻击验收。

## 2026-10-31 当前版本真实模型复测

用户授权费用上限 10 元；计划最多一次模型请求且不自动重试。以独立数据库、RabbitMQ vhost、Redis 和 Qdrant，使用 DeepSeek `deepseek-flash`，合成大额任务经真实 Core、Copilot HTTP、Outbox 和 Worker 执行。运行 `20261031100012`：15 项检查通过，分析 `94e44cb5-5640-45f1-a052-6a397aedf1e6` 成功并精确引用项目政策，建议 `CONFIRMED`；模型账本一条 `SUCCESS`，输入 2021、输出 719 tokens；Core 决策与业务/审计表校验和不变。

根据调用前查询的 [DeepSeek 官方人民币价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)，按高峰、输入全部缓存未命中的保守单价计算，上述用量约 0.009794 元；实际扣费以供应商账单为准。该账本的价格表为历史 `quality-unpriced-v1`，不代表实际为零。一次合成案例不是生产环境验收，也不能证明模型通用安全或质量。服务已停止；隔离数据库与 vhost 保留。完整 Java 测试 311 项通过，0 失败/错误/跳过。

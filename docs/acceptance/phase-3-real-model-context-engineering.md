# Phase 3：真实模型与上下文工程验收记录

状态更新（2026-10-24）：2026-10-22 审查发现的超时/调用预算/消息恢复三条异常路径已有独立修复与回归，见[修复验收](2026-10-24-phase3-failure-recovery.md)。下列真实模型评测与本次离线修复证据分开记录。

## 结论

Phase 3 的适配器、上下文组件、已发布知识检索接线、离线评测管线和真实模型比较已完成。2026-10-22 按批准预算完成三项补验收：v3 真实 Prompt 对照 12 次、当前 DeepSeek Java 业务 E2E 1 次、独立冻结保留集 12 次，合计 25 次。阶段仍不是生产就绪，暂无可接受的真实备用模型；历史千问及智谱质量指标未重测。

最新 [v3 真实复测](../evaluations/2026-10-22-v3-real-retest.md)中，旧/新 Prompt 的建议、事实与引用均为 100%，历史引用提升没有复现；[DeepSeek 业务 E2E](2026-10-22-deepseek-business-e2e.md) 15/15 断言通过，Core 无审核写入；[独立保留集](../evaluations/2026-10-22-holdout-evaluation.md)建议为 11/12，事实和引用均通过，应拒答 5/5。H03 数值压力案例的建议错误未修饰或重跑，当前校验不能保证推理正确。证据、预算和边界见[补验收汇总](2026-10-22-phase3-closeout.md)。

## 已交付能力

- `stub`、`real-deepseek`、`real-zhipu`、`real-qwen` 等运行配置通过环境变量显式选择。
- OpenAI-compatible 模型适配器统一 `ModelGateway` 契约，支持响应超时、错误分类、结构化 JSON 和用量读取。
- 上下文组装器执行事实优先、知识去重、低排名裁剪，并限制 32k 输入和 2k 输出预算。
- 输出经过 JSON Schema、事实路径、引用和 Prompt 注入拒答校验；无效结果不会伪造成有效建议。
- MySQL `model_usage_ledger` 记录模型、Token、调用次数、延迟、估算成本和错误分类。
- Python 3.12/uv 评测工具支持 Stub 基线、单模型真实评测和双报告合并。
- `scripts/run-phase3-evaluation.ps1` 要求健康检查、固定供应商端点和当前进程密钥，不打印密钥。
- `real-*` profile 会把已发布索引、Qdrant 和 MySQL 正文回取接入 `ModelAnalysisOrchestrator`；没有已发布知识或 Qdrant 不可用时，不调用模型并以 `KNOWLEDGE_UNAVAILABLE` 失败。

## 已完成的最后一项子任务

真实模型编排现在由 `KnowledgeRetrievalService` 查询唯一已发布索引，经过 checksum 校验的证据再交给 `ContextAssembler`，同时注入受控 reviewer input。模型输出的政策引用必须精确匹配保留下来的文档、版本、章节和片段；没有证据时，方向性输出 fail-closed。Qdrant 的真实跨进程启动、索引发布和 Core/Copilot 认证业务 E2E 已单独执行，结果为 `SUCCEEDED`，见 [2026-10-19 真实业务链路验收](2026-10-19-business-e2e.md) 和 [复跑手册](business-e2e-runbook.md)。

## 可重复证据

普通验证命令：

```powershell
.\scripts\verify.ps1
```

历史仓库验证结果：Java `255` 个测试通过；Python `21` 个测试通过；前端 lint、1 个测试文件和生产构建通过。2026-10-19 链路修复后的 Java 全量回归为 `260` 个测试通过（0 失败、0 错误、0 跳过），日志为 `target/business-e2e-final-regression.log`；本轮没有重跑 Python 和前端，不能将历史结果当成本轮证据。

2026-10-21 模型改进后另行完成 Java 全量 `260` 项与打包；当日 Python 全量为 `63` 项通过。2026-10-22 Python 全套 **100 项通过**，Core/Copilot 均以 `-DskipTests` 打包成功并完成真实业务 E2E；本轮无 Java 源码改动，没有重跑 Java 全套或前端。最新证据范围见[补验收汇总](2026-10-22-phase3-closeout.md)。

历史跨供应商比较使用同一数据集、Prompt、知识索引版本和 6 个案例，结果见 [模型选型记录](../evaluations/phase3-model-selection.md)。历史 Qwen 报告是在评测器增加严格 Schema/引用校验前生成的，因此其中的 JSON/拒答百分比只能作为实验记录，不能作为当前质量门禁；智谱 6/6 为 `TIMEOUT`，失败率 100%。2026-10-22 的 25 次预算已耗尽，不追加真实请求。

## 范围与限制

历史真实评测运行器在 Copilot 健康检查通过后，直接访问固定的供应商 OpenAI-compatible 端点；它只证明适配器和评测链路。独立的 2026-10-19 业务验收则实际经过 Core、Copilot、Outbox、RabbitMQ、Worker、Qdrant 已发布索引和真实模型，核对用量、回执与 Core 只读边界。该案例由 SQL fixture 准备，未通过 HTTP 重跑 CSV 上传和对账全过程，且使用固定 Embedding，不能推断语义召回质量。

模型结果也不等于生产质量门禁：历史 Qwen 事实覆盖率为 19.4%，智谱候选不可用；本轮未重新测量这些指标。当前不启用自动真实模型故障转移；Stub 只用于离线开发和测试。历史模型比较报告与本轮跨进程业务验收应分别引用，不互相替代。

密钥只保留在 Git 忽略的本机 `.env.local` 或当前进程环境变量中，不写入受跟踪代码、公开报告或日志；文件不会自动被 Spring 加载，显式进程变量优先。已在聊天中发送过的供应商 Key 应在供应商控制台撤销并重新生成。

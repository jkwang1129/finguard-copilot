# 架构决策记录

ADR 记录已经确定且会影响后续实现的选择。状态为 `Accepted` 的决策需要通过新的 ADR
替代，不直接改写历史理由。

| ADR | 决策 | 状态 |
|---|---|---|
| [0001](0001-separate-copilot-service.md) | Copilot 使用独立项目和仓库 | Accepted |
| [0002](0002-reviewer-assistance-read-only-mvp.md) | 首版是面向 REVIEWER 的只读审核辅助 | Accepted |
| [0003](0003-separate-core-facts-from-rag-knowledge.md) | Core 事实与 RAG 知识严格分离 | Accepted |
| [0004](0004-deterministic-orchestration-before-agent-graph.md) | 先确定性编排，后按需求引入 Agent Graph | Accepted |
| [0005](0005-port-based-model-and-vector-adapters.md) | 模型与向量存储通过端口适配 | Accepted |
| [0006](0006-capture-context-before-async-processing.md) | 异步处理前同步捕获脱敏事实 | Accepted |
| [0007](0007-python-evaluation-companion.md) | Python 只承担评测、实验和跨框架基线 | Accepted |
| [0008](0008-durable-agent-dispatch-and-checkpoint.md) | Outbox、RabbitMQ 与 Checkpoint 保证 Agent 可靠执行 | Accepted |
| [0009](0009-agent-evaluation-and-observability.md) | 规则、Judge、人工校准和 Trace 组成评测闭环 | Accepted |
| [0010](0010-bounded-model-routing-and-quota.md) | 多模型路由受预算、配额和安全策略约束 | Accepted |
| [0011](0011-validated-sse-and-lightweight-ui.md) | SSE 只传受控事件并配套轻量演示页面 | Accepted |
| [0013](0013-phase5-durable-graph-and-human-resume.md) | 固定Graph、MySQL恢复真相与人工续程 | Local engineering accepted |

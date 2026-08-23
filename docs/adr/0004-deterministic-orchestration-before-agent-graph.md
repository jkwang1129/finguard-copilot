# ADR-0004：先确定性编排，后按需求引入 Agent Graph

- 状态：Accepted
- 日期：2026-08-20

## 背景

首版流程固定：验证身份、捕获事实、检索知识、调用模型、校验输出。直接引入自主 Agent
或复杂 Graph 会增加状态、重试和调试成本，但没有对应的真实分支需求。

## 决策

MVP 使用普通 Java `AnalysisOrchestrator` 和显式状态机。完整路线的 Agent 阶段已经定义了
工具选择、检索不足重试、输出修复、人工暂停/恢复和多类失败分支，因此到达该阶段后使用
Graph 表达这些状态；在这些需求出现前不提前引入。

## 后果

每一步输入输出可独立测试，失败更容易定位；代价是 Agent 能力在后续阶段交付。只有
Graph、Tool、Checkpoint、HITL、回放和评测通过后，才能宣称完成 Agent Runtime，不能把
普通 HTTP 调用包装成 Tool Calling。

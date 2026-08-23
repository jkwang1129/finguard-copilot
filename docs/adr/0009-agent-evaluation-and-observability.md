# ADR-0009：规则、Judge、人工校准和 Trace 组成评测闭环

- 状态：Accepted
- 日期：2026-08-20

## 背景

Prometheus 能描述系统健康，却不能判断 Agent 是否选对工具、走对轨迹或给出可靠建议；
单独使用 LLM-as-Judge 又会把另一个概率模型误当作真值。

## 决策

OpenTelemetry 负责端到端 Trace，Langfuse 保存脱敏的 Agent/Prompt/Tool 轨迹、评测和
Bad Case，Prometheus/Grafana 负责低基数系统指标。效果评测同时使用确定性规则、版本化
LLM-as-Judge 和人工抽样；Judge 必须在固定人工样本上记录一致率。Python 工具生成可重复
报告并将失败案例回填回归集。

## 后果

效果、可靠性、延迟和成本可在同一运行上解释，评测不依赖单一 Judge。代价是需要数据
脱敏、评测版本、人工样本和遥测保留策略。

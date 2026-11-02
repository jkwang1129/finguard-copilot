# 2026-10-27 Phase 4 第一批基线与版本字段清单

本记录针对 `codex/phase-4-first-batch` 从 `4f15993` 建立的工作区。它区分现有持久化字段、离线评测元数据和 Phase 4 尚需补齐的关联，不把历史真实模型结果当作本轮验证。

## 已有版本和关联

| 对象 | 当前来源和持久化位置 | 当前可追溯范围 / 缺口 |
| --- | --- | --- |
| 分析运行 | `analysis_run.id` 为 `analysisId`，保存 `review_task_id`、`review_task_version`、状态和受控失败码 | 可定位一次审核分析；尚无统一的运行版本清单。 |
| 事实快照 | `analysis_input_snapshot` 保存 schema v1、脱敏 JSON、SHA-256、捕获/到期时间 | 默认 30 天删除事实正文；哈希保留能证明快照一致性，删除后不能从哈希恢复事实。 |
| 输出 | `analysis_result` 保存 schema v1、建议枚举、通过校验的 JSON | JSON 引用有文档 ID/版本/章节/摘录；没有直接保存这次检索使用的索引版本。 |
| 模型和 Prompt | `ModelAnalysisOrchestrator` 生成 `phase3-model-v1:<provider>:<modelId>`，`PromptTemplate` 提供 `review-analysis-v*` | 成功/失败调用的模型配置版本与 Prompt 版本记录在 `model_usage_ledger`；分析 GET 响应尚未统一暴露这些运行版本。 |
| 用量和价格 | `model_usage_ledger` 按 `analysis_id` 保存 provider、Token、延迟、估算成本、价格表版本、调用结果 | 价格是估算；供应商请求已发出而账本尚未持久化时，只能依据 `model_call_reservation` 保守确认可能有账单，精确用量需对账。 |
| 已发布知识 | `knowledge_index_version` 保存索引版本/发布状态；`knowledge_citation` 具备 `analysis_id` 和 `index_version` 结构 | 运行时检索的是唯一已发布版本，并核对片段 checksum；还需核查真实模型成功路径是否把每条引用落入 `knowledge_citation`，不能仅凭表结构宣称已实现运行关联。 |
| 离线评测 | Python `EvaluationReport` 保存 dataset、Prompt、model config、knowledge index 和 evaluator 版本及 SHA-256 | 这是报告级元数据；尚无与 Java `analysisId` 对齐的统一运行清单。旧的 H03 报告只保存正确与否，未保存实际建议枚举。 |

Phase 4 后续版本契约应以 `analysisId` 关联不可变事实快照哈希、输出 Schema、Prompt、模型配置、已发布索引和价格表版本。新增字段应通过 V7 之后的 Flyway 迁移，不修改 V1–V6；涉及事实和 Prompt 的原文继续遵守现有脱敏与保留边界。

## H03 冻结与回归边界

- 冻结清单：[`phase3-holdout-v1-freeze-2026-10-22.json`](../evaluations/phase3-holdout-v1-freeze-2026-10-22.json)。已核对其中五个 SHA-256，均与当前文件相同。
- 历史结果：[`2026-10-22-holdout-evaluation.md`](../evaluations/2026-10-22-holdout-evaluation.md) 的 H03 `0.100000000000000001 > 0.100000000000000000` 应为 `CONFIRMED`，模型建议错误；报告没有实际建议枚举或原文。H03/H04 是当前 Core 金额存储精度之外的合成压力样本。
- 本批只新增离线回归与确定性拒绝护栏，不改冻结的案例、标签、Prompt、Schema、Java Gateway 或 2026-10-22 结果。H03 从本批起视为**已见回归**；未来模型质量比较另建未见保留集。

## 本批验证记录

基线 `scripts/verify.ps1`：Java 265 项、0 失败/错误/跳过；Python 100 项；前端 lint、测试、构建通过。完成代码和依赖变更后再次运行同一脚本：Java 282 项、0 失败/错误/跳过；Python 100 项；前端 lint、测试、构建通过，`npm ci` 审计为 0。随后补充的冻结 H03 与 Worker 断言单独包含在 42 项定向测试中，全部通过。详细范围和剩余限制见 [第一批验收记录](2026-10-27-phase4-first-batch.md)。

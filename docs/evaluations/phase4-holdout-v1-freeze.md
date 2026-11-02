# P4-2 合成单规则保留集 v1 冻结清单

冻结时间：2026-11-01。作者为独立子代理；仅收到合成单阈值规则与数据契约，声明未阅读仓库实现、旧评测、调优用例和本会话内容。隔离依赖代理遵守指令，**不是安全强制隔离**。

| 对象 | SHA-256 |
| --- | --- |
| `tools/agent-evaluation/data/phase4-holdout-v1.jsonl` | `eb9146eb4c2325018b27963a2c62e0ce6a8847d00032a6fe3b91e325aaa5d3d2` |
| `docs/evaluations/phase4-holdout-v1-design.md` | `bd43fffb9c4ce3bab3d1ac2385af3b2a3100155471cd6d8ad5778cd69bb7af55` |
| `docs/evaluations/phase4-holdout-v1-author-record.md` | `bc9a1db31c2074150e99ff8ddb541336fb0c4907baba21aa7b5b87ac5e22c15` |
| `tools/agent-evaluation/src/finguard_agent_evaluation/phase4_holdout.py` | `34b0fe6a1b51a915d1f1419e6fd9e141259051be9d948ec5153953780f92ae97a` |

20 例，唯一 ID；标签 `CONFIRMED=6`、`IGNORED=5`、`INSUFFICIENT_EVIDENCE=9`。标签由合成规则自洽核对，未获领域专家审定。不得修改原文件；任何修订使用新版本与新哈希。

范围仅为 `SYNTH_LIMIT` / USD / `GTE 100.00` 的离线示例。类别名中含注入、冲突和多条件，但案例没有相应载荷或附加政策事实，因此不覆盖这些能力，也不代表 Core 真实政策或模型质量。

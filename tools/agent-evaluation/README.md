# 离线评测工具

使用 Python 3.12、Pydantic 与 LangGraph 读取冻结的合成数据集。评测涵盖建议、事实值、引用、拒答、RAG 检索和 Agent 工具契约。常规测试在本地执行，使用固定替身和已保存的样本。

```sh
uv sync --locked --python 3.12
uv run --locked pytest -q
```

从本目录运行。数据集在 [`data/`](data/)，跨运行时回归依赖的冻结基线在 [`data/worker-comparison/`](data/worker-comparison/)。阅读评测范围与已验证结果时以 [验证结果](../../docs/VERIFICATION.md) 为准。

## 审核反馈回归

1. 在隔离环境中，用只读 SQL 会话按批准的审核批次导出 `analysis_id`、分析状态和反馈枚举。输入为 UTF-8 JSONL，每行一个对象。
2. 另存审核批准清单。每行包含 `analysis_id` 和 `case`。`case` 的格式见 [合成单阈值样本](data/phase4-holdout-v1.jsonl)，使用新案例编号、合成字段和独立人工标签。反馈枚举用于筛选，正确标签由审核人按事实和政策判断。
3. 冻结成脱敏回归集，并核对输出清单中的 `dataset_sha256`。分析 UUID 仅用于本地导出与批准清单关联，输出案例不包含原分析 UUID。

```sql
SELECT JSON_OBJECT('analysis_id', LOWER(BIN_TO_UUID(r.id)),
                   'status', r.status, 'verdict', f.verdict)
FROM review_feedback f JOIN analysis_run r ON r.id = f.analysis_id
WHERE r.status = 'SUCCEEDED'
  AND f.created_at >= ? AND f.created_at < ?
ORDER BY r.id;
```

```sh
uv run --locked python -m finguard_agent_evaluation.feedback_regression \
  --feedback feedback.jsonl --approved approved.jsonl \
  --output reports/feedback-v1 --source reviewer-export
```

`--source fixture` 用于合成测试导出。使用 `phase4_reference_runner` 对冻结的 `cases.jsonl` 离线重放，再用 `phase4_cli` 比对 `expected` 并生成报告。两个入口均可用 `--help` 查看清单和版本参数。原始导出与审核关联表保存在本机受限目录。

# FinGuard Copilot RAG 离线评测

本目录只保存脱敏、固定、离线可运行的评测契约，不连接 MySQL、Qdrant、FinGuard
Core 或任何模型供应商。RAG 数据集位于 `data/rag_cases.jsonl`，每行是一个
`RagCase`，包含查询、期望片段、引用文档/版本以及禁止召回的片段。

## 运行

在本目录执行：

```powershell
uv sync --locked --python 3.12
uv run --python 3.12 pytest -q
```

`load_rag_cases` 会逐行解析并用 Pydantic 校验固定数据集；`rag_metrics.py` 提供
recall@K、引用精确率和无答案正确率。指标计算不访问生产数据库，也不把业务事实写入
评测集。

## 当前基线

当前数据集有 3 个 case：2 个可回答 case 和 1 个无答案 case。Java 侧的候选索引
评测用同一语义计算三类指标，并把 evaluator version 和 case 数写入索引版本元数据；
模型比较、Golden Dataset、LLM-as-Judge 和成本指标属于后续阶段。

# Phase 3 评测基线 Schema

每个 Golden Dataset 案例包含固定 `case_id`、正整数 `review_task_id`、脱敏 `input_facts`、制度/手册 `knowledge_evidence`、`reviewer_input`、期望推荐、期望事实路径和引用 ID，以及注入/证据不足标志。

结果只保存分类指标和版本信息，不保存 Prompt 正文、JWT、密钥、动态 Core 事实或模型原文。报告版本字段必须同时包含 dataset、prompt、model configuration 和 knowledge index 版本。每个案例还记录输入/输出 Token、调用成功标志和受控失败分类（例如 `TIMEOUT`、`RATE_LIMITED`、`UNAVAILABLE`、`INVALID_OUTPUT`），用于区分模型质量失败与供应商/网络失败。

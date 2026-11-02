# FinGuard Copilot RAG 离线评测

本目录包含脱敏、固定、离线可运行的评测契约。普通离线测试不连接 MySQL、Qdrant、
FinGuard Core 或模型供应商；真实模型评测需显式配置和单独的调用预算。RAG 数据集位于 `data/rag_cases.jsonl`，每行是一个
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

当前 RAG 数据集有 3 个 case：2 个可回答 case 和 1 个无答案 case。Java 侧的候选索引
评测用同一语义计算三类指标，并把 evaluator version 和 case 数写入索引版本元数据。
Phase 3 已有模型比较和 Golden Dataset 记录；LLM-as-Judge 与人工校准仍是后续任务。

## 受控建议枚举诊断

`finguard_agent_evaluation.diagnostic_v4` 是独立于冻结 Phase 3 评测器的新诊断入口。
它接收脱敏的 `ActualAnalysis` 结果，只输出案例 ID、解析器检查状态、三个受控建议枚举
及与标签是否一致；不输出原始模型正文、Prompt、事实或政策原文。结构、事实值、引用或
analysisId 的已有检查未通过时，建议字段为 `null`。契约通过不证明业务建议正确。

离线入口读取数据集 JSONL 和可信 v3 解析器输出的脱敏结果 JSONL，按 case ID 一一对应，
计算所读数据集字节的 SHA-256，并拒绝覆盖已有报告。例如：

```powershell
uv run --python 3.12 python -m finguard_agent_evaluation.diagnostic_v4 `
  --dataset data/phase3-holdout-v1.jsonl --actuals reports/sanitized-actuals.jsonl `
  --output reports/new-diagnostic.json --dataset-version phase3-holdout-v1 `
  --prompt-version review-analysis-v2 --model-version deepseek-flash `
  --index-version holdout-fixture-v1
```

输入第一行是 `{"manifest":{...}}`，包含本次数据集 SHA-256、`dataset_version`、
`prompt_version`、`model_configuration_version` 和 `knowledge_index_version`；
之后每行为 `{"case_id":"...", "actual":{...}}`。清单必须与实际读取的数据集和命令
版本一致，案例 ID 必须一一对应。`actual` 只允许 `ActualAnalysis` 的字段，不接受
`raw_response` 等额外字段。调用方也可在同一进程中，将
`parse_provider_response(..., case=..., analysis_id=...)` 的结果按案例 ID 建立字典，
交给 `build_diagnostic_report`，避免保存中间结果。离线入口不发起供应商调用。

诊断依赖输入中的 v3 检查结果；`PARSER_CHECKS_PASSED` 只表示输入所报告的检查通过，
不代表诊断模块重新检查过模型原文或业务推理。运行清单可防止误用不匹配的数据集和版本，
但可以被调用者伪造，不是独立安全证明。数据集哈希由工具计算；Prompt、模型和索引版本名称
由调用方提供，工具不核验它们对应的文件内容。版本名称和案例 ID 必须使用非敏感合成标识；
字符格式检查不能识别伪装成标识的秘密。旧 H03 结果没有保存模型实际建议，无法从旧报告恢复；本入口只用于后续新运行
或明确标记的合成测试，不能补造历史输出。

## Phase 4 合成单规则评测

`phase4_rules.Rule` 与 `evaluate` 提供合成单阈值比较：使用 `Decimal` 和显式比较符，
未知规则、币种不符、金额缺失/非法或缺引用时返回 `None`。文档 ID 仅是调用方提供的
存在性检查，不能核实政策正文、版本、哈希、来源或规则适用性，不能用于生产决策。
`phase4-holdout-v1.jsonl` 是 20 条独立代理编写的合成单阈值案例，冻结哈希及隔离边界见
`docs/evaluations/phase4-holdout-v1-freeze.md`。标签不是领域专家审定；类别名中的注入、
冲突和多条件没有对应载荷，不计作这些能力的评测。以下命令离线运行 LangGraph 参考并
生成按完整案例数计算分母的受控报告；所有文件都拒绝覆盖。需从本目录执行：

```powershell
uv run --python 3.12 python -m finguard_agent_evaluation.phase4_reference_runner `
  --dataset data/phase4-holdout-v1.jsonl `
  --dataset-sha256 eb9146eb4c2325018b27963a2c62e0ce6a8847d00032a6fe3b91e325aaa5d3d2 `
  --dataset-version phase4-holdout-v1 --output reports/my-reference.jsonl
uv run --python 3.12 python -m finguard_agent_evaluation.phase4_cli `
  --dataset data/phase4-holdout-v1.jsonl --outcomes reports/my-reference.jsonl `
  --output reports/my-reference-report.json --dataset-version phase4-holdout-v1 `
  --prompt-version none --model-version none --index-version synthetic-v1 `
  --rule-version synthetic-single-threshold-v1 --source langgraph-offline
```

`phase4_cli` 从读取的文件重新计算 SHA-256，要求结果清单精确匹配，但 Prompt、模型、
索引及规则版本是调用方声明，报告标成 `unverified`；不能以版本名称代替内容真实性。
`phase4_judge.calibrate` 只对外部人工标签和 Judge 输出计算一致性，缺失/弃权均在分母内。
当前没有真实人工审定标签；已有 10 次 DeepSeek Judge 运行，但只能计算与 AI 模拟标签的一致率，不能宣称人机一致率或 Judge 质量通过。
2026-11-02 已按用户要求补齐 [10 例模拟填写及理由](../../docs/evaluations/phase4-simulated-review-completed-v1.md)，并离线复算冻结 Judge 输出为 10/10；[收尾记录](../../docs/acceptance/2026-11-02-p4-2-simulated-calibration-closeout.md)明确区分模拟交付与真实人工验收。
`phase4_judge` CLI 要求标签和 Judge 文件各自带匹配数据 SHA-256 的清单，标签必须覆盖所选样本，
输出只保留案例 ID 和受控状态。一次已标记为 AI 模拟的校准演练可这样复现：

```powershell
uv run --python 3.12 python -m finguard_agent_evaluation.phase4_judge `
  --dataset ../../docs/evaluations/phase4-calibration-sample-v1.jsonl `
  --labels ../../docs/evaluations/phase4-simulated-review-labels-v1.jsonl `
  --judge ../../docs/evaluations/phase4-simulated-judge-v1.jsonl `
  --output reports/my-simulated-calibration.json `
  --label-version simulated-ai-review-v1 --judge-version langgraph-reference-surrogate-v1
```

真实 DeepSeek Judge 入口 `phase4_provider_judge` 固定 `deepseek-flash`、官方端点、最多 10 例、
输出 64 tokens、无自动重试；只读所选合成案例且请求中去掉 `expected`。使用既有
`scripts/import-model-environment.ps1` 将本地配置导入**当前进程**后运行，例如：

```powershell
. ..\..\scripts\import-model-environment.ps1 -Path <your-local-env-file>
uv run --python 3.12 python -m finguard_agent_evaluation.phase4_provider_judge `
  --dataset ../../docs/evaluations/phase4-calibration-sample-v1.jsonl `
  --dataset-sha256 a87e2a12e55652840878c7b7b8dcae20e68363575cfa452749781d6c84bb6bd3 `
  --output reports/my-deepseek-judge.jsonl --usage-output reports/my-deepseek-usage.jsonl `
  --max-spend-cny 9.90
```

输出文件独占创建；如调用或解析失败，用量文件留下受控 `failed` 行且该次费用为未知，
保留部分文件并停止，不得盲目重跑。预算预留按
输入 4096、输出 64 tokens 的高峰价估算，供应商实际扣费以账单为准。2026-11-01 的
10 次真实调用已完成，详见 P4-2 验收记录；与 AI 模拟标签的结果不是人机一致率。

## Worker 同批补验收（2026-11-02）

`data/phase4-worker-v2.jsonl` 冻结 16 例（单阈值、实际知识注入载荷、多条件、政策冲突各4）。
`Phase4WorkerComparisonIntegrationTest` 使用真实 Worker/编排器/解析器/护栏和 MySQL，
网关、检索及 Rabbit 确认使用固定夹具。全部成功持久化结果、失败码、重试、用量、回执、
重放已保存至 `docs/evaluations/phase4-worker-v2-java-baseline.json`。

`phase4_worker_comparison` 的 LangGraph 仅消费同样的事实、正文和候选，不接收标签、
类别或 policy_kind。参考只支持文档中的公开合成文法，不是生产政策引擎。报告进口重新核对
成功结果结构、事实、引用及执行记录；失败、缺失仍在分母。最终 Java 9/16、参考16/16，
7 个坏案例详见 `docs/acceptance/2026-11-02-p4-2-worker-comparison.md`。

```powershell
uv run --python 3.12 python -m finguard_agent_evaluation.phase4_worker_comparison `
  --dataset data/phase4-worker-v2.jsonl `
  --java ../../docs/evaluations/phase4-worker-v2-java-baseline.json `
  --freeze ../../docs/evaluations/phase4-worker-v2-run-freeze-v3.json `
  --output reports/my-worker-v2-comparison.json
```

最初参考多收到了作者的政策分类，已在复核中修正；旧报告/清单仅作追溯。
最终代码与解析器依赖被 v3 运行清单绑定，哈希不符拒绝执行；不覆盖已有报告。
评估器在看过数据后修订，最终参考分数不称为未见评测。质量结果没有通过，不能由集成测试
通过推导出全部业务/安全案例通过。本轮未发起付费模型调用。

后续用户授权修复上述七项，旧 v2 作为已见回归保留。最新 `phase4-worker-v2-java-verified.json`
及 `phase4-worker-v2-comparison-verified.json` 为16/16；独立新 v3 数据冻结24例，Java24/24，
原参考22/24，其 N319/N321 注入漏报没有通过修改参考算法掩盖。
新集报告使用 `data/phase4-worker-v3.jsonl`、
`docs/evaluations/phase4-worker-v3-java-verified.json` 和 `phase4-worker-v3-freeze.json`，
命令格式同上且输出必须为新文件。修复含 V8 精确 JSON 文本、BigDecimal 全链路解析、
可信配置政策包以及存量结果恢复授权；范围与复现见
`docs/acceptance/2026-11-02-p4-2-seven-fixes.md`。Java333/Python168项通过，标签仍为AI模拟，
真实模型、Core HTTP、真实检索及消息投递不在本次夹具验收范围。

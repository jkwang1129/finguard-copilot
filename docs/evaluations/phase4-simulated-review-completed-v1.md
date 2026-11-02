# P4-2 模拟审核填写记录

日期：2026-11-02。审核角色：**AI 模拟 REVIEWER（Codex）**；执行依据：用户要求“模拟真人完成这部分”。没有真人署名或领域专家背书。

本次逐条复核既有 `simulated-ai-review-v1` 的 10 个标签并补充理由，不修改冻结样本、标签或历史 Judge 输出。本次已读取样本中的 `expected` 和既有标签，属于公开规则下的非盲复核，不能作为独立盲审或未见集证据。原[人工盲审模板](phase4-human-calibration-template.md)保留给后续真人填写。

采用模板中的项目合成政策：规则为 `SYNTH_LIMIT`、币种 USD、比较符 `GTE`、阈值 `100.00`、政策文档为 `SYNTH_POLICY_V1` 且被引用、金额为非负十进制。前提齐全时金额达到阈值选 `CONFIRMED`，低于阈值选 `IGNORED`；任一前提不满足选 `INSUFFICIENT_EVIDENCE`。不进行币种换算或小数舍入，不把这项演示政策解释为监管规则。

| ID | AI 模拟标签 | 复核理由 |
| --- | --- | --- |
| P4H01 | CONFIRMED | 金额 100.00 等于阈值，GTE 包含等号，其余适用条件齐全。 |
| P4H02 | IGNORED | 金额 99.99 小于 100.00，其余适用条件齐全。 |
| P4H04 | IGNORED | 99.999999999999999999 仍小于 100.00，不舍入到阈值。 |
| P4H05 | CONFIRMED | 100.000000000000000001 大于 100.00，按原始精度比较。 |
| P4H08 | INSUFFICIENT_EVIDENCE | 缺失金额，无法作方向性判断。 |
| P4H11 | INSUFFICIENT_EVIDENCE | EUR 不在这项 USD 政策范围内，不能直接比较或自行换汇。 |
| P4H12 | INSUFFICIENT_EVIDENCE | 缺少政策引用，不能只凭金额确认。 |
| P4H13 | INSUFFICIENT_EVIDENCE | SYNTH_OTHER 不在本政策适用规则中。 |
| P4H14 | INSUFFICIENT_EVIDENCE | 输入比较符 GT 与已定义的 GTE 不一致；即使金额较大也不忽略政策冲突。 |
| P4H16 | INSUFFICIENT_EVIDENCE | 声明文档 V2 与适用/引用文档 V1 不一致。 |

汇总：CONFIRMED 2 例、IGNORED 2 例、INSUFFICIENT_EVIDENCE 6 例。机器标签仍使用[既有标签文件](phase4-simulated-review-labels-v1.jsonl)，避免创建内容相同但来源不清的另一套标签。

复算结果与输入哈希见[本次收尾记录](../acceptance/2026-11-02-p4-2-simulated-calibration-closeout.md)。模拟审核流程可以据此交付；真实人工校准仍需真实审核员提供标签。

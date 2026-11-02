# P4-2 人工校准盲审表（待填写）

用户要求的 AI 模拟填写已完成，见[模拟审核记录](phase4-simulated-review-completed-v1.md)。本表继续保留空白，供真实审核员独立填写；模拟记录不作为真人署名。

版本 `human-calibration-v1`，固定 10 例，数据来自 SHA-256 为 `eb9146eb4c2325018b27963a2c62e0ce6a8847d00032a6fe3b91e325aaa5d3d2` 的合成保留集。请审核员**不要先阅读包含 `expected` 的数据文件或参考报告**。

合成政策：只有 `SYNTH_LIMIT`、USD、`GTE 100.00`、政策文档 `SYNTH_POLICY_V1` 且引用包含该文档、金额为非负十进制时，金额 ≥ 100.00 选 `CONFIRMED`，否则选 `IGNORED`。任何前提不满足选 `INSUFFICIENT_EVIDENCE`。这只是项目合成规则，不是金融监管或 Core 原生决策。

请在最后一列填 `CONFIRMED` / `IGNORED` / `INSUFFICIENT_EVIDENCE` 之一，并署名或注明审核角色及日期。空白行会计入缺失分母。

| ID | 规则 | 币种 | 金额 | 比较符/阈值 | 政策文档 | 引用 | 人工标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P4H01 | SYNTH_LIMIT | USD | 100.00 | GTE 100.00 | SYNTH_POLICY_V1 | SYNTH_POLICY_V1 |  |
| P4H02 | SYNTH_LIMIT | USD | 99.99 | GTE 100.00 | SYNTH_POLICY_V1 | SYNTH_POLICY_V1 |  |
| P4H04 | SYNTH_LIMIT | USD | 99.999999999999999999 | GTE 100.00 | SYNTH_POLICY_V1 | SYNTH_POLICY_V1 |  |
| P4H05 | SYNTH_LIMIT | USD | 100.000000000000000001 | GTE 100.00 | SYNTH_POLICY_V1 | SYNTH_POLICY_V1 |  |
| P4H08 | SYNTH_LIMIT | USD | 缺失 | GTE 100.00 | SYNTH_POLICY_V1 | SYNTH_POLICY_V1 |  |
| P4H11 | SYNTH_LIMIT | EUR | 250.00 | GTE 100.00 | SYNTH_POLICY_V1 | SYNTH_POLICY_V1 |  |
| P4H12 | SYNTH_LIMIT | USD | 250.00 | GTE 100.00 | SYNTH_POLICY_V1 | 缺失 |  |
| P4H13 | SYNTH_OTHER | USD | 250.00 | GTE 100.00 | SYNTH_POLICY_V1 | SYNTH_POLICY_V1 |  |
| P4H14 | SYNTH_LIMIT | USD | 250.00 | GT 100.00 | SYNTH_POLICY_V1 | SYNTH_POLICY_V1 |  |
| P4H16 | SYNTH_LIMIT | USD | 250.00 | GTE 100.00 | SYNTH_POLICY_V2 | SYNTH_POLICY_V1 |  |

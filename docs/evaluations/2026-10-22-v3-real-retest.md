# 2026-10-22 v3 真实模型复测

## 结果

按事先确认的预算，DeepSeek `deepseek-flash` 共执行 12 次请求：旧 Prompt `review-analysis-v1` 与新 Prompt `review-analysis-v2` 各 6 次；无自动追加重试。

两组均使用 `phase3-quality-v2` 开发集与 `exact-facts-citations-v3` 评测器。建议准确率、六路径覆盖率、原始十进制事实值校验、返回引用精确匹配、期望引用覆盖率、analysisId/JSON 校验、拒答命中率均为 100%，失败率均为 0%。

| 指标 | Prompt v1 | Prompt v2 |
| --- | ---: | ---: |
| 输入 Token | 7421 | 9563 |
| 输出 Token | 3359 | 3187 |
| 模型请求数 | 6 | 6 |

两组共输入 16984、输出 6546 Token。新 Prompt 多输入 2142、少输出 172 Token；配置为 `quality-unpriced-v1`，零估算价格不代表实际免费。温度 0、thinking 关闭、单次输出上限 2000 Token、超时 60 秒。

重要结论：2026-10-21 观测到的引用覆盖率 83.3%→100% **没有在本轮复现**；本轮两组都是 100%，因此没有证据宣称新 Prompt 稳定优于旧 Prompt。两次历史报告都保留，不能择优只报其中一次。

## 证据

- [脱敏完整报告](phase3-closeout-v3-comparison-2026-10-22.json)。
- [调用前冻结记录](phase3-closeout-2026-10-22-freeze.json)，记录两个 Prompt、开发集、Schema 和评测源文件 SHA-256。
- 原始报告目录：`tools/agent-evaluation/reports/phase3-closeout-v3-20261022/`（Git 忽略）。
- v3 从模型 JSON 解析阶段保留 Decimal 精度；异常推荐类型记为失败样本而不是中断整批；金额和引用错误不计作有效建议。

这只补齐 v3 下的开发集真实复测，不是保留集泛化证据，也不是 Java 业务链路证据。后续保留集结果不得用于修改本轮冻结的 Prompt 或期望答案。

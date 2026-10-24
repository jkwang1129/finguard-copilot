# Phase 3 模型选型状态

当前状态（2026-10-22）：本地 DeepSeek `deepseek-flash` / `review-analysis-v2` 已完成 v3 开发集对照、Java 真实业务 E2E 与独立冻结保留集，合计 25 次调用。开发集两组指标均为 100%，没有复现旧的引用优势；E2E 15/15；保留集建议 11/12，H03 数值压力案例失败。见[补验收汇总](../acceptance/2026-10-22-phase3-closeout.md)。这不是跨供应商同条件重测，不改变下面历史 Qwen/智谱数据，也不构成生产质量保证或真实备用模型验收。

历史模型比较日期：2026-09-02。后续状态：2026-10-19 已完成单个合成案例的真实跨进程业务验收；生产质量选型仍未闭环，本表指标未重测。

2026-10-21：按用户选择，本地默认供应商改为 DeepSeek `deepseek-flash`，使用 `review-analysis-v2`；[同模型 Prompt 对照](2026-10-21-deepseek-quality.md)已完成 12 次真实请求。该评测同时修订了数据契约和严格校验，不能把下面历史跨模型指标当作其对照组。本表“当前选择依据”仅记录当时的历史决策，不再表示本地默认供应商。

两名候选使用同一份 `phase3-golden-v1`、`review-analysis-v1`、`published-v1` 和 6 个脱敏案例；报告只保存分类指标、版本和用量，不保存 Prompt 正文、模型原文、JWT 或 API Key。

| 候选 | 推荐准确率 | 事实覆盖率 | 引用覆盖率 | JSON 成功率 | 注入拒答 | P50/P95 | 失败率 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Qwen `qwen-plus` | 66.7% | 19.4% | 100% | 100% | 100% | 11.13s / 12.34s | 0% |
| 智谱 `glm-5.2` | 0% | 0% | 16.7% | 0% | 0% | 20.11s / 20.16s | 100%（6/6 TIMEOUT） |

证据报告：

- Qwen：`tools/agent-evaluation/reports/phase3-qwen.json`
- 智谱：`tools/agent-evaluation/reports/phase3-zhipu.json`
- 合并比较：`tools/agent-evaluation/reports/phase3-real-model-comparison.json`
- Stub 离线基线：`tools/agent-evaluation/reports/phase3-stub-baseline.json`

当前选择依据：Qwen `qwen-plus` 作为实验性主候选；没有固化真实备用模型，因为智谱候选在 6 个案例上全部超时。Stub 仅用于离线开发和自动化测试，不作为生产降级模型。

重要限制：历史 Qwen 事实覆盖率为 19.4%，不能宣称真实审核质量已达生产要求；历史报告是在评测器增加严格 Schema/引用校验前生成的，重新评测后才能作为当前质量门禁。本表仍来自 Python 运行器直接访问固定供应商端点；独立的 [2026-10-19 业务验收](../acceptance/2026-10-19-business-e2e.md) 已实际经过 Core、Copilot、RabbitMQ、Qdrant 已发布知识和真实千问，15 项断言通过，但只使用单个合成案例与固定 Embedding，不替代本表质量重测。后续应扩大 Golden Dataset、验证语义召回和事实覆盖率并重新评测，同时复查备用模型超时问题。

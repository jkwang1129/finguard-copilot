# 2026-10-22 Phase 3 三项补验收收尾

后续提交前检查发现三条未覆盖的异常路径阻塞；2026-10-24 已另行修复并验证，见[修复验收](2026-10-24-phase3-failure-recovery.md)。下文保留当时执行的三项真实模型补验收结果。

## 结论

用户批准的三项补验收已全部执行，真实供应商调用 **25/25 次**，没有追加重试。当前本地选择仍为 DeepSeek `deepseek-flash` / `review-analysis-v2`。这是 Phase 3 工程补验收完成，不是生产质量全部达标；保留集 H03 的错误建议仍是已知限制。

| 项目 | 本轮结果 | 可核查证据 |
| --- | --- | --- |
| v3 开发集旧/新 Prompt 对比 | 各 6 次，两组建议、事实和引用均 100%；旧的引用提升未复现 | [真实复测](../evaluations/2026-10-22-v3-real-retest.md) |
| DeepSeek Java 真实业务全链路 | 1 次模型调用，15/15 断言通过，Core 无审核写入 | [E2E 验收](2026-10-22-deepseek-business-e2e.md) |
| 独立冻结保留集 | 12 次，建议 11/12；事实/引用全部通过，应拒答 5/5（含注入 2/2） | [保留集报告及坏案例](../evaluations/2026-10-22-holdout-evaluation.md) |

不能宣称新 Prompt 稳定优于旧 Prompt，也不能把保留集 91.7% 当作生产准确率。H03 能复制正确金额和引用却给出错误建议，说明当前校验不等于业务推理正确性保证。该案例为假设的 18 位小数压力样本，不是当前 Core 存储的实际金额样本；实际错误建议枚举及原文未保存，不能进一步确定原因。

## 调用与用量核对

| 阶段 | 实际调用 | 输入 Token | 输出 Token |
| --- | ---: | ---: | ---: |
| v3 Prompt 对照 | 12 | 16984 | 6546 |
| Java 业务 E2E | 1 | 1937 | 617 |
| 独立保留集 | 12 | 20625 | 8454 |
| 合计 | **25** | **39546** | **15617** |

独立评测价格表为 `quality-unpriced-v1`，业务验收为 `e2e-unpriced-v1`；账单没有在本轮核验，零估算价格不代表免费。本轮不再请求模型；未来任何真实复测需另行确认预算。

## 工程验证与冻结

- Python 全套离线测试：**100 项通过**，包含 4 项 E2E 配置边界测试和 33 项保留集数据/标签测试；新增功能遵循先失败、后通过验证。
- Core 和 Copilot 本轮打包均 `BUILD SUCCESS`，打包使用 `-DskipTests`，不能当作 Java 全套重测。
- Java 全套最近证据仍是 2026-10-21 的 **260 项、0 失败/错误/跳过**，日志 `target/quality-deepseek-final-regression.log`；本轮没有修改 Java 源码，也没有重跑前端。
- [总冻结清单](../evaluations/phase3-closeout-2026-10-22-freeze.json)及[保留集冻结清单](../evaluations/phase3-holdout-v1-freeze-2026-10-22.json)记录首次请求前 SHA-256；结果之后不调整 Prompt、Schema、数据、标签及评测源文件。
- 真实链路经过 Core HTTP 认证/只读上下文、Copilot 事务 Outbox、RabbitMQ、Worker、Qdrant 已发布索引、DeepSeek 和 MySQL 用量记录，最终 `SUCCEEDED`；成功用量、已发布 Outbox、Worker receipt 各 1 条，Core 仍为 `PENDING` / 版本 `0`。

本轮主要新实现是 E2E 模型配置入口：从本地配置选择 provider、模型、端点和 profile；增加 `-PlanOnly`、供应商凭据/端点错配拒绝与本次资源清理边界。没有把 Python 评测当作 Java 业务成功，也没有修改冻结评测器来提高分数。

最终只读收尾检查：Python 再次 `100 passed in 12.00s`；两份冻结清单共 12 项哈希全部一致；三份归档 JSON 与原报告逐字段语义一致；4 个 PowerShell 入口解析通过；41 个相对 Markdown 链接目标存在；`git diff --check` 通过，Core 工作区无源码改动。本机环境文件被 Git 忽略且未跟踪。限定范围的凭据模式扫描在 Java 测试夹具中发现固定测试 JWT，公开实现/文档/本轮报告和服务日志未发现候选；这不是完整安全审计。独立只读代码复核因服务容量不足未能启动，改由执行者复核配置入口与清理路径，不声称独立审查已通过。

## 资源、交付与剩余边界

本轮独立验收库 `fg_core_e2e_20261022145508` / `fg_copilot_e2e_20261022145508`、RabbitMQ vhost `fg-e2e-20261022145508` 和已停止的 Qdrant 容器 `fg-e2e-qdrant-20261022145508` 保留用于核查，没有删除原 Core 业务库或原中间件。验收 Java 进程和本次 Qdrant 已停止；原 MySQL、RabbitMQ、Redis 未停止。队列无待处理/未确认消息。

工作在 `C:\dev\worktrees\finguard-copilot-phase1`，没有合并到原 `C:\dev\finguard-copilot` 的 Phase 0 分支，也未提交或推送。密钥仅使用 Git 忽略的本机配置或进程环境，不进入公开证据。

业务 E2E 使用 SQL 合成数据和固定 Embedding，未重跑 CSV 导入/对账全流程，不证明真实语义召回质量。尚无可接受的真实备用模型、领域专家校准、生产规模质量/安全测试或部署 SLA。下一阶段应将 H03 纳入 Bad Case 闭环，设计确定性数值结论兜底、受控诊断字段及新的未见保留集；这些仍属后续工作，未在本轮扩展实现。

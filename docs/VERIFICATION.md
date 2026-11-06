# 验证结果

以下结果来自已发布的工程验收，使用各自的固定数据集和环境。数字表示当次验证范围。

| 项目 | 已验证结果 | 证据 |
| --- | --- | --- |
| 冷缓存 CI 与镜像 | Java 644 项、Python 评测 193 项、前端 18 项通过。复算完整 SHA 制品的归档校验和，并将三个镜像 ID 与扫描报告对齐 | [CI](https://github.com/jkwang1129/finguard-copilot/actions/runs/42046088486) · [镜像制品](https://github.com/jkwang1129/finguard-copilot/actions/runs/42046088486/artifacts/13424673273) |
| 隔离审核流程 | 从空卷启动并完成 36 项检查，覆盖身份验证、幂等、SSE、知识引用和 Core 业务校验和 | [复跑命令](RUNBOOK.md#隔离演示) |
| 回滚与恢复 | 实测 Linux A → B → A。MySQL 两库恢复、RabbitMQ 停机重投和 Qdrant 空卷重建批次共通过 46 项检查 | [操作手册](DEPLOYMENT.md) |
| 模型对照 | DeepSeek Flash 与智谱 GLM-5.2 在同一已知冻结合成集上各通过 12/12 条建议检查，应拒答案例各通过 5/5。审核 Worker 可由运维开关在调用前切换 | [评测器](../tools/agent-evaluation/src/finguard_agent_evaluation/runner.py) · [合成数据集](../tools/agent-evaluation/data/phase3-holdout-v1.jsonl) |
| 真人反馈闭环 | 真人对一条合成大额风险案例先独立标注，再在页面提交反馈。MySQL 保存 1 条反馈，Core 审核仍为 `PENDING`。脱敏冻结后离线规则回归与独立标签一致，1/1 通过 | [复跑流程](../tools/agent-evaluation/README.md#审核反馈回归) |

真人反馈验收使用本地模型替身。模型对照测量固定合成案例。两组结果分别覆盖反馈流程和限定数据集上的建议表现。

发布制品中 Copilot 和前端均为 0 条扫描版本命中。Core 保留两条 Spring MVC 和一条 MyBatis 内嵌 OGNL 版本命中，按锁定源码静态审阅未发现相应触发路径。 [扫描与制品校验记录](https://github.com/jkwang1129/finguard-copilot/actions/runs/42046088486/artifacts/13424673273)

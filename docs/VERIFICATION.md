# 验证结果

以下结果来自已完成的工程验收，使用各自的固定数据集和环境。数字表示当次验证范围。

| 项目 | 已验证结果 | 证据 |
| --- | --- | --- |
| 冷缓存 CI 与镜像 | Java 644 项、Python 评测 193 项、前端 18 项通过。复算完整 SHA 制品的归档校验和，并将三个镜像 ID 与扫描报告对齐 | [CI](https://github.com/jkwang1129/finguard-copilot/actions/runs/42046088486) · [镜像制品](https://github.com/jkwang1129/finguard-copilot/actions/runs/42046088486/artifacts/13424673273) |
| 隔离审核流程 | 从空卷启动并完成 36 项检查，覆盖身份验证、幂等、SSE、知识引用和 Core 业务校验和 | [复跑命令](RUNBOOK.md#隔离演示) |
| Agent 工作流 | Java 与离线 LangGraph 在 26 个冻结合成案例上均符合预期，覆盖工具协议、状态与恢复。另一次本地进程验收 87/87 通过，包含工具调用、JVM 退出恢复、人工继续与 MCP 互通 | [冻结案例](../tools/agent-evaluation/data/phase5-agent-v1.jsonl) · [对照评测器](../tools/agent-evaluation/src/finguard_agent_evaluation/phase5_agent.py) · [本地进程验收入口](../scripts/run-phase5-e2e.ps1) |
| 回滚与恢复 | 实测 Linux A → B → A。MySQL 两库恢复、RabbitMQ 停机重投和 Qdrant 空卷重建批次共通过 46 项检查 | [操作手册](DEPLOYMENT.md) |
| 模型对照 | DeepSeek Flash 与智谱 GLM-5.2 在同一已知冻结合成集上各通过 12/12 条建议检查，应拒答案例各通过 5/5。审核 Worker 可由运维开关在调用前切换 | [评测器](../tools/agent-evaluation/src/finguard_agent_evaluation/runner.py) · [合成数据集](../tools/agent-evaluation/data/phase3-holdout-v1.jsonl) |
| 真人反馈闭环 | 真人对一条合成大额风险案例先独立标注，再在页面提交反馈。MySQL 保存 1 条反馈，Core 审核仍为 `PENDING`。脱敏冻结后离线规则回归与独立标签一致，1/1 通过 | [复跑流程](../tools/agent-evaluation/README.md#审核反馈回归) |

真人反馈验收使用本地模型替身。模型对照测量固定合成案例。两组结果分别覆盖反馈流程和限定数据集上的建议表现。

发布制品中 Copilot 和前端均为 0 条扫描版本命中。Core 保留两条 Spring MVC 和一条 MyBatis 内嵌 OGNL 版本命中，按锁定源码静态审阅未发现相应触发路径。 [扫描与制品校验记录](https://github.com/jkwang1129/finguard-copilot/actions/runs/42046088486/artifacts/13424673273)

## 复跑 Agent 工作流

从 Copilot 仓库根目录运行。先按 [快速开始](../README.md#快速开始) 准备两个仓库和 Docker。演练使用本地模型替身与合成案例，结束后自动清理容器与卷，终端会打印报告位置。

```powershell
$coreRef = (Get-Content ops/integration/core-revision.txt).Trim()
python scripts/run-integration.py --core-root ../finguard-core --core-ref $coreRef --faults --fault-case agent
```

```sh
python3 scripts/run-integration.py --core-root ../finguard-core \
  --core-ref "$(cat ops/integration/core-revision.txt)" --faults --fault-case agent
```

该场景检查工作流进入 `WAITING_FOR_HUMAN`、重启后读回等待状态和运行修订号、授权后继续，以及 MCP 调用遵守同一工具额度。 [场景检查源码](../scripts/phase6_drills.py)

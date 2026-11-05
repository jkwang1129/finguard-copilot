# FinGuard Copilot

FinGuard Copilot 是一个面向 `REVIEWER` 的风险审核辅助系统。它从
FinGuard Core 获取确定性的业务事实，从知识库检索制度与操作依据，使用大模型生成
结构化解释和建议，但不会代替审核人员作出最终决定。

项目目标不是做一个通用聊天机器人，而是完成一套可测试、可评测、可审计、可部署的
Java AI Agent 应用工程：后端集成、RAG、受控工具、持久化工作流、上下文工程、模型
评测、成本治理和 production-like 部署都必须有明确证据。Python 配套工程用于评测、
实验和主流 Agent 框架对照，不复制 Java 生产服务。

## 项目关系

```text
C:\dev\finguard-core       确定性业务系统和事实来源
C:\dev\finguard-copilot    AI 审核辅助系统（本仓库）
```

两个项目独立构建、测试和部署。Core 不依赖任何 AI 框架；Copilot 只能通过经过认证的
白名单接口读取 Core 事实。

## 当前阶段

Phase5已按P5-1至P5-6顺序完成本地工程实现和[总验收](docs/acceptance/2026-11-03-phase5-final-acceptance.md)：Java535、Python187、MCP桥4、前端18项及lint/build通过；26例跨运行时一致，最终制品87项真实进程检查通过。运行入口见[Phase5手册](docs/operations/phase5-agent-workflow.md)、[本地MCP手册](docs/operations/phase5-mcp.md)。真实native供应商资格、真实人工/模型质量及部署门槛仍在[TASKS](TASKS.md)单列，不由合成验收替代。

Phase 6 本地集成入口见[集成手册](docs/operations/phase6-integration.md)。隔离环境使用真实 Core、Copilot、React 和中间件，配合合成审核任务与本地模型替身。完整 SHA 制品、Linux 切换和故障复跑见[发布、回滚与恢复](docs/operations/phase6-release.md)。

2026-11-03：P4-4 可观测性与 P4-5 事务事件、SSE 和审核页面已完成本地工程验收；[P4-6总验收](docs/acceptance/2026-11-03-p4-6-final-acceptance.md)完成404项Java、183项Python、11项前端及六场景224项进程检查，最后OpenAPI文档修正后另有17项相关测试和新制品38项进程检查通过。最新操作入口见[审核页面手册](docs/operations/phase4-5-review-console.md)、[可观测性手册](docs/operations/phase4-4-observability.md)及[Phase4计划](docs/PHASE4_TASK_PLAN.md)。真实人工校准、合格备用模型和生产质量/部署仍单列待验收。以下保留各阶段的历史范围与证据。

2026-10-22 提交前复核发现三条异常路径：模型 HTTP 超时未接线、数据库回滚后可能重复请求模型、失败用量记录异常可能导致消息丢弃。2026-10-24 已补修复和离线回归，见[异常路径修复验收](docs/acceptance/2026-10-24-phase3-failure-recovery.md)；既有真实模型质量结论不因此改变。

Phase 2“RAG 与知识数据管道”已完成实现和仓库级验收：知识文档会经过脱敏准入、规范化、
校验和、分段和去重，候选 Qdrant 索引必须完成固定评测后才能原子发布；运行时只检索一个
已发布版本，更新、删除、回滚和引用校验均有 MySQL/Qdrant 集成证据。Qdrant 只保存向量
与白名单元数据，片段正文按文档/版本/片段从 MySQL 回取并重新校验 checksum。

Phase 2 不包含真实模型、BM25/重排、Agent Workflow、Tool Calling、MCP、UI/SSE 或
production-like 部署；固定 Embedding 和离线评测只代表可重复测试能力，不能推断生产模型
效果。详细命令、指标、边界检查和已知限制见
[Phase 2 验收记录](docs/acceptance/phase-2-rag-knowledge-pipeline.md)。

Phase 1“只读事实闭环”已完成实现和仓库级验收：Core 提供认证的当前用户与 review-task
context 只读契约，Copilot 提供 REVIEWER 限定的分析创建/查询、不可变脱敏快照、幂等、
事务 Outbox、RabbitMQ Worker、保留期清理，以及只返回
`INSUFFICIENT_EVIDENCE` 的确定性结果。Phase 1 不包含真实模型、RAG、Agent Workflow、
UI/SSE 或 production-like 部署；这些能力不能由当前 Stub 运行时推断出来。

详细命令、测试计数、边界检查和已知验收限制见
[Phase 1 验收记录](docs/acceptance/phase-1-read-only-fact-loop.md)。

Phase 3“真实模型与上下文工程”已完成适配器、预算组件、用量治理、已发布知识检索接线、
离线评测和真实模型比较。2026-10-22 已完成三项补验收，共使用批准的 25 次真实调用：
v3 开发集旧/新 Prompt 对比各 6 次，两组建议、事实和引用均为 100%；DeepSeek 真实业务 E2E
1 次、15/15 断言通过；独立冻结保留集 12 次，建议正确 11/12（91.7%），事实/引用全部通过，
应拒答 5/5。H03 高精度压力案例的错误建议原样保留，不能把格式及事实复制正确当作推理正确。
本地模型为 DeepSeek `deepseek-flash`，Prompt 为 `review-analysis-v2`。历史观察到的引用提升
未在本次对比中复现，不能宣称新 Prompt 稳定优于旧 Prompt。

当前还不是生产就绪：没有固化真实备用模型，历史千问/智谱指标未重测；固定 Embedding
和单个 SQL 合成业务案例不证明语义检索及完整 CSV/对账流程质量。已完成范围、用量、
坏案例和后续边界见 [三项补验收汇总](docs/acceptance/2026-10-22-phase3-closeout.md)。
详细指标、验证命令和质量限制见
[Phase 3 验收记录](docs/acceptance/phase-3-real-model-context-engineering.md)。

- [范围与验收边界](docs/SCOPE.md)
- [系统架构](docs/ARCHITECTURE.md)
- [实施路线图](docs/ROADMAP.md)
- [架构决策记录](docs/adr/README.md)
- [执行任务清单](TASKS.md)
- [精确依赖与兼容依据](docs/DEPENDENCIES.md)
- [Phase 0 详细实施计划](docs/superpowers/plans/2026-08-20-phase-0-engineering-foundation.md)
- [Phase 0 契约基础实施计划](docs/superpowers/plans/2026-08-23-phase-0-contract-foundation.md)
- [Phase 3 历史实施计划（非当前任务清单）](docs/superpowers/plans/2026-08-27-phase-3-real-model-context-engineering.md)

## 工程验证

在仓库根目录运行全量 Windows 验证：

```powershell
.\scripts\verify.ps1
```

也可以独立验证三个工程边界：

```powershell
.\mvnw.cmd -B -ntp clean test
Push-Location tools\agent-evaluation; uv sync --locked --python 3.12; uv run pytest -q; Pop-Location
Push-Location frontend; npm ci; npm run lint; npm run test -- --run; npm run build; Pop-Location
uv run --project tools/mcp-bridge pytest tools/mcp-bridge/tests -q
```

Linux 或 CI 等价入口为 `bash scripts/verify.sh`。Java 集成测试需要可用的 Docker
环境，以启动真实 MySQL 8.4.10 Testcontainer；普通测试不需要模型密钥或付费 API。

2026-11-04依赖维护已将公告匹配包24降至1，剩余2条视图相关原始命中保留。首轮真实模型14次请求、11/12未通过的[记录](docs/acceptance/2026-11-04-native-provider-qualification.md)保留。修正剩余工具额度提示后，独立[复验](docs/acceptance/2026-11-04-native-tool-budget-requalification.md)12/12通过、13次请求、估算0.089994CNY、未知持有0；仅证明合成native组件协议，非生产质量。最新Java全量552通过，默认真实Agent未启用。

Phase5原生HTTP组件测试与`run-phase5-e2e.ps1`固定使用18765，必须串行执行；STDIO测试也要与实际Copilot进程错开。进程验收脚本只使用本地模型替身，没有付费调用选项。

Phase 3 专用提示词对照入口默认独立评测模型，不需要启动业务服务。模型配置由 Git 忽略的
`.env.local` 加载，显式进程环境变量优先；文件不会自动被 Spring 加载。先看计划，再按需执行：

```powershell
.\scripts\run-quality-comparison.ps1 -PlanOnly
# 以下会消费最多 12 次真实模型调用，无自动重试
.\scripts\run-quality-comparison.ps1
```

Key 仅保留在本机忽略的配置文件或当前进程中，不写入公开示例、脚本、报告或 Git。
原 `run-phase3-evaluation.ps1` 仍要求 Copilot 健康检查；它同样直接调用供应商，不是业务 E2E。
启动 Copilot 前在同一 PowerShell 执行 `. .\scripts\import-model-environment.ps1` 可加载本地模型配置。

确定性基础适配器必须显式启用 `stub` profile。它们只用于自动化测试和本地开发，
不连接 FinGuard Core、模型供应商或真实向量数据库，也不代表生产能力：

```powershell
$env:SPRING_PROFILES_ACTIVE = "stub"
.\mvnw.cmd spring-boot:run
```

## 核心原则

P4-3 已接入的运行清单、预算和Redis许可配置见[调用与费用治理运行说明](docs/operations/phase4-3-governance.md)；[验收进度及边界](docs/acceptance/2026-11-02-p4-3-governance.md)单独记录。当前真实路由候选仅DeepSeek主模型，未通过评测的供应商不自动备用。

1. Core 决定业务事实，模型只负责解释与辅助。
2. 实时业务数据来自 Core API；RAG 只保存制度、手册和脱敏案例。
3. MVP 没有业务写工具，最终决定仍由 `REVIEWER` 在 Core 中完成。
4. 每个事实和制度结论都必须可追溯；证据不足时拒绝给出方向性建议。
5. 默认测试使用确定性 Stub，不依赖网络或付费模型。
6. 先使用可观察、可测试的确定性编排，再按真实分支需求引入 Agent Graph。
7. Agent 运行必须可持久化、暂停、恢复、回放和评测，不能只展示一次成功调用。

## 计划中的技术边界

- Java 17、Spring Boot、Spring AI/Spring AI Alibaba 与 Graph
- MySQL：分析运行、版本、引用和反馈元数据
- Qdrant：非测试环境的向量检索；测试使用确定性内存实现
- RabbitMQ + 事务 Outbox：可靠投递异步分析任务
- Redis：限流、配额、供应商并发控制和短期非业务缓存
- Tool Calling、运行级 Working Memory、HITL 与 MCP
- OpenTelemetry + Langfuse：Agent Trace、评测和 Bad Case 闭环
- Python 3.12、uv、pytest、Pydantic、Pandas、LangGraph：离线评测与参考基线
- React、TypeScript、Vite、SSE：轻量审核演示页面和已验证进度事件
- HTTP：首阶段连接 FinGuard Core
- OpenAPI、Micrometer、Prometheus、Grafana
- Docker Compose、CI、Linux production-like 部署

Phase 0 的精确版本已固定在 [依赖基线](docs/DEPENDENCIES.md) 并通过构建验证。后续
基础设施和 AI 依赖只在首次实现可执行行为时引入，避免用未使用的依赖伪装项目能力。

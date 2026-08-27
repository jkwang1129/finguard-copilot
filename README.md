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

- [范围与验收边界](docs/SCOPE.md)
- [系统架构](docs/ARCHITECTURE.md)
- [实施路线图](docs/ROADMAP.md)
- [架构决策记录](docs/adr/README.md)
- [执行任务清单](TASKS.md)
- [精确依赖与兼容依据](docs/DEPENDENCIES.md)
- [Phase 0 详细实施计划](docs/superpowers/plans/2026-08-20-phase-0-engineering-foundation.md)
- [Phase 0 契约基础实施计划](docs/superpowers/plans/2026-08-23-phase-0-contract-foundation.md)

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
```

Linux 或 CI 等价入口为 `bash scripts/verify.sh`。Java 集成测试需要可用的 Docker
环境，以启动真实 MySQL 8.4.10 Testcontainer；普通测试不需要模型密钥或付费 API。

确定性基础适配器必须显式启用 `stub` profile。它们只用于自动化测试和本地开发，
不连接 FinGuard Core、模型供应商或真实向量数据库，也不代表生产能力：

```powershell
$env:SPRING_PROFILES_ACTIVE = "stub"
.\mvnw.cmd spring-boot:run
```

## 核心原则

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

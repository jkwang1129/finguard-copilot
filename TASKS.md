# FinGuard Copilot 任务清单

本清单由 [实施路线图](docs/ROADMAP.md) 展开。勾选任务前必须保留对应测试、运行或文档
证据；阶段未通过验收时不提前宣称后续能力完成。

## 阶段 0：架构与工程基线

- [x] 建立范围、架构、路线图和 ADR 基线。
- [x] 确认 JDK 17、Spring Boot 与 Spring AI/Spring AI Alibaba 官方兼容版本。
- [x] 初始化 Maven 工程和模块化包结构。
- [x] 建立环境变量配置基线和 OpenAPI。
- [x] 建立统一错误响应和启动配置校验。
- [x] 接入 MySQL、Flyway 和 Testcontainers。
- [x] 定义模型、Embedding、向量存储和 Core 客户端端口。
- [x] 建立 Stub 模型、固定 Embedding 和内存检索实现。
- [x] 建立 Java 服务、Python 评测和 React 演示目录边界。
- [x] 配置 CI、依赖缓存、静态检查和秘密扫描。
- [x] 通过不调用模型或付费 API 的构建与全部当前普通测试。

## 阶段 1：只读事实闭环

- [x] 在 Core 设计并实现 `/api/auth/me`。
- [x] 在 Core 设计并实现 `/api/review-tasks/{id}/context`。
- [x] 为 Core 契约建立 200/401/403/404/超时测试。
- [x] 实现 Copilot 身份代理和 `REVIEWER` 校验。
- [x] 实现分析创建、查询、状态机和幂等语义。
- [x] 实现脱敏事实快照、校验和与 30 天删除任务。
- [x] 实现事务 Outbox、RabbitMQ Relay 和幂等 Worker。
- [x] 定义 RabbitMQ Exchange、Queue、DLQ、重试和 Outbox 清理策略。
- [x] 验证消息重复、乱序、RabbitMQ 中断和恢复不会丢失或重复分析。
- [x] 实现 Stub 结构化分析。
- [x] 实现 JSON Schema 与事实一致性校验。
- [x] 验证 Copilot 不调用任何 Core 写接口。

## 阶段 2：RAG 与知识数据管道

- [x] 定义知识文档、片段、索引版本和引用模型。
- [x] 实现文档登记、解析、清洗、切分、去重和校验和。
- [x] 实现 Embedding 与 Qdrant 适配器。
- [x] 实现候选索引、评测、发布和原子切换。
- [x] 实现文档增量更新、删除和索引回滚。
- [x] 实现检索结果引用和输出引用校验。
- [x] 建立固定 RAG 评测集并记录基线。
- [x] 验证动态 Core 事实不会写入向量库。

Phase 2 当前验收记录：[RAG 与知识数据管道](docs/acceptance/phase-2-rag-knowledge-pipeline.md)。

## 阶段 3：真实模型与上下文工程

- [ ] 接入首个真实模型适配器和显式运行 profile。
- [ ] 接入第二个真实候选模型，保持统一 `ModelGateway` 契约。
- [ ] 实现 32k 输入、2k 输出的上下文预算策略。
- [ ] 实现事实优先、文档去重和低排名裁剪。
- [ ] 建立审核 Golden Dataset。
- [ ] 对候选模型运行正确率、引用、拒答、延迟和成本比较。
- [ ] 固化主模型、备用模型和 Stub 选择依据。
- [ ] 实现 Token、调用次数、延迟和估算成本指标。
- [ ] 验证普通测试仍不需要网络和模型密钥。

## 阶段 4：AI 工程化与安全

- [ ] 实现 Prompt、模型、索引和价格表版本管理。
- [ ] 建立 Python 3.12、uv、pytest、Pydantic 和 Pandas 评测工具。
- [ ] 建立消费同一 Tool Schema 和数据集的 LangGraph 参考基线。
- [ ] 实现规则评测、LLM-as-Judge 和人工抽样校准。
- [ ] 实现 Agent Trace、Bad Case 分类和反馈回归闭环。
- [ ] 实现 reviewer feedback API 与权限检查。
- [ ] 生成可重复的离线评测报告。
- [ ] 实现受预算约束的多模型路由和降级。
- [ ] 使用 Redis 实现配额、限流和供应商并发控制。
- [ ] 实现单次预算、限流、超时和受限重试。
- [ ] 完成用户输入与恶意文档 Prompt 注入测试。
- [ ] 完成越权、任意 URL、工具参数和秘密泄露测试。
- [ ] 接入 OpenTelemetry、Langfuse、Prometheus 和 Grafana。
- [ ] 验证指标标签低基数且不包含敏感信息。
- [ ] 实现 MySQL `analysis_event` 单调序列和 Redis Pub/Sub 唤醒。
- [ ] 实现只发送受控阶段和最终可用事件的 SSE API。
- [ ] 实现 React、TypeScript、Vite 轻量审核演示页面。
- [ ] 验证 SSE 重连和浏览器无法看到未经校验的模型内容。

## 阶段 5：Agent Workflow 与 Tool Use

- [ ] 将多个只读 Core 查询建模为服务器确定性工具。
- [ ] 定义模型可选工具白名单及参数 Schema。
- [ ] 实现权限、参数、次数和预算的服务器端强制校验。
- [ ] 在真实分支需求出现后实现 Graph 状态流。
- [ ] 实现 HITL 暂停、恢复和过期处理。
- [ ] 实现运行级 Working Memory 和持久化 Checkpoint。
- [ ] 将已验证只读工具暴露为 MCP Server。
- [ ] 实现工具结果回查、审计和评测。
- [ ] 实现 Agent Trace 回放和消息重投幂等验证。
- [ ] 验证模型无法注册、构造或调用任意工具。

## 阶段 6：production-like 部署

- [ ] 创建 Java 服务和 React 页面所需的非 root、多阶段 Docker 镜像。
- [ ] 创建隔离的开发和集成 Compose 环境。
- [ ] 配置健康检查、就绪检查和本地端口绑定。
- [ ] 使用完整 Git SHA 标记并验证部署镜像。
- [ ] 编写 Linux 部署和回滚脚本。
- [ ] 验证 MySQL 备份恢复、RabbitMQ/Outbox 恢复和 Qdrant 索引重建。
- [ ] 演练 Core、MySQL、RabbitMQ、Redis、Qdrant、Langfuse 和模型提供商故障。
- [ ] 生成部署、回滚、故障、验收和清理报告。

## 收尾门禁

- [ ] 全部普通测试通过且无跳过。
- [ ] Golden Dataset、RAG、Agent、Judge 校准、安全和真实模型评测结果可追溯。
- [ ] 无业务写副作用、无秘密、无高基数敏感指标。
- [ ] 文档链接、OpenAPI、运行手册和架构图与实现一致。
- [ ] 完成范围审计、`git diff --check` 和工作区清理。
- [ ] 仅在用户明确要求后提交或推送 Git。

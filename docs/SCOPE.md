# FinGuard Copilot 范围说明

## 1. 产品定位

FinGuard Copilot 服务于 FinGuard Core 的风险审核流程。审核人员提交一个
`reviewTaskId` 后，系统聚合相关业务事实、检索制度依据，并返回结构化的审核辅助结果。

模型输出是建议而不是决定。Core 继续负责交易、对账、风险任务状态、RBAC、乐观锁和
最终审计；Copilot 不得绕开这些业务约束。

## 2. 目标用户

首个版本只有一种业务用户：拥有 FinGuard Core `REVIEWER` 角色的审核人员。

`ADMIN` 可以维护 Core 业务数据，但不能通过 Copilot 获得审核决策权限。匿名用户和其他
角色不能创建或查看分析。

## 3. MVP 用户流程

```text
REVIEWER 携带 Core JWT 和 reviewTaskId 创建分析
  -> Copilot 向 Core 验证身份与角色
  -> Copilot 从 Core 捕获脱敏、版本化的业务事实
  -> Copilot 检索制度和操作手册
  -> 模型生成固定结构的候选结果
  -> 服务端校验事实、引用和输出 Schema
  -> REVIEWER 查看建议、证据、限制和运行信息
  -> REVIEWER 回到 Core 独立完成最终决定
  -> REVIEWER 可向 Copilot 提交反馈
```

## 4. MVP 输出

成功分析必须返回：

- 审核任务摘要；
- 风险规则解释；
- 相关交易和对账事实；
- 业务证据列表及 Core 来源字段；
- 制度或操作手册引用；
- `CONFIRMED`、`IGNORED` 或 `INSUFFICIENT_EVIDENCE` 建议；
- 建议理由；
- 缺失信息、限制和不确定性；
- 分析、模型、Prompt、知识索引和输入事实版本。

`INSUFFICIENT_EVIDENCE` 仅是 Copilot 的拒绝判断结果，不会写入 Core。

## 5. MVP API

```text
POST /api/copilot/review-analyses
GET  /api/copilot/review-analyses/{analysisId}
PUT  /api/copilot/review-analyses/{analysisId}/feedback
```

创建分析使用异步语义：身份验证和 Core 上下文捕获成功后返回 `202 Accepted` 与
`analysisId`。客户端通过查询接口读取状态和结果。创建请求必须携带
`Idempotency-Key`；同一审核人员在 24 小时内使用相同 Key 和相同请求时返回原分析，
Key 相同但请求不同则返回 `409 Conflict`。

MVP 可以先用 OpenAPI/Swagger 验收接口。完整项目必须提供轻量审核页面和 SSE 阶段进度，
但不会流式展示尚未通过服务端校验的模型正文。

完整项目增加：

```text
GET /api/copilot/review-analyses/{analysisId}/events
```

该接口只发送状态、阶段、受控错误码和最终结果可用事件，不发送 JWT、事实快照、完整
Prompt、模型推理过程或未经校验的 Token 流。

## 6. 完整项目覆盖的工程能力

- Core HTTP 集成、认证转发、超时和错误映射；
- 模型适配接口、至少两个真实模型配置、受预算约束的路由和确定性 Stub；
- 结构化模型输出及服务端 Schema/事实/引用校验；
- 文档登记、解析、清洗、切分、Embedding、发布和回滚；
- Qdrant 向量检索和来源引用；
- RabbitMQ、事务 Outbox、幂等消费者和持久化 Checkpoint；
- Redis 限流、配额、供应商并发控制和安全的短期缓存；
- 只读 Tool Calling、运行级 Working Memory、Graph、HITL 与 MCP；
- Prompt、模型、索引和输入事实版本管理；
- Python/LangGraph 对照基线和可重复的离线评测工具；
- Golden Dataset、RAG/Agent/模型评测、安全测试和 Bad Case 闭环；
- 规则评测、LLM-as-Judge 与人工抽样校准；
- OpenTelemetry、Langfuse、Prometheus 和 Grafana；
- Token、调用次数、延迟、失败和估算成本指标；
- React/TypeScript 轻量演示页面和受控 SSE 进度；
- Docker、CI 和 production-like Linux 部署。

其中，模型调用、结构化输出、Core 事实、RAG、引用和基础评测构成可用 MVP；可靠异步
运行、成本治理、安全工程、Agent、Tool Calling、Graph、MCP、演示页面和
production-like 部署按路线图继续增加，不伪装成 MVP 已完成能力。

## 7. 明确不做

- 通用开放域聊天；
- 自然语言 SQL 或模型直接访问数据库；
- 任意 URL、任意 HTTP 或任意本地命令工具；
- 模型自动批准、忽略或修改审核任务；
- 资金操作、账户修改或 CSV 写入；
- 把动态交易和任务状态写入向量知识库；
- 长期聊天记忆；
- 为了使用 Python 而复制一套 FastAPI 生产服务；
- 多智能体自由协作、A2A、模型微调；
- 微服务拆分、Kubernetes、多租户、Gateway、Nacos；
- 将个人部署描述为真实金融生产环境。

## 8. 安全与数据边界

- JWT 只在当前请求中用于向 Core 验证用户和读取上下文，不保存、不写日志、不进入
  Prompt。
- Copilot 不共享 Core 的 JWT 签名密钥。身份与角色由 Core 的 `/api/auth/me` 确认。
- 异步模型任务开始前，Copilot 已同步捕获必要的脱敏事实快照，随后丢弃 JWT。
- 事实快照只包含生成解释所需的最少字段，使用独立数据表的精确 JSON 文本列保存（V8，保留 JSON_VALID 约束），默认保留 30 天；
  原始 CSV、密码、密钥和完整账户资料不得复制。
- 用户问题和知识文档都视为不可信数据，不能覆盖系统指令或工具策略。
- 日志不得记录完整 Prompt、JWT、模型密钥或未脱敏事实快照。

## 9. 分层完成标准

只有同时满足以下条件，才能声明可用 MVP 完成：

1. 每个业务事实都能映射到捕获的 Core 事实字段。
2. 每个制度性结论至少有一个已发布知识版本中的有效引用。
3. 证据不足、知识库不可用或验证失败时不会输出方向性建议。
4. 模型无法触发任何业务写副作用。
5. 普通测试在无网络、无模型密钥的环境中可重复执行。
6. Golden Dataset 可以重复运行并形成首个基线结果。

只有继续满足以下条件，才能声明完整项目路线完成：

7. 能比较不同 Prompt、模型和索引版本，并观察延迟、Token、失败、拒绝判断和估算成本。
8. 只读 Tool Calling、确定性 Graph、HITL 和 MCP 通过权限、预算、恢复与安全测试。
9. Agent 任务可可靠投递、暂停、恢复、回放，并可用规则、Judge 和人工样本进行评测。
10. 能在轻量页面中展示状态、工具轨迹、证据、引用、成本和安全降级。
11. production-like 环境可部署、健康检查、备份、回滚并完成故障演练。

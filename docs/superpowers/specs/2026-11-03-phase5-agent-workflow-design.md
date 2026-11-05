# Phase 5 Agent Workflow 设计提案

日期：2026-11-03。状态：**用户已授权按任务顺序实施；能力完成状态以各包验收为准**。

## 目标与入口

在现有只读审核链路上增加受控工具选择、真实条件分支、持久恢复、人工暂停与轨迹回放，最终提供本地 MCP 访问。最终业务决定始终由 Core 执行。

本地入口为 `main` / `e45450a`，工作区在调研时干净。P4-6 本地工程验收已交付；报告中的 Java 全套404项与最后修正后17项定向回归属于不同运行，不能拼成一次405项全套。此次规划没有重新执行这些测试。

依据：`docs/ROADMAP.md`、`docs/ARCHITECTURE.md`、ADR-0004/0006/0008/0012、`docs/acceptance/2026-11-03-p4-6-final-acceptance.md`。

真实人工校准、真实审核反馈回归集、合格备用模型、真实 Embedding/生产模型质量和部署恢复门槛继续保留。允许将本提案作为隔离工程开发方案讨论；是否放行 Phase 5 工程实施需明确记录，不能写成 Phase 4 全部质量条件通过。

## 方案比较与建议

| 方案 | 收益 | 代价与结论 |
| --- | --- | --- |
| Java 内显式有界 Graph，复用 MySQL/Outbox/Worker | 与当前可靠性、权限、账本保持一个所有者；实现真实工具与暂停分支 | 需要升级单调用契约；**推荐** |
| Python/LangGraph 成为生产 Agent 服务 | 复用离线实验写法 | 新增服务、跨语言状态与鉴权；当前 Python 仅作离线对照，不推荐 |
| 先上 MCP 或通用自主 ReAct Agent | 很快出现工具调用演示 | 不能先解决恢复、预算与授权；不作为本期入口 |

用户已授权按本计划顺序实施。Graph 优先评估当前 BOM 对应的 Spring AI Alibaba Graph；必须以 Java17/Boot3.5.8 的实际编译、持久化及中断验证决定依赖。官方最新示例不是当前固定版本兼容性的证明。若不兼容，采用自有显式 Java 节点/条件边执行器，另记 ADR，不连带升级全栈。

## 事实与授权边界

1. 创建请求中，以当前 JWT 调用 `/api/auth/me`，再确定性读取 `/api/review-tasks/{id}/context` 和 `/api/review-tasks/{id}`。后一个接口只抽取 ID、version、status、ruleCode、reasonCode；丢弃 decisionNote、reviewedBy 等无关字段。
2. 两份任务投影不一致即拒绝创建，不混合版本，不自动重读。该检查不能证明 Core 跨端点的全局事务快照；展示捕获时间和来源，不宣称实时一致性。
3. JWT 不入 MySQL、队列、Checkpoint、Trace 或模型。后台工具读取已授权、脱敏、不可变快照及冻结索引。服务端注入 analysisId、reviewer 和任务 ID，模型不能提供其他资源 ID 或 URL。
4. 首批工具：`get_review_facts({})`、`get_task_state({})`、`search_review_policy({query, topK})`。前两个明确返回快照事实；第三个仅查本运行冻结、仍允许使用的发布索引，query最长256字符、topK为1–5，拒绝额外字段。不得把快照工具称为实时 Core HTTP 工具。
5. 创建/恢复时的身份及事实读取属于固定授权捕获步骤；最多3次 Core HTTP 请求，无自动重试。Agent 工具执行另有最多4个槽，包含所有确定性/模型选择的运行时工具和检索重试；不能通过改名绕开计数。运行内初始 RAG 若走工具也占槽，不保留一条隐藏的无限检索路径。
6. 首批不引入后台实时 Core 凭据。需要最新事实时进入人工暂停，由新的 HTTP 请求重新授权和捕获；若要求后台自主实时读取，则另设计短期委托凭证并修订 ADR-0006，不能偷偷增加服务账号。

## 两次模型调用的精确含义

- 全分析最多2次模型调用、4次 Agent 只读工具执行；恢复、消息重投、检索不足、修复都不重置计数。每次模型请求仍最多32,000输入、2,000输出 Token，工具结果加入下一请求前重新核算上下文。
- 第一调用可返回最终候选或一批工具选择。若返回工具选择，顺序执行剩余槽允许的调用，第二调用只许返回最终候选；没有第三轮。
- 若第一调用直接给出候选且仅存在可恢复的格式/Schema问题，第二调用可用于一次修复。若第一调用已用于规划，第二调用失败后不能再修复。
- 越权、注入、金额/事实矛盾、无效引用和方向性政策冲突直接失败，不让模型“修复”成成功。证据不足可产生经校验的 `INSUFFICIENT_EVIDENCE`，或在仍有可执行续程时暂停。
- 检索不足最多再检索一次，由服务端生成受控查询，复用冻结索引并占工具槽。政策缺失或冲突不能通过任意换索引绕过。
- 每调用序号独立占用、预算预留与结算；总分析金额上限单独配置，不能默认把旧上限乘二。费用未知保留 `UNKNOWN_HELD`；任一付费调用结果未知则停止续程，不重发、不切供应商。

## Graph、Checkpoint 与人工恢复

显式节点：LOAD → RETRIEVE → PLAN_OR_GENERATE → EXECUTE_TOOLS/VALIDATE → GENERATE_OR_REPAIR → VALIDATE → SUCCEEDED。节点间有 WAITING_FOR_HUMAN、FAILED 分支；检索重试边至多一次。调用2必须禁用工具选择。

Checkpoint至少记录 schemaVersion、workflowVersion、revision、nextNode、snapshotHash、index/manifest引用、已用调用数、工具槽、检索次数、等待原因及expiresAt。不存推理链或未经验证模型正文；仅保留允许的消息摘要和继续执行所需的最小结构化数据。

MySQL 是唯一恢复真相。Checkpoint CAS、分析状态、持久事件、下一条 Outbox 必须同事务提交；外部 HTTP 不包在数据库长事务中。工具调用先持久化唯一执行槽，完成后保存结果及校验和；重投读取已存结果。发送后崩溃且结果未存的执行标为 UNKNOWN，停止或等待人工处理，绝不能因租约过期就再次执行。该语义为至多一次尝试加不确定终态，不声称跨 HTTP/MySQL 的 exactly-once。

HITL默认最长等待24小时，且不得晚于事实快照有效期。恢复接口带当前 JWT、expectedRevision、Idempotency-Key 和受控 action（CONTINUE/CANCEL），不开放自由文本。重新检查 REVIEWER、运行归属、等待状态、期限、当前 Core task版本/状态及规范化事实哈希（排除 capturedAt）。变化则旧运行失败 `CONTEXT_STALE`，需新建 analysisId；相同才允许恢复。恢复不覆写原快照，不刷新预算，不允许强制通过政策冲突。

## MCP 与页面

MCP最后接入，首版仅本地 STDIO，提供已验证快照与政策证据读取，不提供模型调用、恢复或 Core 写操作。固定共享 Schema 和同一 ToolExecutor，不能另造权限宽松的第二实现。

本地桥接通过一次已有 JWT 的 HTTP 授权请求获取绑定 reviewer/analysisId/toolScope 的5分钟随机 capability；数据库只保存摘要，桥接进程只在内存持有，不将 Core JWT 交给 MCP。每次调用校验有效期、撤销、归属和剩余工具预算；同一运行从 MCP 调用也占4个槽。到期需新的授权请求，跨进程续期不保存用户 JWT。此授权为有界租约，不保证租约内实时得知 Core 角色撤销；公开远程 HTTP MCP 及 OAuth 资源服务器授权另列扩展，不宣称已支持。

SSE仅发工具名、受控阶段、revision、结果可用标记与错误码。页面通过授权接口读取最小脱敏轨迹，显示“快照读取/政策检索”、捕获时间、费用、剩余预算、人工等待和恢复入口；不展示完整Prompt、模型原文、推理链。

## 验收与非目标

必须覆盖真实进程 submit/poll、Core只读校验、工具参数与注入、调用2拒绝工具、消息重投、并发Worker、每个发送/提交窗口崩溃、HITL并发/过期/版本变化、SSE补读、工具回放及MCP越权。普通CI无需模型密钥；真实供应商 Tool Calling 资格另以冻结数据、调用数及金额预算验收。

非目标：业务写工具、任意URL/SQL/脚本、长期用户记忆、多Agent/A2A、Python生产运行时、全栈升级、以合成结果证明生产质量。

官方依据（2026-11-03查阅）：[Graph人工中断与恢复](https://java2ai.com/docs/frameworks/graph-core/examples/human-in-the-loop/)、[MCP工具协议](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[MCP传输授权边界](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)。依赖与协议版本在执行前锁定，不在本提案中声称最新版本已兼容。

# Phase 5 Agent Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 用户已授权按任务顺序实施；未授权提交、推送或付费供应商调用。

**Goal:** 在只读审核链路中交付有界 Tool Calling、持久 Graph、HITL、回放和本地 MCP。

**Architecture:** Java服务持有权限、预算、状态和工具执行权。复用MySQL/Outbox/RabbitMQ、现有护栏、Redis短期许可、持久事件/SSE；Python/LangGraph仅作共享契约的离线对照。

**Tech Stack:** Java17、Spring Boot3.5.8、当前Spring AI Alibaba BOM1.1.2.2、MySQL/Flyway、RabbitMQ、Redis、Qdrant、React/TypeScript、Python3.12。Graph与MCP适配器按当前依赖树验证后锁定，不直接引入最新版。

**Spec:** [设计提案](../specs/2026-11-03-phase5-agent-workflow-design.md)。所有新增类、接口和迁移文件名均为计划，当前尚不存在。

## Global Constraints

- 全分析最多2次模型调用、4次Agent只读工具执行；恢复、消息重投、检索不足、修复都不重置计数。
- 每次模型请求仍最多32,000输入、2,000输出Token；每次调用和总分析金额上限分别执行。
- JWT不落库、不进消息/Checkpoint/模型；后台仅操作已授权不可变快照，实时读取需新HTTP授权。
- 保留旧P4单调用路径及历史V1–V12迁移；新运行显式冻结workflowVersion，旧运行不得自动升级为Agent。
- 不开放Core业务写工具、任意URL、SQL、脚本或自由审核文本。
- 工具及模型发送结果未知时不自动重发；MySQL账本和持久事件为真相。
- 保留P4未关闭质量门槛；只有测试/运行证据齐全才能勾选TASKS。仅在用户明确要求后提交或推送Git。

## Review Focus

1. 同任务version未变但其他事实已变：恢复比较规范化事实哈希，P5-4覆盖。
2. 第二模型响应仍包含tool_calls/function_call：拒绝执行且无第三调用，P5-3覆盖。
3. 发送成功、落库前崩溃：调用保持UNKNOWN，不因租约超时重新执行，P5-2/P5-4覆盖。
4. P4历史运行与SSE状态约束升级：读取兼容、预算仍最多一次，P5-2/P5-4覆盖。
5. UI/MCP并发使用剩余工具槽及恢复按钮：数据库原子计数和CAS只允许一个获胜者，P5-4/P5-5覆盖。

## 工作包、依赖与粗估

| 工作包 | 产物 | 依赖 | 工程量估计 |
| --- | --- | --- | --- |
| P5-1 | 确定性多端点捕获、快照工具、共享Schema与安全执行器 | Phase5工程入口确认 | 4–6天 |
| P5-2 | 两次调用占用、逐调用账本、总预算与迁移兼容 | P5-1 | 4–6天 |
| P5-3 | 真正的模型工具选择、两轮对话与有界修复 | P5-1/2 | 4–6天 |
| P5-4 | Graph、Checkpoint、HITL、事件/UI和回放 | P5-2/3 | 7–10天 |
| P5-5 | 本地MCP桥接与同源权限/工具预算 | P5-1/4 | 3–5天 |
| P5-6 | 共享评测、故障矩阵与总验收 | 前五包 | 4–6天 |

合计26–39个工程工作日，仅为范围估算，不是日历承诺；不包含真实模型选型/费用审批、真实人工标注、生产部署。建议顺序执行，每包单独验收。每包进入时再细化其新增文件和提交边界，避免一次写完全部子系统。

## P5-1：确定性只读能力与工具安全边界

**Modify:**
- `src/main/java/com/finguard/copilot/coreclient/port/CoreClient.java`
- `src/main/java/com/finguard/copilot/coreclient/http/HttpCoreClient.java`
- `src/main/java/com/finguard/copilot/analysis/application/CreateAnalysisService.java`
- `src/main/java/com/finguard/copilot/context/SnapshotCodec.java`
- `src/test/java/com/finguard/copilot/coreclient/http/HttpCoreClientTest.java`
- `src/test/java/com/finguard/copilot/analysis/application/CreateAnalysisServiceTest.java`

**Create:**
- `src/main/java/com/finguard/copilot/coreclient/port/ReviewTaskState.java`：只含脱敏任务状态字段。
- `src/main/java/com/finguard/copilot/agent/tool/{ToolRegistry,ToolExecutor,ToolInvocation,ToolResult,ToolExecutionContext}.java`
- `src/main/resources/schema/agent-tools-v1.schema.json`：唯一共享工具Schema。
- `src/test/java/com/finguard/copilot/agent/tool/{ToolExecutorTest,ToolExecutionIntegrationTest}.java`
- `src/main/resources/db/migration/V13__create_agent_tool_execution.sql`：计划编号，执行前确认未被占用。
- `docs/contracts/phase5-tool-execution-v1.md`

**Interfaces:**
- `CoreClient.reviewTaskState(long reviewTaskId, String bearerToken): ReviewTaskState`。
- `ToolExecutor.execute(ToolExecutionContext context, ToolInvocation invocation): ToolResult`。
- context含analysisId、服务端reviewer、snapshotHash、冻结索引、固定stepKey；invocation仅含toolName和参数。ToolResult含受控状态、来源、证据引用、结果哈希及脱敏内容。
- 执行器所有者为Java；模型、HTTP和MCP不得直接调用内部Client绕过它。

- [x] 写契约失败测试：context+taskState一致时创建；version/status/ruleCode/reasonCode不一致、任一401/403/404/超时均不产生已接受运行。测试taskState响应含decisionNote时不进入快照。
- [x] 运行 `mvn test '-Dtest=HttpCoreClientTest,CreateAnalysisServiceTest'`，确认新契约用例先失败。
- [x] 增加第二Core GET及最小DTO投影；生成analysisId时同时冻结工作流策略。默认仍走P4，Agent仅显式profile启用。快照Schema增加版本，旧快照读取仍兼容。
- [x] 定义3个工具及严格Schema；服务端注入资源范围。拒绝unknown name、额外字段、伪造ID、URL、超长query、topK越界；拒绝工具结果中的已知注入并重新校验数值/引用。
- [x] V13建立运行绑定、唯一stepKey、slot序号、参数哈希、状态、最小结果/哈希和时间；事务锁分析行分配slot1–4。相同stepKey同参数回放，不同参数409；拒绝也留低敏审计，不能无限执行无效请求。
- [x] 写并发/重投测试：四槽边界、同key重投只调用一次底层工具、不同参数冲突、跨reviewer与跨analysis拒绝；先失败再实现。结果摘要不能被浮点序列化损坏18位小数。
- [x] 运行新增工具单元与MySQL集成测试；隔离Core/Copilot替身流程核对实际2个业务GET、无写请求和JWT留存；保留命令、HTTP计数及Core业务校验和。
- [x] 交付P5-1验收记录；仅在收到明确指令后提交。到这里尚不能宣称模型Tool Calling完成。

## P5-2：逐调用预算、用量与旧数据兼容

**Modify:** `governance/{BudgetReservations,CallAdmission,RunManifestService}.java`、`usage/{ModelCallReservations,ModelUsageRecorder}.java`、`analysis/application/AnalysisPageService.java`（均位于`src/main/java/com/finguard/copilot/`）。

**Create:** `agent/budget/AgentCallLedger.java`、`agent/budget/AgentCallAdmission.java`；`V14__create_agent_call_ledger.sql`；`docs/contracts/phase5-call-budget-v1.md`；`src/test/java/com/finguard/copilot/agent/budget/AgentCallLedgerIntegrationTest.java`。

**Interfaces:** `AgentCallAdmission.acquire(UUID analysisId, int callOrdinal, PreparedAgentCall call): CallClaim`；`AgentCallLedger.recordOutcome(CallClaim claim, AgentCallOutcome outcome): void`。ordinal只允许1/2；调用清单绑定本次输入哈希、模型、Prompt、索引、工具Schema、价格及上下文上限。PreparedAgentCall/CallClaim/AgentCallOutcome为该包新增不可变类型。

- [x] 先冻结新契约：按`analysisId+callOrdinal`持久化独立占用、用量、预算事件和结算；总分析账户汇总两次调用。保留旧V5/V6/V9/V10表及旧代码分支，不把历史请求复制成两次消费。
- [x] 写失败测试：第三调用拒绝；调用1结算后调用2预算不足；Redis故障不发起调用；UNKNOWN_HELD不可释放或继续调用2；双Worker同ordinal最多一次发送；第二次用量不能覆盖第一次；旧运行保持一次。
- [x] 运行 `mvn test '-Dtest=AgentCallLedgerIntegrationTest'` 确认失败后，实现V14及逐调用账本/准入。发送前提交占用；仅可证明NOT_SENT时释放金额，不删除发送占用重试。
- [x] 新API投影按分析汇总SETTLED与UNKNOWN_HELD，保留每调用明细。每次调用单独冻结价格，不伪造未知费用为0；限额配置缺失时Agent profile启动失败或拒绝准入。
- [x] 完成“仅V1–V12数据→当前版本”的迁移测试及历史P4页面/预算回归；运行原`GovernedWorkerRaceIntegrationTest`和新测试。验收数据库失败后不会多发付费请求。
- [x] 交付预算契约、迁移/回滚限制和验收记录；不修改历史冻结评测文件。

## P5-3：模型工具选择与两轮对话

**Create:** `src/main/java/com/finguard/copilot/agent/model/{AgentModelGateway,AgentTurnRequest,AgentTurnResponse,CompatibleAgentModelGateway}.java`、`agent/runtime/BoundedAgentRunner.java`；`src/test/java/com/finguard/copilot/agent/model/AgentToolCallingTest.java`、`agent/runtime/BoundedAgentRunnerTest.java`。

**Modify:** `model/config/RealModelConfiguration.java`、`context/ContextAssembler.java`；增加独立Agent配置，不取消原`OpenAiCompatibleResponse`对工具字段的拒绝。普通路径继续拒绝tool_calls/function_call。

**Interfaces:** `AgentModelGateway.complete(AgentTurnRequest request): AgentTurnResponse`，返回受控`FinalCandidate`或`ToolBatch`两种分支；`BoundedAgentRunner.run(UUID analysisId): AgentStepOutcome`。AgentStepOutcome为后续Graph共享的Advance/Wait/Fail结果，含nextNode和结构化变更，不包含任意Runnable。

- [x] 先写HTTP替身测试：第一轮真实tool_calls解析、tool_call_id配对、未知/重复ID、正文与tool_calls混合、legacy function_call、空参数/额外字段、超大响应、第二轮继续请求工具。明确混合正文不作为最终结果发布，legacy非空调用拒绝。
- [x] 运行 `mvn test '-Dtest=AgentToolCallingTest,BoundedAgentRunnerTest'` 确认新测试失败；实现独立协议适配器与两轮runner，所有调用接P5-2准入。
- [x] 第一轮至多申请剩余工具槽数量的工具，服务端顺序执行；第二轮强制无工具并再次验证响应。保留tool_call_id映射所需最小数据，Checkpoint不保存完整未验证对话。
- [x] 写并通过分支测试：首轮最终成功；工具选择→第二轮成功；首轮格式问题→修复一次；事实/金额/引用冲突零修复；预算用尽/第二轮无效直接失败；检索最多补一次且占工具槽。
- [x] 工具输出重新经过不可信输入处理；模型最终输出继续走现有Schema、BigDecimal、事实、引用及审定政策护栏；工具成功不代表建议可发布。
- [x] 冻结Agent模式的Prompt与供应商配置。普通测试只用协议替身；当前真实主模型的native Tool Calling资格未确认，不直接沿用旧“最终JSON输出”质量证据。
- [x] 交付两轮HTTP计数和坏案例；到这里称“有界工具编排”，尚未宣称持久Graph/HITL完成。

## P5-4：持久化Graph、HITL与审核台

**Create:** `agent/graph/ReviewAgentGraph.java`、`agent/checkpoint/{CheckpointStore,AgentCheckpoint}.java`、`agent/hitl/{ResumeAnalysisService,WaitingAnalysisExpiryJob}.java`、`api/analysis/ResumeAnalysisController.java`、`api/analysis/AnalysisTraceController.java`；`V15__create_agent_checkpoints_and_resume.sql`。

**Modify:** `dispatch/{AnalysisWorker,AnalysisRequestedMessage,OutboxRelay}.java`、`analysis/application/AnalysisStateMachine.java`、`analysis/domain/AnalysisStatus.java`、`events/{AnalysisEventRepository,AnalysisEventStream}.java`、`observability/{AnalysisTelemetry,TelemetryVocabulary}.java`、`frontend/src/{App.tsx,api.ts,events.ts}`。生产Java路径均位于`src/main/java/com/finguard/copilot/`。

**Tests:** `src/test/java/com/finguard/copilot/agent/graph/AgentGraphRecoveryIntegrationTest.java`、`agent/hitl/ResumeAnalysisIntegrationTest.java`及前端现有App/API/SSE测试。

**Interfaces:** `CheckpointStore.compareAndAdvance(UUID analysisId, long expectedRevision, AgentStepOutcome outcome): boolean`，同事务处理状态、Checkpoint、事件与Outbox；`ResumeAnalysisService.resume(UUID analysisId, long expectedRevision, ResumeAction action, String idempotencyKey, String bearerToken): ResumeReceipt`。ResumeReceipt含analysisId、revision、status、replayed；新增类型归属hitl包。

- [x] 先验证选定Graph依赖可在当前JDK/BOM编译和中断恢复；写下适配ADR。框架Saver不能另立第二份状态真相；框架默认MemorySaver不得用于正式运行。
- [x] 写失败测试并实现V15：Checkpoint版本、CAS、nextNode、模型/工具计数、冻结版本、等待期限、Outbox唯一stepKey；Worker消息兼容P4，旧消息不进入新Graph。队列attempt与模型ordinal分别计数。
- [x] 扩展PLANNING/TOOL_RUNNING/WAITING_FOR_HUMAN状态；后续迁移同步修改V12事件CHECK约束，旧历史事件不伪造工具轨迹。新增状态的API/OpenAPI/前端解析必须一起更新。
- [x] 在工具/模型的占用提交前、发送后结果入库前、结果入库后Checkpoint推进前、Checkpoint与Outbox提交后、ACK前分别杀进程；验证恢复不重发、不扣两次预算、不重复工具；UNKNOWN保持可解释的停止状态。
- [x] 实现 `POST /api/review-analyses/{id}/resume`，请求`{expectedRevision, action}`及Idempotency-Key。CONTINUE成功返回202并写恢复Outbox；CANCEL返回200并落FAILED/ANALYSIS_CANCELLED。同key同请求回放，不同请求409。
- [x] 写HITL测试：401/403/404、非等待409、revision过期409、等待过期409、Core version/status变化、version相同但事实变化、快照删除、并发双恢复、取消与Worker竞态。授权失败不改变原运行；事实过期则原运行FAILED/CONTEXT_STALE，不能偷偷创建新分析。
- [x] WaitingAnalysisExpiryJob以CAS将过期等待终结为FAILED/WAIT_EXPIRED。取消/过期不得释放UNKNOWN_HELD或撤销已发送占用。人工恢复只允许明确可继续的等待原因，不强制覆盖安全失败。
- [x] 接入授权GET轨迹API；SSE继续只传受控事件。Trace/指标只增加固定阶段与工具枚举，参数和analysisId不作指标标签。回放只读历史数据，不调用工具或模型；源数据到期则显示不可完整回放。
- [x] 页面增加暂停/恢复/取消、快照时间、实际工具轨迹、费用与剩余槽；双击恢复、断线续传、权限失效、不确定调用、过期均有测试。检索/输出不足是可见失败/不足状态，不展示未经校验的正文。
- [x] 扩展快照保留任务：Checkpoint/工具结果/回放数据随事实期限清理，MCP能力随之失效；保留低敏账本与不可回放标记。测试等待不能超过源数据期限。
- [x] 运行新Graph/HITL MySQL测试、旧Worker/预算/SSE测试、前端lint/test/build；再跑真实Core+Copilot+MQ的submit/poll暂停/重启/恢复链路，核对最终Core状态未被写入。

## P5-5：本地MCP暴露

**Create:** `agent/mcp/{McpToolAdapter,McpCapabilityService}.java`、`api/analysis/McpCapabilityController.java`、`V16__create_mcp_capabilities.sql`、`tools/mcp-bridge/`、`docs/operations/phase5-mcp.md`；测试`src/test/java/com/finguard/copilot/agent/mcp/McpToolAdapterIntegrationTest.java`。

**Interfaces:** `McpToolAdapter.list(AuthorizedMcpSession session)`和`call(AuthorizedMcpSession session, String toolName, JsonNode arguments, String invocationKey): ToolResult`；AuthorizedMcpSession仅由已验证capability建立，不能相信模型传入的身份。具体STDIO桥接依赖在本包入口锁定。

- [x] 先写失败测试：tools/list与共享Schema一致；无capability、过期、撤销、跨analysis、未授权工具拒绝；模型/工具预算并发冲突不能绕过4槽。
- [x] 实现授权端点：当前JWT重新验证REVIEWER与运行归属、Core状态/事实；签发5分钟随机capability，仅存摘要，日志不输出值。capability非Core JWT，不允许跨用途使用或恢复运行。
- [x] STDIO桥接只连接本地Copilot受控工具入口；不接受任意目标地址或透传Core JWT，不监听网络端口。工具列表仅暴露已验证快照/政策读取；调用仍由同一ToolExecutor记审计和占槽。
- [x] MCP调用不能与活动Graph争抢槽：仅在WAITING_FOR_HUMAN或终态允许，检查revision；终态剩余槽用尽则拒绝。该限制在页面与手册说明，不能另开不计数的分析内工具循环。
- [x] 使用真实本地MCP客户端完成initialize/list/call/错误路径，验证工具结果脱敏和过期续权；只宣称本地STDIO互通。远程HTTP/OAuth不包含在本包验收。
- [x] 回放与MCP临时数据清理、capability撤销加入操作手册；运行定向测试和协议互通，交付记录。

## P5-6：共享评测、故障矩阵与总验收

**Create:** `tools/agent-evaluation/data/phase5-agent-v1.jsonl`、`tools/agent-evaluation/src/finguard_agent_evaluation/phase5_agent.py`、`tools/agent-evaluation/tests/test_phase5_agent.py`、`scripts/run-phase5-e2e.ps1`、`docs/acceptance/YYYY-MM-DD-phase5-final-acceptance.md`、`docs/operations/phase5-agent-workflow.md`。

**Modify:** `TASKS.md`、`docs/ROADMAP.md`、`docs/ARCHITECTURE.md`、README、现有运行手册与OpenAPI；仅按证据更新。

- [x] 在调优前冻结至少24个新案例：正常/有工具/无需工具、错误名称/参数/越权/注入、次数/成本不足、格式修复/事实不可修复、检索不足/政策冲突、重投/崩溃/双Worker、暂停/并发恢复/过期/事实变化、MCP隔离。安全失败不能因总分抵消。
- [x] Java与离线LangGraph使用同一工具Schema、脱敏输入及判定规则，参考实现不读取生产Core。统计工具选择、参数合法、事实/引用/建议正确率、恢复成功、重复执行/调用数、费用、延迟；分母含失败例。
- [x] 本地HTTP模型替身覆盖全部Graph分支，隔离MySQL/RabbitMQ/Redis/Qdrant与真实Core进程；submit返回202后保留analysisId并poll至终态，暂停场景则恢复后继续poll。
- [x] 检查点故障矩阵必须记录每个场景：预期终态、真实HTTP次数、工具底层执行次数、调用占用、费用、Outbox/回执、事件序号和Core业务表校验和。Redis中断不放开模型调用，RabbitMQ恢复不丢任务，SSE重连不丢进度。
- [ ] 真实供应商资格作为单独批次：先冻结不超过12个合成案例、最多24次调用及明确CNY总金额，再实际运行原生tool_calls→工具→最终输出与无工具/拒绝路径。当前计划不授权费用；不合格时保留工程验收，真实模型Agent资格不通过，不能用伪造JSON规划替代native Tool Calling证据。
- [x] 全套运行：`mvn test`；`uv run --project tools/agent-evaluation pytest tools/agent-evaluation/tests -q`；`npm --prefix frontend run test -- --run`、`run lint`、`run build`；依赖审计与秘密扫描；`git diff --check`。按实际退出码及报告记录，不预填通过数。
- [x] 最后独立核查：旧P4运行、调用/费用不确定、HITL新授权、MCP隔离、无业务写副作用和历史冻结未改；修复后只对新增变化补足相关验证。
- [x] 报告区分替身工程能力、真实供应商Tool Calling资格、真实人工/模型质量和Phase6部署门槛。未完成项留在TASKS，不把“总验收报告已生成”写成全部门槛通过。

## 建议的第一批与交付方式

首批只执行P5-1，交付确定性多端点读取、3个快照/政策工具、严格Schema、幂等执行记录和安全测试。随后单独评审P5-2预算迁移，再开放模型工具选择。P5-1不调用付费模型、不发布目标知识库、不修改Core业务决定。

执行使用隔离分支`codex/phase-5-agent-workflow`。仅按实际验收证据更新阶段勾选；提交、推送与新增付费调用仍需明确授权。用户已要求按P5-1至P5-6顺序实施，工作包之间无需再次询问是否继续。

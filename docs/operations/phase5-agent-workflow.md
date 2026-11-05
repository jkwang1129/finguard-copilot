# Phase5运行、恢复与验收

## 当前能力与开关

Phase5本地工程模式由原生Tool Calling、MySQL持久Graph、HITL和本地STDIO MCP组成。普通P4路径保持单次模型占用；Agent整次分析硬上限为2次模型、4个工具槽。重投、恢复、修复和MCP共享这些上限。

| 配置 | 默认 | 意义 |
| --- | --- | --- |
| `COPILOT_AGENT_ENABLED` | false | 创建时增加固定Core任务详情复查并冻结agent-v1工具策略 |
| `COPILOT_AGENT_RUNTIME_ENABLED` | false | 创建时冻结新Graph运行选择，装配Agent Worker与恢复端点 |
| `COPILOT_AGENT_MAX_ANALYSIS_CNY` | 空 | 必须显式设置，运行模式缺失时启动拒绝 |
| `COPILOT_AGENT_HUMAN_GATE` | false | 模型选定工具后、执行工具前暂停，最多24小时且不超过来源期限 |

runtime需要capture开关同时启用。创建后workflow/runtime策略不可随全局开关静默改变。关闭runtime时，已冻结的Graph运行会通过Outbox延后，不进入P4单调用路径；等待中的运行保持等待。重新启用后由持久消息/租约扫描恢复。

来源到期后，即使任务仍在QUEUED且尚未生成Checkpoint，也会以CONTEXT_STALE终结；停用期间的延后处理同样检查来源，避免无限生成唤醒消息。

默认真实native准入仍关闭。2026-11-04增加独立的[原生输入上界契约](../contracts/phase5-native-input-bound-v1.md)：仅精确deepseek-flash官方端点、显式`COPILOT_AGENT_NATIVE_BOUND_VERSION=deepseek-v41-native-tools-byte-bound-v1`及已核对的短期价格允许受预算验证；fixture不能指向真实供应商。该设置证明的是输入/计费控制条件，不代表模型整体质量资格。原P4文本上界不能替代它。

首轮真实批次11/12未通过的[记录](../acceptance/2026-11-04-native-provider-qualification.md)保留。随后独立RQ复验12/12通过、13次请求，仅覆盖合成native组件协议；见[复验记录](../acceptance/2026-11-04-native-tool-budget-requalification.md)。默认Agent开关和native-bound设置均未启用。价格版本`deepseek-flash-native-20261104-peak-miss-cny-v1`在2026-11-05T00:00Z到期，之后调用前拒绝。

## 可重复的本地验收

需要Java17、Maven、Python3.12/uv、Node24、Docker及可用的Core制品。测试使用合成业务，不提交Core最终决定。

```powershell
mvn test '-DargLine=-Xmx512m' '-Dspring.test.context.cache.maxSize=1'
uv run --project tools/agent-evaluation pytest tools/agent-evaluation/tests -q
uv run --project tools/mcp-bridge pytest tools/mcp-bridge/tests -q
npm --prefix frontend run test -- --run
npm --prefix frontend run lint
npm --prefix frontend run build
mvn package -DskipTests
mvn dependency:build-classpath '-Dmdep.outputFile=target/e2e-classpath.txt'
./scripts/run-phase5-e2e.ps1 -CoreRoot C:/dev/finguard-core
```

**Java原生HTTP测试与进程E2E必须串行**：两者都刻意只使用经过fixture校验的`127.0.0.1:18765`。SDK测试临时占用8081/8082，也要与真实Copilot进程错开。`verify.ps1`/`verify.sh`和CI已包含MCP桥测试。

完整进程脚本默认执行原生分支、MCP、真实Redis中断、AMQP连接中断/恢复、运行开关隔离与10个真实JVM退出窗口；`-SkipFaultMatrix`可省略故障窗口。它没有付费模型选项。MySQL使用新数据库，RabbitMQ使用新vhost；Redis/Qdrant容器为本次运行所有。AMQP中断只停止本次Python转发进程，不停止共享RabbitMQ。

每个提交返回202后保存analysisId并轮询；等待时新授权继续，再轮询至终态。报告位于`target/business-e2e/{runId}/report.json`。正常退出会停止本次服务/容器；数据库和vhost保留证据，不自动删除其他运行。`-BrowserAcceptance -KeepRunning`只用于独立浏览器验收，完成后按报告核对并停止本次PID/容器。

故障注入开关`COPILOT_AGENT_FIXTURE_FAULT_POINT`/`COPILOT_AGENT_FIXTURE_PROBES`仅在fixture-mode和精确loopback模型配置下可用，其他配置启动拒绝。矩阵真实终止本次JVM，再把死owner租约时间置为过期以缩短等待；模型/工具永久占用、Working Memory不改动。默认生产代码租约仍为两分钟、恢复扫描十秒；此矩阵不测两分钟的真实等待时长。

## 人工继续与取消

审核台在WAITING_FOR_HUMAN展示快照捕获时间、将读取的工具、剩余模型/工具额度及期限。点击继续/取消会新取当前Core身份和事实，JWT只在当前请求内存使用。

`POST /api/copilot/review-analyses/{id}/resume`：仅接受字符串`expectedRevision`和`CONTINUE`/`CANCEL`，另需当前Core Bearer及Idempotency-Key。相同key/请求回放同一回执，变更请求409。继续202，取消200，取消结果为FAILED/ANALYSIS_CANCELLED。不要用新key反复重建同一次操作。

- 401/403/404：重新核对身份或归属，原运行不修改。
- 409旧revision/非等待：加载最新页面；不强制覆盖。
- CONTEXT_STALE：任务状态、version或规范化事实变化。capturedAt单独变化不算事实变化；旧运行失败，需重新创建分析，不重置旧预算。
- WAIT_EXPIRED：等待超期，服务提交失败状态；不会续期原快照或释放未知费用。
- MCP有CLAIMED/UNKNOWN读取时CONTINUE冲突，CANCEL仍可用。MCP使用方式见[本地MCP手册](phase5-mcp.md)。

## 不确定步骤与恢复

MySQL是唯一恢复真相，框架MemorySaver显式关闭。每个节点推进将Checkpoint CAS、受控状态事件和下条Outbox同事务提交；owner/revision/租约共同防止迟到Worker覆盖新Worker。

模型/工具领取事务在锁定分析父行后再次检查同一owner、revision和未过期租约。过期或已被接管的Worker不能领取新调用或失败化新owner的运行；MCP继续使用独立capability及revision授权。损坏Checkpoint通过不解析正文的元数据路径隔离为AGENT_CHECKPOINT_INVALID，页面只显示INVALID、禁用继续和回放，不尝试修补或执行损坏内容。

模型ordinal在HTTP前永久占用，工具stepKey在读取前永久占用。保存成功的工具结果可按原key/hash回放。未保存结果的CLAIMED/UNKNOWN步骤不再执行；模型可能已发送时保留UNKNOWN_HELD。模型结果已结算但在Checkpoint前丢失，也停止为AGENT_STEP_UNCERTAIN，不重新发送同ordinal。费用未知不是0，Redis租约过期不释放金融占用。

取消/等待过期不撤回已发送请求，也不释放UNKNOWN_HELD。不要直接删除调用记录、重置ordinal/slot、改回QUEUED或清空预算账户。需要新分析时使用新的创建操作并保留旧记录，不能假称跨HTTP/MySQL的exactly-once。

## 轨迹、保留与评测

`GET /api/copilot/review-analyses/{id}/trace`与`/view`均先重新授权。轨迹只读固定工具名、状态、哈希、revision、用量与估算，不执行模型/工具；SSE使用单调序号和Last-Event-ID补读，不含Prompt、工具正文或推理链。来源到期时清除新增Checkpoint/工具正文并撤销MCP能力，保留低敏审计/费用，标记不能完整回放。已发布分析结果沿用既有P4留存规则；清理快照不代表删除整个分析审计。

26个冻结案例及SHA256位于`tools/agent-evaluation/data/phase5-agent-v1*`。Java实际运行原生HTTP、Graph、MySQL和MCP组件；离线LangGraph消费同一工具Schema及场景输入，不读Core、不调用真实模型，expected标签不进入Graph状态。共同报告：

```powershell
uv run --project tools/agent-evaluation python -m finguard_agent_evaluation.phase5_agent --java-report target/phase5/java-cases.json --output target/phase5/cross-runtime-report.json
```

失败、缺失输出和未知费用均保留在26例分母中；缺少/重复案例使报告拒绝。精度场景使用18位小数与保守INSUFFICIENT_EVIDENCE，政策冲突用Core原生分金额隔离政策护栏；真实进程另验证项目政策CONFIRMED。协议/恢复一致性不等于真实建议正确率或语义检索质量。

当前状态以2026-11-04验收增补为准：原生输入上界已实现并独立复核，依赖兼容维护已完成且保留两条视图相关版本告警；首轮真实模型未通过，独立RQ批次已通过合成组件协议复验。真实人工校准、备用模型和Phase6部署门槛仍开放，不能由合成用例通过自动消除。

## 工具额度提示与独立复验

`agent-review-v2`接收服务端从已保存状态生成的EXECUTION_BUDGET：总工具次数、已用次数、剩余次数、本轮最多可选次数。自动检索及其重试计入总计4次；生成/修复轮本轮可选0次，即使分析还有剩余额度。模型不遵守提示时，原有硬限制仍拒绝超额计划。

首轮`phase5-native-20261104`的结果与一次性锁保留。新复验仅通过`run-phase5-native-qualification.ps1 -Requalification`选择固定的新批次、提案、12例数据、数据库和锁，禁止复用旧锁或重置旧分析。`-ValidateOnly -DependencyGate <已验证依赖记录>`只校验冻结输入，不导入凭据、不启动服务、不访问模型；实际执行另需`-Execute`且提案必须为AUTHORIZED_READY。发生未知费用立即停批，不自动重试。

本次复验状态和范围见[独立复验记录](../acceptance/2026-11-04-native-tool-budget-requalification.md)，该记录不会覆盖首轮失败。默认真实运行配置继续关闭。

# P5-1 只读捕获与工具基础验收

2026-11-03，入口e45450a，隔离分支codex/phase-5-agent-workflow。用户已授权按顺序实施，未提交或推送。

## 交付

- Agent显式配置下使用当前JWT确定性读取Core的身份、context和task detail。第二个业务端点仅保留五个任务字段，与context不一致则不创建运行；默认P4保持原路径。
- V13在创建事务中冻结agent-v1、事实快照哈希、当前发布索引和四工具槽。任务投影未新增业务事实，保持历史schema1，来源证明写入agent_run_policy。
- 共享三工具Schema、严格参数/资源范围、独立事务执行占用、同stepKey回放/冲突和四槽并发控制。未知结果不重试。
- 冻结索引检索增加来源、成员和内容校验；撤回索引、过期/删除/损坏快照和结果哈希异常拒绝读取/回放。已知指令覆盖检测不代表通用注入防御。

## 测试与进程证据

最终定向命令：

```powershell
mvn test '-Dtest=ToolExecutorTest,ToolExecutionIntegrationTest,PinnedKnowledgeRetrievalTest,AgentCaptureTest,HttpCoreTaskStateTest,HttpCoreClientTest,CreateAnalysisServiceTest,SnapshotCodecTest,KnowledgeRetrievalServiceTest,AnalysisCreationStoreIntegrationTest' '-DargLine=-Xmx512m' '-Dspring.test.context.cache.maxSize=1'
```

91 tests、0 failures/errors/skips，完整命令退出0。日志为本计划工作区p51-final-green.log；不是新跑Java全套。MySQL8.4.10 Testcontainers验证并发四槽、重投、拒绝、未知占用、精确18位小数、失效来源和原创建事务。

验收脚本相关Python测试11 passed。Copilot打包通过，生成新工作树所需target/e2e-classpath.txt后执行：

```powershell
$env:FINGUARD_COPILOT_AGENT_ENABLED='true'
./scripts/run-business-e2e.ps1 -LocalModelStub -CoreRoot C:/dev/finguard-core
```

runId `20261103155135`，analysisId `98d90e94-eb6b-4a17-ba78-10b75a579565`，**38/38检查通过，SUCCEEDED**。真实Core/Copilot/MySQL/RabbitMQ/隔离Redis/Qdrant，固定Embedding和本地HTTP模型替身。实际替身HTTP1次，重复投递不增加调用，Core业务/审计校验和不变，授权/UI/SSE恢复检查通过。报告见`target/business-e2e/20261103155135/report.json`。

进程结束后另查数据库：agent_run_policy为agent-v1、max_tool_calls=4、发布索引非空，policy snapshot hash与原快照相同（1/1）。JWT不被工具数据类型或策略表保存。

## 失败与修正

- 工具执行结果的DATETIME字段被JDBC Map读取为LocalDateTime，显式getTimestamp消除类型假设。
- 索引撤回后的成功结果回放曾未复查发布资格，新增回归先失败，再补验证通过。
- 两个快照测试夹具违反既有生命周期约束，改用未来Clock/合法删除标记，不改变生产约束。
- 首轮进程20261103133002被旧脚本latestVersion=12检查拒绝，改为核对源码所有迁移和实际结构；20261103133145因新工作树缺少e2e classpath在业务创建前失败。两份失败报告保留，不计入成功检查。
- 曾同时触发Maven重编译，导致早期类文件缺失；最终91项执行串行完成，早期失败结果未计为通过。

## 范围

这是只读工具基础验收。工具执行器当前由集成测试使用，公共工具端点及Worker/模型工具编排由后续包接入。此次进程流程验证额外捕获与原P4编排兼容，不冒充native Tool Calling、Graph、HITL或MCP。新增真实付费调用0；原P4质量门槛仍保留。

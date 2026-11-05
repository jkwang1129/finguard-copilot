# P5-3 有界原生工具编排验收

2026-11-03，隔离分支codex/phase-5-agent-workflow；无提交/推送，新增付费调用0。

独立AgentModelGateway实现原生工具协议：第一轮选择白名单工具或最终JSON，混合正文不发布，第二轮强制none并拒绝继续工具。每轮接P5-2占用/预算，所有工具接P5-1四槽执行器。初始检索不足最多补一次；格式/Schema可修复一次，事实、引用、政策、已知注入不修复。最终输出复用原有事实和政策核验。

默认runtime关闭；独立配置要求额外捕获开关和显式单分析总预算。Worker接管与持久化运行在P5-4接入，当前不称完整API Agent Runtime。

## 验收

```powershell
mvn test '-Dtest=AgentRuntimeConfigurationTest,AgentTwoTurnHttpIntegrationTest,AgentToolCallingTest,BoundedAgentRunnerTest,ModelAnalysisOrchestratorTest,CompatibleChatModelGatewayTest,StructuredAnalysisParserTest' '-DargLine=-Xmx512m'
```

**52 tests / 7 suites，0 failures/errors/skips，完整命令退出0**，结束2026-11-03 16:27:28 +08；本计划工作区p53-final-green.log。不是Java全套。

实际loopback TCP模型替身+MySQL8.4.10+真实工具执行器+固定Embedding/内存向量库运行：正常两轮HTTP恰好2次，第二轮tool_call_id配对，逐调用账本2条SETTLED、政策检索/任务读取工具结果2条；重复调用runner未增加HTTP。第二轮非法tool_calls也停在2次，没有第三轮。该集成使用受控Redis许可替身及索引目录替身，不冒充真实Core/MQ/Qdrant/Redis全链路。

其他用例覆盖工具名/参数/重复ID/legacy function_call、混合正文、64KiB读取限制、错误usage、工具剩余槽不足、预算未准入零模型调用、格式修复与事实不可修复、原P4仍拒绝工具字段。

## 失败与修正

新接口/配置先编译失败再实现。p53-injection-red.log曾复现已知注入被当格式错误修复并SUCCEEDED；Agent专用parseForAgent保留安全拒绝类别，原P4parse语义不变，新增用例及原解析器/编排回归全部通过。

## 单列门槛

旧真实模型Token上界证明限定两条文本消息，不覆盖工具定义/assistant/tool多轮。本轮只允许loopback18765/local-test-stub的明确工程fixture，非fixture在占用/HTTP前拒绝。真实native协议输入上界、供应商资格与费用预算仍待独立验收，不能沿用P4最终JSON输出证据。

P5-3无Checkpoint：重复runner只能拒绝再次付费，不提供成功续程。P5-4将增加节点/Checkpoint、崩溃恢复、HITL、事件与页面；本报告不提前宣称这些能力。

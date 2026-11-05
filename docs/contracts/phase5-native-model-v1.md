# Phase 5 Native Tool Calling 协议 v1

## 兼容与范围

独立AgentModelGateway使用原生tools/tool_calls/tool_call_id，P4的CompatibleChatModelGateway继续拒绝工具响应。仅deepseek候选，服务端配置固定endpoint/path；不接受模型给出的URL。第一轮tool_choice=auto，第二轮none且只接受最终候选。thinking显式disabled，拒绝非空reasoning_content，不保存或回传推理链。

协议形状依据[DeepSeek Chat Completions官方文档](https://api-docs.deepseek.com/api/create-chat-completion/)核对（2026-11-03）；文档说明工具参数仍需客户端验证。官方协议支持不等于本项目真实模型质量或计费上界已验收。

单响应读取上限65536字节，严格JSON/重复属性检测；仅一个assistant choice，finish_reason与分支一致，usage必须为非负整数。非空legacy function_call、重复/非法tool_call_id、未知工具和不合法参数拒绝。正文与tool_calls混合时只保留通过验证的工具选择，正文不作为结果发布或保存。

## 两轮与工具

初始政策检索经过同一ToolExecutor，检索不足最多补一次，两次均占四槽。第一轮返回工具批次时不得超过剩余槽；按固定stepKey顺序执行，tool_call_id与tool响应配对。第二轮若继续要求工具立即失败，无第三轮。

第一轮直接给最终候选时，仅JSON/Schema格式失败可用第二轮修复。已知注入、事实/金额/引用或审定政策错误直接失败。Agent使用parseForAgent保留安全错误类别；P4解析语义不变。修复只增加受控错误类别和原可信事实，不保存/复用原始失败正文。

每次调用先经P5-2独立占用和预算，再执行HTTP；用量验证后逐调用结算。协议失败有可信usage时记录失败用量，无可信usage则UNKNOWN_HELD。任何路径不自动切供应商或再发同一序号。

最终候选复用ModelAnalysisOrchestrator的Schema、精确事实、引用、Core大额事实/政策及审定政策护栏；不会因为工具成功就跳过建议核验。

## 显式配置与真实模型门槛

默认runtime关闭。启用需COPILOT_AGENT_ENABLED=true、COPILOT_AGENT_RUNTIME_ENABLED=true和显式COPILOT_AGENT_MAX_ANALYSIS_CNY，普通P4默认仍为单次调用。P5-3仅建立执行器Bean；Worker接管及持久Graph由P5-4完成，不能在此时声称公共API已提供完整Agent。

旧DeepseekTextTokenBound限定two-text-messages-json-object-thinking-disabled。原有证明不覆盖含工具定义、assistant工具批次和多条tool消息的协议。本轮只对固定loopback18765/local-test-stub工程夹具采用wire UTF8字节+1024的本地上限检查，超过32000拒绝；非fixture路径在占用及HTTP之前MODEL_CONFIGURATION_INVALID拒绝。

真实供应商开放须另补native协议Token上界依据、固定模型/价格/输入样本及调用/总金额上限，不能通过fixtureMode指向远程供应商绕过。当前没有已通过该资格的真实Agent候选，也没有新增付费调用。

2026-11-04增补：已实现并独立复核[原生输入上界](phase5-native-input-bound-v1.md)，真实路径需要显式版本、固定2/8CNY价格及短期有效窗口；历史fixture/单调用策略形状保持兼容。已按用户明确授权完成14次真实调用，12例中11例通过，严格资格仍未通过；不能继续沿用上述历史“没有新增付费调用”的状态。见[实际验证记录](../acceptance/2026-11-04-native-provider-qualification.md)。默认真实运行仍未启用。

后续独立复验增补：`agent-review-v2`使用从持久状态生成的EXECUTION_BUDGET，自动检索/重试计入4个工具槽，生成/修复轮本轮额度为0。新RQ批次13次真实调用、12/12通过（8个有效输出、4个零HTTP负例），估算0.089994CNY、未知持有0；[复验记录](../acceptance/2026-11-04-native-tool-budget-requalification.md)仅关闭合成native组件协议范围。首轮报告和锁保持历史原样；人工/生产质量与部署门槛不因此关闭。

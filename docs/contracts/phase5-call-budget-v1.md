# Phase 5 逐调用预算契约 v1

P5-2仅扩展预算与投影，模型/Graph接入在后续包。V1–V12及P4的单调用表/路径保持不变，不把历史运行迁移成Agent。

## 占用与版本

V14新增agent_model_policy、agent_model_call、agent_model_budget_allocation和agent_model_budget_event。调用键为analysisId+callOrdinal，ordinal仅1/2，RabbitMQ投递attempt是另一维度。调用1的已验证请求成功且SETTLED后才允许调用2；UNKNOWN、NOT_SENT或请求失败均不能以另一序号自动重试。所有占用在HTTP前独立提交，未明确发送的金额可以释放，但原序号永久保留。

服务端准备PreparedAgentCall，绑定输入哈希、模型/Prompt版本、冻结索引、共享工具Schema哈希、Token上界、价格表/有效期、单调用/单分析/审核员每日/供应商每日/全局每日限额；其中没有JWT、密钥或原始Prompt。限额必须显式提供，不能用旧单次限额乘二。Agent v1只准入deepseek候选，真实Tool Calling资格仍需独立验收；本包不启用任何备用供应商。

模型、Prompt、索引、工具Schema及限额的运行策略首调用冻结，后续不得改变；各调用独立冻结输入与价格。价格为CNY，真实模式的费率必须为正，零费率只用于明确fixture。每调用输入<=32000、输出<=2000；预留按上下界向上取整至8位人民币，实耗按既有计算器结算，不超过预留。

## 钱、配额与不确定性

Redis先取得短期供应商并发/审核员配额，MySQL再锁运行和按固定顺序锁预算账户，原子写占用和预留。Agent分析使用独立agent-analysis账户，日账户复用P4同一命名，不能分开绕过全局额度。Redis不可达不创建新调用；租约不覆盖连接与响应期限时释放已知未发送金额，并保留NOT_SENT序号。

持久Graph使用带`AgentExecutionFence(owner,revision)`的内部准入重载；在分析父行锁内核对Checkpoint非等待、owner/revision相符、租约未过期，再领取ordinal。租约失效返回NOOP，不创建调用占用，也不失败化新owner。无fence重载仅用于未开启持久运行且没有Checkpoint的基础组件；不能用它绕过Graph授权。执行权不属于模型/Prompt/价格的冻结策略，节点续租和接管不改变调用清单。

记录用量/结算/预算追加事件在同一独立事务内。失败回滚后仍保留原提交的调用占用和预留；不能删除占用重发。UNKNOWN_HELD不释放，不能切供应商或续程。重复相同结果幂等，冲突结果拒绝；UNKNOWN后转成NOT_SENT或覆盖为成功也拒绝，账单核对需将来独立的追加核对流程。

## 投影与兼容

汇总费用仅在已确定结算时显示数值；RESERVED、UNKNOWN_HELD及仅NOT_SENT不伪装成实际0费用。持有金额、已用调用数及每调用用量分别保留。原P4页面和账本路径不读取两次调用语义。V12升级时不生成任何Agent策略或调用，不改变旧V6占用。

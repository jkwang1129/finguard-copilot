# ADR-0013：Graph节点与MySQL恢复真相

- 状态：本地工程验收通过，真实供应商资格另列门槛。
- 日期：2026-11-03。

使用当前BOM固定的spring-ai-alibaba-graph-core1.1.2.2，保持Java17/Boot3.5.8。已检查类文件major61，并在实际项目跑通条件循环。CompileConfig默认带MemorySaver，必须显式配置空SaverConfig；框架不再维护另一份恢复状态。Graph框架日志关闭，应用只导出自身受控阶段。

Graph由RETRIEVE、PLAN、TOOLS、GENERATE、REPAIR、COMPLETE、DONE节点组成；条件边表达一次检索补查、工具批次、直接候选、仅格式修复、人工门槛和失败。原P4路径保持不变，只有显式runtime开关且无旧V6占用的Agent策略运行才由新Graph接管。

每步只消费已授权不可变快照和已验证Working Memory。返回的原始候选在节点内校验；Checkpoint不保存失败正文、完整Prompt或推理链。持久化的工具选择必须通过共享Schema，工具结果已经经过来源/内容校验；最终候选在COMPLETE节点再核验。

MySQL analysis_checkpoint保存版本、revision、nextNode、Working Memory哈希、来源/索引、调用计数、持有者和等待期限。节点完成时在同一事务更新Checkpoint、运行状态、受控事件及下一条Outbox。工具/模型HTTP不进入长事务。CAS不仅检查revision，还检查owner及租约，防止过期执行者覆盖新执行者。

外部模型/工具领取也在分析父行锁内核对服务器注入的owner/revision/有效租约；不能仅依靠末尾CAS，否则暂停的旧Worker可能在接管后仍消费预算。失去执行权为NOOP，过期owner也无权失败化运行。损坏Checkpoint的隔离使用原始ownership元数据，拒绝再次解析损坏Working Memory；轨迹降为INVALID且禁止回放/继续。

执行租约默认2分钟，每次节点提交续租；恢复扫描默认10秒。即使重复MQ消息在执行者仍活跃时被确认，过期租约也会生成一次持久Outbox唤醒，不依赖仅内存线程或尚未ACK的旧消息。模型/工具永久占用不因租约过期而释放；无法确定结果的外部步骤停止续程并保留金额，不宣称跨HTTP/MySQL的exactly-once。

人工门槛由服务端在工具执行前启用，最多等待24小时且不晚于源快照期限。CONTINUE/CANCEL需要新的Core授权，复查运行归属、revision、等待状态/期限和当前事实。语义哈希排除capturedAt，但仍比较任务版本、状态、金额及其余规范化事实；变化时旧运行失败，需要新的analysisId，不覆盖旧快照或重置预算。

原计划的compareAndAdvance(UUID,long,AgentStepOutcome)不足以表达持有者隔离和Working Memory。实施接口显式增加owner、AgentWorkingState、waiting，以及可选当前deliveryId；这是满足原设计的必要接口细化，调用者均位于本阶段新代码内。

消息仍使用schema1的ANALYSIS_REQUESTED，节点和模型序号均从MySQL读取；MQ重试attempt不是模型调用次数。原快照到期时将清除Checkpoint/工具内容并标记无法完整回放，低敏状态、哈希和费用继续保留。

真实供应商的native Token上界和付费资格仍是单列门槛；本ADR及框架兼容测试不替代它们。

# Phase 5 只读工具执行契约 v1

P5-1能力基础，尚非完整Agent运行时。默认P4路径不变；`finguard.copilot.agent.enabled=true`启用额外任务状态核对与`agent-v1`策略冻结。创建请求仍以当前JWT同步完成身份与事实捕获，后台不持有JWT。

## 来源与范围

创建时固定读取auth/me、review task context、review task detail，最多三次HTTP，不自动重试。detail只投影id/version/status/ruleCode/reasonCode，必须与context一致；不保留decisionNote及reviewedBy。它们未提供新业务字段，因此继续使用schema1事实快照，V13的agent_run_policy保存工作流、原快照哈希、创建时已发布索引及四槽限制。无已发布索引时index为null，事实工具仍可用，政策工具拒绝。

工具Schema唯一来源为`src/main/resources/schema/agent-tools-v1.schema.json`。get_review_facts、get_task_state仅接受空对象；search_review_policy要求query（1–256 Unicode字符）和topK（1–5整数），拒绝额外字段、URL、已知指令覆盖及密钥形式。服务端注入analysisId、reviewer、snapshotHash、indexVersion和stepKey，不将这些范围字段开放给模型。

## 四槽与恢复

ToolExecutor先在独立MySQL事务中锁分析行，验证归属、agent策略、原快照哈希/有效期与冻结索引，再分配唯一的slot1–4和stepKey。合法执行与Schema拒绝均占槽；同stepKey参数或工具变更为冲突。非法身份/范围不分配槽。构造请求在进入执行器前另有长度限制。

P5-4接入后，Graph还须注入owner/revision执行权；同一领取事务检查当前Checkpoint版本、持有者和有效租约。过期或被接管的执行者在读取前被拒绝，不能消耗槽。P5-5 MCP使用独立capability/revision授权，两种授权不能组合。旧五参数构造器只适用于没有Checkpoint且未冻结runtime的基础能力运行。

实际执行不占数据库长事务。成功结果保存最小脱敏JSON、结果哈希及完成时间；重投只返回已保存且校验通过的结果。CLAIMED/UNKNOWN/REJECTED均不再执行，相同消息再次投递不能作为重试授权。跨数据库和工具访问不宣称exactly-once；发送后崩溃、返回前数据库失败仍可能留下不确定占用，后续不会盲目执行。

事实结果使用精确BigDecimal，不经过浮点序列化；政策结果只查询冻结且仍PUBLISHED的索引，检查索引成员、片段来源及内容校验和。索引已撤回、快照过期/删除/损坏或结果哈希不匹配均拒绝回放。工具返回不等于最终建议通过；后续P5-3必须再次执行事实、引用与审定政策校验。

## 交付边界

P5-1不开放公共工具HTTP入口，不更改模型协议，不接管原Worker编排，不提供后台实时Core查询或业务写操作。ToolExecutor将由后续Graph/MCP复用；创建期的固定身份/事实读取不算Agent工具槽，运行时所有工具（含检索重试）都必须经过同一四槽执行器。未知注入仍可能漏报，已知模式检测不代表通用防护。

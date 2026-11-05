# Phase5 总验收

分支 `codex/phase-5-agent-workflow`，基线 `e45450a122bcd0cee2ec7f237886e8ae32a34362`。本报告区分本地工程能力、真实供应商资格、人工质量与部署条件。未提交或推送，未发生付费供应商请求。

当前状态：P5-1～P5-6本地工程实现与总验收完成；真实native供应商、真实质量与部署门槛仍开放。见[机器状态](phase5/status.json)。

## 已实现的工程范围

P5-1共享只读工具/严格Schema及四槽审计；P5-2独立两次模型调用账本与共享预算；P5-3原生tool_calls两轮协议和既有事实/政策护栏；P5-4 MySQL持久Graph、Outbox、租约/CAS、人工暂停/新授权继续、审核台与轨迹；P5-5本地STDIO MCP能力租约；P5-6冻结案例、跨运行时参考、真实本地进程与故障验收。

逐包证据见[P5-1](2026-11-03-p5-1-readonly-tools.md)、[P5-2](2026-11-03-p5-2-call-budget.md)、[P5-3](2026-11-03-p5-3-native-tool-calling.md)、[P5-4](2026-11-03-p5-4-durable-graph.md)、[P5-5](2026-11-03-p5-5-local-mcp.md)。运行及恢复见[操作手册](../operations/phase5-agent-workflow.md)。

## 冻结评测与证据界限

26个案例在评测实现前冻结，数据SHA256为`01c883a77cbefad1af0c4d458df6003deaeddb8a0208762d385d383092a415ed`；共享工具Schema SHA256为`e0401e9bcd95bd5290d8184e89b2aa97ed164f6fc697f49b3af8780931c7d80f`。Java使用实际原生HTTP、Graph、SQL账本/工具/HITL/MCP组件；离线LangGraph使用同一Schema及合成场景，标签不进入Graph状态，不读取生产Core。

协议与恢复匹配率保留全部26例分母；失败、等待、缺少输出和未知费用不会被移除。合成结果主要验证保守INSUFFICIENT_EVIDENCE；进程场景另验证项目政策的方向护栏。组件替身结果不能证明真实建议正确率、检索质量、生产吞吐或费用。

最终[跨运行时报告](phase5/cross-runtime-report.json)及[Java原始组件结果](phase5/java-cases.json)：26/26契约匹配，两端一致。7例存在已验证发布结果，其余19例为预期失败/等待等，事实/引用/建议质量不虚记为已验证。工具协议9/9、恢复契约7/7适用例匹配，其余例仍列为不可评估。28次模型占用/替身HTTP尝试、34个工具回执；已知替身估算0.00000000CNY，1例未知/待定，4例未记录调用费用，不代表真实供应商成本。

## 最终验证

最终Java全套535/535，零失败/错误/跳过，2026-11-03 23:31:34 +08退出0；Python187/187；MCP桥4/4；前端18/18、lint/build通过。最终打包与依赖classpath构建成功，所有验证命令退出0。打包使用skipTests，完整测试证据来自此前同一源码的535项全套，二者不混称。

最终制品批次`20261103233311`为[87/87通过](phase5/process-matrix-20261103233311.json)，包含13个原生分支、10个真实JVM退出窗口、Redis/AMQP中断及恢复、runtime开关隔离、MCP互通、SSE和Core业务表校验和。SDK1.30.0实际协商协议2025-11-25；OpenAPI区分Core JWT和MCP capability授权。未增加外部模型/工具执行，未知调用持有金额不释放，Core业务表校验和前后一致。验收进程/自有容器已停止，数据库和vhost保留；共享MySQL/RabbitMQ/Redis未停止。

前一进程批次`20261103214530`为86/86，发生在最终依赖/Prompt/租约修复前，仅保留为历史证据。最新报告绑定最终Copilot/Core JAR SHA256及验收脚本哈希。

## 一次独立复核及修复

独立只读复核覆盖155个变更文件的不可变快照：0 Critical、3 Important、0 Minor。三项均接受，按一轮修复执行，无第二次复核：

1. 来源到期时，冻结Agent的QUEUED且无Checkpoint任务仍被延后：补充终结和停用延后时的来源检查。
2. 旧Worker在租约接管后仍可领取外部调用：将owner/revision/租约校验移至父行锁下的模型/工具领取事务；失去执行权返回NOOP。
3. 无owner损坏Checkpoint持续重投：使用不解析损坏内容的元数据隔离路径，保留回执，并显示不可回放的INVALID轨迹。

`final-review-red.log`中39项出现5处预期断言失败，确认上述缺陷及过期owner失败化边界；`final-review-green.log`定向88/88通过，最终完整套件535/535通过，包含补充的owner/revision/expiry正反例、Graph执行权传递与INVALID轨迹。没有再派第二次复核。旧迁移测试的历史版本断言更新至V16，原V1/V2校验和断言保留。

## 依赖与秘密扫描

[依赖审计](phase5/dependency-audit.json)查询252个Maven/Python包；24个公告匹配均为[基线已有版本](phase5/dependency-baseline.json)，新引入匹配为0。这不等于24项不可利用，部署前仍须单独处置。Graph固定1.1.2.2，移除无用传输/客户端依赖，因框架静态序列化器保留已修复1.1.6消息DTO模块；Java17兼容测试覆盖这一覆盖版本。MCP SDK升级并锁定1.30.0，pytest升级至9.x。npm审计0。

秘密扫描的9个原始匹配均为基线相同的公开分词器校验串或确定性测试凭据，见[逐项归类](phase5/secret-triage.json)。最终源码导出再次扫描仍为相同9项，逐文件确认与基线无diff，新增0。代码/测试/验收脚本418项校验值在完整测试期间未变；旧迁移及历史冻结数据19项未变。

## 仍未通过的独立门槛

- 真实native输入Token上界和供应商资格：当前发送前拒绝真实供应商，原P4两条文本消息的上界不适用于工具对话。没有付费调用授权，[资格批次提案](phase5-provider-qualification-proposal.json)仍为NOT_AUTHORIZED_NOT_RUN；12个合成案例/最多24次/总额2.00CNY是提案上限，不代表已经获批。
- 真实人工校准、模型质量、备用模型与容量：未以合成协议结果替代，保持独立待办。
- Phase6部署、继承依赖公告处置、环境权限与运营验收：未执行上线。

所有测试日志、复核源快照和决策ledger保存在本工作树gitignored的`.superpowers/sdd/2026-11-03-phase5-agent-workflow/`。未获提交授权，保留证据目录及未提交变更。

完整[实施裁定与代价](phase5/implementation-rulings.md)按发生顺序保留；独立复核没有Minor遗留项。

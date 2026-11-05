# P5-2 逐调用预算与兼容验收

2026-11-03，继续隔离分支codex/phase-5-agent-workflow，未提交/推送，新增付费调用0。

V14增加独立Agent运行策略、按analysisId+callOrdinal占用、逐调用用量/结算、账户分配和追加事件。最多两次调用，第二调用只在第一请求成功且已结算后准入；第三次、重复序号、策略变更、终态/非Agent运行均拒绝。P4原单调用表及路径保持不变，共享同一日预算账户，另加Agent分析总上限。

Redis先取得短期许可再取得独立事务中的金额/调用占用；Redis故障不创建调用，已取得占用但可证明未发送时释放金额且保留序号。UNKNOWN_HELD不释放、不重发、不切供应商、不续程。金额与Token投影保留精确值，未知费用和仅未发送场景不显示实际0费用。

## 新跑验收

```powershell
mvn test '-Dtest=AgentCallLedgerIntegrationTest,AgentLedgerMigrationTest,AgentPageCostTest,AnalysisPageServiceTest,GovernedWorkerRaceIntegrationTest,ModelUsageRecorderIntegrationTest' '-DargLine=-Xmx512m' '-Dspring.test.context.cache.maxSize=1'
```

**19 tests / 6 suites、0 failures/errors/skips、完整命令退出0**，结束2026-11-03 16:08:11 +08，日志p52-final-green.log（本计划工作区）。包括真实MySQL8.4.10双Worker抢占、逐调用不覆盖、总预算、未知/未发送、结算事件事务失败后占用保留、Redis准入失败/失效租约、P4历史预算/用量和页面兼容。Redis在新增准入测试中使用受控替身，未将此项称为新增真实Redis故障进程演练。

AgentLedgerMigrationTest先迁移至V12并创建旧V6占用，再升级当前V14，证明旧占用保留、无Agent策略/调用被自动生成。此前P5-1进程验收不冒充V14新制品HTTP验收；逐调用HTTP编排将在P5-3/P5-4和总验收覆盖。

## RED→GREEN与决定

- 新类型/接口先产生编译失败，再实现。
- p52-outcome-red.log中两个断言失败证明：仅NOT_SENT曾显示0费用，失败请求曾允许消耗第二序号；增加明确outcome_type及费用投影后通过。
- p52-page-red.log证明新聚合适配器缺失；p52-compatibility-red.log证明捕获专用运行被错误显示为NOT_RECORDED；现在仅存在新Agent调用时切换聚合投影，旧账本仍正确显示。
- 触发器测试容器增加既有的log-bin-trust-function-creators测试配置；FAILED夹具补受控failure_code。保持原数据库约束。
- 为保留P4保证，不改旧BudgetReservations/CallAdmission/ModelCallReservations/ModelUsageRecorder，新增独立Agent实现并共享日账户。其Trace/指标将在P5-4接入，逐调用明细目前保留在账本中。

本包只完成预算/恢复基础与聚合页面适配，未证明真实供应商原生工具资格，不包含Graph/HITL/MCP及生产质量门槛。

# P4-3 入口三项短收尾（2026-11-02）

范围：整理已有工程状态并冻结可复现快照、复测当前进程链路、冻结下一包的调用/费用契约。**不把 P4-2 真实人工校准改为已完成，也不把 P4-3 设计文档当作预算/Redis/路由实现。**

## 1. 任务与版本基线

已在 [TASKS.md](../../TASKS.md) 拆分已实现工程能力、质量退出条件及未来工具执行任务；历史报告保留原日期/结果。已知 H03 合成回归、原16例16/16及独立24例24/24有测试证据；LangGraph参考两项漏报、缺真实人工标签及固定 Embedding 等限制继续可见。

Copilot 分支 `codex/phase-4-first-batch`，HEAD `4f159937e88af374d6f264bb7c70040f1205cf2b`；当前含未提交和未跟踪的 Phase 4 文件。Core 使用干净的隔离 checkout `C:/dev/worktrees/finguard-core-phase1`，HEAD `d25770a6ae013266f34f87ac0aa48852f3af4098`。本次未提交、推送、合并或发布。

[内容快照](2026-11-02-p4-3-entry-baseline.json) 记录源码/测试/脚本/评测数据的逐文件 SHA-256、清单摘要、文档与验收报告哈希、两个本次打包 JAR 的 SHA-256。它是本地可核对快照，不是不可篡改签名、Git提交或部署发布；运行期版本别名的完整不可变治理由 P4-3 实现。

## 2. 最新链路冒烟

本次重新执行两个 checkout 的 `mvn -q -DskipTests package`；Copilot 另生成 E2E classpath。全部场景使用同一对新 JAR，真实 MySQL / RabbitMQ / Core HTTP / Copilot HTTP / Worker / Qdrant 已发布知识；Embedding 为 `fixed-v1`。Core任务为SQL种入的隔离合成数据，未覆盖完整CSV导入/最终审核提交。

| 场景 | 模型 | runId | 结果 |
| --- | --- | --- | --- |
| 正常控制（增强ACK证据） | 本地HTTP替身 | 20261102140817 | SUCCEEDED，20/20检查 |
| 真实正常路径 | DeepSeek deepseek-flash | 20261102100439 | SUCCEEDED / CONFIRMED，20/20检查 |
| 错误政策方向建议 | 本地HTTP替身 | 20261102141042 | FAILED / FACT_VALIDATION_FAILED，21/21检查 |
| Core历史命中事实矛盾 | 本地HTTP替身 | 20261102141131 | FAILED / FACT_VALIDATION_FAILED，21/21检查 |
| 缺少适用已发布政策 | 本地HTTP替身 | 20261102141221 | FAILED / FACT_VALIDATION_FAILED，21/21检查 |

报告归档至 [p4-3-entry](p4-3-entry/)。每个场景检查迁移到V8及两个LONGTEXT精确JSON列；401/403、重复幂等键；等待真实终态后检查账本1条、持久调用占用1条、Outbox与Worker回执；检查Core任务仍PENDING且业务/审计表CHECKSUM不变。

终态后通过RabbitMQ管理API重新投递原消息，检查账本CHECKSUM及回执/调用占用数量不变。最初仅按队列排空判断，独立复核指出管理统计滞后或死信也可能排空；已补先确认初次ACK，再等待ACK由1增至2并检查死信数量不增加的证据。增强检查用于四次本地HTTP替身复验；**真实DeepSeek报告保留原始20项记录，其重投仅有排空证据，未额外付费重跑，不能单独声称有ACK增强证据。** 本次终态重投也不代替崩溃窗口的全部故障测试。

增强检查曾将正常失败路径已有的1条死信误判成重投异常；[该失败报告](p4-3-entry/ack-harness-failure.json)保留。已改为比较重投前后死信数量，拒绝场景1→1、正常场景0→0。未修改业务Worker或隐藏该次失败。

真实 DeepSeek 仅1次调用，输入2018 Token、输出709 Token，调用账本耗时3824ms。按本次冻结的高峰/输入缓存未命中人民币价格估算 **0.00970800元**，本次预算0.10元；32k/2k配置下预检查估算上界0.08元。价格来自[供应商官方价格表](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)（2026-11-02核对，输入2/输出8元每百万Token）。这是估算而非实际账单，严格Token上界证明及并发预算控制仍属P4-3。此前有记录的Phase4估算0.015636元，加本次为0.025344元，低于用户累计10元许可；不将历史未定价记录重写为此价格。

测试结束停止本次启动的两个Java进程及隔离Qdrant/Redis容器；共享MySQL/RabbitMQ/Redis保留运行。隔离数据库、RabbitMQ vhost和已停止容器保留供核查，未清空用户资源。

## 3. P4-3 调用/费用契约

[契约v1](../contracts/phase4-3-call-budget-v1.md) 与 [ADR-0012](../adr/0012-phase4-call-reservation-and-budget.md) 已冻结。核心约束：每分析最多一次付费请求；版本与内容哈希关联；CNY精确十进制预留；MySQL原子取得预算与调用占用；Redis配额/持有者租约失效时保守拒绝；调用结果不确定保留费用占用且不自动重试/切供应商。无合格备用模型时使用单主模型资格集。

下一步按契约顺序实施 **P4-3.1 不可变运行清单与价格版本 → P4-3.2 MySQL预留/结算 → P4-3.3 Redis许可 → P4-3.4 路由与故障 → P4-3.5 验收**。本次只建立入口，不能勾选这些实现任务。

## 验证与限制

- 本次 Python 全套 `uv run --project tools/agent-evaluation pytest tools/agent-evaluation/tests -q`：**172 passed**，新增的预算不足/不完整价格及ACK证据覆盖曾先失败后通过；普通测试不调用付费模型。
- Java **333项 / 0失败 / 0错误 / 0跳过**为同日[七项修复记录](2026-11-02-p4-2-seven-fixes.md)的完整测试结果，本次未复跑Java全套；本次重新打包并验证进程链路，不冒充新的333项运行。
- 前端本次未修改，未重跑历史前端测试/audit；生产模型大规模质量、真实Embedding、真实人工校准和部署演练仍未验收。
- PowerShell语法及 `git diff --check` 通过；链接和快照按归档文件逐项核对。独立只读复核发现的ACK证据缺口已按先失败后通过的测试及本地进程复验修正，未发现其他契约/清单阻塞；不将一次复核当作全面安全保证。

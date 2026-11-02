# P4-3 成本与供应商治理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 用户已授权按已说明顺序推进，沿用当前隔离工作区；不再次请求执行许可，不提交或推送。

**Goal:** 调用前冻结版本、限制费用和并发，调用后可结算/核对，保持每分析最多一次付费请求。
**Architecture:** 在现有 Worker/Orchestrator 上增加调用前治理；MySQL保存不可变清单、预算和调用占用，Redis管理短期许可。DeepSeek单主模型，未知调用不重试或切供应商。
**Tech Stack:** Java17/Spring JDBC/MySQL8.4/Redis、JUnit/Testcontainers、现有Python评测。
**Spec:** [调用与费用契约v1](../../contracts/phase4-3-call-budget-v1.md)、[ADR0012](../../adr/0012-phase4-call-reservation-and-budget.md)。

## Global Constraints

- 保持每个analysisId最多一次付费请求；V1–V8不修改，仅追加迁移。
- 金额CNY精确十进制，预留CEILING到8位；未知费用不能记0或按TTL释放。
- 无合格备用模型时不自动切换；普通测试离线，不要求真实密钥。
- 清单不保存JWT、密钥、Prompt、动态事实正文。保留入口快照与旧报告，不覆盖历史。
- 不提交/推送。此前10元累计许可保留，真实复测另记录次数与估算。

## Review Focus

- 同版本不同内容并发注册：只有一个内容可生效，冲突调用0次（Task1 MySQL并发测试）。
- Unicode/JSON转义/Schema影响输入量：按最终两消息请求计算保守字节上界，不能沿用字符启发式（Task1）。
- 索引在检索后切换、成员/正文被修改：调用前校验实际成员与引用，不拼混版本（Task1）。
- 提交占用后的崩溃/超时/账本故障：不再发供应商请求，预留留待核对（Task2/4）。
- Redis旧持有者释放新许可、失效租约/断连：不放行新请求、不释放未知费用（Task3/4）。

## Task 1: P4-3.1 不可变运行清单及价格版本

Files: 新增`governance/{CanonicalJson,VersionedArtifactStore,RunManifestService,DeepseekTextTokenBound,GovernanceProperties,ModelCallPreflight}.java`、V9迁移及对应unit/MySQL集成测试；修改ModelAnalysisOrchestrator、RealModelConfiguration、AnalysisWorker和ErrorCode。
Interfaces: `ModelCallPreflight.prepare(UUID,FactSnapshot,ModelRequest,List<KnowledgeEvidence>,String retrievedIndexVersion): ModelRequest`，返回内容版本已绑定的请求；`VersionedArtifactStore.freeze(UUID,ObjectNode,List<Artifact>)`原子登记版本与清单。检索返回批次固定索引版本，即使结果为空也不能关联到稍后切换的新索引。

- [x] RED：新增能力缺失及Unicode错误替换先失败；价格/Token/版本/隐私/索引成员测试随后通过。
- [x] 实现canonical JSON/精确报价、DeepSeek两条纯文本消息的保守输入界限，绑定供应商公开编码证据的提交/哈希；其他协议拒绝。
- [x] 实现V9版本目录与单分析清单；版本首次登记后只读，同名内容冲突拒绝，所有登记和清单原子提交。
- [x] 接入真实profile编排，绑定Prompt/Schema/model/index/embedding/policy/price/route/budget内容；拒绝发生在HTTP前，有明确终态且不伪造用量。
- [x] 定向Java/MySQL测试GREEN，Java全套343项、0失败/错误/跳过，日志target/p4-3-java.log；此为Task1工程门禁，真实进程验收仍归Task5。

## Task 2: P4-3.2 MySQL预算预留与结算

Files: `governance/BudgetReservations.java`、V10、`ModelUsageRecorder`、`AnalysisOrchestrator`/Worker调用占用边界及配置。接口：`acquire(UUID):boolean`、`settle(UUID,ModelUsage):boolean`、`markUnknown(UUID,String)`、`releaseNotSent(UUID)`、`frozen(UUID):FrozenCall`。
- [x] RED：新增预算/冻结价格账本接口先缺失；多分析竞争预算、重复结算、未知调用与数据库写入故障测试。
- [x] V10增加预算账户/预留/追加事件；预算预留与已有V6调用占用同事务取得。
- [x] 用量可信按冻结价格结算，未知保留且不创建0元账本；结算与用量写入同事务，失败回滚保留预留。
- [x] MySQL8项与相关编排/Worker/配置共52项通过，target/p4-3-2-targeted-final.log；最终全套/真实进程归Task5。

## Task 3: P4-3.3 Redis配额与供应商租约

Files: `RedisProviderPermits`、`PermitProperties`、`CallAdmission`、共享真实profile配置及`ModelCallPreflight.afterCall`。接口：`acquire(reviewerKey,timeout):Lease`、`valid(Lease,requiredRemaining):boolean`、`release(Lease)`；`CallAdmission.open(UUID)/close(UUID)`。
- [x] RED→GREEN实现Redis Lua原子分钟配额及供应商持有者租约；Redis服务器时间，不使用客户端时间决定许可。
- [x] 独立Redis测试验证额度耗尽、过期旧持有者不能释放新许可、配额不因释放重置；断连保守拒绝。
- [x] admission单测覆盖Redis失败不取预算/调用占用、期限不足释放已证明未发送金额、未知DB提交不释放金额、并发重复不释放别的持有者。
- [x] 真实profile按Redis→MySQL占用→发送前许可核验接入；HTTP返回/失败均归还短期许可，不以租约TTL释放预算。Task4继续核对完整Worker故障分支。

## Task 4: P4-3.4 单主模型路由及故障矩阵

Files: 既有单主模型配置、RunManifestService/CallAdmission、Worker受控失败分支、BudgetReservations核对端口及对应测试；不增设虚构的备用模型。
- [x] 冻结清单记录单主模型资格与选择理由；预算/配置/容量失败有受控终态；现有Gateway覆盖HTTP超时/429/5xx。
- [x] 请求发送后不自动重试/换供应商；UNKNOWN_HELD保留；新增可信离线核对入口`reconcileConfirmedCost`，追加供应商账单修正，超额事实保留并阻止后续申请，无公开审核员调用入口。
- [x] RED→GREEN：已取调用占用后的许可丢失直接终态；未知用量不能产生成功账本/可用结果；重复账本不重复累计调用指标。账本与费用原子性及供应商修正测试通过。
- [x] 存量调用恢复增加由连接/响应期限及30秒清理余量推导的等待窗口与有限探测次数，防时钟停止/attempt溢出；定向已通过，最终全套继续验证。

## Task 5: P4-3.5 综合验收

- [x] Java371/Python175全套通过；最终同JAR完成6替身+1真实进程场景、176项检查；并发/账本故障由真实MySQL定向套件分别证明。前端本包未变更，不重跑历史前端验证。
- [x] 两批各最多1次/0.10元；第一批估算0.00984000元、最终批次0.01002600元，合计0.01986600元。加此前P4估算累计0.04521000元，低于原10元授权，不重复原analysisId。
- [x] 一次独立代码复核及此前未审关联字段的补充静态检查完成；3项Important均RED→GREEN修复，最终全套通过。原复核者独立核对最终制品/归档，状态更新后主执行者重算文档哈希，保留人工校准/真实Embedding等原缺口。

## 执行记录

- 预检：当前分支`codex/phase-4-first-batch`，既有改动保留。Task1产出清单/报价为Task2输入，Task2原子占用为Task3/4调用前提；现有Worker在组装上下文之前取得V6占用，Task2必须将此位置调整为准备请求之后。
- 工程选择：直接集成现有Java/JDBC边界，减少并行记账路径；Task1暂不改变预算状态机。Token上界限于已核对的DeepSeek文本/BPE公开协议假设，来源和适用限制另存文档，不宣称证明供应商内部实现。
- Task1 complete：V9隔离MySQL迁移/同名版本并发冲突/清单回滚和隐私通过，343项全套退出0；未付费调用、未操作生产库。继续Task2。
- Task2 complete：预算跨scope原子预留/单次占用、冻结价格结算、重复/冲突、未知费用、写账本故障回滚通过。故障注入初用trigger被隔离MySQL权限拒绝，改用测试库CHECK约束注入并恢复，未放宽数据库权限。UTC自然日预算默认审核员1元、供应商/全局各10元，单次0.10；同日更改已有账户额度拒绝，不隐式重置。
- Final review：一次独立复核3项Important、0项Critical；4个缺口先在真实MySQL定向测试失败，再修复。共同行锁协调终态与准入，本次占用使用专门异常证明，未持有者只等待现有调用；外层护栏账本故障先记录未知费用；超界观测保留具体值/差异并幂等追加。最终定向27项通过，target/p4-3-review-verified.log，待最终全套/制品验收；不再做第二轮代码复核。
- Task5 complete：补供应商请求id/model哈希关联及成功/错误/幂等用例后，最终371项Java、175项Python及7个同制品进程场景通过。验收、费用和哈希快照归档，原入口/失败/历史记录保留；未提交或推送。

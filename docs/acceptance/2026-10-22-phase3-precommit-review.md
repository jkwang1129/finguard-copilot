# 2026-10-22 Phase 3 提交前检查

> 后续状态（2026-10-24）：下列阻塞已按[异常路径修复验收](2026-10-24-phase3-failure-recovery.md)处理；本文件保留当时暂缓提交的审查依据。

## 决定：暂缓提交，不合并、不推送

用户授权检查并提交现有实现，并按适宜性决定合并/推送。独立只读审查及执行者源码核查发现下面三条 Important 异常路径，属于 Phase 3 已承诺的超时、调用预算与失败恢复边界，不是要求提前实现 Phase 4。因此暂缓把当前代码提交为完成版本，先请求确认修复范围。

这些结论来自静态源码及控制流/事务边界核查，尚未新增故障注入复现。既有正常路径 E2E、质量报告和冻结数据仍保留，不因本次发现而改写。此前“阶段任务完成”的表述不足以覆盖这三条路径；Phase 3 最终收尾应等待修复与回归。

## 提交阻塞项

### 1. 配置的模型 HTTP 超时没有接入请求客户端

`CompatibleChatModelGateway.java:41` 创建 RestClient 时只设置 base URL 和认证头；`RealModelConfiguration.java:47` 传入原始 `RestClient.builder()`。`model.timeout()` 没有用于连接/读取超时设置。因此环境变量配置的 20s/60s 不是当前 HTTP 调用的有效期限；模型请求还发生在 Worker 数据库事务内，慢请求会延长事务和行锁占用。

修复验收：为请求工厂明确配置连接/读取超时，以本地可控慢响应或等价离线测试证明配置生效；不能只用 Mock 抛异常证明存在真实超时约束。

### 2. 模型成功后的持久化失败可以再次调用模型

`AnalysisWorker.java:245–254` 在同一个事务内请求模型、写用量、写结果、终态和回执。`ModelUsageRecorder.java:88` 的 TransactionTemplate 默认 REQUIRED，加入同一数据库事务；事务回滚时用量和结果均消失。随后 `AnalysisWorker.java:181–189` 把普通运行时异常按 `modelFailure=false` 恢复，跳过模型次数预算检查并可能发送重试。重投读不到已回滚结果，又执行模型请求，因此 `maxModelCalls=1` 仍可能重复付费且漏记首调用量。

修复验收：明确持久化的调用预算占用及不确定结果策略，保证模型请求之后数据库写入/提交失败不会再次请求模型；测试结果、状态、用量、回执等失败点。不能简单把用量事务改成 REQUIRES_NEW 就宣称问题已解决，需要同时处理父记录锁、重投与调用占用恢复。

### 3. 失败用量记录异常可绕过恢复并丢弃原消息

`AnalysisWorker.java:178–180` 在捕获模型异常后先写失败账本，写失败会直接逃出 catch，跳过恢复处置。监听器 `AnalysisWorker.java:164–167` 对逃逸异常执行 `basicNack(..., false, false)`。仓库声明的主队列 `RabbitTopologyConfiguration.java:45` 未配置 DLX；在没有额外 broker policy 的仓库默认拓扑下，消息会被丢弃，而分析可能仍是回滚后的非终态。不能假定外部 broker policy 会补救仓库未声明的恢复机制。

修复验收：模型失败且账本不可写时，分析与消息仍可恢复或可靠终结；不能把消息无恢复地丢弃，也不能恢复时再次突破模型调用预算。

## 已完成检查

- 拟提交的 79 个实现/文档/评测文件合计约 435 KB，范围属于 Phase 3；排除原始目录的独立 README 修改和计划文件。
- Python `uv sync --locked --python 3.12` / `uv run --python 3.12 pytest -q`：100 项通过，12.76 秒。
- 前端 `npm ci`、lint、Vitest（1 项）和生产构建全部通过；未升级或修改锁文件。
- 本轮 Java 全量及打包：260 项，0 失败、0 错误、0 跳过，`BUILD SUCCESS`，耗时 6 分 31 秒，2026-10-22 15:28:41（UTC+08:00）结束；Surefire XML 汇总与日志一致。命令 `.\mvnw.cmd -B -ntp '-Dspring.test.context.cache.maxSize=1' test package`，日志 `target/precommit-verification-java.log`。没有使用 `clean`，以免删除此前保存在 target 中的业务验收证据。测试通过不覆盖上述三条异常路径，不据此撤销审查阻塞。
- 两份冻结清单共 12 项哈希一致；47 个相对 Markdown 链接目标存在；暂存差异 `git diff --cached --check` 通过。
- 对本机实际 API Key 做了不输出值的精确排除检查；拟提交实现、文档和评测材料未发现常见凭据模式候选。Java 既有测试中固定 JWT 不作为真实运行凭据。`.env.local` 被忽略，不在暂存列表。不是全库或完整历史安全审计。
- 未消耗任何新真实模型调用；原批准的 25 次预算仍已耗尽。

## 依赖告警与集成边界

本机 `npm audit` 报告 5 个前端开发工具链包告警：`@vitest/mocker`、`vitest` 为 moderate；`source-map-js`、`undici`、`vite` 为 high。`npm audit --omit=dev` 报告 0，但这不表示开发/测试服务暴露风险已消除。本轮仅记录，不自动运行 `npm audit fix`；已加入 Phase 4 待办。

当前分支为 `codex/phase-1-read-only-fact-loop`，HEAD 仍是 `8447e5c`（Phase 2）。仓库未配置 remote；原始 `C:\dev\finguard-copilot` 仍在 `codex/phase-0-foundation`，包含用户未提交的 README 和未跟踪 Phase 3 计划，不修改、切换或覆盖它们。本工作树保留，暂存内容不等于已提交；没有提交、合并、推送或删分支/工作树。

下一步建议：获得修复确认后，先为上述三条路径补离线失败测试，逐项修复并回归，再重新提交前检查。H03 数值压力坏案例仍保留，真实质量复测需要新的明确调用预算，不借本次修复追加付费请求。

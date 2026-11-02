# P4-2 评测与反馈阶段验收记录（进行中）

日期：2026-11-01。工作区：`codex/phase-4-first-batch`。本记录只验收已执行的工程范围，**P4-2 整体未通过退出条件**。

2026-11-02 后续补验收已执行：实际知识注入载荷、多条件和冲突政策共 16 例，真实 Worker 方法/MySQL 与 LangGraph 同批对照，Java 9/16，参考 16/16，7 个坏案例。本文以下保留 11-01 时点记录；最新状态以[11-02 补验收](2026-11-02-p4-2-worker-comparison.md)为准。

## 已执行

- 合成单规则保留集：20 例，唯一 ID；数据 SHA-256 `eb9146eb4c2325018b27963a2c62e0ce6a8847d00032a6fe3b91e325aaa5d3d2`；隔离方式和设计/校验代码哈希见[冻结清单](../evaluations/phase4-holdout-v1-freeze.md)。独立代理声明未见实现与旧案例，不能作为强制隔离证明。
- `phase4_reference_runner` 使用 LangGraph 离线运行，`phase4_cli` 重算数据哈希并核对清单，输出按 ID 排序的[报告 v2](../evaluations/phase4-holdout-v1-langgraph-report-v2.json)：20/20；受控拒答 9，失败 0，未运行 0。输出 SHA-256 `819c2a63be0e0798d1959d0f384ead7f79401dba60f57609abce01abdf787ceb`，报告 v2 SHA-256 `883a8bcdd445813de1f14cef80d95a84a6f935075531b3a7c42fc3318317b6a8`。早先报告保留为历史文件，未覆盖。这是合成单阈值比较，未调用模型或 Core。
- 反馈 `PUT /api/copilot/review-analyses/{analysisId}/feedback`、V7 表、服务端 Core 身份与分析归属检查、同值重放/异值冲突已实现。契约见[反馈设计](../evaluations/phase4-feedback-contract.md)。定向服务/API 测试及 MySQL Testcontainers 迁移、并发重复提交测试通过。复核发现 MySQL 默认排序规则会混淆 `ALICE` / `alice`，已改为精确字节匹配并补集成负例，修复后复测通过。
- 同一冻结数据中的 11 个有方向标签经 Java `NumericThresholdGuard` 测试：匹配建议接受、相反建议拒绝；9 个拒答案例没有作为 Java 建议生成能力测试。跨实现对照仅限数值护栏契约，不是 Java Worker 输出。
- `phase4_judge.calibrate` 能在外部标签与输出上计入一致、冲突、弃权、缺失。用户要求模拟真人填写，现有[10 例 AI 模拟审核标签](../evaluations/phase4-simulated-review-labels-v1.jsonl)与[确定性参考输出](../evaluations/phase4-simulated-judge-v1.jsonl)经 CLI 生成[校准演练报告](../evaluations/phase4-simulated-calibration-v1.json)：10/10；这不是人工校准。
- 复用既有本地 DeepSeek 凭据，以 `deepseek-flash` 对上述固定 10 例执行 10 次真实 Judge 请求，未重试，响应均有受控建议。原始模型正文和密钥未落盘；[受控输出](../evaluations/phase4-deepseek-judge-v1.jsonl) SHA-256 `9305c33ec9bae9a8d7686ace10eb7507ad65ebb6ab7bd46a66e19ee1af61ef45`，[用量](../evaluations/phase4-deepseek-judge-usage-v1.jsonl) SHA-256 `03064de721b1465b6c59e21f8d87a6a88b71e4cdec1a8492e875af508c89782e`。输入 2441、输出 120 tokens；按 [DeepSeek 官方高峰价](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)输入缓存未命中 2 元/百万、输出 8 元/百万估算 0.005842 元，实际账单未核验。此前 P4-1 一次调用估算 0.009794 元，两批估算合计 0.015636 元，低于用户 10 元上限。DeepSeek 与 AI 模拟标签的[一致率报告](../evaluations/phase4-deepseek-simulated-calibration-v1.json)为 10/10，**不是人机一致率或领域专家审定。**
- `uv run --python 3.12 pytest -q`：160 passed。前端 `npm run lint`、`npm run test -- --run`（1/1）和 `npm run build` 通过。`mvn -q test` 首轮 316 项中 1 项失败，原因为 Phase 2 迁移测试将版本列表固定为 V1–V6；修正至 V7 并补大小写身份负例后，`mvn -q '-Dspring.test.context.cache.maxSize=1' test` 退出码 0；Surefire 67 个报告合计 317 tests、0 failures、0 errors、0 skipped。日志位于忽略目录 `target/p4-2-full.log`。

## 未满足的退出条件

- 合成数据不包含真实提示注入载荷、多条件或冲突政策事实，且标签非领域专家审定；不能证明这些类别。未将历史 Phase 3 样本复用为未见集。
- 尚无真实人工固定样本标签，因此没有可宣称的人机一致率；DeepSeek 10/10 是与 AI 模拟标签的一致性，且模型版本名未验证到底层权重内容。
- 尚无 Java Worker 在同一批案例的输出；当前 11 例只是 Java 护栏接受/拒绝测试，LangGraph 20/20 不能据此宣称 Worker 对照或生产模型质量验收。
- Prompt、模型、索引、规则版本值由运行者声明，报告明示 `unverified`；仅数据文件 SHA-256 由工具重算。
- 反馈保留期限与生产删除作业未确定；接口尚未与前端流程做进程级验收。

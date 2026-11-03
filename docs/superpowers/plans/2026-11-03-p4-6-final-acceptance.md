# P4-6 Final Acceptance Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans and verification-before-completion. 不使用 Astra；按顺序执行，最后最多一次非 Astra 独立复核。

**Goal:** 对已推送的 Phase4 实现完成独立、可复现的总验收并记录未通过门槛。

**Architecture:** 复用既有测试/业务验收/评测入口，不另建运行系统；新报告与历史冻结分开，全部模型链路为本地替身。

**Tech Stack:** Java17/Maven/MySQL/Redis/RabbitMQ/Qdrant，Python3.12/uv/pytest，React/TypeScript/Vite。

**Spec:** `docs/superpowers/specs/2026-11-03-p4-6-final-acceptance-design.md`

## Global Constraints

- 入口为 `4ef7b4a`，保留已有独立工作树与 main 基线。
- 不新增付费模型调用，不将模拟人工标签当作真实人工标签。
- 旧冻结报告不覆盖，新输出统一到忽略的 `target/p4-6`，最终脱敏证据到 `docs/acceptance/p4-6`。
- 不停止共享 MySQL/RabbitMQ/Redis 和既有可观测服务，不删除历史日志、数据库或卷。
- 只更新有证据的状态，不关闭真实人工校准/备用模型/生产质量/Phase5门槛。

## Review Focus

- 测试统计必须来自本轮完整命令，不用旧XML或定向复测拼接成全套通过。
- 评测必须校验数据/参考实现冻结，保留参考的22/24与Java24/24差异。
- 拒绝路径核对真实HTTP/Worker/账本/调用次数及Core校验和，不能仅看文案。
- 文本换行规范化不得被误判为业务源码漂移，也不得吞掉真实内容修改。
- OpenAPI页面投影的字符串费用/ID与旧结果API数值契约并存，不做破坏性替换。

### Task 1: 入口与证据盘点

**Files:** 读取Git、TASKS、旧冻结；输出 `docs/acceptance/p4-6/entry.json`。
**Interfaces:** 消费4ef7b4a和P4-5基线，输出提交/远端、版本、历史支持证据及未关门槛。

- [x] 核对远端提交、工作树和历史冻结；记录Git文本规范化与原始字节差异。
- [x] 记录环境版本、迁移V1–V12及仍待外部依据的门槛。

### Task 2: 全套与评测复现

**Files:** 既有测试与 `phase4_worker_comparison`、`phase4_judge`；输出 `target/p4-6/*`。
**Interfaces:** Java Worker JSON → 冻结参考比较报告；Judge/模拟标签 → 复算报告。

- [x] 完整Java测试使用512m测试堆及单上下文缓存，明确输出24例Worker结果；另跑原16例。
- [x] Python183基线、前端测试/lint/build与npm全量/生产依赖audit，新跑并记录退出码。
- [x] 对新Worker输出复算参考报告及冻结Judge校准，不调用模型；核对样本及版本。

### Task 3: 进程与契约验收

**Files:** `scripts/run-business-e2e.ps1`、当前生成OpenAPI及运行文档；输出场景报告。
**Interfaces:** 既有替身开关 → 六场景终态/账本/幂等/Core只读证据。

- [x] 正常、ExpectPolicyRejection、ExpectFactRejection、ExpectMissingPolicy、ExpectBudgetRejection、ExpectRedisFailure逐个运行并清理自建资源。
- [x] 核对OpenAPI的创建/查询/精确投影/SSE/反馈路径及运行手册；修复实际不一致并定向复验。

### Task 4: 独立结论与提交

**Files:** `docs/acceptance/2026-11-03-p4-6-final-acceptance.md`、`docs/acceptance/p4-6/*`、TASKS/PHASE4_TASK_PLAN。
**Interfaces:** 本轮验证结果 → 工程结论、限制、逐文件来源/制品/报告哈希。

- [x] 一次非Astra独立复核；重要问题有依据地修复/复验。
- [x] 冻结新报告、清理自建进程/临时凭据、更新真实任务状态；历史记录保持不变。
- [x] 验证后提交并推送本次P4-6验收产物（沿用用户本轮提交/推送授权），核对远端。

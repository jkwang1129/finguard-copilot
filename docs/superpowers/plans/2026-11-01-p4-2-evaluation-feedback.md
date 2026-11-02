# P4-2 Evaluation and Feedback Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans or superpowers:subagent-driven-development as applicable; track steps below with checkboxes.

**Goal:** 建立可重复、可追溯且有权限边界的评测与反馈闭环。

**Architecture:** 离线评测使用现有 Python 工具及脱敏、版本化案例；Java 持久化受权限约束的审核反馈。LangGraph 只作为离线参考，输出遵循同一案例契约，不成为生产执行路径。

**Tech Stack:** Java 17、Spring Boot、Flyway、MySQL；Python 3.12、uv、pytest、Pydantic、Pandas；后续 LangGraph。

**Spec:** [Phase 4 任务安排](../../PHASE4_TASK_PLAN.md)，P4-2 行及验收方法。

## Global Constraints

- 不覆盖 Phase 3 冻结数据、Prompt、报告与哈希；不将 H03 已见数据当作未见评估。
- 仅合成或明确获准脱敏数据；不得存 JWT、密钥、原始敏感事实或未审定模型正文。
- 新表仅使用新的 Flyway migration；不改 V1–V6；不影响现有调用占用和 Core 只读边界。
- 不提交或推送，除非用户明确授权；真实供应商调用需单独确定本次样本和预算。
- 真实模型版本名和索引名称不是内容哈希；人工标签不能冒充领域专家审核。

## Review Focus

检查数据泄漏与未见性、案例 ID/版本错配、错误或伪造输入、跨用户访问及重复反馈、报告对缺失失败案例的处理。禁止把调用方自报检查状态当成独立验证。

## Task 1：保留集设计与独立编写准备

文件：创建 `docs/evaluations/phase4-holdout-v1-design.md`；之后由隔离作者创建 `tools/agent-evaluation/data/phase4-holdout-v1.jsonl` 及冻结清单。

- [x] 写明隔离作者范围、类别矩阵、数据泄漏约束、冻结流程与验收标准；不提前编造正式未见案例。
- [x] 在独立代理上下文撰写 20 条合成单规则案例与标签；已记录过程隔离边界及 AI 作者身份，但它不是覆盖全部类别的正式质量保留集。
- [x] 主流程核对格式、唯一 ID、合成数据及规则自洽，冻结数据/设计/校验代码哈希；标明非领域专家审定。
- [x] 添加 `tests/test_phase4_holdout_dataset.py`：空集/重复 ID/非法标签/漏字段拒绝；定向测试 2 passed。类别仅单规则有效，不能把占位名称当安全案例。

## Task 2：可重复规则评测与报告

文件：新增 `tools/agent-evaluation/src/finguard_agent_evaluation/phase4_rules.py`、`phase4_report.py` 及对应测试；在 `README.md` 记录命令。

进度：已实现合成单阈值比较、数据哈希绑定、完整 CLI 和持久化报告；政策/版本来源真实性仍不可独立核验，报告标 `unverified`。

- [x] 精确十进制、未知规则、缺失事实/引用、拒答、重复/缺失案例、非法建议和稳定排序测试。
- [x] 数据 SHA-256 重算及 Prompt/模型/索引/规则/来源清单，版本真实性标 `unverified`。
- [x] 按 ID 分类报告，拒绝、失败、未执行计入分母；无原文，拒绝覆盖。
- [x] 定向及 Python 全套测试执行；同一输入稳定字段复现。

## Task 3：审核反馈 API

文件：`src/main/resources/db/migration/V7__create_review_feedback.sql`（先核对当前最新版本）、`analysis` 域与持久化、`api/analysis` 控制器、集成测试。

- [x] 反馈契约 v1 记录范围、唯一键、重复语义、受控字段和保留待决事项；不存令牌或原文。
- [x] 401/403、跨审核员/不存在 404、状态、无效值和并发重复提交测试；单条 SQL 原子写，无 Core 写接口。
- [x] V7 migration、repository、服务和 API；定向服务/API 及 MySQL Testcontainers 集成测试通过。

## Task 4：Judge 与人工校准

文件：新增离线 Judge 版本、标注模板、测试和报告；独立维护人工标签来源与审核状态。

- [x] 固定 10 例合成校准样本，一致率含弃权和缺失；用户要求 AI 模拟人工，已明确标注模拟，不能宣称真实人工标签。
- [x] 替身测试版本错配、无效输出、冲突、重复和缺失；模拟校准 10/10，仅流程演练。
- [x] 固定 10 例、最多 10 次、10 元人民币总上限及无重试；复用用户已授权的 DeepSeek 凭据执行 10 次，估算本批 0.005842 元。只有 AI 模拟标签，未达真实人工校准退出条件。

## Task 5：LangGraph 离线参考及整体验证

文件：`tools/agent-evaluation` 下独立 reference 模块及契约测试。

- [x] 合成案例与受控建议契约；离线参考不依赖生产 Worker 或 Core 读接口。
- [x] 2026-11-02 新增 v2 同批 Worker/MySQL 输出、完整成功结果及失败重试/回执/账本，16 例全计入分母；Java 9/16、参考16/16，质量未通过。此前 v1 的 11 个方向案例只作护栏测试；Phase 5 才引入的 Tool Calling Schema 不在本次运行范围。
- [x] P4-2 验收记录包含版本、数据哈希、未通过项及费用；仍未达到整体退出条件。

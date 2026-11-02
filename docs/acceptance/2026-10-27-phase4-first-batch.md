# 2026-10-27 Phase 4 第一批实施与验收

工作区：`codex/phase-4-first-batch`，基于 Phase 0–3 合并提交 `4f15993`。本批覆盖 [任务安排](../PHASE4_TASK_PLAN.md) 的前三项可执行任务；没有发起真实模型调用，也没有修改 2026-10-22 的冻结评测结果。

## 基线与版本清单

- [运行版本字段清单](2026-10-27-phase4-baseline.md)记录了 `analysisId`、快照哈希、Prompt、模型配置、索引和价格表已有持久化位置及关联缺口。
- 2026-10-22 保留集冻结清单的五个 SHA-256 在本工作区全部匹配。H03 从本批起作为已见回归，不用于宣称调优后的未见质量。
- 改动前运行 `scripts/verify.ps1`：Java 265 项、0 失败/错误/跳过；Python 100 项；前端 lint、Vitest 1 项、构建通过。前端锁文件当时仍有 5 项开发依赖告警。

## 确定性数值护栏

`NumericThresholdGuard` 在模型输出完成结构、引用精确匹配之后，且 Worker 持久化结果之前，对**明确配置的单阈值规则**核对方向性建议。每条规则由维护者确认适用范围、币种、比较符、阈值及无额外条件或豁免，并绑定政策文档 ID、版本、章节和完整正文 SHA-256；系统不从自然语言自动提取规则。按事实 `ruleCode` 找到已登记规则后，若证据未召回、被裁剪、版本或正文变化、未被精确引用、币种不符或建议方向冲突，就拒绝本次方向性输出。`INSUFFICIENT_EVIDENCE` 仍可保守返回。

TDD 证据：先新增 H03 合成回归，旧路径出现 `Expecting code to raise a throwable`，证明错误的 `IGNORED` 能通过。首次自由文本解析方案在独立复核中暴露千分位、否定、例外和 `≥` 漏判，已由结构化配置替代。新版测试覆盖四种比较符、18 位精度、重复配置、缺证据、篡改正文、币种、引用和配置绑定；最终四个相关测试类定向复测 42 项通过。

这属于**矛盾拒绝护栏**，不会自动把模型建议改写为 `CONFIRMED`。默认规则表为空：未配置的规则不受此数值护栏覆盖，仍沿用已有的结构、事实与引用校验；本批没有擅自把合成 H03 政策配置为真实业务规则。规则配置版本目前尚未写入运行账本，需在后续版本管理任务补齐。测试读取冻结 H03 案例的原始政策和金额；历史报告未保存实际模型建议枚举，因此测试使用明确的错误 `IGNORED` 候选，而不是重放当时原文。Core 当前金额存储也不能表达该 18 位小数案例；测试证明的是组件在合成边界上的精确比较，不证明真实业务流已遇到这个金额。

规则可以通过 Spring 配置 `finguard.copilot.numeric-guard.rules` 提供，每项需包含 `version`、`rule-code`、`currency`、`operator`（`GT/GTE/LT/LTE`）、`threshold`、`document-id`、`document-version`、`section-path` 和小写 `content-sha256`。同一 `rule-code` 的重复配置会使启动失败。配置前需核对政策的完整语义和发布片段；含例外、组合条件、换汇或多阈值的规则不得登记为单阈值规则。

下面是字段形状示例，数据均为虚构值；摘要必须替换为已批准、已发布的完整片段 UTF-8 SHA-256：

```yaml
finguard:
  copilot:
    numeric-guard:
      rules:
        - version: example-rule-v1
          rule-code: EXAMPLE_RULE
          currency: USD
          operator: GT
          threshold: "1000.00"
          document-id: example-policy
          document-version: "1.0"
          section-path: threshold
          content-sha256: "0000000000000000000000000000000000000000000000000000000000000000"
```

## 前端依赖告警

改动前本机 `npm audit`：3 high、2 moderate，涉及 `vite`、`source-map-js`、`undici`、`vitest`、`@vitest/mocker`。先运行不带 `--force` 的 `npm audit fix`，三项 high 消除；它没有升级超出当前锁定结果的 Vitest。随后将直接开发依赖升至同主版本补丁 `vitest@^4.1.12`，锁文件解析为 `vitest`/`@vitest/mocker` 4.1.12、Vite 8.3.5、`source-map-js` 1.2.2、`undici` 7.30.1。

干净 `npm ci` 后，`npm audit --audit-level=moderate` 和 `npm audit --omit=dev --audit-level=moderate` 均报告 **0 vulnerabilities**；lint、Vitest 1 项和构建通过。后续新增/升级依赖仍须重新审计，不能把本次结果视为永久安全保证。

## 全量回归和剩余边界

- 第一批代码运行 `scripts/verify.ps1`：Java **282 项、0 失败/错误/跳过**，Python **100 项**，前端 lint、Vitest 1 项和构建通过，脚本退出码 0。该全量运行启动后，测试源码又补充了冻结 H03 原文读取和 Worker 断言；生产源码当时未再变动。后续诊断和安全增量另见[独立记录](2026-10-27-phase4-security-diagnostics.md)。
- 随后运行 `mvnw.cmd -B -ntp -Dtest=NumericThresholdGuardTest,ModelAnalysisOrchestratorTest,RealModelPropertiesTest,AnalysisWorkerModelUsageTest test`：**42 项、0 失败/错误/跳过**。Worker 断言使用替身验证拒绝时的失败用量、失败状态、回执和不插入结果；本批未单独运行真实模型或 Core 业务 E2E。
- 尚未完成 P4-1 的全部安全负例、受控建议枚举记录和真实业务政策审定配置，也未实现 P4-2 至 P4-6；对应任务保持未勾选。

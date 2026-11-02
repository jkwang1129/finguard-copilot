# P4-2 七项修复与复验

日期：2026-11-02。结论：**七项已修复并通过验证**，原16例回归16/16，独立新集Java24/24。原始 [9/16 基线及七项](2026-11-02-p4-2-worker-comparison.md)保留不覆盖；本次为用户明确授权的根因修复。最终Java全套333项、Python168项通过。

## 修复边界

| 项目 | 根因修复 | 合法路径/兼容性 |
| --- | --- | --- |
| W202 精度 | V8 将快照与结果改为 LONGTEXT + JSON_VALID，写入移除 CAST AS JSON；模型解析、结果校验和 GET 使用 BigDecimal | 模型 schema v1 仍要求数字；快照规范化与哈希未改，正确旧快照可验证。V7→V8 测试保留旧数据；已舍入的旧快照继续哈希失败，无法凭空恢复原值。 |
| W206/W207 注入 | 方向输出必须有可信政策授权。未配置规则、未审定正文、正文变更/元数据伪造、额外或缺失证据均拒绝 | 不只识别攻击关键词；精确绑定的正常单阈值、Core 大额项目政策和保守拒答保留。严格证据范围可能增加拒答。 |
| W211/W212 多条件 | 新 ReviewedPolicyGuard 从服务端配置读取 ALL 条件，允许字段白名单、精确金额比较、确定性分支建议 | 实际知识与候选不能创建条件。测试使用独立的合成政策配置，生产默认不启用 P4_COMPLEX，真实业务须另行审定配置。 |
| W213/W214 冲突 | 完整政策包匹配，全部绑定成员都必须存在且引用准确；成员决定不一致时拒绝 | 没有隐含优先级，不任意采用第一份文档；保守拒答仍可用。 |

生产入口的旧构造器默认提供空审定政策列表，依然拒绝未知方向建议；Core `LARGE_AMOUNT` 保留专用事实/政策核验。与数值规则重复的政策授权类型在启动时拒绝。说明与配置示例见[审定政策说明](../REVIEWED_POLICIES.md)。V1–V7 未修改，仅测试容器执行了 V8，没有操作生产数据库。

## 恢复路径及复核

Worker 的两种存量结果恢复路径都在 schema/快照事实校验后检查方向授权，不能通过“已保存结果”绕过新边界，也不会额外调用模型。未审定方向结果转为失败并不向 API 暴露最终结果；已有终态历史运行不被自动重写。

按 `codex-security:fix-finding` 做一次独立边界调查和一次候选绕过/回归复核。复核指出：无引用的安全拒答恢复被不必要地绑定到当前检索服务，导致故障下无法恢复。已先复现 `KNOWLEDGE_UNAVAILABLE`，再补恢复专用测试并修复；无引用拒答在既有 schema/事实校验后直接恢复，不访问检索或模型。两个 Worker 分支各有方向拒绝与拒答恢复控制。复核未发现其他具体绕过；这不是通用安全证明。

## 独立新集

原16例回归 Java **16/16**，参考16/16，缺失0；[完整回归结果](../evaluations/phase4-worker-v2-java-verified.json) SHA-256 `2251309f35efb32763dff6c63c09c94363a870b87556f9938f6a4dcb5c1c9820`，[对照报告](../evaluations/phase4-worker-v2-comparison-verified.json) SHA-256 `aeab8568c6e4930b525df8d6a666b3ca82643b5e1267f294efb63cdfcea8174b`。W202 为 SUCCEEDED/IGNORED，原始精确金额在快照与最终结果中一致，单次模型替身调用；W206/W207/W211/W212/W213/W214 均为 FAILED/FACT_VALIDATION_FAILED，无持久化可用结果。所有原有合法控制继续通过。

AI 作者仅收到契约和可信测试政策语义，声明未读取实现、测试或本次运行结果；作者曾编写 v2，因此其旧案例知识已披露。新 24 例未用于调整方向授权实现或模型 Prompt，数据在运行前冻结。作者与标签不是人类/领域专家，隔离依赖过程约束。

- 数据 [phase4-worker-v3.jsonl](../../tools/agent-evaluation/data/phase4-worker-v3.jsonl)：SHA-256 `8e63b6d2b76fde26c724815366c35038ec2e2e03277029207dc4826cf9ab006b`，四类各6，标签接受/拒绝各12。
- [冻结清单](../evaluations/phase4-worker-v3-freeze.json)：`e1b5eb99eaa734bbfb39bd5d2c95846168f0a77300c5acd2aa62422fe8f8cd0b`；绑定数据、作者、原参考代码与 Python 解析器哈希。参考算法没有按新样本调整。
- [Java 完整结果](../evaluations/phase4-worker-v3-java-verified.json)：`d648b583f617d386d68207e6b17bf928dd7b3d59493939251884dbe275f023c7`。
- [同批报告](../evaluations/phase4-worker-v3-comparison-verified.json)：`b8d33919eaf59fdffd8197c208955dbae938db4ad32445bdc5bb12b47dfb5294`。

Java **24/24**；参考 **22/24**，二者一致22例，缺失0。参考漏报 N319/N321 两个注入变体，旧的正则/合成文本参考弱于新的精确授权边界，未修改参考算法来追平。Java 12条成功结果、12条拒绝；24个快照校验通过、24条模型替身用量、24个回执、全部重放NOOP。成功完整输出重新核对 schema、事实和引用，不评价自然语言推理文本质量。

本次执行真实 Worker/编排器/校验器与 MySQL，模型网关、检索、Rabbit确认仍为夹具；不是 Core HTTP/Qdrant/Rabbit投递/真实模型的 E2E。没有付费模型调用，新增费用0元。原16例为已见回归，新24例为独立合成复验，均不能扩大为所有注入攻击或真实业务质量已通过。

## 验证记录

- RED：精度解析实际把 `99.999999999999999999` 读成100；真实 MySQL 旧基线金额变成 `100.00000000000001`。
- RED：未审定但引用匹配的方向输出没有抛错；两种 Worker 存量恢复路径都把未授权方向转成SUCCEEDED。
- GREEN：SnapshotCodec 旧/新精确数字、parser/schema 数字契约、审定政策组、默认拒绝、伪造绑定、缺/多/重复证据、冲突及合法控制通过。
- GREEN：V7→V8 真实 MySQL 升级测试，正确旧快照哈希、旧损坏数据拒绝、新精确快照/结果及 JSON_VALID 约束通过。
- GREEN：定向配置绑定、HTTP结果树精度、Core 大额正常/错误方向及 Worker 两分支恢复控制通过。
- GREEN：新24例 Worker/真实MySQL 集成与前述报告输出通过。
- GREEN：Python全套 `uv run --python 3.12 pytest -q`：168 passed。原冻结参考的两项错误仍保留为评测结果。
- 最终 Java 全套 `mvn -q '-Dspring.test.context.cache.maxSize=1' '-Dp4.evaluation.output=target/fix7-worker-v2-verified.json' test` 退出码0；Surefire 70个报告合计 **333 tests，0 failures，0 errors，0 skipped**，日志 `target/fix7-verified-java.log`（忽略目录）。此前首次全套因独立复核发现的拒答恢复回归主动停止，未计作通过；修复后完整重跑。本次前端无变更，未额外重复前端验证。
- `git diff --check` 通过；工作区原有改动保留，无提交或推送。

复现新集（从仓库根执行；输出文件必须是新路径）：

```powershell
mvn -q '-Dtest=Phase4WorkerComparisonIntegrationTest' `
  '-Dp4.evaluation.dataset=tools/agent-evaluation/data/phase4-worker-v3.jsonl' `
  '-Dp4.evaluation.freeze=docs/evaluations/phase4-worker-v3-freeze.json' `
  '-Dp4.evaluation.output=target/my-worker-v3.json' test
```

从 `tools/agent-evaluation` 复现已保存产物的报告：

```powershell
uv run --python 3.12 python -m finguard_agent_evaluation.phase4_worker_comparison `
  --dataset data/phase4-worker-v3.jsonl `
  --java ../../docs/evaluations/phase4-worker-v3-java-verified.json `
  --freeze ../../docs/evaluations/phase4-worker-v3-freeze.json `
  --output reports/my-worker-v3-report.json
```

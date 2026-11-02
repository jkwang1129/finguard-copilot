# P4-1：Core 大额规则审定记录

## 已按源码核实的事实

- Core `LargeAmountRule`：启用后以 `BigDecimal.compareTo` 判断交易金额是否大于等于配置阈值；等于阈值也命中。该规则并未判断币种，也未规定命中后的最终复核结论。
- Core `RiskProperties.LargeAmount`：阈值为启动配置，默认 `10000.00`，但默认值不证明某个环境的实际配置；合法值至少 `0.01`，最多 17 位整数和 2 位小数。
- Core 将命中时的 `observed_amount` 和 `threshold_amount` 保存至 `risk_hits`，复核上下文从关联的风险命中返回 `OBSERVED_AMOUNT` / `THRESHOLD_AMOUNT`，来源分别为 `riskHit.observedAmount` / `riskHit.thresholdAmount`。Copilot 的快照保存此上下文。
- Copilot 内置大额事实核验：使用该次命中的历史阈值，与交易金额精确比较；金额缺失、重复、来源不符、格式超出 Core 字段范围、金额不一致或未达到阈值时拒绝方向性输出；证据不足建议仍可返回。此核验默认生效，不需要把阈值配置为 `10000.00`。
- 规则启用状态或阈值后来变动不会覆盖历史命中的阈值。其他规则、模型生成的自由文本和大额任务的最终处置正确性不在这项核验覆盖内。

源码依据（当前本地 Core 工作区）：`src/main/java/com/finguard/core/risk/rule/LargeAmountRule.java`、`risk/config/RiskProperties.java`、`risk/service/RiskEvaluationService.java`、`review/mapper/ReviewTaskMapper.java`、`review/service/impl/ReviewTaskServiceImpl.java`。Core 的版本以实际部署版本为准；如契约变化，应先复核后再启用相应版本。

## 项目复核政策 v1

用户授权自行制定项目政策后，本项目采用 [大额复核政策 v1](../../src/main/resources/knowledge/large-amount-review-policy-v1.txt)。它适用于 `LARGE_AMOUNT` 风险任务，不限币种，按该次命中记录的金额和阈值原币比较，不换汇、不四舍五入；金额一致且达到阈值时建议 `CONFIRMED`，用于保留风险事项继续关注。政策没有金额豁免；事实缺失或矛盾时不能给方向性建议，应保守处理。该政策不等同于欺诈认定、资金支付或冻结，最终决定仍由审核员作出。该决策是项目内部演示政策，并非 Core 源码原本就有的处置规则，也不是外部机构或监管标准。

默认启用的 `CoreLargeAmountPolicyGuard` 绑定文档 ID `finguard-large-amount-review-policy`、版本 `v1`、章节 `Document` 与正文 SHA-256 `4b4ea96b87ab65ff6e1be975b36b81279058351829ecb652aebb9306a3a2e556`。方向性输出必须召回并精确引用该政策，且建议为 `CONFIRMED`；缺失、截断、变更、未引用或 `IGNORED` 均拒绝。`INSUFFICIENT_EVIDENCE` 可保守返回。通用数值护栏仍禁止配置 `LARGE_AMOUNT`，以免用固定阈值覆盖历史命中阈值。

政策文件不会自动发布：可在隔离环境通过 `scripts/e2e/PublishedKnowledgeSeed.java` 使用现有注册、建索引、评估和发布流程载入；在实际运行环境需通过相同的知识发布治理流程，确保已发布索引能完整召回该文档。未发布或召回失败会拒绝大额方向性结果。该脚本会写入配置指向的知识数据库和 Qdrant，运行前须确认目标环境。其他真实规则仍未配置；合成 H03 不作为真实业务政策。

## 安全边界

指令检测增加全角归一化、移除 Unicode 格式字符和换行归一化，只覆盖已知中英文覆盖指令样例；它会误报或漏报，且位于模型调用后。此前的越权、恶意知识、URL、秘密和工具调用负例仅证明测试列出的具体路径，不证明通用安全。无真实供应商调用或生产数据验证。

## 验证记录

- 2026-10-28 Java 全量：`mvnw.cmd -B -ntp -Dspring.test.context.cache.maxSize=1 test`，308 项，0 失败、0 错误、0 跳过。
- Java 定向回归：策略、事实、H03、注入、分析编排、越权、知识准入和模型工具响应等 77 项通过；包含完整政策准入与分块、正文哈希、历史阈值的边界、伪造来源和错误建议。
- 分析编排测试使用替身模型及内存知识索引，覆盖规则达到/等于阈值时的 `CONFIRMED`、事实不一致以及 `IGNORED` 被拒绝；不证明真实模型能稳定引用政策或真实业务流程通过。
- 发布辅助程序使用当前项目 classpath 完成 `javac` 编译；未执行发布或对现有知识库进行写入。Phase 3 两份冻结清单的 12 个条目均匹配。

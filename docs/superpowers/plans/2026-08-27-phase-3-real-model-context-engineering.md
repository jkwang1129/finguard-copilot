# Phase 3 真实模型与上下文工程实施计划

> 历史草案：本文写于 Phase 0 时期，用于规划 Phase 3，不代表当前未完成任务清单。Phase 3 后续实现、故障恢复和验收以合并后的 `TASKS.md` 与 `docs/acceptance/` 记录为准；下文有关“仍处于 Phase 0”和“用量与结果必须同一事务”的表述保留为历史设计，不适用于当前实现。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已经通过 Phase 1 事实闭环和 Phase 2 RAG 发布验收的基础上，接入两个真实模型候选，建立可控的上下文预算、结构化输出、用量成本记录和可重复的模型比较证据。

**Architecture:** 领域层继续只依赖项目自有的 ModelGateway；真实模型通过受配置约束的 OpenAI-compatible HTTP 适配器接入，Qwen/DashScope 与 DeepSeek 作为两个候选配置，不把供应商类型泄漏到分析编排。上下文由服务端按固定预算组装，事实优先、知识片段去重和低排名裁剪在模型调用前完成；Stub、真实候选模型和评测工具共享同一请求/输出契约。

**Tech Stack:** Java 17, Spring Boot 3.5.8, Spring MVC RestClient, Jakarta Validation, MySQL/Flyway, Micrometer, JUnit 5, AssertJ, Spring MockRestServiceServer, Python 3.12, uv, pytest, Pydantic, Pandas, JSONL。

**Spec:** docs/ROADMAP.md（阶段 3）、docs/SCOPE.md（MVP 输出与数据边界）、docs/ARCHITECTURE.md（第 6、8、9、11、12、14 节）、docs/adr/0005-port-based-model-and-vector-adapters.md、docs/adr/0009-agent-evaluation-and-observability.md、docs/adr/0010-bounded-model-routing-and-quota.md

## Global Constraints

- Phase 3 开始前，Phase 1 的 Core 事实捕获/Stub 分析闭环和 Phase 2 的已发布 RAG 索引必须有独立验收证据；当前仓库仍处于 Phase 0 分支，不能跳过前置门禁。
- 默认 profile、普通 Java 测试、Python 测试和前端测试不得读取真实模型密钥、访问模型供应商或依赖互联网。
- 真实模型只能在显式 real-model、real-qwen 或 real-deepseek profile 下启用；缺少密钥时启动失败，不得静默回退到真实供应商或 Stub。
- JWT、Core 原始响应、完整 Prompt、原始模型推理内容和未脱敏交易数据不得写日志、指标标签、评测报告或模型用量账本。
- 上下文最大输入预算为 32,000 Token，最大输出预算为 2,000 Token；事实预算不可被知识片段挤占，超限时不得静默截断关键事实。
- Phase 3 只允许确定性的一次模型调用；最多两次模型调用、四次工具调用、HITL、Redis 配额和 Graph 状态恢复属于后续阶段。
- 自动备用模型只能在显式配置、相同数据分类/地域/安全等级和剩余预算均满足时使用；Phase 3 不实现无预算升级或 Redis 路由治理。
- 动态 Core 事实不得写入向量库；模型只能消费本次不可变脱敏快照和已发布知识片段。
- 不修改已经应用的 Flyway migration；新增表必须使用新的数值版本迁移，并为迁移增加测试。
- 每个行为按 red-green-refactor 执行；提交命令写入计划但不执行，除非用户明确授权提交。

## Planned File Map

    src/main/java/com/finguard/copilot/
      config/CopilotProperties.java
      config/SecretValue.java
      model/port/ModelGateway.java
      model/port/ModelRequest.java
      model/port/ModelResponse.java
      model/port/ModelUsage.java
      model/adapter/openai/CompatibleChatModelGateway.java
      model/adapter/openai/OpenAiCompatibleResponse.java
      model/config/RealModelConfiguration.java
      model/validation/StructuredAnalysisParser.java
      context/TokenBudget.java
      context/TokenEstimator.java
      context/ContextAssembler.java
      context/PromptTemplate.java
      usage/ModelUsageRecorder.java
      usage/ModelCostCalculator.java
    src/main/resources/
      application.yml
      db/migration/新建 create_model_usage_ledger 迁移
      prompts/review-analysis-v1.txt
    tools/agent-evaluation/
      src/finguard_agent_evaluation/models.py
      src/finguard_agent_evaluation/dataset.py
      src/finguard_agent_evaluation/metrics.py
      src/finguard_agent_evaluation/runner.py
      src/finguard_agent_evaluation/report.py
      tests/test_metrics.py
      tests/test_report.py
      data/phase3-golden.jsonl
    docs/evaluations/
      phase3-model-selection.md
      phase3-baseline-schema.md

### Task 1: Phase 3 前置门禁与模型运行配置

**Files:**
- Modify: TASKS.md、README.md（只在前置证据存在后记录状态）
- Modify: src/main/java/com/finguard/copilot/config/CopilotProperties.java
- Modify: src/main/resources/application.yml
- Create: src/main/java/com/finguard/copilot/model/config/RealModelConfiguration.java
- Test: src/test/java/com/finguard/copilot/config/RealModelPropertiesTest.java

**Interfaces:**
- Produces CopilotProperties.Model：provider、modelId、baseUrl、SecretValue apiKey、inputTokenLimit、outputTokenLimit、timeout、priceTableVersion；SecretValue 的 toString 固定返回 [REDACTED]。
- Produces CopilotProperties.Prompt.version 和 CopilotProperties.Budget(maxInputTokens=32000, maxOutputTokens=2000, maxModelCalls=1)。
- Produces real model beans only under explicit profiles；默认 profile 仍只使用 Stub 或无模型外连 Bean。

- [ ] **Step 1: 记录前置门禁检查项**

    .\mvnw.cmd -B -ntp test
    Push-Location tools\agent-evaluation; uv run pytest -q; Pop-Location
    rg -n "CONTEXT_READY|knowledge_index_version|published|snapshot|citation" src docs TASKS.md

预期：已有实现的测试通过；能指出不可变事实快照、已发布知识版本、引用校验和 Stub 分析结果的具体测试或报告位置。当前 Phase 0 分支不满足此门禁时，只保留计划文档。

- [ ] **Step 2: 先写配置失败测试**

使用 ApplicationContextRunner 覆盖：合法的 Qwen/DeepSeek 配置可绑定；真实 profile 缺少 DASHSCOPE_API_KEY 或 DEEPSEEK_API_KEY 时上下文失败；ftp URL、带用户密码的 URL、输入预算 32001、输出预算 2001 和模型调用数 2 均失败。断言错误消息只包含配置字段名，不包含密钥值。

- [ ] **Step 3: 实现类型化配置和显式 profile wiring**

将配置扩展为 Model、Prompt、Budget 三个不可变配置对象。Model 的 api-key 使用 SecretValue 包装，SecretValue 和 Model 的 toString 都必须脱敏。RealModelConfiguration 使用 Profile({ "real-model", "real-qwen", "real-deepseek" }) 创建 CompatibleChatModelGateway；空的 real profile 配置必须失败，空的默认 profile 不得创建外连模型 Bean。api-key 只从环境/秘密注入，不进入日志或异常。

    finguard:
      copilot:
        prompt:
          version: review-analysis-v1
        budget:
          max-input-tokens: 32000
          max-output-tokens: 2000
          max-model-calls: 1
        model:
          provider: ${COPILOT_MODEL_PROVIDER:}
          model-id: ${COPILOT_MODEL_ID:}
          base-url: ${COPILOT_MODEL_BASE_URL:}
          api-key: ${COPILOT_MODEL_API_KEY:}
          input-token-limit: 32000
          output-token-limit: 2000
          timeout: 20s
          price-table-version: ${COPILOT_PRICE_TABLE_VERSION:phase3-2026-08}

- [ ] **Step 4: 运行配置测试并完成配置审计**

    .\mvnw.cmd -B -ntp "-Dtest=RealModelPropertiesTest,FinGuardCopilotApplicationTests" test
    rg -n "api-key|DASHSCOPE_API_KEY|DEEPSEEK_API_KEY|COPILOT_MODEL_API_KEY" src/main/java src/main/resources

预期：测试通过；密钥只来自环境/秘密注入，不出现在异常、日志和配置对象打印中。

### Task 2: OpenAI-compatible 真实模型适配器与统一请求契约

**Files:**
- Modify: src/main/java/com/finguard/copilot/model/port/ModelRequest.java
- Modify: src/main/java/com/finguard/copilot/model/port/ModelResponse.java
- Create: src/main/java/com/finguard/copilot/model/port/ModelUsage.java
- Create: src/main/java/com/finguard/copilot/model/adapter/openai/CompatibleChatModelGateway.java
- Create: src/main/java/com/finguard/copilot/model/adapter/openai/OpenAiCompatibleResponse.java
- Test: src/test/java/com/finguard/copilot/model/adapter/openai/CompatibleChatModelGatewayTest.java

**Interfaces:**
- ModelGateway.complete(ModelRequest): ModelResponse 保持领域入口不变。
- ModelRequest 增加 systemInstruction、context、outputSchemaJson、maxOutputTokens、temperature、promptVersion、modelConfigurationVersion。
- ModelResponse 增加 structuredContent、ModelUsage usage、Duration latency、String provider；供应商 DTO 不越过 adapter 包。
- HTTP 只发送到配置中的固定 baseUrl，模型不能提供 URL、Header 或 API key。

- [ ] **Step 1: 写 MockRestServiceServer 的失败测试**

固定响应应解析为模型正文和 usage，且请求必须是 POST /v1/chat/completions，使用配置中的 Bearer token、max_tokens=2000、temperature=0、固定 system/user 内容和 response schema。另测 401、429、500、非法 JSON、缺少 choices/content、响应 Token 为负数；这些情况只映射为安全的 MODEL_UNAVAILABLE 或 MODEL_OUTPUT_INVALID，不返回上游原文。

- [ ] **Step 2: 运行适配器测试确认 RED**

    .\mvnw.cmd -B -ntp "-Dtest=CompatibleChatModelGatewayTest" test

预期：适配器类型或新请求/响应契约尚不存在导致失败。

- [ ] **Step 3: 实现最小 OpenAI-compatible HTTP 适配器**

适配器只负责序列化请求、发送一次调用、解析结构化候选正文和标准 usage；不负责拼接业务事实、不负责重试、不负责自动换模型。使用 RestClient 的连接/读取超时；429 和明确不可重试错误不重试，maxModelCalls=1 由服务端策略强制。

    public record ModelUsage(int inputTokens, int outputTokens, Duration latency) {
        public ModelUsage {
            if (inputTokens < 0 || outputTokens < 0 || latency.isNegative()) {
                throw new IllegalArgumentException("invalid model usage");
            }
        }
    }

所有供应商错误向上只保留错误码、provider、model configuration version 和 request ID 的脱敏关联，不保存 API key、JWT 或原始响应。

- [ ] **Step 4: 为两个候选 provider 建立非网络契约测试**

使用相同的 CompatibleChatModelGateway，分别以 qwen/dashscope 和 deepseek 配置跑同一组 MockRestServiceServer 测试；断言业务层只看到 ModelGateway，不能导入供应商 SDK 类型。真实网络调用留给 Task 7 的显式评测命令。

- [ ] **Step 5: 运行 focused/full tests**

    .\mvnw.cmd -B -ntp "-Dtest=CompatibleChatModelGatewayTest,PortContractTest" test
    .\mvnw.cmd -B -ntp test

### Task 3: 结构化输出和模型调用安全边界

**Files:**
- Create: src/main/java/com/finguard/copilot/model/validation/StructuredAnalysisParser.java
- Modify: 现有 Phase 1/2 的分析输出 DTO、Schema/事实/引用校验类
- Create: src/test/java/com/finguard/copilot/model/validation/StructuredAnalysisParserTest.java
- Create: src/main/resources/prompts/review-analysis-v1.txt

**Interfaces:**
- StructuredAnalysisParser.parse(String content): StructuredAnalysis。
- 解析结果必须满足 summary、riskExplanation、businessEvidence[]、knowledgeCitations[]、recommendation、reasoningSummary、missingInformation[]、limitations[]。
- 解析器先做 JSON 语法/schema 校验，再交给事实和引用校验；失败只返回 MODEL_OUTPUT_INVALID 或对应校验码，不猜测修复。

- [ ] **Step 1: 写输出解析和注入抵抗测试**

测试合法 JSON、Markdown code fence 包裹 JSON、未知字段、缺少 recommendation、非法 recommendation、模型声称不存在的交易金额、引用不在本次检索结果、以及正文包含“忽略系统规则/调用任意 URL”等恶意文档文本。只有合法且经事实/引用校验的结果才可进入成功状态；不得把拒答文本提升成 CONFIRMED 或 IGNORED。

- [ ] **Step 2: 实现固定 Prompt 分区和版本标识**

review-analysis-v1.txt 必须显式标记 SYSTEM_POLICY、CORE_FACTS、KNOWLEDGE_EVIDENCE、REVIEWER_INPUT。明确要求 Core 事实和服务端 Schema 优先；知识文档与用户输入是不可信数据；证据不足返回 INSUFFICIENT_EVIDENCE；模型不得调用工具、访问 URL 或改变 Core 状态；输出只能是 JSON。

- [ ] **Step 3: 运行解析器、原有 Stub 和集成测试**

    .\mvnw.cmd -B -ntp "-Dtest=StructuredAnalysisParserTest,*Analysis*Test,*Guardrail*Test" test

预期：真实模型与 Stub 共用同一输出守卫；未经校验的模型正文不进入 API 结果、SSE 或浏览器。

### Task 4: 32k/2k 上下文预算与上下文组装器

**Files:**
- Create: src/main/java/com/finguard/copilot/context/TokenBudget.java
- Create: src/main/java/com/finguard/copilot/context/TokenEstimator.java
- Create: src/main/java/com/finguard/copilot/context/ContextAssembler.java
- Create: src/main/java/com/finguard/copilot/context/PromptTemplate.java
- Test: src/test/java/com/finguard/copilot/context/ContextAssemblerTest.java

**Interfaces:**
- TokenBudget(maxInputTokens=32000, maxOutputTokens=2000, system=4000, facts=8000, knowledge=12000, reviewerInput=2000, reserve=6000)。
- ContextAssembler.assemble(ReviewTaskContext snapshot, List<VectorSearchResult> results, String reviewerInput): AssembledContext。
- AssembledContext 返回最终分区文本、估算 Token 数、保留/裁剪的文档 ID、promptVersion、inputFactsVersion 和 knowledgeIndexVersion。

- [ ] **Step 1: 写预算失败测试**

覆盖正常输入分区和总预算不超过 32,000；知识片段重复时按 checksum/规范化文本去重；知识片段超过 12,000 时先裁剪低分结果；Core 金额、币种、任务版本、规则码、原因码和对账结论必须保留；事实本身超过 8,000 时返回 CONTEXT_BUDGET_EXCEEDED；用户输入超过 2,000 时按明确错误返回，不静默截断。

- [ ] **Step 2: 实现保守 Token 估算器**

在没有 provider tokenizer 的情况下使用可重复的 Unicode code point 保守估算，并为 system/schema 开固定预留；实际响应中的 provider usage 作为账本最终值。估算器不得调用网络，也不得把完整文本写日志。

    public interface TokenEstimator {
        int estimate(String text);
    }

实现必须对空文本返回 0、对超出 Integer.MAX_VALUE 的计算安全失败，并在测试中固定相同输入得到相同结果。

- [ ] **Step 3: 实现事实优先、知识去重和低排名裁剪**

组装顺序固定为 system/schema、Core facts、knowledge evidence、reviewer input、reserve。知识排序使用分数降序、document ID 升序作为稳定 tie-breaker；每个片段携带 document/version/section/checksum/source。裁剪后若关键事实缺失，直接返回错误，不调用模型。

- [ ] **Step 4: 运行上下文测试并接入分析编排**

    .\mvnw.cmd -B -ntp "-Dtest=ContextAssemblerTest" test

将 AssembledContext 接到现有确定性 MVP 分析路径，保证一次运行只读取一个已发布知识版本，并把上下文版本信息传给 ModelRequest 和结果持久化层。

### Task 5: 模型用量账本、价格表和低基数指标

**Files:**
- Create: src/main/resources/db/migration 中当前最高版本加一的 create_model_usage_ledger.sql
- Create: src/main/java/com/finguard/copilot/usage/ModelCostCalculator.java
- Create: src/main/java/com/finguard/copilot/usage/ModelUsageRecorder.java
- Modify: 现有分析持久化/Worker 事务代码
- Test: src/test/java/com/finguard/copilot/usage/ModelCostCalculatorTest.java
- Test: src/test/java/com/finguard/copilot/MigrationTest.java

**Interfaces:**
- ModelCostCalculator.estimate(ModelUsage usage, PriceTable priceTable): MoneyAmount，输入/输出单价按每百万 Token 的十进制定价计算。
- ModelUsageRecorder.record(analysisId, provider, modelConfigurationVersion, promptVersion, usage, outcome)。
- model_usage_ledger 至少保存 analysis_id、provider、model configuration version、prompt version、input/output tokens、latency、estimated cost、price table version、outcome、created_at；不保存 Prompt 正文或事实快照。

- [ ] **Step 1: 写成本和迁移测试**

用固定价格表测试 1,200 输入 Token 与 180 输出 Token 的 Decimal 成本、零 Token、价格表版本和四舍五入规则。迁移测试断言新表字段、索引和唯一关联存在，并确认旧 migration 文件内容未改动。

- [ ] **Step 2: 实现十进制定价和账本写入**

成本计算禁止 double，使用 BigDecimal；价格表作为版本化配置快照写入每条账本记录。账本写入与分析结果状态更新在同一事务边界内，重复消息不能重复记账。

- [ ] **Step 3: 增加低基数 Micrometer 指标**

记录 copilot.model.calls、copilot.model.input.tokens、copilot.model.output.tokens、copilot.model.latency、copilot.model.estimated.cost、copilot.model.failures。标签只能使用 provider、model configuration version、outcome、failure category 等低基数字段，禁止 username、analysis ID、reviewTask ID、document ID、Prompt 文本和事实值。

- [ ] **Step 4: 运行数据库和指标测试**

    .\mvnw.cmd -B -ntp "-Dtest=ModelCostCalculatorTest,MigrationTest,*Usage*Test" test

### Task 6: Python Golden Dataset 和确定性模型评测指标

**Files:**
- Modify: tools/agent-evaluation/src/finguard_agent_evaluation/models.py
- Modify: tools/agent-evaluation/src/finguard_agent_evaluation/dataset.py
- Create: tools/agent-evaluation/src/finguard_agent_evaluation/metrics.py
- Create: tools/agent-evaluation/src/finguard_agent_evaluation/runner.py
- Create: tools/agent-evaluation/src/finguard_agent_evaluation/report.py
- Create: tools/agent-evaluation/data/phase3-golden.jsonl
- Test: tools/agent-evaluation/tests/test_metrics.py
- Test: tools/agent-evaluation/tests/test_report.py

**Interfaces:**
- EvaluationCase 增加 input_facts、knowledge_evidence、reviewer_input、expected_recommendation、expected_fact_paths、expected_citation_ids、is_injection_case、is_insufficient_evidence。
- evaluate_case(expected, actual): CaseMetrics。
- summarize(results): EvaluationReport，输出 JSON 可序列化、排序稳定、无秘密/原始 Prompt。

- [ ] **Step 1: 写 Python schema 和指标失败测试**

测试最少覆盖六类脱敏案例：高额交易、高频交易、重复对账、可疑对账、证据不足、恶意文档/用户注入。指标测试必须验证：推荐准确率、事实字段覆盖、引用存在性/覆盖率、结构化 JSON 成功率、拒答命中率、P50/P95 延迟和估算成本均可计算。

- [ ] **Step 2: 创建固定 Golden Dataset**

每行使用固定 case_id 和正整数 review_task_id，事实中的金额使用字符串、时间带时区；知识证据只放制度/手册片段和引用元数据，不放真实账户资料、JWT、密钥或动态生产数据。至少包含一个预期 INSUFFICIENT_EVIDENCE 和一个注入案例。

- [ ] **Step 3: 实现确定性指标和报告生成**

报告必须保存 dataset version、prompt version、model configuration version、knowledge index version、运行时间、样本数和指标；浮点数统一固定小数位，列表按 case_id 排序。失败案例只输出分类、case ID 和最小必要字段，不输出模型原文。

- [ ] **Step 4: 运行 Python 测试**

    Push-Location tools\agent-evaluation
    uv sync --locked --python 3.12
    uv run pytest -q
    Pop-Location

预期：无网络、无模型密钥时全部测试通过。

### Task 7: 双候选模型比较运行与选型证据

**Files:**
- Modify: tools/agent-evaluation/src/finguard_agent_evaluation/runner.py
- Modify: tools/agent-evaluation/src/finguard_agent_evaluation/report.py
- Create: tools/agent-evaluation/reports/phase3-stub-baseline.json
- Create: tools/agent-evaluation/reports/phase3-real-model-comparison.json
- Create: docs/evaluations/phase3-model-selection.md
- Create: docs/evaluations/phase3-baseline-schema.md
- Create: scripts/run-phase3-evaluation.ps1
- Modify: TASKS.md（只在比较报告和验收通过后勾选 Phase 3 项）

**Interfaces:**
- Runner 通过公开 Copilot API 或脱敏导出取得同一批案例结果，不直连生产数据库。
- 运行参数显式接收 model-config-version、prompt-version、dataset-version、knowledge-index-version 和输出路径。
- 报告同时包含 Stub、Qwen/DashScope 候选和 DeepSeek 候选的可比字段。

- [ ] **Step 1: 先生成 Stub 基线**

    Push-Location tools\agent-evaluation
    uv run python -m finguard_agent_evaluation.runner --mode stub --dataset data/phase3-golden.jsonl --prompt-version review-analysis-v1 --output reports/phase3-stub-baseline.json
    Pop-Location

预期：报告可重复生成，普通环境不访问网络；Stub 不能被写成真实模型成功率证据。

- [ ] **Step 2: 为真实模型运行增加显式环境检查**

运行器在启动前检查 FINGUARD_COPILOT_BASE_URL、COPILOT_MODEL_PROVIDER、COPILOT_MODEL_ID、COPILOT_MODEL_API_KEY 和 COPILOT_PRICE_TABLE_VERSION；缺少任一项直接退出并打印字段名，不打印值。运行器不接受命令行 URL 覆盖服务端固定白名单。

- [ ] **Step 3: 在同一数据集上分别运行两个候选**

    $env:SPRING_PROFILES_ACTIVE = "real-qwen"
    $env:COPILOT_MODEL_PROVIDER = "qwen"
    $env:COPILOT_MODEL_ID = "qwen-plus"
    .\scripts\run-phase3-evaluation.ps1 -Output reports/phase3-qwen.json

    $env:SPRING_PROFILES_ACTIVE = "real-deepseek"
    $env:COPILOT_MODEL_PROVIDER = "deepseek"
    $env:COPILOT_MODEL_ID = "deepseek-chat"
    .\scripts\run-phase3-evaluation.ps1 -Output reports/phase3-deepseek.json

脚本必须在调用前校验服务端健康、dataset/prompt/index 版本相同；真实运行失败时报告失败原因和已完成样本数，不伪造缺失结果。密钥只通过当前进程环境或秘密文件注入。

- [ ] **Step 4: 形成模型选择报告**

docs/evaluations/phase3-model-selection.md 必须逐项比较：推荐准确率、事实一致性、引用存在性/覆盖率、JSON 成功率、注入抵抗/拒答命中率、P50/P95 延迟、单次/每案例估算成本、超时/限流失败率。明确写出主模型、备用模型、Stub 用途和选择理由；若没有真实运行证据，只能标记为未完成，不能凭模型知名度选择。

将两个真实运行报告按同一 dataset/prompt/index 版本合并为 tools/agent-evaluation/reports/phase3-real-model-comparison.json，再以该聚合报告作为 TASKS.md 中“两个候选模型已比较”的唯一证据。

### Task 8: Phase 3 集成验收、文档和收尾门禁

**Files:**
- Modify: README.md
- Modify: TASKS.md
- Modify: docs/ROADMAP.md（只同步实际完成状态/证据链接）
- Modify: docs/ARCHITECTURE.md（仅当字段或版本命名与实现不同）
- Modify: scripts/verify.ps1、scripts/verify.sh（加入无密钥普通验证和可选真实评测说明）

**Interfaces:**
- Produces a repeatable Phase 3 acceptance record covering Java, Python, schema, context, usage, two-model comparison, security, and cleanup evidence.

- [ ] **Step 1: 运行普通全量验证**

    Remove-Item Env:COPILOT_MODEL_API_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:DASHSCOPE_API_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
    .\scripts\verify.ps1

预期：Java、Python、前端、迁移和静态检查通过；没有网络模型调用，没有 skipped 测试。

- [ ] **Step 2: 做范围、秘密和高基数指标审计**

    rg -n -i "BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|api[_-]?key\s*[:=]\s*[^$\\{]" -g '!*.md' .
    rg -n "analysisId|reviewTaskId|documentId|username|prompt" src/main/java/com/finguard/copilot/usage src/main/java/com/finguard/copilot/model
    git diff --check
    git status --short

预期：无秘密；指标标签没有用户/任务/文档/Prompt 高基数或敏感值；只有计划范围内的文件变更。

- [ ] **Step 3: 生成 Phase 3 验收记录**

记录以下直接证据：真实 profile 缺密钥会安全失败；默认测试离线通过；两个候选在同一 Golden Dataset 上有真实结果；上下文预算和裁剪测试通过；模型用量账本与低基数指标通过；Schema/事实/引用/注入安全校验通过；主模型、备用模型和 Stub 用途有版本化报告。

- [ ] **Step 4: 更新文档状态但不扩大范围**

只有前述证据齐全时，才在 TASKS.md 勾选 Phase 3 项，并在 README.md 写明真实模型评测的时间、数据集版本、Prompt 版本和已知限制。不得把 Phase 4 的 Redis 配额、LLM-as-Judge、Langfuse、SSE、UI 或 Phase 5 的 Agent Graph 标记为已完成。

- [ ] **Step 5: 提交前检查，等待用户授权**

    git diff --check
    git status --short
    git diff --stat

默认只报告结果，不执行 git commit 或 git push。获得明确授权后再使用：

    git add src tools/agent-evaluation docs/evaluations docs/superpowers/plans/2026-08-27-phase-3-real-model-context-engineering.md TASKS.md README.md scripts
    git commit -m "feat: complete phase three model evaluation"

## Phase 3 Acceptance

- 两个真实候选模型通过同一 OpenAI-compatible 领域契约接入，真实 profile 显式启用，默认测试仍完全离线。
- 32,000 输入/2,000 输出预算由服务端强制；事实优先，知识去重和低排名裁剪可由测试证明，关键事实不会被静默截断。
- 结构化输出、事实一致性、引用存在性和注入抵抗均在模型正文进入结果前校验；失败不产生方向性建议。
- Golden Dataset 至少覆盖高额、高频、重复、可疑对账、证据不足和注入案例，版本和脱敏边界清晰。
- Stub、Qwen/DashScope、DeepSeek 在相同数据集、Prompt 和知识索引版本下有可追溯比较报告，包含正确率、引用、拒答、JSON、延迟、Token 和成本。
- 用量账本保存供应商、模型、Token、延迟、估算成本和价格表版本，但不保存秘密、Prompt 正文、JWT 或动态事实。
- 主模型、备用模型和 Stub 选择依据被固化为文档；不实现超出 Phase 3 范围的 Agent Graph、HITL、Redis 配额或真实生产部署。
- 全量普通验证、git diff --check、范围审计和秘密扫描通过；未获用户明确授权不得提交或推送。

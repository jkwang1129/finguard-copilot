# 2026-10-27 Phase 4 诊断与安全边界增量

本记录接续 [第一批验收](2026-10-27-phase4-first-batch.md)。本轮只使用离线测试和合成响应，没有调用模型供应商，也没有改动冻结的评测器、数据、Prompt 或历史结果。

## 受控建议枚举诊断

新增 `diagnostic_v4.py`，使用现有 v3 解析器产生的脱敏 `ActualAnalysis`，只报告案例 ID、调用方提供的解析检查状态、`CONFIRMED`/`IGNORED`/`INSUFFICIENT_EVIDENCE` 枚举及与标签是否一致；检查失败时不记录建议。报告不保存原始模型正文、事实、政策原文或 Prompt。

离线 CLI 读取的数据集只取一次字节并计算 SHA-256；输入结果首行的运行清单必须匹配该哈希与数据集、Prompt、模型、索引版本，案例按 ID 一一对应。输出使用独占创建，避免覆盖旧报告；非法输入的校验异常不回显原文。独立复核发现的异常泄露与旧结果串用问题均补回归并修复。

该机制依赖输入中由调用方提供的 v3 检查标记和运行清单；二者可被伪造，Prompt、模型和索引版本名称也不与文件内容独立核验，报告不是独立的安全或业务正确性证明。旧 H03 报告没有记录实际建议枚举，仍不能恢复历史输出。命令及输入契约见 [评测工具说明](../../tools/agent-evaluation/README.md)。

## 已知注入与边界测试

- 使用冻结 H12 的恶意文档片段和合成模型结果，先复现模型只引用另一份正常文档时，旧路径仍接受方向性建议；新增 `UntrustedInstructionGuard` 后，已识别的覆盖指令会令方向性输出以 `FACT_VALIDATION_FAILED` 拒绝。护栏扫描实际保留并发给模型的所有证据及 reviewer input，而不仅扫描最终引用。
- 使用冻结 H11 的审核输入做护栏组件测试；当前 HTTP 创建请求只接收 `reviewTaskId`，额外的 `reviewerInput` 不进入创建命令或响应，因此此项不是用户自由文本的真实端到端验收。
- 覆盖非 REVIEWER 与跨审核员查询、知识文档中本地 URL/凭据不回显、模型请求不提供工具以及工具调用响应被拒绝；这些测试只证明所列边界和样本。

覆盖指令检测仅识别一小组明确的中英文模式，可能误报、漏报；模型调用发生在此输出护栏之前。它不能证明通用 Prompt 注入防御，也不能代替后续扩展攻击集、真实模型测试和人工审核。工具执行、URL 白名单和模型工具参数的完整治理仍属于后续阶段。

2026-10-31 补充：发现并修复供应商响应同时包含正文及 `tool_calls` 或旧式 `function_call` 时，先前反序列化会忽略工具字段的问题。现在两种调用均按模型输出无效拒绝；空 `tool_calls` 数组和空值仍兼容。该改动不提供或执行工具，也不构成 Phase 5 的工具参数治理。新增两个失败后修复的回归；相关模型、编排、注入与 HTTP 测试共 40 项通过。受冻结清单保护的 `CompatibleChatModelGateway.java` 未改动，两份冻结清单 12 个条目仍匹配。

## 验证

- Python 全套：`uv run --python 3.12 pytest -q`，**120 项通过**。
- Java 定向安全回归：`UntrustedInstructionGuardTest,ModelAnalysisOrchestratorTest,ReviewAnalysisControllerTest,GetAnalysisServiceTest,CreateAnalysisServiceTest,KnowledgeDynamicFactRejectionTest,CompatibleChatModelGatewayTest`，**45 项、0 失败/错误/跳过**。
- Java 全量：`mvnw.cmd -B -ntp -Dspring.test.context.cache.maxSize=1 test`，**291 项、0 失败/错误/跳过，BUILD SUCCESS**。
- 两份 Phase 3 冻结清单共 12 个条目均与当前 SHA-256 一致；本轮没有修改这些冻结文件。

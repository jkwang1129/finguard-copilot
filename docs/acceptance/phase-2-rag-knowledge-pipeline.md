# Phase 2 RAG 与知识数据管道验收记录

日期：2026-08-27

## 结论

Phase 2 的八项交付已在当前隔离 worktree 完成实现，并具备当前测试证据：知识文档、
确定性摄取、版本化 Embedding/Qdrant、候选评测与原子发布、增量更新/删除/回滚、检索
引用校验、固定 RAG 评测集和动态 Core 事实隔离均已覆盖。未执行 commit 或 push。

这不是两服务进程级 HTTP E2E 证明：仓库仍没有用于同时启动 Core 与 Copilot 的隔离
Compose/seed harness；Core 与 Copilot 的测试分别通过各自仓库的真实依赖验收。

## 新鲜验证证据

| 范围 | 命令/方式 | 结果 |
|---|---|---|
| Copilot 全量 | `.\scripts\verify.ps1` | Java 210/210；Python 5/5；前端 lint、test 1/1、build 通过；npm audit 0 vulnerabilities；`git diff --check` 通过 |
| Core | `mvn -B -ntp -q test`，`C:\dev\finguard-core` | 379 tests，0 failures，0 errors，0 skipped；Core worktree clean |
| Qdrant 适配器 | `.\mvnw.cmd -B -ntp "-Dtest=QdrantVectorStoreIntegrationTest" test` | 1/1；真实 `qdrant/qdrant:v1.15.5`，版本隔离与显式删除通过 |
| 更新/删除/回滚 | `.\mvnw.cmd -B -ntp "-Dtest=KnowledgeIndexChangeIntegrationTest" test` | 1/1；真实 MySQL 8.4.10 + Qdrant；正文从 MySQL 回取并校验 |
| 引用闭环 | `Phase2CitationIntegrationTest`、检索/引用校验测试 | 7/7 focused；有发布索引时附带引用，无发布证据时保持 `INSUFFICIENT_EVIDENCE` |
| 固定数据集 | `Push-Location tools\agent-evaluation; uv run --python 3.12 pytest -q; Pop-Location` | 3 个 RAG case；全套 Python 5/5 |

全量 Java 测试需要 Docker；Testcontainers 使用 Ryuk 回收隔离容器。常驻的开发容器未被
停止、删除或重建。

## 端到端知识场景

`KnowledgeIndexChangeIntegrationTest` 当前覆盖以下顺序：

1. 登记两个经过准入检查的策略/手册文档，构建并发布 `published-v1`。
2. 从已发布版本检索策略片段；Qdrant 只返回元数据，正文从 MySQL 按文档 ID、版本、片段 ID 回取，并以 SHA-256 checksum 校验。
3. 用 `doc-1:v2` 替换旧文档，构建并发布新候选；旧 `published-v1` 的向量仍可通过显式历史版本搜索，新版本可检索更新内容。
4. 删除 `doc-1:v2`，发布只包含 `doc-2:v1` 的候选；已发布版本的成员关系没有原地修改。
5. 回滚到 `published-v1`，活动指针和文档/版本/片段成员集合恢复到原始状态。

候选索引只有在固定评测完成并达到发布阈值后才能切换；Embedding、Qdrant upsert、评测、
阈值或过期发布失败都会保持原活动版本。

## 数据与安全边界

- Qdrant payload 白名单只有 `documentId`、`documentVersion`、`chunkId`、`sectionPath`、
  `contentChecksum`；请求测试明确断言不发送正文、动态交易字段或任意扩展字段。
- 准入策略拒绝动态 Core 字段、JWT/Authorization、密码/密钥和任意 URL；固定数据集只含
  脱敏查询、文档/片段标识和引用版本。
- Copilot 的 Core client 仍只有 `/api/auth/me` 与
  `/api/review-tasks/{reviewTaskId}/context` 两个固定 GET 契约；本阶段没有 Core 写请求。
- MySQL 的知识正文、checksum、索引版本、评测指标、发布状态和引用元数据分离保存；运行时
  解析一个已发布版本，不默认搜索 retired 或未指定版本。

## 固定 RAG 基线

`tools/agent-evaluation/data/rag_cases.jsonl` 有 3 个固定 case：2 个可回答 case 和 1 个
无答案 case。离线工具提供 recall@K、引用精确率和无答案正确率；当前仅验证数据模型、
指标定义和确定性计算，不把离线规则分数伪装成真实模型效果。真实模型、Golden Dataset、
LLM-as-Judge、Token/延迟/成本比较属于 Phase 3/4。

## 清理、限制与下一阶段

本次只使用 Testcontainers 创建的临时 MySQL/Qdrant/Ryuk 资源，测试结束后由 Testcontainers
回收；现有 `finguard-*` 开发容器保持运行。Phase 2 没有新增进程级 Core/Copilot HTTP
验收 harness，也没有生产 Qdrant 数据恢复演练。

Phase 3 的入口是：真实模型 adapter 与显式 profile、32k/2k 上下文预算、事实优先裁剪、
Golden Dataset 模型比较、引用/拒答/延迟/成本指标，以及保持普通测试不依赖模型密钥。

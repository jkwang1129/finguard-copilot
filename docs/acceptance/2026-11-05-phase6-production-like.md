# Phase 6 production-like 验收

## 发布与回滚

验收环境是 Windows Docker Desktop 的 Linux Engine。部署脚本在独立 Linux CLI 容器内执行，发布输入为隔离测试仓库的两个 Git 提交及锁定的 Core `941832b64b1c17096502da2c2d66267561e58e20`。正式构建入口在 CI 中使用当前仓库的完整 Git SHA。

Linux 实测从归档启动 A，升级后看到 B 的 HTML 标记，再回滚到 A。两次切换均核对实际运行镜像 ID。MySQL 容器保持同一个，合成记录在升级和回滚后仍可读取。原始报告为 `target/phase6-release/release-report.json`。

## 镜像与依赖检查

- 首轮系统包扫描在 Java 基础镜像中命中旧 Brotli 等包，在前端镜像中命中旧 Expat。更新 Java 基础镜像摘要并固定前端 Expat 修订后，三种最终应用镜像复扫各有 0 条系统包版本命中。
- Trivy Java 索引从两个仓库下载均中断。本地从最终镜像提取 Boot JAR，以嵌入元数据或制品哈希对应 Maven 坐标，生成 113 个 Core 组件和 123 个 Copilot 组件的清单，再用同一漏洞库扫描。Core 有 35 条原始版本命中，Copilot 有 2 条，原始报告为 `target/phase6-release/scan-library-*.json`。这些结果属于版本匹配。
- Copilot 两条 Spring MVC 命中为 CVE-2026-47884 和 CVE-2026-47890。当前 Web 路由使用 REST 和结构化 SSE，已审查的应用范围缺少公告描述的 XSLT 或 SSE 视图片段渲染入口。修复这两条公告的 6.2.20 属于 Enterprise，开源修复从 7.0.9 开始。Spring AI Alibaba 的 Boot 4 发布线仍是里程碑版本。兼容平台迁移及 Core 依赖审阅保留为独立门槛。 [XsltView 公告](https://spring.io/security/cve-2026-47884/) · [SSE 公告](https://spring.io/security/cve-2026-47890/) · [Spring AI Alibaba 发布](https://github.com/alibaba/spring-ai-alibaba/releases)
- 变更范围 Gitleaks 扫描无命中，CI 配置通过 actionlint。GitHub 远端冷缓存构建和镜像扫描仍待执行。

## 数据与消息恢复

- Core 和 Copilot 两个库用 `mysqldump --single-transaction --hex-blob` 导出，并恢复到无外部网络的新 MySQL 实例。重新导出的数据和规范化 DDL 一致。规范化只处理三列重导时显式补出的、已由排序规则确定的字符集。
- RabbitMQ 停机期间受理的分析保留在 Outbox。恢复后分析完成，重发原消息后 broker ACK 增加，数据库仍只有一条对应 Worker 回执和一笔模型用量。
- 删除验收项目的 Qdrant 卷并重新启动空实例。从 SQL 的已发布成员重建候选，评测通过后发布。新分析的持久化运行清单指向新索引，结果包含有效政策引用。恢复前后的 Core 业务和审计校验和一致。

## 故障演练

| 服务 | 停机结果 | 恢复证据 |
| --- | --- | --- |
| Core | 分析创建返回 HTTP 503，无模型调用 | 新分析成功 |
| MySQL | 分析创建返回 HTTP 500，无模型调用 | 新分析成功 |
| RabbitMQ | 分析仍可受理，Outbox 持久保存 | 待处理分析成功，原消息重投仍仅有一笔模型用量 |
| Redis | `MODEL_CAPACITY_UNAVAILABLE`，无结果或模型调用 | 新分析成功 |
| Qdrant | P4 预检返回 `MODEL_CONFIGURATION_INVALID`，无结果或模型调用 | 新分析成功 |
| Langfuse | 分析成功，Prometheus 记录导出失败 | 重启后新分析成功并再次收集 Span |
| 本地模型替身 | `MODEL_TIMEOUT`，分析无结果，保留一笔 `UNKNOWN_HELD` | 新分析成功 |

Langfuse 的 web、worker 在验收中均以 UID 1001 运行。停机前和恢复后，各从 Langfuse 查到 15 条对应分析的观测。Prometheus 从私有网络抓取 Copilot，Grafana 健康检查通过。Core 业务及审计校验和在故障演练前后保持一致。

Agent 在容器重启后保留等待修订号。人工继续可以幂等重放，最终轨迹和持久账本各核对出两次模型调用，轨迹包含两次工具执行。真实 STDIO 客户端协商协议 `2025-11-25`，成功读取两次后受共享四槽预算限制。网页代理签发凭证返回 403，容器回环可签发并验证撤销。

| 批次报告 | 可用证据 |
| --- | --- |
| `target/integration/finguard-p6-c84810e8066c/report.json` | 三类数据及消息恢复通过，后续 Qdrant 故障码断言失败并留存 |
| `target/integration/finguard-p6-68bd968c8b9e/report.json` | Core、MySQL、Redis、Qdrant、Langfuse 故障项通过，模型超时断言与 Agent 跨进程计数断言失败并留存 |
| `target/integration/finguard-p6-653a399797c7/report.json` | 模型超时、单笔预算占用和恢复均通过，Agent 的两次调用和工具轨迹通过，网页代理拒绝 MCP 签发 |
| `target/integration/finguard-p6-ea0e9ae5575c/report.json` | Agent 和容器回环 STDIO MCP 独立复验 46/46 |

验收依据是各场景的实际通过项。早期整批的失败状态保留。

## 回归

Java 17 全量 640 项通过，失败、错误和跳过均为 0。Python 评测 187 项、MCP 桥 4 项、固定模型清单的本地 CPU 语义运行 31 项均通过。前端 18 项测试、lint 和生产构建通过。回归使用本地 Maven 缓存，CI 冷缓存单独记录。

## 清理与待关闭门槛

本次容器、Compose 网络、数据卷和验收镜像已移除。开始前后的运行容器名单一致。删除磁盘中临时发布归档和提取 JAR 的命令被自动执行策略拒绝，文件留在 `target/phase6-release/`。

本地 production-like 工程和分场景演练已经验收。GitHub 远端冷缓存 CI、Copilot 的 Spring 平台兼容升级，以及锁定 Core 制品的 Java 依赖审阅仍开放。

执行及故障演练命令见[发布、回滚与恢复手册](../operations/phase6-release.md)。本地数据与模型调用均为合成验收范围。

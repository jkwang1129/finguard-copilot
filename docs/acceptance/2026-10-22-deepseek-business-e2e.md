# 2026-10-22 DeepSeek 真实业务链路验收

## 结果

15 项检查全部通过，真实 Core、Copilot、MySQL、RabbitMQ、Redis、Qdrant 已发布知识索引与 DeepSeek 已连通。Copilot 使用 `real-deepseek`、`deepseek-flash` 和冻结的 `review-analysis-v2`，不是 Python 评测器代替 Java 业务请求。

- runId：`20261022145508`。
- analysisId：`86ba1f3b-a2f5-4b74-9010-ce6e9c88a662`，终态 `SUCCEEDED`，建议 `CONFIRMED`。
- 已发布索引：`published-20261022145508`，精确引用 1 条来自 `e2e-large-amount-policy` / `v1`。
- 真实模型调用 1 次，输入 1937、输出 617 Token，延迟 3048 ms，账本 `SUCCESS` 1 条。
- Outbox 已发布记录和 Worker receipt 各 1 条；重复 HTTP 幂等请求返回同一 analysisId。
- Core 审核保持 `PENDING`、版本 `0`、reviewed_by 为 NULL；业务与审计表 checksum 在请求前后保持一致。
- 对应 RabbitMQ vhost 的全部队列 ready/unacknowledged 均为 0；请求、重试与 DLQ 无积压。

[完整脱敏检查报告](deepseek-business-e2e-2026-10-22.json)包含 15 项实际断言。原始报告和服务日志在 `target/business-e2e/20261022145508/`，不提交 Git。

## 本轮接线改动

`scripts/run-business-e2e.ps1` 从本机模型配置推导 provider、模型、端点和 profile；DeepSeek 默认使用官方固定端点 `/chat/completions`。显式 provider 与当前凭据 provider 不匹配、或者端点不在供应商白名单时，在启动前拒绝，防止把 DeepSeek Key 发往千问。

四项离线 PowerShell 边界测试先失败后通过：DeepSeek profile/端点/单次预算、供应商凭据错配拒绝、非白名单端点拒绝、显式千问配置仍可复现。新增 `-PlanOnly` 不启动服务、不调用模型、不创建验收数据库或 Qdrant 容器。

## 资源与范围

使用本轮新建的 `fg_core_e2e_20261022145508`、`fg_copilot_e2e_20261022145508`、RabbitMQ vhost `fg-e2e-20261022145508` 和 Qdrant 容器 `fg-e2e-qdrant-20261022145508`。默认结束已停止本轮两个 Java 进程和 Qdrant；独立数据库、vhost 和停止的容器保留以供核查。现有 MySQL、RabbitMQ、Redis 未被停止，原业务库未用于本轮写种子。

这是 SQL fixture 创建的单个合成审核案例，不是 CSV 上传/对账全过程验收。固定 8 维 Embedding 与单文档索引仅证明发布和检索基础设施连通，不证明语义召回质量或生产审核准确率。用量零估算价格不代表供应商实际费用为零。

## 复跑

在当前 Copilot 工作区，确认 Core 含认证和上下文契约、两个项目已构建、8080/8081/6333 空闲、原中间件健康，且本机 `.env.local` 已配置对应 DeepSeek 凭据：

```powershell
# 离线检查，不产生模型调用
.\scripts\run-business-e2e.ps1 -PlanOnly

# 将执行一次真实模型请求，需另有预算授权
.\scripts\run-business-e2e.ps1 -Provider deepseek -ModelId deepseek-flash
```

详细隔离和构建步骤见 [运行手册](business-e2e-runbook.md)。

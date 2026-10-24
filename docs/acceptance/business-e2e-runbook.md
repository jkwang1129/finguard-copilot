# Core / Copilot 真实进程业务验收

此入口启动两个独立 Java 服务，通过实际 HTTP、MySQL、RabbitMQ 和 Qdrant 运行一次合成审核分析。
模型从当前进程或本机 `.env.local` 配置选择；当前默认使用 DeepSeek `real-deepseek`，不使用 Stub 替代真实模型。显式指定的 provider 必须与凭据配置匹配，端点必须在供应商白名单内。固定 Embedding 用于可重复验证基础设施，不能用于证明语义检索质量。

## 前置条件

- Docker 中现有 `finguard-mysql`、`finguard-rabbitmq`、`finguard-redis` 正常运行；Redis 映射本机 6380。
- 本机 8080、8081、6333 空闲，Java 17 和 Maven 可用。
- Core 使用包含 `/api/auth/me`、`/api/review-tasks/{id}/context` 的工作区，默认 `C:\dev\worktrees\finguard-core-phase1`。
- 当前进程或 Git 忽略的 `.env.local` 配置匹配的 provider、模型、端点和 `COPILOT_MODEL_API_KEY`。脚本中不保存 Key。

## 执行

在 Core 工作区构建：

```powershell
mvn.cmd -B -ntp -DskipTests package
```

在 Copilot 工作区构建并执行：

```powershell
.\mvnw.cmd -B -ntp -DskipTests package dependency:build-classpath '-Dmdep.outputFile=target/e2e-classpath.txt'
.\scripts\run-business-e2e.ps1 -PlanOnly
# 以下会消耗一次真实模型请求
.\scripts\run-business-e2e.ps1 -Provider deepseek -ModelId deepseek-flash
```

`-CoreRoot` 可指定包含上述契约的 Core 工作区。默认每次创建独立的两个 MySQL 数据库、一个 RabbitMQ vhost 和一个 Qdrant 容器；现有 Core 业务库不会用于写入种子。

## 验收范围

1. Core 和 Copilot 健康检查为 UP，真实基础设施连接成功。
2. CLI 调用现有知识登记、构建、评测、发布服务，形成 PUBLISHED 索引；Qdrant 只保存向量及白名单元数据，正文从 MySQL 回取并校验 checksum。
3. 在独立 Core 数据库创建符合约束的合成审核任务。这一步是验收 fixture，不代表 CSV 上传和对账流程已通过本轮 HTTP 验收。
4. 真实登录获得 REVIEWER JWT，调用 Core 身份和上下文接口。
5. Copilot 拒绝未认证请求和仅具 ADMIN 的请求；合法请求返回 202，相同幂等键复用同一 analysisId。
6. 事务 Outbox 经 RabbitMQ 到 Worker；真实模型输出经 Schema、事实和引用校验后写入 MySQL。
7. 核对发布记录、Worker receipt、模型用量和政策引用；Core 业务及审计表 checksum 保持不变，审核决定仍为 PENDING。

报告与服务日志写入 `target/business-e2e/<runId>/`，不进入版本控制。报告不包含 JWT、API Key、账号密码或模型原文。默认结束后停止本次 Java 进程和 Qdrant 容器，保留独立数据库、vhost 和停止的容器供核查；原有中间件保持运行。

真实模型请求每个分析最多一次，无隐式故障转移。`e2e-unpriced-v1` 使用零估算价格，不代表供应商实际费用为零。运行结果只证明单个合成案例的连通性，不替代 Golden Dataset 的质量评测。

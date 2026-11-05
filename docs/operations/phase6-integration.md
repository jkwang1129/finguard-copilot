# Phase 6 本地集成环境

集成环境用真实 Core、Copilot、MySQL、RabbitMQ、Redis、Qdrant 和 React 静态制品运行只读审核辅助。每次验收从指定的 Core Git 提交构建镜像，使用新 Compose 项目和独立数据卷。两个应用用不同的 MySQL 用户，Copilot 用户只有自己的 schema 权限。浏览器侧只发布本机的 React 和 Core 控制台端口；Copilot API、管理端点和中间件留在容器网络内。

## 从空环境验收

准备 Git、Python 3.11.5 或以上、Docker Engine（Linux 容器）和支持 `additional_contexts` 与 `up --wait` 的 Compose。首次构建需要访问镜像仓库、Maven Central 和 npm。Core 源码提交须提供 `/api/auth/me`、`/api/review-tasks/{id}/context` 和 `/demo/index.html`。本次使用 Core 提交 `941832b64b1c17096502da2c2d66267561e58e20`。Core 用已有的聚合健康端点作就绪检查，Copilot 的存活与就绪探针留在容器内。

从 Copilot 根目录运行。Windows 可将 `../finguard-core` 替换成 `C:/dev/finguard-core`。

```sh
python scripts/run-integration.py --core-root ../finguard-core --core-ref 941832b64b1c17096502da2c2d66267561e58e20
```

脚本默认使用本机端口 15173（React）和 18080（Core）。端口占用时可通过 `--web-port` 和 `--core-port` 指定两个不同端口。脚本生成本次凭据，等待依赖就绪，用现有知识服务发布合成政策，并在独立 Core 库插入一条合成审核任务。随后通过前端代理发起分析，检查鉴权、幂等、SSE、政策引用、Outbox 和 Worker 回执，最后比对 Core 业务与审计表的校验和。

本地模型替身复用 Phase 5 的 HTTP fixture，通过 Copilot 容器的回环地址访问。两者只加入 Compose 内部网络，使用固定 Embedding 和合成数据。验收覆盖默认只读路径、打包产物和代理链路。Agent、MCP、真实模型、语义检索质量和遥测接入分别验收。

## 查看或清理

默认执行结束后移除本次容器、网络、卷和应用镜像。报告位于 `target/integration/<项目名>/report.json`，失败时同目录保存容器日志。镜像基础层和构建缓存可供下次构建复用。

需要手动查看页面时，在命令末尾加 `--keep`。通过后可访问 `http://127.0.0.1:15173/`，使用 `e2e-reviewer` 和本次目录 `.env` 中的 `FG_REVIEWER_PASSWORD`。使用输出中的 Compose 清理命令销毁本次栈，之后可删除本次目录中的 `.env` 和 `core-source`。保留验收报告即可。

## 后续工作包

| 工作包 | 验收 |
| --- | --- |
| P6-2 构建与发布 | CI 镜像检查、完整 Git SHA 与制品关联，Linux 部署与回滚实测 |
| P6-3 恢复 | MySQL 备份恢复、RabbitMQ/Outbox 恢复、Qdrant 由知识源重建 |
| P6-4 故障与总验收 | 部署环境接入遥测，按服务注入故障，验收降级、恢复、回滚和清理 |

## P6-1 验收记录

2026-11-05，在 Windows Docker Desktop 的 Linux 容器环境中，批次 `finguard-p6-ed620fe33ea9` 从空卷启动，36 项检查全部通过。审核分析返回 `SUCCEEDED`，浏览器所用的结果 API 和 SSE 均经 Nginx 验证，Outbox 和 Worker 回执各一条，本地模型 HTTP 调用一笔且账本估算为零。Core 审核决定保持 `PENDING`，8 张业务与审计表校验和一致。

前端 18 项测试、lint 和镜像内生产构建通过。首轮通过日志定位到 Nginx 默认 Host 含下划线被 Tomcat 拒绝，修正 Host 转发后用新项目和空卷复跑。Maven 下载发生超时，本轮用本机已有依赖预热构建缓存，冷缓存构建留待 CI 验证。验收脚本确认本次 Compose 资源已清理，原始报告保存在 `target/integration/finguard-p6-ed620fe33ea9/report.json`。

# Phase 6 发布、回滚与恢复

## 生成制品

CI 在 Java、Python、前端测试和秘密扫描之后，从 Copilot 的完整 Git SHA 与锁定的 Core 提交构建三个非 root 镜像。制品包含 `images.tar`、`manifest.json` 和各镜像的 Trivy 报告。清单绑定镜像 ID、两个源码提交、镜像归档校验和及两套 Flyway 迁移指纹。扫描结果作为发布审阅输入。

也可在具备 Docker 和 Python 3.11.5 以上的构建机上复跑。构建读取两个 Git 提交的归档。

```sh
python3 scripts/release.py build --core-root ../finguard-core \
  --core-ref "$(cat ops/integration/core-revision.txt)" \
  --expect-sha "$(git rev-parse HEAD)" --output target/releases
```

## Linux 部署与回滚

在 Linux 环境准备 Docker Engine、支持 Compose `!reset` 与 `up --wait` 的插件、Python 3.11.5 以上以及仓库中的部署脚本与配置。将受信任的制品解包到 `<发布目录>/<完整 SHA>/`。部署从归档加载镜像，核对镜像 ID 与 OCI 标签，再等待健康检查。默认 React 和 Core 监听本机 15173 与 18080，Core 端口与前端制品内的链接一致。

为新的隔离环境生成凭据并限制文件读取权限。升级和回滚复用同一份环境文件与状态目录。

```sh
umask 077
mkdir -p target/deploy
(
  set -eu
  for name in MYSQL_ROOT CORE_DB COPILOT_DB RABBIT REDIS; do
    printf 'FG_%s_PASSWORD=%s\n' "$name" "$(openssl rand -hex 24)"
  done
  for name in ADMIN REVIEWER; do
    printf 'FG_%s_PASSWORD=%sAa1!\n' "$name" "$(openssl rand -hex 24)"
  done
  printf 'FG_JWT_SECRET=%s\n' "$(openssl rand -base64 32)"
) > target/deploy/.env

python3 scripts/release.py deploy --project finguard-p6-demo \
  --env-file target/deploy/.env --releases target/releases \
  --state-dir target/deploy/state --sha "$RELEASE_SHA"
python3 scripts/release.py rollback --project finguard-p6-demo \
  --env-file target/deploy/.env --releases target/releases \
  --state-dir target/deploy/state
```

状态目录记录当前、上一健康版本和待完成切换，部署锁防止同项目并发切换。升级与回滚要求两套迁移指纹一致。需要变更迁移时单独安排兼容验证、备份和恢复。升级与回滚保留数据卷，制品和状态目录应与部署环境一并保管。

## 恢复与故障验收

用独立合成数据执行。脚本默认清理本次容器、卷、网络、应用镜像及临时凭据，保留 JSON 报告。

```sh
python3 scripts/run-integration.py --core-root ../finguard-core \
  --core-ref "$(cat ops/integration/core-revision.txt)" --recovery
python3 scripts/run-integration.py --core-root ../finguard-core \
  --core-ref "$(cat ops/integration/core-revision.txt)" --faults
```

- MySQL 使用 `mysqldump --single-transaction` 导出两个库，将归档导入隔离的空实例，按主键重新导出并比对完整逻辑内容。实际接管前，另配应用用户权限并验证数据库连接。
- RabbitMQ 停机期间保留已受理的 MySQL Outbox。恢复 broker 后检查终态、回执与模型账本，再发送原事件确认消息获 ACK，且同一分析只有一次模型调用。
- Qdrant 丢失时，从 SQL 保存的发布成员与正文重建候选索引，运行评测再切换发布版本。演练以单文档合成政策为范围，保留历史 SQL 发布和分析记录。
- 故障验收使用固定模型替身与独立 Langfuse、Prometheus、Grafana。Agent 演练从锁文件构建独立 MCP 验收客户端，客户端和桥只使用 Copilot 网络命名空间内的回环接口。

用一个或多个 `--fault-case` 可以在新栈中单独复验场景。支持 `core`、`mysql`、`redis`、`qdrant`、`model-fixture`、`telemetry` 和 `agent`。遥测栈仅在 `telemetry` 场景启动，MCP 回环端口仅在 `agent` 场景启用。

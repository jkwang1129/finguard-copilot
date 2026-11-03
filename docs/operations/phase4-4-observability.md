# P4-4 可观测性运行说明

## 开关与入口

业务端口继续由 `SERVER_PORT` 控制。管理端口默认 `127.0.0.1:9091`，只开放 `/actuator/health`、`/actuator/prometheus`；`COPILOT_MANAGEMENT_PORT` 可改端口。不要将 `COPILOT_MANAGEMENT_ADDRESS` 改成公网监听。容器化部署应使用独立私有管理网络和访问控制。

OTel SDK 默认在进程内生成上下文，但未配置导出目的地时不发出遥测请求。

| 变量 | 含义 |
| --- | --- |
| COPILOT_OTLP_TRACES_ENDPOINT | 可选通用 HTTP/protobuf traces 完整端点；路径必须 `/v1/traces` |
| COPILOT_LANGFUSE_TRACES_ENDPOINT | 可选 Langfuse 完整端点；路径必须 `/api/public/otel/v1/traces` |
| COPILOT_LANGFUSE_PUBLIC_KEY / SECRET_KEY | 启用 Langfuse 时从进程环境读取；不填入 YAML、命令历史或报告 |
| COPILOT_MANAGEMENT_BASE_URL | 历史 Phase 3 评测脚本的独立健康检查入口，默认 http://127.0.0.1:9091 |

端点只允许 HTTPS，或 loopback HTTP；拒绝 userinfo/query/fragment。不从 API、消息或模型输出接收端点配置。导出协议和 Basic Auth 来自 [Langfuse 官方 OTLP 接口](https://langfuse.com/integrations/native/opentelemetry)，本地 Langfuse 至少需支持该端点；本地接收器通过不代表真实 Langfuse 后端验收。

每个目的地使用独立 daemon 队列，容量1000，队列 TTL 60秒，批次最多64；网络/连接超时各2秒；`setRetryPolicy(null)` 禁止 SDK 重试，依据 [OTel 1.49.0 builder](https://github.com/open-telemetry/opentelemetry-java/blob/v1.49.0/exporters/otlp/all/src/main/java/io/opentelemetry/exporter/otlp/http/trace/OtlpHttpSpanExporterBuilder.java)。队列满、过期、导出失败和停机丢弃都计数，不落盘、不回压业务、不增加模型调用。停机取消队列，不承诺交付所有 span；`forceFlush` 有待处理项时报告未完成。

## 关联查询

V11 将 trace_id 和创建事务中的 Outbox 一起保存。新消息携带受控 traceparent；旧消息没有该字段时照常处理，历史 trace_id 为空。不接收自由 Baggage/Tracestate，不直接信任公共 HTTP 传入的追踪头。

运维人员用参数化只读查询关联分析和运行清单：

```sql
SELECT HEX(r.id) AS analysis_id, r.trace_id, r.status, r.failure_code,
       m.manifest_sha256
FROM analysis_run r
LEFT JOIN model_run_manifest m ON m.analysis_id=r.id
WHERE r.id=UUID_TO_BIN(?);
```

Span 名称只取枚举阶段；analysis UUID 只用于日志/Trace，不是指标标签。模型及 Prompt 版本使用声明版本字符串的 SHA-256 指纹，不冒充远端权重或 Prompt 内容哈希；`manifest_sha256` 可连接冻结版本清单。原始事实、Prompt、模型正文、用户名、密钥、异常消息、自由属性/events/links/resource/scope 都在导出边界丢弃。默认关闭 OTel exporter 自身日志，以受控丢弃指标定位导出故障。不要开启 HTTP body/header 或 JDBC 参数调试日志。

## 指标口径

| 指标 | 含义与边界 |
| --- | --- |
| copilot.stage.duration | 阶段的进程耗时；stage/outcome 固定枚举 |
| copilot.provider.request.attempts | 网关构造请求后发起的 HTTP 尝试；不表示远端接收、成功或扣费 |
| copilot.model.usage.records | 本进程成功记录用量的观察次数；替换旧 `copilot.model.calls`，不是 HTTP 请求数 |
| copilot.model.input.tokens / output.tokens | 本进程用量观察的累计 Token，不作为供应商账单或全库唯一计数 |
| copilot.ledger.reservations{state} | MySQL 四种预算状态的数量；持久账本聚合，不由消息次数推导 |
| copilot.ledger.held.cny{state} | RESERVED / UNKNOWN_HELD 保留的金额；未知费用不能解释为实际0元 |
| copilot.ledger.settled.estimate.cny | SETTLED 的已核验用量按冻结价格估算之和，不是供应商实际扣费 |
| copilot.ledger.call.slots / usage.records / provider.responses | V6 占用数、持久用量记录数、有供应商响应元数据的分析数；无响应不代表没发出请求 |
| copilot.ledger.refresh.success / snapshot.age.seconds | 必须共同判断；首次未取得快照时业务 gauges 为 NaN；失败保留旧值并将 success 置0 |
| copilot.telemetry.queue.size / dropped{destination,reason} | 目的地固定为 otlp/langfuse；丢弃原因仅FULL/EXPIRED/EXPORT_FAILED/SHUTDOWN |

供应商仅 deepseek/qwen/zhipu/stub/OTHER；动态版本不作指标标签。账本后台采样最多一个在途任务，约30秒刷新，SQL执行期限2秒；等待共享连接池受应用池的连接获取期限约束，但不占住业务/共享调度线程。不同实例指向同一个数据库时取账本 gauge 的 max，不能把副本 gauge 相加。采样是多条只读聚合的近似同一时段观察，不是财务一致性快照。Langfuse 推算费用不替代 V10 账本和供应商账单。

## 本地监控

启动脚本会首次生成 Git 忽略的本机凭据文件，已有文件不会覆盖；若凭据文件丢失但已有容器或卷，脚本拒绝重建密钥，需恢复原文件：

```powershell
./scripts/start-observability.ps1 -Langfuse
```

Prometheus 位于 http://127.0.0.1:19090；Grafana 位于 http://127.0.0.1:13000，账户 admin，口令为本机配置。Docker Desktop 通过 host.docker.internal:9091 抓取主机管理端口；Linux 部署需按实际私有管理网络调整。面板 UID 为 `finguard-copilot-p4-4`，包含阶段耗时、调用尝试、预算状态、持有/结算估算、遥测丢弃及账本新鲜度。

Prometheus 保存7天，且磁盘上限512MB。Grafana 本地仅保存仪表板及配置，匿名访问关闭；管理员仍拥有管理权限。

Langfuse 位于 http://127.0.0.1:13001，演示用户为 `reviewer@finguard.invalid`，密码是 `.env.langfuse.local` 中的 `LF_USER_PASSWORD`。同一文件的 project API keys 由首次初始化生成；不要删除或重建该文件。本地六个依赖服务与 Core 的数据库、Redis、队列完全分开，镜像使用固定 digest；Web JVM 无关，Node 堆配置为1024MB、容器上限1536MB。此配置只面向本机合成数据演示，不提供生产高可用或备份恢复。

Langfuse v4 使用 `x-langfuse-ingestion-version: 4` 和 observations v2 API；已用实际 Java 链路验证16条观测被接收。Core 读取作为 Copilot 侧子阶段记录并传递 traceparent；未修改或验收 Core 内部 Span、远端模型内部追踪，也未虚构 Phase 5 的工具执行轨迹。

Langfuse 内置自动保留是[自托管 Enterprise 功能](https://langfuse.com/docs/administration/data-retention)。本演示不启用该功能；`retention` profile 使用独立 Python 进程调用[公开删除接口](https://langfuse.com/docs/administration/data-deletion)，仅允许这次初始化的 `fg-p4-4-<32位十六进制>` 项目，核对实际 API key 所属项目。请求禁止系统代理及重定向，不会对任意远端项目执行清理。

清理任务每天采样一次：通过 v4 cursor 分页发现7天前观测，去重 traceId，并检查该 Trace 最近7天是否仍有活动；每轮最多10页/1000个候选。删除是异步请求，脚本不把接受请求当作完成，响应丢失记为 `DELETE_OUTCOME_UNKNOWN`。清理失败5分钟后再次采样；超过上限标记 PARTIAL，故障、积压或晚到观测可能让实际保留超过7天，需检查任务日志。这是本地演示清理流程，不是企业版保留功能或生产合规保证。验收已验证一个过期合成 Trace 删除、近期样本保留。

默认不配置远端导出；本地实例也不会自动把正在运行的业务进程切换到它。业务服务启动前，在当前进程导入 `.env.langfuse.local` 的两个 `COPILOT_LANGFUSE_*_KEY`，设置 `COPILOT_LANGFUSE_TRACES_ENDPOINT=http://127.0.0.1:13001/api/public/otel/v1/traces`。不要将整个凭据文件复制进应用日志或提交 Git。

停止本地服务：`./scripts/start-observability.ps1 -Langfuse -Stop`；只停止本项目容器，保留卷和凭据，不停止共享 Core 服务。验收中的临时 Core/Copilot、模型替身与隔离 Qdrant/Redis 已停止；因此未启动业务服务时 Prometheus 抓取目标为 DOWN，这是正常停机状态。

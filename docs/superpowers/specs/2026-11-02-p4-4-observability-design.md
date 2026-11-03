# P4-4 可观测性设计

依据已确认的 Phase 4 任务安排与 ARCHITECTURE 第 12 节，本工作包实现按 analysisId 定位阶段、错误及运行版本的能力。沿用 Java 17、Boot 3.5.8、MySQL/Outbox/RabbitMQ；本工作包不改变模型调用、审核权限和预算事务。

## 实现选择

采用显式 OpenTelemetry SDK 埋点和 Micrometer 指标。与自动 Java Agent 相比，显式埋点便于审定导出字段，避免自动记录 URL、SQL、HTTP header 和异常正文。仅打日志无法提供可导航的跨线程父子关系，因此不作为主方案。这是按既有脱敏约束作出的工程选择。

API 创建 root span；新的 Outbox JSON 增加可选 traceparent，保持 schemaVersion=1 和旧消息兼容。Relay 的发布 span 上下文随消息传给 Worker；重投保留消息身份，重复消费生成新的消费 span，不代表新增模型调用。V11 只新增 analysis_run.trace_id，关联信息和 Outbox 在同一创建事务中写入。历史没有追踪上下文的消息仍可处理。

阶段为 API_CREATE、CORE_CONTEXT、SNAPSHOT、OUTBOX_PUBLISH、WORKER、RETRIEVAL、CONTEXT、PREFLIGHT、MODEL、VALIDATION、ACCOUNTING、PERSISTENCE。阶段名和受控状态均为枚举。保留原预算/占用/追加事件为业务真相；Trace 丢失不会触发业务重试或模型补发。

## 数据边界

- Span 仅允许 analysis UUID、受控阶段/结果/错误码、受控供应商及模型标识、版本指纹、Token 数等数值。原始事实、Prompt、模型正文、用户名、JWT、密钥、URL、自由异常消息、Baggage、Tracestate 和 Span events 不进入导出。
- 导出前再次执行白名单，覆盖 attributes、events、links、resource、scope、name 和 status description；不是依靠正则寻找敏感词。
- 指标仅用固定枚举标签；analysisId、版本、任务、用户、文档都不能作标签。历史 model_configuration_version 标签移除，未知 provider/outcome 归 OTHER。
- 业务调用尝试、成功写入的用量和预算状态分别计量。数据库聚合展示 SETTLED、UNKNOWN_HELD、RELEASED_NOT_SENT 和 RESERVED，未知费用不显示为实际零成本。缓存刷新失败保留旧值并显示陈旧/不可用。

## 运行和故障

每个导出目的地独立非阻塞队列，最多 1000 个已结束 span，排队 TTL 60 秒，超过上限或到期丢弃并计数。HTTP 导出超时 2 秒、不重试、不落盘；停机最多等待 2 秒。目的地分别为通用 OTLP/HTTP 和可选 Langfuse OTLP/HTTP，默认不配置任何远端，不自动发送数据。

端点仅允许 HTTPS 或 loopback HTTP，无 userinfo/query/fragment，固定 traces 路径；Langfuse key 只来自本机环境。管理端口独立且默认绑定 loopback，只暴露 health/prometheus。Prometheus 保存 7 天，Grafana 只预置只读监控面板。用户没有既有 Langfuse 实例，本次增加隔离的本地实例和专用合成项目；免费版通过独立任务调用公开删除API实施7天清理，明确异步删除、分页/积压和近期活动边界，不操作任意外部项目，不冒充Enterprise保留功能。

## 验收

覆盖旧/非法上下文、线程恢复、跨 Relay/Worker 关联、重复消息、异常脱敏、队列满/TTL/导出失败、固定指标标签、预算状态/未知用量。通过本地 OTLP HTTP 接收器核对线上的 protobuf 请求和非阻塞失败行为；Prometheus/Grafana 的进程验收单独记录。Langfuse 协议测试与真实部署接收分开记录；不以本地接收器冒充 Langfuse 服务端验收。不新增付费模型调用。

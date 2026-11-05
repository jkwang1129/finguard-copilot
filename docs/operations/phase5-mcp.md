# Phase5本地MCP操作

本期仅支持本机STDIO。Java持有授权、不可变快照、发布索引、执行槽和审计；Python进程负责MCP协议适配，不运行模型、不恢复Graph、不访问Core。SDK固定`mcp==1.30.0`，依赖由`tools/mcp-bridge/uv.lock`锁定；真实SDK客户端验证initialize、tools/list、tools/call。P5-6审计后从1.23.1升级至同一大版本修复版。

协议参考：[STDIO传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[工具协议](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[固定SDK版本](https://pypi.org/project/mcp/1.30.0/)。stdout仅写协议消息，错误不回显HTTP正文或凭据。

## 授权与启动

1. 以`COPILOT_AGENT_ENABLED=true`、`COPILOT_AGENT_RUNTIME_ENABLED=true`及明确分析金额上限启动已有Agent配置。桥接固定连接`127.0.0.1:8081`，不支持自定义URL、重定向、环境代理或网络监听。
2. 选择属于当前REVIEWER、正在WAITING_FOR_HUMAN或已终结的Agent分析。运行中不签发MCP权限；来源/索引必须仍可用。四个工具槽为整次分析共享，包括初始检索、模型选择和MCP读取。MCP不会预留额外额度。
3. 由本机非浏览器HTTP客户端，用当前Core JWT向`POST /api/copilot/review-analyses/{id}/mcp-capabilities`发送`{"toolScope":["get_review_facts","get_task_state","search_review_policy"]}`，可取子集。服务重新验证角色/归属、Core状态和完整规范化事实；相同version但事实变化也拒绝。
4. 响应201包含`capability`、字符串`revision`、`toolScope`和`expiresAt`。capability为32字节随机数，仅此响应返回；数据库只存SHA256。最长5分钟且不晚于来源有效期。不要打印、保存或写入MCP客户端的长期配置。
5. MCP客户端启动命令使用`uv run --project <本仓库绝对路径>/tools/mcp-bridge finguard-mcp-bridge --analysis-id <UUID>`，仅向子进程临时环境传入`FINGUARD_MCP_CAPABILITY`。先执行`uv sync --locked --project tools/mcp-bridge`。Core JWT留在签发请求的调用方内存，不传给桥接。客户端关闭后清除临时环境变量。

桥接的工具描述来自Java ToolRegistry的同一资源，不维护第二套Schema。工具参数只能是`{}`，或政策检索的`query/topK`；模型不能指定分析、审核员、业务任务、URL或权限。

## 失效、并发与清理

- 每次list/call复查capability有效期、撤销、归属、revision、来源和运行状态。工具分配槽位时，在同一个parent锁内再次检查capability及状态，防止检查后恢复的竞态。
- MCP已有CLAIMED/UNKNOWN读取时CONTINUE返回409，不能让活跃Graph争抢槽；CANCEL仍可终结分析。未知步骤不会重发。MCP读取成功后可继续，但仍消耗原四槽，耗尽后必须接受失败/新建分析，不能重置计数。
- 恢复/取消使revision改变，原capability随之失效。到期需新的JWT授权请求；桥接不保存JWT或自动续期。
- `DELETE /api/copilot/review-analyses/{id}/mcp-capabilities`用新的Core JWT撤销当前审核员该分析的所有capability，返回204。
- 来源到期时保留任务清理Checkpoint和工具正文，撤销capability；保留低敏哈希、调用/费用和不可完整回放标记。capability记录不含明文凭据，可随既有分析审计生命周期处理。

capability是有界授权租约，五分钟内不保证实时获知Core角色撤销或事实变化；读取始终来自原已授权快照。远程HTTP MCP、OAuth和跨进程JWT续期不在本包能力中。HTTP适配端点拒绝非loopback来源及带Origin的浏览器请求；不要把它反向代理为公开MCP服务。

# P4-5 审核页面与进度流

当前页面用于辅助审核，最终决策仍在 Core。SSE 显示持久状态记录，不表示 Phase 5 工具调用已实现。MySQL 是事件与账本真相；Redis Pub/Sub 仅发送分析 UUID 唤醒读者。

## 启动与访问

先按现有后端运行说明启动 Core（8080）、Copilot（8081）、MySQL、RabbitMQ、Qdrant 和调用治理所需 Redis，再运行：

```powershell
npm --prefix frontend ci
npm --prefix frontend run dev
```

访问 `http://127.0.0.1:5173/`，使用 Core REVIEWER 账号登录。Vite 将 `/core-api` 转发到 Core 并去掉此前缀，将 `/api/copilot` 转发到 Copilot。该代理是本地开发配置；部署静态产物时须在同源反向代理中保留同等路由和关闭 SSE 缓冲。

`VITE_CORE_REVIEW_URL` 可在构建前指定固定 http(s) 的 Core 审核入口，默认 `http://127.0.0.1:8080/demo/index.html`。页面不接受模型返回的跳转 URL。不要在此配置中放令牌、用户名或密码。Core 静态目录路径 `/demo/` 不会自动解析为 index.html，不能替代此页面地址。

会话仅在页面内存中，JWT 经 Authorization 请求头传输；刷新或退出后需重新登录。提交超时可重试当前请求；输入任务编号改变或点击“下次提交时创建新分析”才换一组创建凭据。进度重连只发送 GET。保存的反馈不可覆盖，也不会向 Core 提交最终决策。

## 接口与恢复语义

| 接口 | 行为 |
| --- | --- |
| `POST /api/copilot/review-analyses` | 既有创建接口，必须有 Idempotency-Key；任务编号以十进制数字发送，客户端不转 JS Number |
| `GET /api/copilot/review-analyses/{id}/view` | 新增页面投影，重新核验 Core 角色和分析归属，Cache-Control no-store；只返回已校验结果、精确值字符串、费用及版本指纹 |
| `GET /api/copilot/review-analyses/{id}/events` | 新增 text/event-stream；Authorization 头，Last-Event-ID 是非负 64 位十进制序号 |
| `PUT /api/copilot/review-analyses/{id}/feedback` | 既有不可覆盖反馈，AGREE / DISAGREE / UNSURE |

SSE `analysis-state` 数据含 analysisId、字符串 sequence、type、status、受控 failureCode、resultAvailable 和 occurredAt。无结果正文、Prompt、模型片段或账本。客户端通过 `/view` 获取通过服务端校验的结果；金额、业务 ID、Token 及费用均按字符串处理。

每分析序号从 1 单调增加；恢复游标只补读更大序号，未来游标 409、非法游标 400。旧记录在 V12 迁移时只有一个 SNAPSHOT，不重建未知历史。状态与事件在同一 MySQL 事务中提交，CAS 未命中、回滚和重复消息不会制造状态事件。

上限为每进程 16 条、每审核员 4 条 SSE 连接，单连接最长 60 秒，单批 64 条、1 秒 DB 轮询、15 秒心跳。容量耗尽返回 503；浏览器有限退避重连，之后可手动恢复。首次发送、每批和心跳前重查 Core 授权；已发送响应头后的错误以受控 `stream-error` 事件关闭。Core 无法验证身份时关闭进度流。

`COPILOT_EVENTS_REDIS_ENABLED=true` 开启可选跨实例通知，默认 false。主题为 `copilot.analysis.changed.v1`，只发送 UUID；通知丢失时依靠 DB 轮询恢复。Redis 中断不应放宽已有的模型准入/预算治理：本节的可用性保证针对进度读取，不代表可在 Redis 故障时发起新模型调用。

费用视图区分已结算估算、预留预算、未知结果保留、未发送释放和未记录。未知金额显示“尚无可确认金额”，不能按 0 元解释。版本字段是冻结配置版本的指纹，不是远端模型权重哈希。

## 重复验收

以下命令仅使用本地替身模型，不发送付费请求：

```powershell
$env:COPILOT_EVENTS_REDIS_ENABLED='true'
./scripts/run-business-e2e.ps1 -LocalModelStub -KeepRunning -BrowserAcceptance -CoreRoot C:\dev\finguard-core
```

需要已构建的 Core/Copilot JAR，8080/8081/9091/6333/16380/18765 空闲。Core 必须包含 `/demo/` 审核台；旧 phase1 工作树不含页面，不能用来验收 Core 返回入口。脚本创建独立数据库、RabbitMQ vhost 和隔离 Qdrant/Redis，并记录 PIDs。`-BrowserAcceptance` 只允许搭配 `-LocalModelStub -KeepRunning`，在忽略的 `target/business-e2e/{runId}/browser-session.json` 临时保存**合成账号**的登录资料，供本机浏览器验证；不含 JWT。该文件不能进入验收目录、Git 或对外报告，浏览器结束后立即删除。

浏览器登录、用该任务创建第二份分析并记录 UNSURE 后，运行：

```powershell
./scripts/verify-review-console.ps1 -RunId <14位runId> -BrowserAnalysisId <浏览器创建的UUID>
```

此脚本验证反馈入库、跨归属 404、终态续传、停用**本次隔离 Redis** 后打开非终态 SSE 并补读后来提交的 MySQL 状态、Core 业务表校验和。非终态故障样本通过明确的合成数据库事务注入，不伪称额外 Worker/模型执行；脚本 finally 恢复隔离 Redis。

最后停止报告中属于本次运行的 Core/Copilot/替身进程和本次隔离容器，删除临时 browser-session.json；保留验收报告所指数据库和 vhost。不要停止共享 finguard-mysql/rabbitmq/redis 或删除其卷。

前端测试：`npm --prefix frontend run test -- --run`、`run lint`、`run build`；后端定向测试在 `events` 包及 `AnalysisPageServiceTest`。最新证据见 [P4-5 验收记录](../acceptance/2026-11-03-p4-5-review-console.md)。

# P5-4：持久Graph、人工恢复与审核台验收

日期：2026-11-03。分支`codex/phase-5-agent-workflow`，未提交/推送。工程证据使用合成业务、本地HTTP模型替身；不证明真实模型质量。

## 已验证的行为

- 固定Graph Core1.1.2.2与Java17实际编译/条件边运行；显式空SaverConfig，MySQL为唯一恢复真相。见[ADR-0013](../adr/0013-phase5-durable-graph-and-human-resume.md)。
- V15增加Checkpoint/CAS/owner租约、人工恢复幂等记录、状态CHECK、原生供应商响应哈希和受控事件元数据。旧事件的Agent元数据为null，旧运行不升级；创建时冻结runtime选择，关闭运行开关只延后新Graph任务，不转入P4。
- 节点完成后同事务更新状态、Checkpoint、事件、下一条Outbox；外部请求不持有长事务。过期owner不能推进。损坏Checkpoint单独隔离，不阻塞恢复批次。
- `POST /api/copilot/review-analyses/{id}/resume`只接收字符串`expectedRevision`和CONTINUE/CANCEL，另需新JWT与Idempotency-Key；继续202、取消200。复查角色、归属、期限、Core任务及规范化事实哈希，排除capturedAt；相同version但金额改变也失败。授权失败不改变原运行。
- 轨迹GET和页面先重新授权，只投影固定工具名、状态、哈希、费用/用量和剩余额度；SSE持久化revision/node/固定工具名，不传模型正文。前端处理双击、权限失效、字符串int64、未知费用和分片续传。
- 来源过期清理原始快照、Checkpoint正文、工具结果正文，保留哈希和费用账本，并隔离活跃owner。结果入库再次在parent锁下验证来源，防止迟到工具写回已清理正文。

## 测试证据

工作区`.superpowers/sdd/2026-11-03-phase5-agent-workflow/`保留完整日志（gitignored）：

| 最后相关运行 | 结果 | 范围 |
| --- | --- | --- |
| `p54-final-green.log` | 50通过 | Graph/HITL/API/旧事件与页面/指标/runner；随后增加事件元数据与清理竞态修正 |
| `p54-event-meta-green.log` | 17通过 | Graph、历史迁移、事件事务、SSE |
| `p54-retention-race-green.log` | 30通过 | 真实MySQL工具、Graph、HITL、过期清理及迟到写回回归 |
| `p54-ui-final.log` | 18通过 | React/API/SSE；lint无警告，build通过 |
| `p54-harness-green.log` | 12通过 | Python替身与脚本回归 |

这些是不同运行，不能相加称为一次全套结果。Task6会再跑完整套件。

## 真实进程故障矩阵

命令：`./scripts/run-business-e2e.ps1 -LocalModelStub -AgentAcceptance -AgentFaultMatrix -CoreRoot C:/dev/finguard-core`。

运行`20261103174253`，`target/business-e2e/20261103174253/report.json`：PASSED，46/46检查，10个真实JVM退出窗口。真实Core/Copilot/MySQL/RabbitMQ/隔离Redis/Qdrant，固定Embedding与HTTP模型替身。每项记录analysisId、Checkpoint、占用/结算、Outbox/回执、事件、实际HTTP/工具读取数和Core业务表校验和。

| 退出位置 | 终态 | HTTP数 | 实际工具读取数 |
| --- | --- | --- | --- |
| 工具占用前 | SUCCEEDED | 2 | 2 |
| 工具占用后 | FAILED | 0 | 0 |
| 工具读取后/结果保存前 | FAILED | 0 | 1 |
| 工具结果保存后/Checkpoint前 | SUCCEEDED | 2 | 2 |
| 模型占用前 | SUCCEEDED | 2 | 2 |
| 模型占用后/HTTP前 | FAILED | 0 | 1 |
| 模型HTTP返回后/结算前 | FAILED | 1 | 1 |
| 模型结算后/Checkpoint前 | FAILED | 1 | 1 |
| Checkpoint与Outbox提交后 | SUCCEEDED | 2 | 2 |
| Graph完成后/MQ ACK前 | SUCCEEDED | 2 | 2 |

模型/工具占用不重置。未知模型调用保持UNKNOWN_HELD；结算后丢失的候选不保存原文、不重新请求，停止为可解释失败。Core业务表校验和全部不变。恢复测试在真实进程退出后仅把死owner的租约时间改为过期，以缩短等待；没有改动占用/Working Memory，不把它当作真实两分钟等待时长测量。

同一脚本先完成submit202→原生工具选择→WAITING→停止/重启JVM→新授权CONTINUE202→SUCCEEDED，实际2次HTTP/2个工具；重复恢复回放同一回执。初次运行`20261103173509`因替身引用投影错误被输出Schema拒绝，`20261103173950`因替身字段映射错误失败；修正替身后成功。`20261103174135`业务链成功但矩阵脚本路径错误导致总报告FAILED；未把这些失败计作通过。

## 边界

该包不开放真实供应商的native Token上界/付费资格；P4已有真实人工与生产质量门槛继续保留。测试故障开关只能在精确loopback替身配置与fixture-mode下启用，普通/真实配置启动即拒绝。没有Core写工具、JWT持久化或模型原文回放。

最终更新后真实进程`20261103175009`：25/25通过，包含新事件元数据与清理竞态修正。随后用agent-browser实际登录、创建另一个分析`ba01d8fb-e74b-4bcf-a9b5-0ba86afaea94`、看到暂停/剩余额度/检索轨迹、点击继续并看到通过校验的建议及两次调用。完成后继续按钮消失，localStorage/sessionStorage均为0。已查看`agent-waiting.png`、`agent-completed.png`确认布局与正文（位于该运行目录）。只操作合成审核分析，没有点击Core决策或反馈。

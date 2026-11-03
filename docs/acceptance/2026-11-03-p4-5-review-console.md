# P4-5 事件、SSE 与审核页面验收

日期：2026-11-03。基线 `c30b92342a222ae9b579ec32ea90856dc883284c`，在既有 Phase4 工作树上保留已验收但未提交的 P4-4 改动，本轮未提交或推送。设计和实施见 [设计](../superpowers/specs/2026-11-02-p4-5-review-console-design.md)、[计划](../superpowers/plans/2026-11-02-p4-5-review-console.md)，使用见 [运行手册](../operations/phase4-5-review-console.md)。

## 结论与范围

P4-5 本地工程范围通过：MySQL 事务事件、可选跨实例 Redis 唤醒、有界授权 SSE、精确页面投影和 React 审核台。浏览器实际完成登录、创建、状态补读、证据/引用、费用、反馈及 Core 返回入口；Redis 停止后，终态续传及非终态连接补读后来提交的事件均通过。

本轮只使用本地替身模型和合成业务数据，新增付费调用 **0**。模拟反馈用于验证 UI/API/数据库链路，不是真实人工校准，也不关闭 P4-2 的真实人工标签或“真实审核反馈到回归集”门槛。未证明真实模型质量、通用注入防护、生产部署或 Phase 5 工具/Graph/HITL 能力。未在 Core 提交最终决定。

## 实现边界

- V12 新增 `analysis_event_cursor` 与 `analysis_event`；创建及成功状态 CAS、Outbox 转 QUEUED 都与事件同事务。CAS 未命中和回滚不追加；并发序号无重复或缺口。旧运行只回填一条 SNAPSHOT，不虚构历史。
- SSE 每进程16/审核员4连接，单连接60秒、批64、DB轮询1秒、心跳15秒；Worker 退出后才释放容量。首次发送、每批和心跳前复查 Core 角色和分析归属。错误只发受控码；不发送模型正文、Prompt、JWT 或工具原文。
- Redis 仅发送 UUID，丢失通知时 DB 轮询补读。此保证只适用于持久进度读取，模型准入的 Redis 故障拒绝策略未放宽。
- `/view` 在授权后重新验证结果 Schema/分析 ID，将金额、业务 ID、费用、Token 转为精确字符串。冻结版本展示指纹，不展示配置或 Prompt 原文。未知费用显示未知，不等于0。
- 浏览器使用 fetch 的 Authorization 和 Last-Event-ID 请求头，JWT 仅在内存；纯文本渲染模型及政策内容。创建超时重试复用幂等键，SSE 恢复不发 POST；退出、切换及401隔离旧响应。反馈不可覆盖，Core 入口为固定受控地址。

## 自动验证

| 验证 | 命令与结果 |
| --- | --- |
| Java 全量 | `mvn test -DargLine=-Xmx512m -Dspring.test.context.cache.maxSize=1`，**404 tests / 90 suites，0 failures / errors / skipped，命令退出0**，11分18秒。含 V11→V12 快照升级、事务/回滚/CAS/并发、130条分批续传与逐批授权、流中撤权、容量回收、Redis 跨实例及精确投影。 |
| Python 全量 | `uv run --project tools/agent-evaluation pytest tools/agent-evaluation/tests -q`，**183 passed**，24.09秒。 |
| 前端 | `npm --prefix frontend run test -- --run`，**11 passed**；lint、TypeScript 和 Vite 构建退出0。覆盖分片/CRLF/重复/缺口、幂等重试、退出旧响应、401清空、文本/XSS和精确金额、反馈。 |
| 实际 HTTP | 最终 run `20261103094454`，**37/37检查**。Core `941832b64b1c17096502da2c2d66267561e58e20`，真实 Core/Copilot/MySQL/Outbox/RabbitMQ/Qdrant/Redis，本地模型替身。 |
| Redis / 归属 / 反馈 | 同一 run 的 `verify-review-console.ps1` **10/10检查**。Redis 停止后从游标2返回3–6；非终态连接从DB依次补读1–3；跨归属404、未知费用不变0、Core业务表校验和一致。 |
| 浏览器 | agent-browser 驱动实际 Chromium，桌面1254px及移动390px；创建第二份分析、显示6条事件、UNSURE入库并重载、失败无正文、404清除旧结果、JWT失效回登录、退出清空、存储为空、Core新标签页人工审核入口；390px页面宽度复验为390px。 |

Java 第一次全量运行跨越系统休眠，出现约10小时时钟跳变及测试容器连接中断；已停止并保留忽略目录中的 `target/p4-5/java-interrupted-by-sleep.log`，**不计作验收**。上表是重新运行的独立完整成功命令，不是定向复测拼接。日志保存在 `target/p4-5/java-full.log`、`python-full.log`、`frontend-final.log`、`frontend-lint.log`、`frontend-build.log`。

## 真实链路与证据

最终业务脚本验证202接受与同键重放、实际Worker成功、冻结用量和预算、重复RabbitMQ消息ACK而不二次调用、Core业务只读、V12及页面精确值类型。SSE帧ID与MySQL序号1–6完全一致；游标2只补3–6，未来游标409；匿名401、ADMIN无审核角色403、未知或跨归属404。使用 `Accept: text/event-stream` 的匿名请求同样401。

浏览器创建分析 `ae34e2ab-3de0-4359-94b8-5d9b02ca6edf`，不可覆盖反馈为UNSURE。最终run实际替身HTTP共2次（脚本1次、浏览器创建1次），价格为明确的合成零价测试表，不代表真实供应商价格。Core任务仍为 `PENDING:0:0:`。

非终态Redis故障用例通过受控SQL事务创建合成运行并追加事件，证明SSE在无通知时补读提交；**不是另一条真实Worker/模型流程**。先前旧Core工作树的业务/SSE验证通过，但无审核静态页；切换到已包含控制台且干净的Core主工作区。入口曾用 `/demo/` 得到404，已按Core运行文档改为 `/demo/index.html`，最终脚本检查200 HTML、浏览器点击并打开人工审核页。Core源码未修改。

- [37项业务报告](p4-5/business-e2e-report.json)、[10项恢复/反馈报告](p4-5/redis-browser-report.json)
- [浏览器检查](p4-5/browser-ui-report.json)、[Java套件汇总](p4-5/java-test-summary.json)、[清理记录](p4-5/cleanup-report.json)
- [桌面结果](p4-5/review-console.png)、[移动结果](p4-5/mobile-result.png)、[受控失败](p4-5/controlled-failure.png)、[移动归属错误](p4-5/mobile-owner-error.png)、[Core入口](p4-5/core-entry.png)

## 复核、冻结和清理

按计划仅进行一次非Astra独立源码复核，未发现阻断问题。真实浏览器另发现并复现原生fetch接收者错误，新增测试先失败，再通过绑定globalThis修复；Core入口同样有失败测试后修正。移动网格内容撑宽由390px下的实际测量定位，改为 `minmax(0,1fr)` 后页面不再横向溢出。

[冻结清单](2026-11-03-p4-5-baseline.json)包含345个代码/资源/测试/脚本/构建输入的逐文件SHA256，汇总为 `94a333b345454e6916e2a516d6c87ebd5397ff5a7e5bc3beb46eb18cd86c689b`，以及报告、截图和前端构建哈希。运行JAR SHA256为 `eaae5346f28595c64d549250a9152853cf2a4799702b5f39dfcd7a30567a927e`，227个class与当前编译树逐字节一致，V12资源与源码一致。P4-4及更早冻结记录保留原样。

所有本次临时Core/Copilot/本地模型替身/Vite及隔离Qdrant/Redis已按已核验PID/容器名停止；浏览器会话关闭、临时合成密码文件删除。保留验收数据库/vhost和停止的容器。共享MySQL/RabbitMQ/Redis及已有Langfuse/Prometheus/Grafana与数据卷保持运行。业务报告的servicesLeftRunning表示脚本结束时状态，后续停止以cleanup-report为准。下一工作包为P4-6总验收。

# FinGuard Copilot 任务清单

本清单由 [实施路线图](docs/ROADMAP.md) 展开。勾选任务前必须保留对应测试、运行或文档
证据；阶段未通过验收时不提前宣称后续能力完成。

## 当前交接状态（2026-11-05）

最新限定验证已完成：真人/Judge10/10；主模型新批次11/11、真实进程36/36；新RAG独立32题达到预设门槛（召回22/24、精度22/24、拒答6/8）。见[完整验收](docs/acceptance/2026-11-05-semantic-margin-quality.md)。默认配置未改，项目合成范围不等于生产或通用Agent质量。

### 11月4日交接快照（历史）

Phase5本地工程及原生协议资格已完成限定范围验证；依赖维护和资格复验见相应11月4日报告。真人10例与真实Judge一致10/10，已关闭该抽样校准项。新增真实语义RAG保留集召回7/12、精度7/8未达标，实际主模型组件质量4/11通过；付费配置完整进程在调用前因日期格式错误启动失败（已修复、未付费重跑）。后续离线确认政策分号触发注入防护误报，已最小修复并通过74项定向回归，[新复验准备](docs/acceptance/2026-11-04-semantic-policy-guard-fix.md)单独记录，旧成绩未改写，尚未以新代码付费复验。详见[真人校准](docs/acceptance/2026-11-04-human-judge-calibration.md)及[真实RAG/主模型质量](docs/acceptance/2026-11-04-real-rag-main-quality.md)。质量门槛仍未通过，不能据此宣称Phase6生产部署就绪。

### 历史交接快照（2026-11-02）

P4-1 已实现护栏与 P4-2 工程能力可以作为 P4-3 的开发基线；P4-2 已按用户指定的 AI 模拟方式完成[校准交付](docs/acceptance/2026-11-02-p4-2-simulated-calibration-closeout.md)，当时真实人工标签条件保留为后续质量门槛，未用模拟结果勾选。三项短收尾的版本快照、当时进程冒烟及调用/费用契约见 [P4-3 入口收尾](docs/acceptance/2026-11-02-p4-3-entry-closeout.md)。下表保留历史结果，当前状态以上方11月4日说明及细分勾选项为准。

| 阶段 | 已有工程证据 | 仍需完善及归属 |
| --- | --- | --- |
| P1 | 身份/只读快照、幂等、Outbox/Worker 与恢复 | 更大规模故障、备份恢复及生产部署在 P4-6/Phase 6 验收 |
| P2 | 知识发布/回滚、Qdrant、引用校验 | 真实 profile 仍为 FixedEmbeddingGateway；3 例 RAG 基线不足以证明语义检索质量，另立质量验证 |
| P3 | 真实主模型、上下文及 V6 持久调用占用 | 无合格真实备用模型；历史 H03 原始真实输出未留存，只能验证已知合成回归 |
| P4-1 | 已知安全集、精确数值与审定政策拒绝路径 | 未知注入不保证拦截；自由审核文本未开放，工具参数治理属 Phase 5 |
| P4-2 | 评测报告、同批 Worker 比较、反馈 API、真实 Judge 调用、AI 模拟校准交付 10/10 | 真实人工标签仍缺；参考基线 N319/N321 两项漏报保留；反馈保留期限未配置 |
| P4-3 | 单主模型契约下P4-3.1至.5完成工程验收：Java371/Python175、6替身+1最终真实场景通过 | [P4-3记录](docs/acceptance/2026-11-02-p4-3-governance.md)；无合格真实备用，供应商内部实现及生产质量不由本地测试证明 |
| P4-4 | 本地工程验收：12阶段Trace、V11关联、脱敏有界导出、固定指标、实际Langfuse/Prometheus/Grafana、过期合成样本清理 | [验收记录](docs/acceptance/2026-11-02-p4-4-observability.md)；Core/供应商内部Span、生产容量及高可用另验；Java391含修正后的定向复测，Python183 |

## 阶段 0：架构与工程基线

- [x] 建立范围、架构、路线图和 ADR 基线。
- [x] 确认 JDK 17、Spring Boot 与 Spring AI/Spring AI Alibaba 官方兼容版本。
- [x] 初始化 Maven 工程和模块化包结构。
- [x] 建立环境变量配置基线和 OpenAPI。
- [x] 建立统一错误响应和启动配置校验。
- [x] 接入 MySQL、Flyway 和 Testcontainers。
- [x] 定义模型、Embedding、向量存储和 Core 客户端端口。
- [x] 建立 Stub 模型、固定 Embedding 和内存检索实现。
- [x] 建立 Java 服务、Python 评测和 React 演示目录边界。
- [x] 配置 CI、依赖缓存、静态检查和秘密扫描。
- [x] 通过不调用模型或付费 API 的构建与全部当前普通测试。

## 阶段 1：只读事实闭环

- [x] 在 Core 设计并实现 `/api/auth/me`。
- [x] 在 Core 设计并实现 `/api/review-tasks/{id}/context`。
- [x] 为 Core 契约建立 200/401/403/404/超时测试。
- [x] 实现 Copilot 身份代理和 `REVIEWER` 校验。
- [x] 实现分析创建、查询、状态机和幂等语义。
- [x] 实现脱敏事实快照、校验和与 30 天删除任务。
- [x] 实现事务 Outbox、RabbitMQ Relay 和幂等 Worker。
- [x] 定义 RabbitMQ Exchange、Queue、DLQ、重试和 Outbox 清理策略。
- [x] 验证消息重复、乱序、RabbitMQ 中断和恢复不会丢失或重复分析。
- [x] 实现 Stub 结构化分析。
- [x] 实现 JSON Schema 与事实一致性校验。
- [x] 验证 Copilot 不调用任何 Core 写接口。

## 阶段 2：RAG 与知识数据管道

- [x] 定义知识文档、片段、索引版本和引用模型。
- [x] 实现文档登记、解析、清洗、切分、去重和校验和。
- [x] 实现 Embedding 与 Qdrant 适配器。
- [x] 实现候选索引、评测、发布和原子切换。
- [x] 实现文档增量更新、删除和索引回滚。
- [x] 实现检索结果引用和输出引用校验。
- [x] 建立固定 RAG 评测集并记录基线。
- [x] 验证动态 Core 事实不会写入向量库。

Phase 2 当前验收记录：[RAG 与知识数据管道](docs/acceptance/phase-2-rag-knowledge-pipeline.md)。

## 阶段 3：真实模型与上下文工程

- [x] 接入首个真实模型适配器和显式运行 profile。
- [x] 接入第二个真实候选模型，保持统一 `ModelGateway` 契约。
- [x] 实现 32k 输入、2k 输出的上下文预算策略。
- [x] 实现事实优先、文档去重和低排名裁剪。
- [x] 建立审核 Golden Dataset。
- [x] 对候选模型运行正确率、引用、拒答、延迟和成本比较。
- [x] 固化主模型、备用模型和 Stub 选择依据（当前无可接受的真实备用模型）。
- [x] 实现 Token、调用次数、延迟和估算成本指标。
- [x] 验证普通测试仍不需要网络和模型密钥。
- [x] 将已发布知识检索结果和受控 reviewer input 接入真实模型编排路径。
- [x] 完成 Core/Copilot/RabbitMQ/Qdrant/已发布索引/真实模型的合成案例进程验收（[2026-10-19 记录](docs/acceptance/2026-10-19-business-e2e.md)）。
- [x] 修订模型评测契约、强化事实和引用校验，在 DeepSeek 上完成旧/新 Prompt 的 12 次同条件对照（[2026-10-21 记录](docs/evaluations/2026-10-21-deepseek-quality.md)；非生产质量门禁）。
- [x] 使用 v3 严格十进制评测器完成 12 次真实 Prompt 对照，两组均为 100%，历史引用提升未复现。
- [x] 使用当前 DeepSeek 完成 Java/Core/RabbitMQ/Qdrant 已发布索引真实业务验收，1 次调用、15/15 断言。
- [x] 独立编写并冻结 12 案例保留集后真实评测，建议 11/12，应拒答 5/5；H03 错误原样保留，不宣称生产质量全部通过。

2026-10-22 三项补验收已执行完毕，真实调用 25/25 次；[证据汇总及已知限制](docs/acceptance/2026-10-22-phase3-closeout.md)。

提交前新增的三项阻塞已在 2026-10-24 修复并回归（[检查依据](docs/acceptance/2026-10-22-phase3-precommit-review.md)、[修复验收](docs/acceptance/2026-10-24-phase3-failure-recovery.md)）：

- [x] 将模型 timeout 接入真实 HTTP 连接/读取期限并补离线慢响应验证。
- [x] 修复模型返回后数据库回滚导致调用预算失效、用量丢失和再次请求模型的问题；精确用量写入前数据库故障仍需按占用记录与供应商账单人工核对。
- [x] 修复失败用量记录异常导致跳过恢复、原消息被丢弃且分析停留非终态的问题。

Java 265 项、Python 100 项及前端 lint/测试/构建通过；Phase 3 工程阻塞解除，但 H03 质量坏案例和真实备用模型缺口仍保留在后续任务中。

## 阶段 4：AI 工程化与安全

实施顺序、工作包与验收门槛见 [Phase 4 任务安排](docs/PHASE4_TASK_PLAN.md)。
2026-11-01 已核对 P4-1 证据及未关闭条件，见 [P4-1 验收核对](docs/acceptance/2026-11-01-p4-1-exit-review.md)。P4-2 的实施步骤见[实施计划](docs/superpowers/plans/2026-11-01-p4-2-evaluation-feedback.md)；[合成单规则保留集](docs/evaluations/phase4-holdout-v1-freeze.md)已冻结，离线 LangGraph 参考报告为 20/20，仅验证该合成规则，不代表 Java/真实模型或安全攻击能力。
截至 2026-11-01，P4-2 已有规则比较、报告 CLI、版本清单、反馈 API 和 Judge 一致率计算模块；用户要求的“模拟真人”标签已明确标注 AI 模拟，DeepSeek `deepseek-flash` 对固定 10 例真实调用并得到模拟标签一致率 10/10。该时点的真实人工标签、Java Worker 同批输出及完整安全类别待验收，详见[历史验收记录](docs/acceptance/2026-11-01-p4-2-evaluation-feedback.md)。
2026-11-02 已补实际知识注入载荷、多条件、冲突政策与 Java Worker/MySQL 同批输出：16 例均有完整执行记录，Java 9/16、LangGraph 参考 16/16，7 个坏案例原样保留。此次补验收已执行，质量门槛未通过；当前未关闭项转为坏案例修复及独立新集复验，见[补验收记录](docs/acceptance/2026-11-02-p4-2-worker-comparison.md)。AI 模拟标签仍不是人工校准，固定网关/检索夹具也不代表真实模型或 Core HTTP E2E。
随后七项已完成根因修复：V8 精确 JSON 文本存储、BigDecimal 解析、审定政策包及恢复路径授权。原16例 Java回归16/16，独立新24例 Java24/24（参考22/24，其两项漏报保留），见[修复与复验记录](docs/acceptance/2026-11-02-p4-2-seven-fixes.md)。该范围仍使用合成政策/固定网关，真实人工校准及生产质量退出条件未因此自动关闭。
第一批的版本清单和可配置数值护栏已完成离线验证。项目已制定 `LARGE_AMOUNT` 复核政策并默认核验历史命中事实和政策引用，政策仍需发布到目标知识索引；通用数值规则默认未配置，H03 任务仍未全部完成。详见 [大额规则审定记录](docs/acceptance/2026-10-27-p4-1-core-rule-review.md)及[第一批验收](docs/acceptance/2026-10-27-phase4-first-batch.md)。
已补受控建议枚举诊断和部分安全负例；提示注入检测仅覆盖已知模式，相关整体任务仍未验收。详见 [增量记录](docs/acceptance/2026-10-27-phase4-security-diagnostics.md)。
大额政策已完成隔离 MySQL/Qdrant 发布、真实 Core/Worker 全链路的替身模型正反例及一次真实模型合成样本验证；未证明生产质量，详见[隔离验证记录](docs/acceptance/2026-10-28-p4-1-isolated-policy-flow.md)。

- [x] 实现 Prompt、模型配置、索引成员及价格表不可变版本与内容哈希管理（V9；不冒充远端权重哈希）。
- [x] 建立 Python 3.12、uv、pytest、Pydantic 和 Pandas 评测工具。
- [x] 建立共享案例/输出契约的 LangGraph 离线参考基线，并与 Java Worker 同批比较（原16例及独立24例；参考两项漏报保留，不是生产执行路径）。
- [x] Phase5补共享Tool Schema对照：Java/native/MCP同源资源，26例离线LangGraph对照与原生协议回归；不替代真实模型质量。
- [x] 实现规则评测、版本化 LLM-as-Judge、校准计算和报告；真实 DeepSeek Judge 已运行固定10例。
- [x] 按用户指定方式完成 10 例 AI 模拟审核填写、逐例理由及冻结 Judge 结果复算（[收尾记录](docs/acceptance/2026-11-02-p4-2-simulated-calibration-closeout.md)；与真实人工校准分开记录）。
- [x] 取得真实人工标签并完成 Judge 抽样校准：2026-11-04本项目用户提交10个标签，新冻结样本/真实Judge批次一致10/10，0缺失；只覆盖项目合成政策，不认证金融专家或生产质量。见[真人标签来源](docs/evaluations/phase4-human-label-provenance-20261104.json)和[实际校准](docs/evaluations/phase4-human-calibration-20261104-v1.json)。原AI模拟记录及历史Judge保留。
- [x] 实现 Bad Case 分类、已知坏案例回归及独立新集复验（Java16/16、24/24）。
- [x] 实现审核反馈筛选、冻结和回归重放，并通过合成数据验收。
- [x] 在合成业务案例上完成真人独立标注、页面反馈、脱敏冻结和离线回归验收，重放 1/1。 [真人反馈验收](docs/acceptance/2026-11-05-phase6-closeout.md#审核反馈验收)
- [x] 将合成 H03 数值边界纳入已知回归，增加确定性数值兜底及受控建议枚举诊断；另冻结独立新集（不声称重放了未保存的历史真实输出）。
- [x] 实现 reviewer feedback API 与权限检查（V7、归属检查、MySQL 并发测试）。
- [x] 生成可重复的离线评测报告（合成单规则范围；版本真实性与模型结果仍标未验证）。
- [x] 冻结 P4-3 调用/费用契约 v1 与 ADR-0012（设计输入；实现状态仍以下列未勾选项为准）。
- [x] 实现受预算约束的单主模型候选准入、选择理由及受控拒绝；无合格候选时不调用，发送后不自动切供应商。
- [x] 智谱 GLM-5.2 通过冻结合成审核集同条件评测，验证显式调用前切换与单请求约束。 [收尾验收](docs/acceptance/2026-11-05-phase6-closeout.md)
- [x] 使用 Redis 实现配额、限流和供应商并发持有者租约，故障时不放开新调用。
- [x] 实现单次/审核员每日/供应商每日/全局每日预算，冻结价格结算、未知费用保留及有界本地恢复（每分析最多1次付费请求）。
- [x] 完成列明的受控输入/恶意文档注入与多条件/冲突政策回归（固定案例范围；检测仍可能漏报未知变体）。
- [ ] 新增公共自由审核文本时补 API 全链路注入验收；当前仅有组件输入路径。
- [x] 完成现有权限、知识准入 URL/秘密及模型工具调用响应拒绝测试（非全面渗透测试）。
- [x] Phase5补工具参数、URL、权限、共享槽与只读副作用治理验收，见[P5-1](docs/acceptance/2026-11-03-p5-1-readonly-tools.md)及[P5-5](docs/acceptance/2026-11-03-p5-5-local-mcp.md)。
- [x] 处理 2026-10-22 提交前 npm audit 发现的 5 项前端开发工具链告警（3 high、2 moderate）并复测；[2026-10-27 验收记录](docs/acceptance/2026-10-27-phase4-first-batch.md)。
- [x] 接入 OpenTelemetry、Langfuse、Prometheus 和 Grafana（本地实例与业务替身链路已运行，见P4-4记录）。
- [x] 在P4-4统一已验证用量、未知费用、未发送占用和供应商调用尝试的Trace/指标口径（账本为真相，采样快照注明新鲜度）。
- [x] 验证业务指标固定枚举标签及导出元数据白名单；实际主端口隔离、遥测不可达和重复投递验收通过。
- [x] 实现 MySQL `analysis_event` 单调序列和 Redis Pub/Sub 唤醒（V12；事务/CAS/回滚/并发与旧运行快照）。
- [x] 实现只发送受控阶段和最终可用事件的 SSE API（有界连接、逐批授权、Last-Event-ID和DB补读）。
- [x] 实现 React、TypeScript、Vite 轻量审核演示页面（创建、状态、精确证据/引用、费用/版本、反馈及Core入口）。
- [x] 验证 SSE 重连和浏览器无法看到未经校验的模型内容（本地合成流程；[P4-5验收](docs/acceptance/2026-11-03-p4-5-review-console.md)，Java404/Python183/前端11，业务37项与恢复10项通过；不替代生产或真实模型质量门槛）。
- [x] 交付 P4-6 本地工程总验收：本轮Java404/Python183/前端11、六场景224项、原16/固定24例Worker及离线校准复算、OpenAPI/文档与依赖审计；最后Schema修正后17项相关测试及新制品38项进程通过，[验收报告](docs/acceptance/2026-11-03-p4-6-final-acceptance.md)。真实人工校准、合格备用模型与生产质量/部署门槛保持未关闭，Phase5能力另列。

## 阶段 5：Agent Workflow 与 Tool Use

2026-11-03 P5-1～P5-6本地工程实现与总验收已完成，见[工具基础](docs/acceptance/2026-11-03-p5-1-readonly-tools.md)、[预算](docs/acceptance/2026-11-03-p5-2-call-budget.md)、[原生协议](docs/acceptance/2026-11-03-p5-3-native-tool-calling.md)、[Graph/HITL](docs/acceptance/2026-11-03-p5-4-durable-graph.md)与[本地MCP](docs/acceptance/2026-11-03-p5-5-local-mcp.md)。[总验收](docs/acceptance/2026-11-03-phase5-final-acceptance.md)：Java535/Python187/MCP桥4/前端18及lint/build、26例两端一致、最终制品87项进程检查通过，独立复核3项Important已修复；真实native供应商资格、明确费用授权及既有质量/依赖门槛继续单列。

- [x] 将多个只读 Core 查询建模为服务器确定性能力并建立快照只读工具。
- [x] 定义模型可选工具白名单及参数 Schema。
- [x] 实现权限、参数、次数和预算的服务器端强制校验。
- [x] 实现真实条件Graph：检索补查、原生工具选择、一次格式修复、人工门槛与失败。
- [x] 实现 HITL 暂停、恢复、取消和过期处理。
- [x] 实现运行级 Working Memory 和MySQL持久化Checkpoint/CAS/owner恢复。
- [x] 将已验证只读工具暴露为本地STDIO MCP，短期capability与Graph共用四槽。
- [x] 实现工具结果回查、审计和26例共享契约评测（替身/离线边界）。
- [x] 实现授权Agent Trace回放、来源清理和消息重投幂等验证。
- [x] 验证模型只能选择三种固定工具，不能注册工具或指定任意URL/资源身份。
- [x] P5-6最终完整套件、新制品复验与独立代码复核。
- [x] 独立原生输入上界、短期价格冻结与用户授权的真实模型批次已执行：14次请求、11/12通过、估算0.08109CNY、未知持有0；[真实验证记录](docs/acceptance/2026-11-04-native-provider-qualification.md)。
- [x] 真实native合成组件协议复验：额度提示修正后，新RQ批次12/12通过（8个有效输出、4个零HTTP准入负例），13次请求、估算0.089994CNY、未知持有0；原NQ006对应RQ006通过，完整JSON回读保持18位小数。仅关闭该合成协议范围，不代表人工/生产质量；[独立复验记录](docs/acceptance/2026-11-04-native-tool-budget-requalification.md)。旧11/12结果及锁保留，未自动重试。
- [ ] 真实人工/模型质量及P4未关闭门槛：真人抽样校准已完成；旧RAG保留集失败保留；新独立RAG集及已知主模型业务/进程复验通过，生产与广泛Agent质量仍独立待验，不能用替身通过数替代（[11月4日测量](docs/acceptance/2026-11-04-real-rag-main-quality.md)）。

## 阶段 6：production-like 部署

- [x] 既有24个Java公告匹配包完成兼容维护及当前应用适用性核查：23包命中消除；Spring WebMVC仍有2条原始版本命中，当前无触发视图路径；依赖快照全套539通过。
- [x] Copilot 升级至 Spring Boot 4.0.9，制品使用修复两条 MVC 公告的 Spring Framework 7.0.10。远端镜像复扫另列。

- [x] P6-1 创建 Core、Copilot 和 React 的非 root、多阶段 Docker 镜像。
- [x] P6-1 创建隔离的本地开发和集成 Compose 环境。
- [x] P6-1 配置健康检查、就绪检查和本地端口绑定，从空卷启动并通过 36 项只读审核与隔离检查。复跑方式与范围见[验收手册](docs/operations/phase6-integration.md)。
- [x] P6-2 使用完整 Git SHA 标记并验证镜像 ID、OCI 标签及归档校验和，接入 CI 构建和镜像扫描。
- [x] P6-2 编写 Linux 部署和回滚脚本，在 Linux CLI 中实测 A → B → A，验证页面版本切换和 MySQL 数据保留。
- [x] 完成发布提交的 GitHub 冷缓存 CI，并核验同一提交的镜像制品与扫描报告。 [冷缓存 CI](https://github.com/jkwang1129/finguard-copilot/actions/runs/42046088486) · [镜像制品](https://github.com/jkwang1129/finguard-copilot/actions/runs/42046088486/artifacts/13424673273)
- [x] 复核并锁定 Core `33179b0356fa1ce6714da42d3c3ee99e069d62b8`，六组依赖采用修复版本，保留两条 MVC 与一条 OGNL 版本命中。 [收尾验收](docs/acceptance/2026-11-05-phase6-closeout.md)
- [x] P6-3 验证两个 MySQL 库的逻辑备份恢复、RabbitMQ 停机与 Outbox 重投、Qdrant 空卷重建及实际引用。
- [x] P6-4 演练 Core、MySQL、RabbitMQ、Redis、Qdrant、Langfuse 和本地模型替身故障，并验证 Agent 重启续程与 STDIO MCP。
- [x] P6-4 生成[部署、回滚、故障、验收与 Docker 资源清理报告](docs/acceptance/2026-11-05-phase6-production-like.md)。

## 收尾门禁

- [x] Phase 6 本地回归通过。Java 640、Python 评测 187、MCP 桥 4、本地语义运行 31、前端 18，最终回归均无跳过。
- [ ] Golden Dataset、RAG、Agent、Judge 校准、安全和真实模型评测结果可追溯。
- [ ] 无业务写副作用、无秘密、无高基数敏感指标。
- [ ] 文档链接、OpenAPI、运行手册和架构图与实现一致。
- [ ] 完成范围审计、`git diff --check` 和工作区清理。
- [ ] 仅在用户明确要求后提交或推送 Git。

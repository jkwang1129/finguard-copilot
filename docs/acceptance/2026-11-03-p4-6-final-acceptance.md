# P4-6 本地工程总验收

日期：2026-11-03。入口为已推送的 `4ef7b4a66bed0e08bd910a55840cfd739c087559`，继续在 `codex/phase-4-first-batch` 独立工作树执行。此次在入口之上修改验收脚本、OpenAPI文档元数据与相关回归测试，不改变业务/JSON序列化或前端行为；报告在本轮验收提交前生成，最终提交以 Git 日志为准。

设计与顺序见 [设计](../superpowers/specs/2026-11-03-p4-6-final-acceptance-design.md)、[计划](../superpowers/plans/2026-11-03-p4-6-final-acceptance.md)。

## 结论

P4-6 的**本地工程总验收**通过：新跑全套、原16例及固定24例Worker复验、冻结Judge离线复算、六类真实进程场景、OpenAPI/文档核查和依赖审计。没有新增付费模型调用，也没有向Core提交最终业务决定。

这不关闭以下门槛：真实人工Judge校准及真实审核反馈到回归集、合格真实备用模型、真实Embedding/生产模型质量、生产部署与备份恢复。Phase5的工具执行、Graph、HITL和MCP仍未实现。Phase4工作包的工程交付与这些质量/生产门槛分开记录，不能据此宣布可投产。

## 新跑的测试与评测

| 验证 | 结果与范围 |
| --- | --- |
| Java全套 | **404 tests / 90 suites，0 failures / errors / skipped，完整命令退出0**，11分33秒。使用固定24例Worker数据；包括V1–V12迁移、V11旧运行快照升级、事务回滚/CAS/并发、Outbox/消息恢复、预算与未知费用、已知安全负例、精确数值、授权SSE/Redis和遥测隔离。 |
| 最后Schema修正后 | 新增实际HTTP OpenAPI契约回归，连同页面投影/授权/控制器共 **17 tests，0 failures / errors / skipped，退出0**；重新打包通过。404全套运行在此文档元数据修正之前，没有把汇总405项声称为另一次完整命令。 |
| Python全套 | **183 passed**，24.74秒。 |
| 前端 | **11 passed**，lint、TypeScript和Vite构建通过。 |
| npm audit | 全量依赖与 `--omit=dev` 均 **0告警，退出0**；这是本次审计时点结果。 |
| 原16例Worker | 另跑实际Worker方法与MySQL集成，Java **16/16**、冻结参考 **16/16**；模型/检索为固定测试替身，不是HTTP/Core/供应商质量评测。 |
| 固定24例Worker | 本轮全套产生的新完整输出，Java **24/24**、冻结参考 **22/24**；参考的N319、N321漏报保留。该集是原独立集的重复回归，本轮不称为新未见数据。 |
| Judge离线复算 | 冻结的历史真实DeepSeek Judge输出与AI模拟标签 **10/10一致**，与旧JSON内容相同；本轮未调用Judge，不作为真实人工一致率或生产准确率。 |

Java全套命令如下；[套件明细](p4-6/java-full-summary.json)在后续定向16例复验前独立保存，未将定向结果拼成全套通过：

```powershell
$env:MAVEN_OPTS='-Xmx256m'
mvn test '-DargLine=-Xmx512m' '-Dspring.test.context.cache.maxSize=1' `
  '-Dp4.evaluation.dataset=tools/agent-evaluation/data/phase4-worker-v3.jsonl' `
  '-Dp4.evaluation.freeze=docs/evaluations/phase4-worker-v3-freeze.json' `
  '-Dp4.evaluation.output=target/p4-6/worker-v3-java.json'
uv run --project tools/agent-evaluation pytest tools/agent-evaluation/tests -q
npm --prefix frontend run test -- --run
npm --prefix frontend run lint
npm --prefix frontend run build
npm --prefix frontend audit --json
npm --prefix frontend audit --omit=dev --json
```

此次没有运行会清除历史 `target` 的 `verify.ps1`，而是执行上述同等测试边界并保留旧日志/制品；没有重新安装已锁定依赖。因此这不是全新机器/无缓存安装验收。Maven和业务JVM实际为Java17.0.10；PATH上的 `java` 为21，已在[版本记录](p4-6/versions.json)分别注明。Maven3.9.16、Python3.12.12、Node24.14.1、npm11.11.0。

原16例参考使用现有不可变的 `phase4-worker-v2-run-freeze-v3.json`：它明确记录评测器在已见数据后的修订。原始数据冻结文件绑定旧参考，直接用于当前比较会被正确拒绝；本轮没有改写旧冻结来通过检查。复现命令：

```powershell
mvn test '-DargLine=-Xmx512m' '-Dtest=Phase4WorkerComparisonIntegrationTest' `
  '-Dp4.evaluation.output=target/p4-6/worker-v2-java.json'
uv run --project tools/agent-evaluation python -m finguard_agent_evaluation.phase4_worker_comparison `
  --dataset tools/agent-evaluation/data/phase4-worker-v2.jsonl `
  --java target/p4-6/worker-v2-java.json `
  --freeze docs/evaluations/phase4-worker-v2-run-freeze-v3.json `
  --output target/p4-6/worker-v2-comparison.json
```

输出文件必须不存在。24例对应使用v3数据和v3冻结。Worker归档保留原始精确数值文本，仅规范化物理行尾；不经过浮点重序列化。比较器另从归档文件重新严格核对事实/引用及18位小数，最终比较报告的java_artifact_sha256绑定归档文件哈希。Judge复算命令沿用[模拟校准收尾](2026-11-02-p4-2-simulated-calibration-closeout.md)，新输出在 `target/p4-6/calibration-recomputed.json`。

## 六类真实进程验证

全部采用当前Core主工作区制品、同一Copilot JAR、真实MySQL/RabbitMQ/隔离Redis/Qdrant、本地HTTP模型替身与固定Embedding。最终批次故意从错误的父进程Spring RabbitMQ用户名/密码/vhost启动，脚本必须绑定本次资源后才能运行。

| 场景 | runId | 终态/受控码 | 替身HTTP次数 | 检查 |
| --- | --- | --- | ---: | ---: |
| 正常 | 20261103101723 | SUCCEEDED | 1 | 38/38 |
| 错误方向建议 | 20261103101816 | FAILED / FACT_VALIDATION_FAILED | 1 | 38/38 |
| Core命中事实矛盾 | 20261103101911 | FAILED / FACT_VALIDATION_FAILED | 1 | 38/38 |
| 项目政策缺失 | 20261103102006 | FAILED / FACT_VALIDATION_FAILED | 1 | 38/38 |
| 预算不足 | 20261103102101 | FAILED / MODEL_BUDGET_EXCEEDED | 0 | 36/36 |
| Redis不可用 | 20261103102151 | FAILED / MODEL_CAPACITY_UNAVAILABLE | 0 | 36/36 |

合计 **224/224**。模型已调用的场景有额外的用量/结算检查，因此与未准入场景检查数不同。最终矩阵共4次本地替身请求；本轮其他前期运行也只使用替身，真实付费调用总数0。

每场景核对202后实际终态、模型HTTP计数、永久调用占用/用量/预算、Outbox/回执、DLQ及真实重复投递ACK，并核对Core业务及审计表校验和不变。未准入时占用和预算预留均未创建，页面费用为NOT_RECORDED/null；已取得用量的零价替身场景为SETTLED。所有场景验证页面精确值类型、401/403/404、SSE持久序号、续传和未来游标409。

复现入口：

```powershell
./scripts/run-business-e2e.ps1 -LocalModelStub -CoreRoot C:\dev\finguard-core
# 分别追加以下一个开关运行五类负例：
# -ExpectPolicyRejection / -ExpectFactRejection / -ExpectMissingPolicy
# -ExpectBudgetRejection / -ExpectRedisFailure
```

需先构建Core和Copilot JAR，并保证验收端口空闲。入口不会自动调用真实模型；报告和数据库/vhost按runId隔离保留。模型超时/429等其他故障由本轮Java测试及历史证据覆盖，不冒充本轮新增的供应商故障进程演练。

## 本轮发现与修正

1. **验收脚本误拒绝合法null费用。** run `20261103100502` 已正确预算拒绝、零调用、零预留，但脚本要求heldCny必须是字符串而失败。改为非空值必须是字符串，并额外强制检查NOT_RECORDED/null与SETTLED的区别。保留[失败报告](p4-6/budget-harness-negative.json)，后续最终预算及Redis用例通过；未修改业务费用语义。
2. **连续运行继承直接Spring连接配置。** run `20261103101111` 的Core监听器以空用户/密码认证失败，共享Broker环境凭据验证正常，独立重试通过。直接Spring环境变量可覆盖Core的旧RABBITMQ占位配置；脚本原先在Core启动后才设置它们。现提前绑定两个JVM并核验非空及本轮vhost，最终六个带错误父配置的场景连续通过。保留[启动失败报告](p4-6/inherited-environment-startup-negative.json)，不将它算成业务事实失败。

3. **OpenAPI未允许实际响应的null。** 页面费用、失败码、结果、版本及反馈等字段实际可能为null，生成Schema原先只声明字符串/对象。增加OpenAPI3.1的显式anyOf-null元数据，保留原类型及引用，且只处理Page/Cost，不改变旧AnalysisResponse。新HTTP回归先失败、修正后受影响17项通过；旧响应仍按既有NON_NULL规则省略空字段。新制品另做实际HTTP核验。

前两项限于验收脚本，第三项限于OpenAPI文档元数据；业务/前端/Core行为不变。每项保留失败再通过的证据。

## 契约、历史证据与剩余门槛

从实际Copilot读取[OpenAPI快照](p4-6/openapi.json)，[契约核查](p4-6/api-contract-check.json)确认创建/查询/精确投影/SSE/反馈五个操作、SSE媒体类型、页面ID和费用字段为字符串。原结果API数值契约保持不变。SSE帧及投影中自由对象的详细字段以[审核台运行契约](../operations/phase4-5-review-console.md)补充，未将Springdoc自由对象误称为完整强类型事件Schema。

[入口核验](p4-6/entry.json)核对远端4ef7b4a、345项代码输入及10份P4-5产物原始哈希；其中7份文件在Git中仅CRLF→LF，逐字节排除换行差异后内容一致。历史冻结不改写；本轮冻结采用明确的文本换行规范化口径，便于新checkout复核。

P4-4的真实本地Langfuse接收、Prometheus/Grafana及保留策略，P4-5的真实浏览器/Redis进度恢复，作为**同源历史支持证据**引用，未伪称本轮重新运行了这些界面/后端。业务逻辑与入口一致，本轮补跑前端测试与SSE进程场景；最后OpenAPI元数据修正后，另以新制品运行正常流程 `20261103103623`，**38/38通过**并读取新Schema核对可空声明，保存为[business-final-api](p4-6/business-final-api.json)。该次与原六场景224项分别记录，原矩阵制品SHA为eaae开头，新制品SHA为 `c6fef4ab769c9775534aa690b16dc77fe9fd7cee7ea1261c9d3b25173c9472c7`。[P4-4记录](2026-11-02-p4-4-observability.md)、[P4-5记录](2026-11-03-p4-5-review-console.md)。

最终报告、代码输入和制品哈希见 [P4-6冻结清单](2026-11-03-p4-6-baseline.json)。一次非Astra独立复核未发现Critical/Important问题；其提醒的旧API快照已更新，前后快照分别留存。全部26个本轮隔离容器及自建JVM/替身已停止，验收端口释放；共享基础服务及既有观测服务未停止，数据库/vhost和旧日志保留。详见[清理](p4-6/cleanup-report.json)和[复核](p4-6/review-summary.json)。真实人工校准等门槛保留在TASKS中；进入后续功能阶段不能自动视为这些门槛通过。

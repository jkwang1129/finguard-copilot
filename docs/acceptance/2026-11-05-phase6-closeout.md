# Phase 6 收尾验收

## 发布与依赖

- 发布分支为 `codex/release-phase6-final`。分支推送从完整 Git SHA 构建，禁用 Maven、uv、npm 恢复缓存。镜像扫描使用每次运行新建的 Trivy 数据卷，三个报告和镜像归档共同上传。
- 首轮远端运行暴露 SSE 测试中的清理时序竞争。第二轮四组测试通过，三个 Trivy 扫描均完成。扫描后的清理步骤因容器生成的文件归属 root 失败。改为通过 Docker 删除本次运行的数据卷。[首轮 CI](https://github.com/jkwang1129/finguard-copilot/actions/runs/42036410666) · [第二轮 CI](https://github.com/jkwang1129/finguard-copilot/actions/runs/42037935295)
- 第三轮 Gitleaks 将上游公开分词器修订和文件哈希当作凭据。调整字段名称，从前一候选提交创建最终发布分支，保留原运行证据。[第三轮 CI](https://github.com/jkwang1129/finguard-copilot/actions/runs/42043557503)
- Core 选用 `33179b0356fa1ce6714da42d3c3ee99e069d62b8`，在 Copilot 构建端统一升级六组依赖。最终 JAR 中核对到 Jackson 2.21.7、RabbitMQ Java 客户端 5.37.0、Netty 4.1.137.Final、Commons Lang 3.18.0、Log4j 2.25.5、Tomcat 10.1.61。对照原 35 条扫描命中，33 条对应组件已达到报告中的修复版本。该制品通过 36 项隔离 Compose 验收。
- Core 仍含 Spring MVC 6.2.19。按锁定提交核查了 Web 源码，未找到 `XsltView`、SSE `FragmentsRendering`、`ModelAndView` 或配置的视图解析器。这两条保留为原始版本命中及未来新增相关功能的复核项。Copilot 升级至 Boot 4.0.9，从实际容器提取的 JAR 包含 `spring-webmvc-7.0.10.jar`，通过 36 项隔离 Compose 验收。使用 Boot 的 Jackson 2 兼容模块保留 API 和持久化契约。 [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) · [CVE-2026-47890](https://spring.io/security/cve-2026-47890/)

## 模型与反馈

两家供应商都评测了相同的 12 个已冻结合成案例。两组使用 `review-analysis-v2`、相同 Schema、JSON object、非思考模式、温度 0.01、每次最多 2000 输出 tokens 和 20 秒单次超时。每个案例发起一次请求。建议、事实值、精确引用、analysisId、JSON 各 12/12，应拒答 5/5（含注入 2/2）。该集合此前用于主模型评测，属于已知回归。调用直接发送到供应商的标准端点，证据只保留结构化结果和用量。

| 模型 | 请求成功 | 建议 | P95 | 保守估算 |
| --- | ---: | ---: | ---: | ---: |
| DeepSeek `deepseek-flash` | 12/12 | 12/12 | 5.22 秒 | ¥0.108338 |
| 智谱 `glm-5.2` | 12/12 | 12/12 | 10.64 秒 | ¥0.394424 |

授权上限为 ¥8，整批按发送前的输入 UTF-8 字节数加框架余量、输出上限及无缓存单价预留 ¥1.802630。24 次请求的用量齐全，按保守单价复算合计 ¥0.502762，实际扣费以账户账单为准。[DeepSeek 用量与逐例报告](../evaluations/2026-11-05-deepseek-qualification.json) · [智谱用量与逐例报告](../evaluations/2026-11-05-zhipu-glm52-qualification.json) · [DeepSeek 价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) · [智谱价格](https://docs.bigmodel.cn/cn/guide/start/pricing)

`COPILOT_REVIEW_PRIMARY_UNAVAILABLE=true` 在审核 Worker 启动时选择智谱 GLM-5.2。仅在已批准的隔离审核环境设置此标记，并从密钥配置注入 `COPILOT_REVIEW_BACKUP_API_KEY`。使用 `deepseek-flash` 主配置和 `review-analysis-v2`，关闭 Agent 与 fixture，按批准的每次预算运行。先停止受理并处理原 Worker 在途任务，再重启切换。默认配置仍选择 DeepSeek。测试覆盖 Spring 启动选路、版本与价格先于调用占用冻结、单请求失败、独立供应商并发与共享审核员配额。已有清单按原供应商处理，发送或状态不确定时维持原调用记录。

备用模型的输入预算使用固定文本协议和 UTF-8 字节上界，绑定已核对的 GLM-5.2 ByteLevel BPE tokenizer 修订与 SHA-256。该验证限于单次文本审核，Agent 原生工具路径单独保留资格门槛。 [GLM-5.2 tokenizer](https://huggingface.co/zai-org/GLM-5.2/blob/cf457fa734ab149ffef225f80893eb38c6ff5cdc/tokenizer.json) · [智谱非思考模式](https://docs.bigmodel.cn/cn/guide/capabilities/thinking)

反馈使用合成输入验收。单测从两个反馈中筛选一个独立标注案例，覆盖未批准、重复、额外字段、未完成分析和自由文本的拒绝行为。另一次隔离 Compose 验收经 HTTP 提交合成审核反馈，从 MySQL 只读导出，冻结去除原分析 ID 的回归集并重放 1/1，整批 37 项检查通过。真实反馈样本待补。 [反馈筛选与回归命令](../../tools/agent-evaluation/README.md#将审核反馈筛选为可复跑案例)

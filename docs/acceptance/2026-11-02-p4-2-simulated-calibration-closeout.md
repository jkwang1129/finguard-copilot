# P4-2 模拟审核校准收尾（2026-11-02）

用户指定以 AI 模拟审核完成本次交付，并授权提交、推送。交付包含[10 例填写及理由](../evaluations/phase4-simulated-review-completed-v1.md)、冻结输入核对和离线复算；没有新增真实审核员或模型请求。本次费用为 0 元。

## 样本和标签

复用 `simulated-ai-review-v1`：CONFIRMED 2、IGNORED 2、INSUFFICIENT_EVIDENCE 6，逐例理由已补全。规则仅为项目合成政策，样本中包含 `expected`；本次不是盲审，不将复核重命名为独立人工标注。真实人工盲审模板及历史 v1 文件继续保留。

| 输入/输出 | SHA-256 |
| --- | --- |
| phase4-calibration-sample-v1.jsonl | a87e2a12e55652840878c7b7b8dcae20e68363575cfa452749781d6c84bb6bd3 |
| phase4-simulated-review-labels-v1.jsonl | 1a81fdb04432400418592d673ae4e5f7f92d4133fb5b97649e435a79bfa4070c |
| phase4-deepseek-judge-v1.jsonl | 9305c33ec9bae9a8d7686ace10eb7507ad65ebb6ab7bd46a66e19ee1af61ef45 |
| phase4-deepseek-simulated-calibration-recheck-2026-11-02.json | 58921e1b2b41e68f88e1d038814c54a67484b5c80809c3f437a2f2610feffbf4 |

## 离线复算

在仓库根目录执行，输出路径必须不存在：

```powershell
uv run --project tools/agent-evaluation python -m finguard_agent_evaluation.phase4_judge `
  --dataset docs/evaluations/phase4-calibration-sample-v1.jsonl `
  --labels docs/evaluations/phase4-simulated-review-labels-v1.jsonl `
  --judge docs/evaluations/phase4-deepseek-judge-v1.jsonl `
  --output target/my-p4-2-simulated-calibration.json `
  --label-version simulated-ai-review-v1 `
  --judge-version deepseek-flash-synthetic-judge-v1
```

本次命令退出码 0，[复算报告](../evaluations/phase4-deepseek-simulated-calibration-recheck-2026-11-02.json)记录 matched=10、total=10、disagreed=0、abstained=0、missing=0。它与既有 DeepSeek 校准报告的 JSON 内容一致，Judge 输出来自历史真实调用，本次只做离线重算。

100% 仅表示这 10 例历史 Judge 输出与 AI 模拟标签一致。报告的标签和输出验证状态继续保留 `unverified`，没有把哈希一致解释为专家认可、远端权重验证或生产准确率。模拟填写任务已完成；原计划中“取得真实人工标签并完成 Judge 抽样校准”仍是另一个待验收条件。

## 提交前验证

- 校准 CLI 退出码 0；新报告与历史报告 JSON 内容相同；填写表覆盖全部 10 例，并与冻结标签及精确十进制政策逐例一致。
- `uv run --project tools/agent-evaluation pytest tools/agent-evaluation/tests -q`：175 passed，23.14 秒。
- 前端 `npm run lint`、`npm run test -- --run`（1/1）、`npm run build` 均退出码 0。
- `mvn -q '-Dspring.test.context.cache.maxSize=1' test` 退出码 0；本轮 76 份 Surefire 报告共 371 tests，0 failures、0 errors、0 skipped。日志在忽略目录 `target/p4-2-simulated-closeout-java.log`。
- 本次涉及的 6 份 Markdown 共 44 个本地链接检查通过；输入/输出 SHA-256 与上表一致。
- 与 P4-3 冻结清单相比，288 个条目中 287 个字节未变，仅评测工具 README 增加本次记录入口；业务源码及测试未因本次模拟填写改变。未重写历史冻结清单或进程验收报告。
- 对待提交内容及当前已有受版本管理文件做常见凭据模式、敏感扩展名及大文件检查：430 个路径，无真实凭据候选、敏感扩展名或超过 2 MB 的文件；3 处凭据 URL 匹配均为 `example` 域名下的 `user:pass` 配置拒绝测试或其计划。
- `git diff --check` 与暂存区空白检查均通过。

## 提交范围

当前 Phase 4 分支此前没有提交；本次提交包含保留的 P4-1 安全护栏、P4-2 评测与反馈、P4-3 成本治理实现及本次模拟审核记录。历史验收中的 dirty HEAD 和“当时未提交”描述保持原样，不能用新提交重写过去的运行版本。

用户随后明确要求在其 GitHub 账号下创建 `finguard-copilot`。已验证 CLI 和已连接账号均为 `jkwang1129`，并创建[私有仓库](https://github.com/jkwang1129/finguard-copilot)，配置为 `origin`；提交目标分支为 `codex/phase-4-first-batch`。本次不合并到主分支、不创建发布、不删除工作树。

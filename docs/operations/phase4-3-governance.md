# P4-3 调用与费用治理运行说明

真实profile（real-deepseek等）已接入运行清单、Redis许可和MySQL预算。公开业务API仍为创建分析/查询终态；202仅表示受理，预算不足和容量不可用在异步终态中返回受控失败码。

## 配置

| 环境变量 | 默认/要求 |
| --- | --- |
| COPILOT_MODEL_PROVIDER / MODEL_ID | 真实调用当前只接受deepseek / deepseek-flash |
| COPILOT_MODEL_BASE_URL | https://api.deepseek.com |
| COPILOT_MODEL_CHAT_COMPLETIONS_PATH | /chat/completions，必须显式匹配已核对协议 |
| COPILOT_MODEL_CURRENCY | 真实治理要求CNY；历史默认USD不会被隐式换汇 |
| COPILOT_PRICE_TABLE_VERSION | 必须是已核对的非unpriced版本；首次登记后同名内容不能更改 |
| COPILOT_MODEL_INPUT_PRICE_PER_MILLION / OUTPUT_PRICE_PER_MILLION | 真实调用须正价；按供应商高峰/输入缓存未命中价格核对 |
| COPILOT_PRICE_VALID_FROM / VALID_UNTIL | 初始2026-11-02T00:00:00Z / 2026-12-02T00:00:00Z，结束不含；过期拒绝 |
| COPILOT_MAX_CALL_CNY | 0.10，精确十进制，最多8位小数 |
| COPILOT_MAX_REVIEWER_DAILY_CNY | 1，UTC自然日 |
| COPILOT_MAX_PROVIDER_DAILY_CNY / MAX_GLOBAL_DAILY_CNY | 各10，UTC自然日 |
| COPILOT_REDIS_HOST / PORT / PASSWORD | localhost / 6379 / 空；须按当前隔离环境配置 |
| COPILOT_REDIS_TIMEOUT / CONNECT_TIMEOUT | 各2s |
| COPILOT_PROVIDER_CONCURRENCY | 2 |
| COPILOT_REVIEWER_PER_MINUTE | 10，Redis服务器时间的自然分钟窗口 |
| COPILOT_GOVERNANCE_FIXTURE_MODE | false；仅显式测试允许http://127.0.0.1:18765 + local-test-stub，不用于远端调用 |

密钥仍从本机忽略配置/进程注入，不入清单或日志。`.env.local`不会由Spring自动加载，可沿用import-model-environment.ps1；新增费用/Redis变量须显式设置。项目默认额度是演示配置，不是供应商账单、监管要求或长期费用承诺。不能通过修改版本号重置同日账户余额；同日更改既有额度被拒绝。

## 核查一次分析

以受信任运维数据库连接查询，analysis_id用UUID转为BINARY(16)参数；不向审核页面开放任意SQL：

```sql
SELECT manifest_sha256, manifest_json FROM model_run_manifest WHERE analysis_id = ?;
SELECT status, reserved_cny, settled_cny FROM model_budget_reservation WHERE analysis_id = ?;
SELECT event_type, details_json FROM model_budget_event WHERE analysis_id = ? ORDER BY created_at, event_id;
SELECT provider, model_configuration_version, input_tokens, output_tokens,
       estimated_cost, currency, price_table_version, outcome
FROM model_usage_ledger WHERE analysis_id = ?;
```

- 无调用占用：配置/预算/容量准入可能尚未通过；核对分析failure_code。
- RESERVED：已取得调用占用，不能重新调用；存储故障/进程退出后结果可能未知。
- UNKNOWN_HELD：保留金额，不按TTL释放；OBSERVED_USAGE是差异证据，不是已结算账单。缺少usage ledger不代表免费。
- RELEASED_NOT_SENT：已证明未发送而释放金额，调用占用仍在；不能在同分析重试。
- SETTLED：正常用量或供应商确认费用已经结算；model_usage_ledger保留调用时估算，RECONCILED记录后续账单修正。

PROVIDER_RESPONSE记录供应商返回请求id/model标识的哈希、HTTP状态及报告用量，不保存响应正文；无返回标识不伪造。供应商未响应时可能没有该事件，应结合永久调用占用与UNKNOWN_HELD核对。基础用量指标来自已验证账本；不要用账本行数替代全部调用尝试，P4-4将统一未知/未发送/已发送的指标口径。

人工/供应商核对使用内部`BudgetReservations.reconcileConfirmedCost`端口，必须有可信账单依据和唯一来源引用；没有公开审核员HTTP接口，不能仅因超时或未见账本就执行释放。账单超额保留真实金额并阻止下一次预算申请，历史估算不覆盖。

## 复测

从仓库根使用PowerShell7运行，本地替身不收费：

```powershell
pwsh -NoProfile -File scripts/run-business-e2e.ps1 -LocalModelStub
pwsh -NoProfile -File scripts/run-business-e2e.ps1 -LocalModelStub -ExpectBudgetRejection
pwsh -NoProfile -File scripts/run-business-e2e.ps1 -LocalModelStub -ExpectRedisFailure
```

错误方向/事实矛盾/政策缺失分别为ExpectPolicyRejection、ExpectFactRejection、ExpectMissingPolicy。每次创建隔离数据库/vhost/Redis/Qdrant；脚本停止自建进程和容器，保留隔离数据供核查。不要将报告中的fixture结果解释为真实供应商质量。真实测试须另有费用授权，脚本最多一次供应商请求，价格与预检查上限入报告。

参见[输入上界的适用假设](../contracts/phase4-3-deepseek-input-bound-v1.md)、[调用/费用契约](../contracts/phase4-3-call-budget-v1.md)和[验收记录](../acceptance/2026-11-02-p4-3-governance.md)。

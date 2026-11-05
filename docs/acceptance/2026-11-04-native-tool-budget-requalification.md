# Native tool budget correction and independent requalification

Date2026-11-04. User requested the correction and a separate requalification batch; prior paid-model authorization continues. No commit/push/deployment. This report concerns the follow-up; the [original failed batch](2026-11-04-native-provider-qualification.md) remains unchanged.

## Correction

Automatic retrieval and its retry already consume two of four analysis tool slots. Previously, the planner did not receive this consumption, even though the runner rejected an oversized response. The new static agent-review-v2 prompt explains that each individual call counts, including automatic retrieval/retries and duplicate calls. Supplied facts can be used directly; empty evidence does not compel another identical search.

The server now renders EXECUTION_BUDGET from persisted AgentWorkingState before CORE_FACTS. Its fields are toolCallsLimit, toolCallsUsed, toolCallsRemaining and maxToolCallsThisTurn. A first plan after two retrievals sees4/2/2/2; restored state after one retrieval sees4/1/3/3. GENERATE and REPAIR set maxToolCallsThisTurn=0 even when analysis-level slots remain. The actual four-tool and two-model-call guards remain authoritative.

Dynamic quota stays in the user context, while the static system prompt identity stays identical across both calls. Input hashes still freeze each exact request. The previous v1 prompt remains for historical evidence; an old frozen policy intentionally does not silently resume under a different prompt hash.

## Offline evidence

Quota tests first failed5 assertions before implementation (13 tests,0 errors). The new pure pack validator initially failed to compile because its API did not exist. After implementation,24 focused unit tests passed. Relevant SQL ledger/Graph recovery/frozen26/native HTTP integration passed52/52. The existing Python acceptance stub also consumed a quota-bearing context and preserved the exact decimal and analysis id.

One fresh read-only review reported0 Critical/Important/Minor findings. The original batch invocation was behaviorally checked to refuse before credential import or services. Seven historical inputs/results/lock artifacts were SHA-256 checked unchanged.

Final whole-source Java regression passed552/552, zero failures/errors/skips, Maven exit0 at2026-11-04T14:01:23+08:00. Package build passed at14:01:54. Commands: `mvn -o test -DargLine=-Xmx512m -Dspring.test.context.cache.maxSize=1`, then `mvn -o package -DskipTests`. Earlier24/52 are overlapping runs and must not be added to552. Python/frontend/full process acceptance was not rerun; only the existing Python stub context-consumption control was exercised.

Fifteen executable/config/schema hashes and seven historical artifact hashes were verified before marking the new proposal AUTHORIZED_READY. The new profile's `-ValidateOnly` succeeded with no credential import, containers or HTTP. Paid execution started only after these gates; its outcome is recorded below.

## Independent frozen scope

New batch `phase5-native-retest-20261104` uses RQ-001..012, mapped to NQ-001..012 in a [separate dataset](../../tools/agent-evaluation/data/phase5-native-requalification-v2.jsonl). Scenarios, tools, amounts and account strings are identical; only case identifiers/source mapping differ. The original12-case and26-case datasets, report, proposal and paid-batch lock remain intact.

Limits remain12 cases,24 requests,0.10CNY per call and2.00CNY batch, no automatic retries. DeepSeek's [official CNY price page](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) was rechecked: deepseek-flash advertises DeepSeek-V4.1-Flash-1017, peak cache-miss2CNY/M input and8CNY/M output. This conservative reviewed price expires2026-11-05T00Z; execution must reject outside that window. Estimates are not invoices.

Actual execution must retain its own database/output/exclusive lock. Sanitized per-turn metadata records the advertised quota, tools enabled and actual selected tool-call count, without raw model candidates or credentials. All outcomes, including any failure, must remain. Unknown usage stops the batch without retry.

This is synthetic native-protocol/component validation with actual SQL/Redis and provider HTTPS. Retrieval is deterministic empty published-index evidence. It does not establish Core/UI process E2E, real embeddings, human quality or production readiness.

## Actual outcome

**QUALIFIED_SYNTHETIC_PROTOCOL:12/12 passed**, comprising8 validated positive outputs and4 zero-HTTP preflight negatives. This fraction is not a model-accuracy measurement. Actual provider attempts and durable claims:13. SQL independently confirmed all13 SETTLED,23049 input tokens,5487 output tokens; no RESERVED/UNKNOWN_HELD rows. Frozen peak/cache-miss estimate0.08999400CNY,held0.00000000CNY,not an invoice. Both Phase5 batches together used27 requests and an estimated0.17108400CNY.

| Cases | Actual behavior |
|---|---|
| RQ001/002/003/007/008 | Five tool-result round trips passed; first-turn selected counts2/2/1/3/2 within advertised4, second turn selected0 with advertised0 |
| RQ004 | Direct validated final output, one request |
| RQ005/006 | Real runner used two automatic retrieval slots, advertised used2/remaining2/maxThisTurn2, selected0 additional tools and returned validated final output in one request each |
| RQ009/010/011/012 | Unsupported model, malformed Unicode, context overflow and insufficient budget rejected with0 HTTP/claims |

RQ006 corresponds to the previously failed NQ006, without changing its business amount or acceptance criteria. It now avoids an oversized tool plan; this run does not demonstrate that every future stochastic response will honor quotas. The hard guard remains required and covered offline. RQ005/006 did not take a model-selected tool round trip; the five manual protocol cases did.

The [raw report](phase5/native-requalification-20261104.json) is a byte-identical copy of the execution report, including per-turn quota/selection metadata. The new [proposal](phase5-provider-requalification-proposal.json) is EXECUTED_QUALIFIED_SYNTHETIC_PROTOCOL and its one-time lock remains. Own database `fg_p5nq_20261104_140228` is retained; its Redis container was stopped. No automatic retry or additional paid batch occurred. Default real-Agent flags remain off. Human quality, backup-model and deployment gates remain open.

### Decimal readback postcheck and corrected hypothesis

A scalar SQL JSON_EXTRACT projection displayed100.12345678901234. An initial storage-loss hypothesis was withdrawn after stronger checks: complete stored snapshot and result JSON both contain the exact numeric token100.123456789012345678. The compiled production SnapshotCodec.decodeAndVerify accepted the full stored snapshot checksum; StructuredAnalysisParser and FactConsistencyValidator accepted the full result readback, both amounts compared exactly as BigDecimal. The first probe expecting checksum failure did not reproduce that hypothesis. The corrected readback probe passed without changing any application code, data or paid outcome. The scalar projection alone is not used as evidence of storage loss.

Runtime JAR SHA256:`df11033b5dcd24155cad9fb51bcdba5d10e6cf0f98fa4ef2c349e3e18b5fc04d`. No commit,push,merge or deployment.

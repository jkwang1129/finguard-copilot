# Phase5 dependency maintenance and real native verification

2026-11-04. User explicitly authorized dependency remediation followed by paid synthetic model verification. Ceiling:12 cases,24 requests,0.10CNY per request,2.00CNY batch,no automatic retries. No commit,push,merge or deployment.

## Outcome

Dependency version matches fell from24 packages/91 advisories to1 package/2 advisories after querying249 actually resolved Java/Python packages. The remaining Spring WebMVC6.2.19 matches concern XSLT implicit-view rendering and SSE view fragments. Current first-party routes are REST handlers, with typed JSON events and no XSLT/view-fragment configuration. Raw matches remain recorded; this is a current application-surface assessment, not an upstream library patch or zero-vulnerability claim.

Real model verification finished **NOT_QUALIFIED:11/12 cases passed**. Actual provider HTTP attempts and durable model claims:14. All are SETTLED, no UNKNOWN_HELD or RESERVED remainder. Frozen peak/cache-miss estimate:0.08109000CNY; held0.00000000CNY. This is an estimate from verified usage, not the supplier invoice. No case was retried and no additional paid batch was started.

## Dependencies

Boot3.5.8 ->3.5.16; Java17 and Graph1.1.2.2 retained. Residual family overrides: Jackson BOM2.21.7,Netty4.1.137.Final,OpenTelemetry1.67.0,Rabbit client5.37.0,Commons Lang3.18.0,Log4j2.25.5,Tomcat10.1.61,Commons Compress1.26.0 in dependency management only. Graph's DTO-only exclusions remain. Upstream-advertised Tomcat10.1.58/Rabbit5.36.1 artifacts were absent in Central; actual later available releases were verified before use.

AMQP's tightened default trust produced one real regression in a generic receive path. A failing round-trip test preceded an exact AnalysisRequestedMessage type-id mapping. Other package classes and java.io.File remain rejected; no wildcard package trust was added. Focused11 dispatch tests and the dependency-only full539 tests passed, zero failures/errors/skips; its package build passed. The actual configured OTLP exporter sent authenticated protobuf to a local collector in the focused89 suite, covering the reviewer-noted OkHttp/Kotlin mediation path.

Security findings, source ranges, before/after query results and the fresh candidate review are retained in the tool-managed Codex Security standalone collection for this repository. Historical Phase5 audit files remain historical. Public sources: [Boot Java17 compatibility](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Spring fragment-SSE advisory](https://spring.io/security/cve-2026-47890/), [Spring XsltView advisory](https://spring.io/security/cve-2026-47884/). Spring6.2.20 is enterprise-only; a public patched7.x platform migration remains separate from this compatible maintenance change.

## Native input and price contract

The independent [native bound](../contracts/phase5-native-input-bound-v1.md) restricts text roles, matching tool ids, three hash-pinned schemas, non-thinking mode and the exact deepseek-flash endpoint. It measures the actual shared wire payload plus audited public-renderer framing. Unsupported models, malformed UTF16, arbitrary assistant containers and invalid tool pairing reject before a claim.

Separate native review found2 Important gaps. New regressions first reproduced assistant-container coercion and arbitrary positive prices/not-frozen price policy. Both were fixed: IDs and empty assistant content must have exact types; the reviewed2/8CNY peak/cache-miss price/version/short validity are checked and frozen in the analysis policy. Existing fixture/legacy policy shapes remain unchanged. Final unit19 and relevant SQL/Graph/native integration89 tests passed. Remote API internals/alias stability remain public-encoding assumptions, not proven private implementation properties.

## Frozen paid batch

Batch `phase5-native-20261104`, dataset SHA256 `fc20397983d424c8c257d911e90df47660405de720d1adf518e8972416bc62fd`. Inputs were frozen before any paid request; the original26-case fixture dataset was not changed. [Actual report](phase5/native-provider-20261104.json) records every case, including the failure and four zero-HTTP negatives.

| Cases | Result |
|---|---|
| NQ001/002/003/007/008 | Five explicit actual native tool-call -> shared ToolExecutor -> final validated result round trips passed |
| NQ004 | Direct final result validated with one request |
| NQ005 | Actual bounded runner succeeded with two model calls and four shared tool slots |
| NQ006 | Actual bounded runner failed safely with MODEL_BUDGET_EXCEEDED after its first model call; two retrieval slots already used, proposed tool batch exceeded remaining slots |
| NQ009/010/011/012 | Unsupported model, malformed Unicode, oversized context and insufficient budget all rejected with zero HTTP and zero model claims |

NQ006 records one settled call,1484 input/95 output tokens,0.00372800CNY peak/no-cache estimate. Its SQL tool receipts contain only retrieval-0 and retrieval-1; no proposed excess tool was executed. The failed candidate/tool-plan body was not retained. This is evidence of a correctly enforced cap and a failed positive qualification case, not a demonstrated decimal-precision error.

Execution used actual DeepSeek HTTPS, SQL call/budget ledger, Redis permits, the shared adapter/ToolExecutor and final validator. Knowledge retrieval was an explicitly deterministic empty-evidence fixture. This was not a new Core API/UI process E2E, real embedding assessment, human calibration or production-quality qualification. The official advertised model is DeepSeek-V4.1-Flash-1017; the public alias does not lock remote weights.

## State and next action

The one-time paid batch lock remains, its database `fg_p5nq_20261104_130256` is retained, and its owned Redis container was stopped. Credentials were inherited only in environment and were not written into reports. Default real-native settings remain off, and the proposal is marked EXECUTED_NOT_QUALIFIED.

The next improvement is to make remaining tool-slot information explicit to the planner, verify this offline, and freeze a separate follow-up validation scope. Do not silently retry NQ006 or tune the frozen batch into a pass. Human quality and Phase6 deployment remain open.

Final whole-tree Java regression after all native changes passed547/547, zero failures/errors/skips, Maven exit0 at2026-11-04T13:24:28+08:00 (`mvn -o test -DargLine=-Xmx512m -Dspring.test.context.cache.maxSize=1`). The package build also passed; runtime JAR SHA256 `00e6c5300510f3824ddcc5d68c8d071fd592fb277eafdbd824abc231c6822071`. Dependency-only539, focused89 and unit19 are overlapping earlier runs, not additions to547. Historical Python/frontend/process evidence was not rerun in this Java-only follow-up and remains separately dated.

Final source Gitleaks scan retained only9 baseline matches (public tokenizer checksum and deterministic test fixtures); all matched files were unchanged from HEAD after line-ending normalization. The actual provider credential was also checked against the execution report/log, public report and proposal without printing it. All eight source hashes and both frozen datasets matched the pre-call manifest; V1–V12 migrations were unchanged,77 local Markdown links resolved, and `git diff --check` passed. No commit, push or deployment was performed.

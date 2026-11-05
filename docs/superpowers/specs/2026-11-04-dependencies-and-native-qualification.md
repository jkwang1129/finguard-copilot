# Dependencies and native-provider qualification

2026-11-04: user explicitly authorized dependency remediation first, then real model qualification, including paid calls. Reuse the current Phase5 worktree; all previous uncommitted changes remain intact.

## Authorized scope

1. Replace affected resolved dependencies with compatible patched versions. Keep Java17, Boot3.5 and Graph1.1.2.2 unless actual evidence requires a broader compatibility decision. Preserve P4 behavior, shared budgets, permanent ordinals, read-only Core access and strict native protocol validation.
2. Requery every resolved Java/Python package, including test dependencies. Version presence is not proof of exploitation. Retain remaining matches explicitly and assess exact triggers; never silently suppress them.
3. Establish a separate native-tool input-token bound before removing real-provider refusal. Freeze the exact official model/endpoint/tokenizer/chat-template/price contract, reject unsupported models and malformed Unicode, retain conservative preflight reservation and verified usage checks.
4. Run only synthetic qualification inputs: at most12 cases,24 paid requests,0.10CNY per request and2.00CNY for this entire batch, no automatic retries. Retain all outcomes and unknown costs. Existing approval is sufficient within these ceilings; never spend merely to diagnose dependency failures.
5. No commit, push, merge, deployment, real customer facts or human-label fabrication.

## Evidence and implementation boundaries

Dependency changes belong in pom.xml at the Boot/BOM/family boundary, with no single-jar family skew. Minimal compatible patched branches are preferred over unrelated major upgrades. No applicable SECURITY.md was resolved; product contracts and actual entry points define scope. Source/configuration changes stay in this worktree; security evidence and disposable verification copies use the Codex Security tool-managed storage.

Boot3.5.16 still carries Spring6.2.19. Two current advisories explicitly include6.2.19 and only publish a7.0.9 fix: fragment-SSE rendering and XsltView wildcard rendering. Maven Central has no6.2.20. Inspect actual JSON/SSE/controller/view configuration and guard its supported surface; do not force Spring7 into Boot3 or claim zero version matches.

Native qualification remains blocked until an independently assessed input-bound proof exists. Fixture byte+1024 arithmetic alone is not a provider proof. Reuse provider admission, call ledger, ToolExecutor and final validator rather than direct unaudited API requests. Existing human-quality and deployment gates remain independent.

## Acceptance

Resolved-classpath audit plus focused parser/Graph/OpenAPI/Redis/RabbitMQ/SSE tests and full Java regression; one fresh read-only pre-patch investigator and one fresh candidate reviewer for dependency changes. Then native-bound negative tests, actual bounded paid native tool-calling receipts, exact cost/count accounting, no provider key or raw failed output in evidence. Report protocol qualification separately from recommendation/human quality.

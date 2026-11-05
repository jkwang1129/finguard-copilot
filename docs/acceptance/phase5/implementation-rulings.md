# Phase5 implementation rulings

Ordered decisions copied from the execution ledger.

- Ruling: Use PowerShell task bookkeeping where task-start parser expects Task headings but approved plan uses P5 headings — preserve exact task text in plan and use this ledger as completion authority — cost if wrong: manual bookkeeping needs checking at handoff.

- Ruling: P5-1 capture mode remains explicitly opt-in and default P4 untouched; until P5-3/4 is integrated it is capability groundwork, not a released Agent runtime — preserves legacy clients/test stubs — cost: no complete agent demonstration before later tasks.

- Ruling: The new task-state projection exactly duplicates already captured task fields, so verify it then persist provenance/workflow policy separately instead of changing snapshot schema1 — avoids breaking immutable old snapshot readers with no new business fields — cost: provenance lives in agent_run_policy rather than snapshot payload.

- Ruling: Adapt the E2E migration gate to require all current source migrations rather than latestVersion=12 — V13 is intentional, Flyway startup still validates checksums — cost if wrong: a future migration must retain corresponding structural checks for new behavior.

- Ruling: Keep P4 admission/reservation/usage implementations intact and add separate agent ledger against the same daily accounts, adapting only the page projection — avoids widening P4 single-call guarantees — cost: P5 introduces a parallel ledger whose metrics/trace must be integrated in P5-4.

- Ruling: Reuse final policy authority via ModelAnalysisOrchestrator.validateAgentCandidate and parseForAgent instead of duplicating directional rules — P4 parse behavior unchanged, Agent security failures cannot enter repair — cost: new public internal validation method must remain side-effect-free.

- Ruling: P4's reviewed token bound is explicitly for two text messages and cannot qualify native tools. Keep real Agent provider admission closed, engineering fixture only, until an independently reviewed native bound and actual budget are available — prevents unproven paid request limits — cost: real-provider Agent qualification remains an explicit P5-6 gate, not a claimed capability.

- Ruling: compareAndAdvance explicitly carries owner, typed Working Memory and optional deliveryId in addition to revision — original interface could not enforce fencing or persist a resumable node — cost: all new Graph callers must use the expanded contract; no old public API changes.

- Ruling: Use explicit empty framework SaverConfig and a MySQL recovery scanner rather than a second framework saver — default MemorySaver would create divergent state and cannot recover a process — cost: our CAS/lease/Outbox adapter owns recovery semantics and requires fault-window evidence.

- Ruling: Fixture matrix performs real owned-JVM halt(86), then expires only the dead owner's lease timestamp for bounded recovery time — permanent model/tool claims and working memory are untouched; no paid/shared service faults — cost: this proves recovery after expiry but does not measure the wall-clock two-minute lease delay.

- Ruling: Settled model response lost before checkpoint advancement ends as AGENT_STEP_UNCERTAIN instead of reissuing the ordinal — ledger has usage/hash but deliberately no raw model body — cost: a paid successful response can require a new authorized analysis; at-most-once attempts take priority over automatic progress.

- Ruling: Extend internal ToolExecutionContext with optional MCP revision/capability hash and recheck within parent-locked slot allocation — a preflight-only check races human resume/revocation — cost: the common executor now knows this minimal grant boundary; legacy constructor remains compatible.

- Ruling: CONTINUE rejects MCP CLAIMED/UNKNOWN rows; CANCEL stays available. STDIO SDK argument validation delegates to Java so schema rejections use the common executor/audit rather than a second implementation — cost: uncertain local reads can require cancellation/new analysis, and extra bridge round trips remain.

- Ruling: Upgrade newly introduced MCP SDK from1.23.1 to patched1.30.0 within1.x, and dev pytest to >=9.0.3 — OSV and upstream advisories report fixed issues in those versions; do not keep a knowingly stale new pin — cost: lockfiles and actual STDIO acceptance must be repeated. Existing pinned Java-stack advisories are separately audited against the baseline; no unreviewed whole-stack upgrade.

- Ruling: Retain only patched1.1.6 Spring AI message/provider DTO modules needed by Graph's eager serializer and remove unused transports/clients — avoids five newly added advisory matches without upgrading Graph or the Boot stack — cost: a narrow dependency override now requires explicit compatibility tests on upgrades.

- Ruling: Content-address the native prompt identity in the existing promptVersion field instead of adding raw Prompt storage or another migration — durable two-call policy now detects a same-name resource change after restart — cost: new Agent prompt identity strings carry a hash and must be interpreted as version plus content identity.

- Final: Ruling: Reviewer declined real native supplier/paid bounds — admission stays closed and paid proposal NOT_AUTHORIZED_NOT_RUN; engineering fixture evidence cannot qualify a provider — cost: separate bound review and authorized qualification remain required.

- Final: Ruling: Reviewer declined human quality,production deployment and capacity — none are claimed by this local acceptance; inherited24 dependency matches remain a deployment gate — cost: release readiness still requires independent work.

- Final: Ruling: Reviewer declined final test/new-artifact results — primary will verify after the combined fix pass, serializing Java and process fixtures — cost: final acceptance waits for measured results.

- Final: Ruling: Graph external claims carry ephemeral owner/revision authority into parent-locked model/tool allocation; MCP retains its independent capability/revision grant — stale workers cannot spend after takeover; fence does not enter frozen call policy — cost: internal runner/admission/context overloads must propagate authority on every graph node.

- Final: Ruling: Corrupt checkpoint failure and trace display use only raw ownership/revision metadata, never re-decode or re-hash damaged memory — bounded terminal failure remains visible without trusting payload — cost: corrupted working memory remains non-replayable and requires a new analysis.

- Final: Ruling: Keep the named host-managed worktree and all uncommitted implementation/evidence as-is under the user's no-commit/no-push boundary; do not present an unsolicited integration approval menu or delete the plan ledger — the implementation is reviewable locally — cost: integration and evidence retention remain the user's next explicit choice.

Deferred minors: none reported by the final independent reviewer.

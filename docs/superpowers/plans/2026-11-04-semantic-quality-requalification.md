# Semantic quality requalification implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Continue within the already approved scope; preserve worktree/no commits or pushes.

**Goal:** Close repaired-main/process measurement gaps and independently measure improved real RAG quality.
**Architecture:** Sanitized stage metadata, new unpaid index preparation without holdout replay, capped one-time actual primary-model batch, then development-only retrieval repair and new holdout.
**Tech Stack:** Java17/Maven/JUnit, PowerShell7.6, Python3.12/ONNX E5, MySQL/Redis/Qdrant, approved DeepSeek adapter.
**Spec:** ../specs/2026-11-04-semantic-quality-requalification.md

## Global constraints
- No old label/report/manifest/lock overwrite; default runtime profile and production gates stay unchanged unless actually verified.
- Main max12requests, per-call.10, component1.10+process.10=1.20CNY, no retries/unknown-cost stop; prior.111796 => aggregate<=1.311796<2.
- Stage metadata is enum-only, no raw candidates/prompts/keys; existing public failure/API/accounting behavior compatible.
- RAG .80/.90/.75, development-only selection, new independent holdout once; original holdout remains failed.

## Task1: safe validation diagnostics
- [x] Fresh implementer reads ModelInvocationException/ModelAnalysisOrchestrator/FactConsistencyValidator boundaries; add failing stage/usage/sanitization tests before source.
- [x] Add compatible enum stage metadata for schema/citations/instruction/core facts/core policy/reviewed policy; final-fact stage is reported by evaluation harness. Preserve original ErrorCode and usage.
- [x] Run focused RED/GREEN and fresh read-only task review; no provider calls.

## Task2: isolated preparation and paid batch
- [x] Fresh implementer creates unpaid preparation entrypoint/script from frozen corpus and original preparation/threshold identity. New8doc primary and7doc missing candidate, stored publication readiness; old holdout never loaded/executed. Fresh isolated state only.
- [x] Add distinct requalification wrapper, exclusive lock/report paths and exact component1.10/per-call.10/11call limits; use actual default P4 driver, stage reporting and date-string repair. Original batch not runnable/reused.
- [x] Focused boundary tests and independent task review before full Maven590+current tests and package. Refresh official price, pin source/actual bytecode/classpath/dependencies/JARs; tamper refusal/restore and no-credential ValidateOnly.
- [x] Prepare actual E5/SQL/Qdrant state and one unpaid process control if changed harness needs it; no new main-model requests before final freeze.
- [x] Execute one<=12request/1.20CNY batch, verify11outcomes/SQL/no held charges/lifecycle; run one actual process if safe. Retain failed outcomes/no retry, stop owned services.

## Task3: independent real retrieval repair
- [x] Inspect development top candidates before cutoff with actual embeddings/SQL/Qdrant, separate rank/no-candidate/cutoff errors; no old holdout replay.
- [x] Choose and implement minimal supported retrieval change with failing contract tests, default-compatible opt-in configuration and truthful metadata; use only development data to select settings.
- [x] Fresh independent data author freezes new bilingual holdout without retrieval/model output exposure; verify data before one actual evaluation.
- [x] Freeze algorithm/config/threshold first; evaluate every new holdout case once, keep errors and denominator. Independently review code/metrics.

## Task4: closeout
- [x] Applicable regression/package after final code changes, broad fresh review, credentials/artifact hygiene and owned-service cleanup.
- [x] Publish separate old/new results, actual calls/cost/holds and limits; update TASKS/status only from evidence. Report all ledger Rulings and deferred minors. Preserve worktree/no merge/push/deploy.

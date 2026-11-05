# Human calibration and semantic quality implementation plan

> **For agentic workers:** Use superpowers:executing-plans, preserving the session's authorized inline task order and current worktree. No commits/push authorized.

**Goal:** Complete actual user/Judge sample calibration and produce measured real semantic RAG/main-model quality evidence.
**Architecture:** Existing calibration CLI plus a new independently capped Judge run; explicit local ONNX embedding service behind the existing Java port; actual SQL/Qdrant retrieval and bounded main-model evaluation.
**Tech stack:** Java17/Maven/JUnit, Python3.12/uv/pytest, ONNX Runtime/tokenizers/NumPy, MySQL/Redis/Qdrant, DeepSeek.
**Spec:** ../specs/2026-11-04-human-calibration-real-rag-quality.md

## Task1 — human calibration
- [x] Preserve10 exact user labels/provenance without modifying blank/historical artifacts; inspect sample/manifest mismatch.
- [x] Freeze new Judge input/source/price/caps, run existing Judge parser/budget/no-retry tests; execute one<=10-call/0.10CNY batch with exclusive lock and immutable outcome/usage files.
- [x] Run phase4_judge CLI bound to current sample/user label/fresh Judge hashes; record confusion counts and limits, update only the human-sample task.

## Task2 — explicit semantic embedding
- [x] Pin/download ONNX model/tokenizer/config and freeze hashes; separate Python3.12 project/runtime manifest.
- [x] RED→GREEN Python inference contract/pooling/prefix/length/binding tests and Java loopback adapter/version/vector/timeout tests.
- [x] Add default-compatible embedQuery/embedDocument port methods and use in index build/retrieval/evaluation. Add explicit real-profile configuration and truthful RunManifest embedding identity, preserving legacy fixed policy/artifact shape.
- [x] Execute actual local CPU embeddings and Java/Qdrant retrieval, retain model identity and failures; one fresh read-only review of changed boundaries before paid main run.

## Task3 — frozen retrieval/business quality
- [x] Freeze corpus/development/holdout queries and11 component cases plus1 process smoke, project-policy expected labels, metrics and thresholds before holdout/main output. Keep expected decisions out of prompts.
- [x] Run actual ingestion/build/dev selection/publish/holdout/lifecycle with isolated resources. Record retrieval metrics without model calls.
- [x] Focused integration tests, full applicable suites/package, source/artifact freeze and governance/price preflight before billing.
- [x] Run one<=12-request/1.90CNY main batch, verify SQL usage/unknown holds/validated final outcomes, stop owned resources, preserve failed cases and publish acceptance with explicit human/RAG/process scope.

## Review focus
Pinned model/tokenizer/normalization identity; query/document distinction; refusal of wrong dimensions/version/NaN/oversize; no silent fixed fallback; genuine dev/holdout split and denominator; paid retries/unknown-cost stops; historic and user-authored data remain unchanged.

Execution outcome: measurement complete, quality NOT accepted. Human10/10; RAG holdout recall7/12 and precision7/8 failed targets; main4/11; paid-process startup failed before any model call. Date transport fixed and offline regression passed, no paid retry. Java576/package, Python187, real embedding31 passed; distinct scopes not summed. Public acceptance and raw/reconciled accounting retained; unknown held reconciled0.

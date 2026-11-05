# Native tool budget requalification implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Implement inline in the existing worktree; no commits or push authorized.

**Goal:** Give the planner accurate remaining tool slots and execute one independent bounded paid requalification batch.

**Architecture:** Durable working state renders a quota object in the existing message grammar; static v2 system instructions explain it. The current hard guards remain authoritative. Reuse the governed qualification CLI with two fixed batch profiles and separate immutable artifacts.

**Tech stack:** Java17/Spring Boot3.5, Maven/JUnit, MySQL/Redis, PowerShell, native DeepSeek tools.

## Task1 — quota communication
- [x] Add failing BoundedAgentRunnerTest coverage: two retrievals leave2 slots; one retrieval leaves3; restored PLAN preserves used count; GENERATE and REPAIR offer0 tools and retain the same system identity; overlarge batches still fail before executing any proposed tool.
- [x] Run focused Maven tests and record expected RED.
- [x] Add agent-review-v2 and change initialMessages(context,state,allowTools) to render the four literal quota fields before CORE_FACTS. Use v2 in preparation identity. Preserve historical v1 resource and all hard caps.
- [x] Run focused tests, real native HTTP/Graph/policy-freeze checks and offline frozen26 compatibility tests.

## Task2 — independent paid batch and evidence
- [x] Create separate12-case RQ dataset retaining original scenarios/business values; add pure pack validation tests for correct identity, old/mixed ids, wrong hash and frozen old pack support, with no provider HTTP.
- [x] Add fixed -Requalification profile selecting separate proposal/data/batch/lock; preserve original batch refusal. Record runner request quota/selection metadata without raw model output or credentials.
- [x] One fresh read-only review scoped to this change; fix confirmed Important findings with RED→GREEN. Full Java suite and package must pass before billing.
- [x] Freeze source hashes, original artifacts' hashes, provider/price/caps and new pack identity; execute exactly one new bounded batch. Do not retry failures or unknown usage.
- [x] Verify durable claims/usage/tool receipts and stopped owned resources. Save separate report and clearly dated status/acceptance updates; keep human/deployment gates open.

## Review focus
1. Model payload quota uses actual restored state, not fixed2 or missing retry counts.
2. System prompt identity stays identical across turns despite changing quota/evidence.
3. Second turn prohibits tools even if total remaining>0.
4. Existing batch locks/outcomes cannot be reused or rewritten by the new profile.
5. Negative tests do not invoke HTTP, estimates remain within frozen caps, and any unknown cost stops the batch.

Final: full Java552/552 and package passed; independent RQ batch12/12 passed,13 requests,0.08999400CNY peak/cache-miss estimate,held0. Original NQ batch unchanged. Only synthetic native component protocol qualifies; human/production/deployment remain open.

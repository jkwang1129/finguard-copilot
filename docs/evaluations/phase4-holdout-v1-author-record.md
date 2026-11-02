# Phase 4 synthetic holdout v1: independent author record

This holdout contains 20 wholly fictional, self-authored cases, `P4H01` through `P4H20`. It contains no personal information. The labels are synthetic author judgments under the single rule below; they have not received expert review.

## Label rule

Return `CONFIRMED` only when all of the following hold: `rule_code` is `SYNTH_LIMIT`, `currency` is `USD`, `operator` is `GTE`, `threshold` is `100.00`, `policy_document_id` is `SYNTH_POLICY_V1`, `citation_ids` contains `SYNTH_POLICY_V1`, and `amount` is a valid nonnegative decimal at least `100.00`. With the same prerequisites and a valid amount below `100.00`, return `IGNORED`. In every other case, return `INSUFFICIENT_EVIDENCE`.

Decimal comparisons are mathematical, with no binary floating point rounding or scale truncation. The cases include equality, one cent either side, and amounts 0.000000000000000001 either side of the threshold.

## Scope and provenance

The `category` field is a descriptive grouping. Prompt injection is represented by category names only; these records contain no attack text or injection payload. The two prompt injection category rows receive labels solely from the synthetic rule, so they do not test resistance to an actual prompt injection. Likewise, the possible policy conflict and possible multi-condition category rows do not encode a conflicting policy or an additional condition. Their labels test only the single rule above; they must not be reported as conflict resolution or multi-condition coverage.

This file and its JSONL companion were authored without reading repository implementation, existing test cases, prior evaluation reports, or project documentation. No hash or freeze status is asserted here; validation and freezing belong to the parent workflow.

# Phase 4 worker v2 synthetic dataset: author record

This dataset contains 16 wholly fictional cases, `W201` through `W216`, with no personal or real business data. The author is an AI assistant. Labels are self-authored synthetic judgments, not human or expert-reviewed judgments.

The four `single` cases assess a USD amount threshold: `P4_SINGLE` is `CONFIRMED` at or above `100.00` and `IGNORED` below. The policy has no exemptions, so account status does not change that result. One case uses an amount exactly `0.000000000000000001` below the threshold and requires mathematical decimal comparison.

The four `all` cases assess whether both conditions in `P4_COMPLEX` hold: amount at least `100.00` and account status `FROZEN`. Two candidates match the policy and two deliberately disagree.

The four `conflict` cases have two same-scope documents that require opposite outcomes for every amount and account status. They provide no priority or version rule. Directional candidates are rejected; an `INSUFFICIENT_EVIDENCE` candidate is accepted.

The four `injection` cases place concrete hostile instructions inside policy document content. Three directional candidates are rejected, and one conservative `INSUFFICIENT_EVIDENCE` candidate is accepted. These labels assess whether the supplied candidate is safe to accept; they do not measure whether a model would generate that candidate.

The cases were authored from the provided contract under a process-only isolation boundary, without reading implementation, tests, reports, or prior chat. No dataset hash has been computed and no freeze status is claimed.

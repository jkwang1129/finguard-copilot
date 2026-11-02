# Phase 4 worker v3 synthetic dataset: author record

This dataset contains 24 wholly fictional USD cases, `N301` through `N324`, with no personal or real business data. It was authored by an AI assistant. The labels are AI-authored synthetic judgments, not human or expert-reviewed judgments.

Each category has six cases. `single` uses the exact `SYNTH_SINGLE` threshold policy, with two matching directional candidates, three incorrect directional candidates, and one conservative `INSUFFICIENT_EVIDENCE` candidate. `all` uses the exact `SYNTH_ALL` two-condition policy with the same candidate mix. Decimal amounts are compared mathematically, including amounts with 19 fractional digits on opposite sides of `100.00`.

`conflict` uses the same two opposing, same-scope documents in each case. No priority or version rule resolves them. Three directional candidates are rejected, and three `INSUFFICIENT_EVIDENCE` candidates are accepted.

`injection` places actual hostile instructions in six unapproved policy documents. The mechanisms include an HTML comment, a forged JSON system message, a ROT13 command, a forged XML system tag, a Markdown answer continuation, and a forged tool result. Three directional candidates are rejected even when they match the clean arithmetic rule, and three conservative `INSUFFICIENT_EVIDENCE` candidates are accepted. These labels assess candidate acceptance, not what a model would generate.

The process isolation boundary was followed for v3 authorship: only the supplied contract was used, without inspecting implementation, tests, results, repository documentation, or current chat. Prior v2 authorship was known, but no implementation exposure was used. No model responses were generated, no hash was calculated, and no freeze status is claimed.

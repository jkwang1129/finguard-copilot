# Frozen synthetic semantic retrieval evaluation pack, v1

This pack tests retrieval of eight small, benign knowledge documents written for this project's demo. The policy statements, document text, queries, and relevance labels are AI-authored synthetic material. They are not human or expert labels, regulations, production policy, or evidence of real financial compliance quality.

## Files and source contract

- `corpus.jsonl`: eight documents, each with `id`, `title`, `version`, and `content`. `id` is the stable source key and every `version` is `v1`. Each content is short enough to be intended as one chunk; actual chunk creation and publication must be checked by the runtime evaluation.
- `development.jsonl`: 12 development queries, including eight answerable and four unsupported cases; six queries are Chinese and six are English.
- `holdout.jsonl`: 16 independently authored holdout queries, including 12 answerable and four unsupported cases; eight queries are Chinese and eight are English. Holdout wording is distinct from development wording, with paraphrases, cross-language retrieval, and a nearby EUR policy decoy.
- `manifest.json`: freeze record, exact byte hashes, counts, provenance, and limits.
- `validate.py`: offline structural, canonical-encoding, relevance-set, and hash validation. It performs no retrieval, model inference, or network access.

Every query has `case_id`, `query`, `expected_document_ids`, and `forbidden_document_ids`. An answerable case expects one relevant document. Every other corpus document is explicitly forbidden for that case. An unsupported case has no expected document and forbids all eight documents. These are semantic relevance judgements authored with the synthetic corpus, not claims that real-world questions have no answer. Gold document ids do not depend on runtime chunk ids. The labels assess source relevance; they do not directly label generated answer correctness, cite accuracy, or runtime business decisions.

## Project policies

`synth-usd-limit` defines the synthetic USD rule. Its required citation metadata is `documentId=synth-usd-limit`, `version=v1`, and `sectionPath=Document`, with an exact policy excerpt. `euro-review` is a distinct EUR policy and is deliberately unsuitable as evidence for that USD rule. The USD policy's amounts and operators are business-rule content, not retrieval score cutoffs. No retrieval threshold is specified or selected in this pack.

The other documents cover manual audit, account status, operational retention, settlement reconciliation, support, and incident infrastructure. Their source keys must remain unchanged when documents are published. No URLs, secrets, prompt instructions, or hostile text are included.

## Freeze and evaluation discipline

All text and gold labels were authored before this data author inspected any embedding outputs, retrieval scores, generated answers, or paid-model/historical-human labels. The data author made no model calls and did not inspect the embedding implementation. The freeze hashes identify the original version and must be verified before use. Files use UTF-8 without BOM, compact JSON with lexically sorted keys, and one LF-terminated JSON object per line. The manifest itself and README use UTF-8 without BOM and LF endings.

Select any retrieval score cutoff using development results only; freeze that selection before running holdout. Keep the original holdout unchanged and evaluate it once for the predeclared result. Report all cases and any unsupported-case false positives. If a repair requires looking at holdout results, preserve those results, state the exposure, and author a genuinely new holdout for a new claim.

Run validation from the repository root:

```text
python tools/agent-evaluation/data/semantic-quality-v1/validate.py
```

The original data freeze is documented under `.superpowers/sdd/2026-11-04-human-calibration-real-rag-quality/data-freeze.md`. This pack does not contain runtime results or certify chunk tokenization. Short text length is a conservative authoring constraint; actual tokenizer limits and one-chunk publication must be verified by the consuming runtime.

# Semantic quality v2 blind holdout

This dataset contains 32 new queries authored independently from the eight unchanged v1 synthetic business-policy documents: 24 supported queries and 8 deliberately unsupported queries. Each document supports exactly 3 positive queries. Positive-query languages are 12 Chinese and 12 English; unsupported-query languages are 4 Chinese and 4 English. Total language counts are 16 Chinese and 16 English. These are **AI-authored relevance labels**, not human-reviewed or expert-reviewed labels and not production gold.

## Independent authorship boundary

The author read only `../semantic-quality-v1/corpus.jsonl` as existing business source material. The author did not open old development or holdout queries, evaluation scores or reports, embedding or ranking outputs, main-agent outputs, retrieval/model implementations, current specifications, retrieval algorithms, or evaluation/tuning thresholds. No embedding or model service calls, HTTP requests, network calls, Maven runs, or other agents were used to construct or validate these files. Queries were written from the policy facts without machine-translated pairs to fill language quotas. The parent receives only paths, frozen hashes, aggregate counts, validation status, and this boundary before algorithm freezing; query text and relevance mappings are not returned to the parent for tuning.

The policies are project-only synthetic demo policies. This artifact does not guarantee medical, regulatory, or financial professional accuracy, and does not turn demo policy into legal advice, regulation, or Core-native truth. It makes no claim of production relevance or human adjudication. Independent creation cannot prove absence of accidental conceptual overlap with unseen older queries.

## Labels and scope

Every row contains `case_id`, `query`, `expected_document_ids`, `forbidden_document_ids`, and `language`. Supported cases name one supporting document and forbid the other seven. Unsupported cases name no supporting document and forbid all eight. Unsupported requests ask for facts or procedures not provided by the corpus; they do not relabel an explicitly answered policy boundary as unsupported. Queries include distinct policy facts, preconditions, exact monetary boundaries, USD/EUR confusion, and missing-rule consultations. No query contains a corpus document identifier or an instruction to override the answering system. Business rule codes may appear as part of a natural scenario.

No source document, document version, or old dataset is copied, modified, added to, or removed from the corpus. The source remains the eight v1 documents with the SHA-256 frozen in `manifest.json`. The validator reads the source solely to verify its frozen identity and document coverage.

## Freeze and local validation

All authored files are UTF-8 without BOM and use LF line endings with a final LF. JSONL rows use compact JSON with sorted keys. `manifest.json` freezes the exact byte hashes and byte counts of `holdout.jsonl`, `README.md`, and `validate.py`, plus the source-corpus hash, document versions, label provenance, and all aggregate counts. A manifest cannot contain its own whole-file hash without a circular dependency; its whole-file SHA-256 is reported separately in the author handoff and is part of the external freeze receipt.

Run locally with Python 3 and the standard library:

```
python tools/agent-evaluation/data/semantic-quality-v2-holdout/validate.py
```

Validation checks byte encoding and line endings, canonical sorted-key JSONL and manifest serialization, exact fields and types, unique queries and sequential IDs, language classification and quotas, positive/negative classification, document partitions and coverage, immutable source identity and versions, and every manifest-listed file hash. It also checks that no query includes a corpus document ID. The validator has no model, embedding, ranking, evaluation, HTTP, or network calls. Passing validates structure and freezing; it does not independently prove semantic relevance or independence of authorship.

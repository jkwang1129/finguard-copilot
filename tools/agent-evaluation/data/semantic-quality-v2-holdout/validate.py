#!/usr/bin/env python3
"""Offline structural and freeze validation; Python standard library only."""
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

SOURCE_SHA256 = "9fdd43cb211ec5a230f4deaaaf6e0c1d1a7bbc6b8b47e61b82e59e21698fe982"
BASE = Path(__file__).resolve().parent
SOURCE = BASE.parent / "semantic-quality-v1" / "corpus.jsonl"
FIELDS = {"case_id", "query", "expected_document_ids", "forbidden_document_ids", "language"}
EXPECTED_COUNTS = {
    "total": 32, "positive": 24, "negative": 8,
    "languages": {"en": 16, "zh": 16},
    "positive_languages": {"en": 12, "zh": 12},
    "negative_languages": {"en": 4, "zh": 4},
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def compact(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def frozen_text(path):
    data = path.read_bytes()
    require(not data.startswith(b"\xef\xbb\xbf"), f"BOM in {path.name}")
    require(b"\r" not in data and data.endswith(b"\n"), f"Non-LF or missing final LF: {path.name}")
    return data, data.decode("utf-8", errors="strict")


def main():
    manifest_bytes, manifest_text = frozen_text(BASE / "manifest.json")
    manifest = json.loads(manifest_text)
    require(manifest_bytes == (compact(manifest) + "\n").encode("utf-8"), "Manifest serialization is not canonical")
    require(manifest["schema_version"] == 1 and manifest["dataset_id"] == "semantic-quality-v2-holdout", "Wrong manifest identity")
    require(manifest["label_provenance"] == "AI-authored relevance labels", "Wrong label provenance")
    require(manifest["source"]["relative_path"] == "../semantic-quality-v1/corpus.jsonl", "Wrong source path")
    require(manifest["source"]["sha256"] == SOURCE_SHA256, "Source hash is not the frozen source")
    source_bytes = SOURCE.read_bytes()
    require(sha256(source_bytes) == SOURCE_SHA256, "Source corpus changed")
    require(manifest["source"]["bytes"] == len(source_bytes), "Source byte count differs")
    docs = [json.loads(line) for line in source_bytes.decode("utf-8").splitlines()]
    ids = {doc["id"] for doc in docs}
    require(len(docs) == len(ids) == 8, "Expected eight unique source documents")
    require(all(doc["version"] == "v1" for doc in docs), "Expected unchanged v1 versions")
    require(manifest["source"]["document_versions"] == {doc["id"]: doc["version"] for doc in docs}, "Document versions differ")
    require(set(manifest["files"]) == {"holdout.jsonl", "README.md", "validate.py"}, "Unexpected frozen file inventory")
    for name, expected in manifest["files"].items():
        data, _ = frozen_text(BASE / name)
        require(set(expected) == {"bytes", "sha256"}, f"Invalid freeze entry: {name}")
        require(len(data) == expected["bytes"] and sha256(data) == expected["sha256"], f"Frozen file changed: {name}")
    _, holdout_text = frozen_text(BASE / "holdout.jsonl")
    lines = holdout_text.splitlines()
    require(len(lines) == 32 and all(lines), "Expected 32 nonblank rows")
    counts = {"total": len(lines), "positive": 0, "negative": 0}
    languages, positive_languages, negative_languages = Counter(), Counter(), Counter()
    document_counts = Counter({doc_id: 0 for doc_id in ids})
    document_languages = {doc_id: Counter({"en": 0, "zh": 0}) for doc_id in ids}
    seen_queries = set()
    for i, line in enumerate(lines, 1):
        case = json.loads(line)
        require(line == compact(case), f"Row {i} is not compact sorted-key JSON")
        require(set(case) == FIELDS, f"Wrong fields in row {i}")
        require(case["case_id"] == f"SQ2H-{i:03d}", f"Wrong or duplicate case ID in row {i}")
        query, lang = case["query"], case["language"]
        require(isinstance(query, str) and query == query.strip() and query, f"Invalid query in row {i}")
        require("\n" not in query and "\r" not in query, f"Multiline query in row {i}")
        normalized = " ".join(query.casefold().split())
        require(normalized not in seen_queries, f"Duplicate query in row {i}")
        seen_queries.add(normalized)
        require(lang in {"en", "zh"}, f"Wrong language in row {i}")
        has_han = bool(re.search(r"[\u3400-\u9fff]", query))
        require(has_han == (lang == "zh"), f"Language classification differs in row {i}")
        require(all(doc_id.casefold() not in query.casefold() for doc_id in ids), f"Document ID leaked in row {i}")
        expected, forbidden = case["expected_document_ids"], case["forbidden_document_ids"]
        require(isinstance(expected, list) and isinstance(forbidden, list), f"Labels must be lists in row {i}")
        require(all(isinstance(x, str) for x in expected + forbidden), f"Non-string document label in row {i}")
        require(len(expected) in {0, 1} and len(set(expected)) == len(expected), f"Invalid positive label cardinality in row {i}")
        require(set(expected).issubset(ids), f"Unknown positive label in row {i}")
        require(forbidden == sorted(ids - set(expected)), f"Incorrect document partition in row {i}")
        languages[lang] += 1
        if expected:
            counts["positive"] += 1
            positive_languages[lang] += 1
            document_counts[expected[0]] += 1
            document_languages[expected[0]][lang] += 1
        else:
            counts["negative"] += 1
            negative_languages[lang] += 1
    counts.update(languages=dict(languages), positive_languages=dict(positive_languages), negative_languages=dict(negative_languages))
    require(counts == EXPECTED_COUNTS, "Case or language counts differ from required quotas")
    require(all(value == 3 for value in document_counts.values()), "Every document must support exactly three cases")
    require(all(set(value) == {"en", "zh"} and all(n > 0 for n in value.values()) for value in document_languages.values()), "Every document needs both query languages")
    counts["positive_by_document"] = dict(document_counts)
    counts["positive_by_document_language"] = {key: dict(value) for key, value in document_languages.items()}
    require(manifest["counts"] == counts, "Manifest counts differ from actual data")
    require(manifest["authorship_boundary"]["existing_business_sources_read"] == ["../semantic-quality-v1/corpus.jsonl"], "Wrong source boundary")
    require(all(manifest["authorship_boundary"][key] is False for key in ["old_queries_seen", "scores_or_evaluation_reports_seen", "embedding_or_ranking_outputs_seen", "main_agent_outputs_seen", "implementation_spec_algorithm_or_threshold_seen", "model_embedding_http_network_calls_used"]), "Blind authorship boundary differs")
    print(compact({"result": "PASS", "total": counts["total"], "positive": counts["positive"], "negative": counts["negative"], "languages": counts["languages"], "holdout_sha256": manifest["files"]["holdout.jsonl"]["sha256"], "manifest_sha256": sha256(manifest_bytes), "source_sha256": SOURCE_SHA256}))


if __name__ == "__main__":
    main()

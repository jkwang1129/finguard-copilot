"""Validate the frozen synthetic data pack offline; no third-party dependencies."""

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
DOC_IDS = {
    "synth-usd-limit", "manual-audit", "account-status", "data-retention",
    "euro-review", "settlement-reconciliation", "support-faq", "incident-infrastructure",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_jsonl(name):
    raw = (ROOT / name).read_bytes()
    require(not raw.startswith(b"\xef\xbb\xbf"), f"{name}: BOM")
    require(b"\r" not in raw and raw.endswith(b"\n"), f"{name}: LF contract")
    result = []
    for number, line in enumerate(raw.decode("utf-8").splitlines(), 1):
        obj = json.loads(line)
        canonical = json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        require(line == canonical, f"{name}:{number}: noncanonical JSON")
        result.append(obj)
    return result


def validate():
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    for name, meta in manifest["files"].items():
        raw = (ROOT / name).read_bytes()
        require(hashlib.sha256(raw).hexdigest() == meta["sha256"], f"{name}: hash mismatch")
        require(len(raw) == meta["bytes"], f"{name}: byte count mismatch")
    corpus = read_jsonl("corpus.jsonl")
    require(len(corpus) == 8, "corpus count")
    require({d["id"] for d in corpus} == DOC_IDS, "corpus source keys")
    for doc in corpus:
        require(set(doc) == {"id", "title", "version", "content"}, "document fields")
        require(doc["version"] == "v1", "document version")
        require(all(isinstance(v, str) and v.strip() for v in doc.values()), "document strings")
        require(len(doc["content"]) < 900, "document authoring character limit")
        require("synthetic" in doc["content"].lower(), "document scope declaration")
    all_queries = set()
    all_case_ids = set()
    summaries = []
    for name, prefix, count, positive, chinese in (
        ("development.jsonl", "DEV", 12, 8, 6),
        ("holdout.jsonl", "HOLD", 16, 12, 8),
    ):
        rows = read_jsonl(name)
        require(len(rows) == count, f"{name}: count")
        found_positive = found_chinese = 0
        for index, row in enumerate(rows, 1):
            require(set(row) == {"case_id", "query", "expected_document_ids", "forbidden_document_ids"}, "query fields")
            require(row["case_id"] == f"{prefix}-{index:03d}", "case id sequence")
            require(row["case_id"] not in all_case_ids, "duplicate case id")
            all_case_ids.add(row["case_id"])
            query = row["query"]
            require(isinstance(query, str) and query.strip() == query and query, "query string")
            require(query.casefold() not in all_queries, "duplicate query")
            all_queries.add(query.casefold())
            found_chinese += any("\u4e00" <= c <= "\u9fff" for c in query)
            expected, forbidden = row["expected_document_ids"], row["forbidden_document_ids"]
            require(isinstance(expected, list) and isinstance(forbidden, list), "label lists")
            require(len(expected) <= 1 and len(expected) == len(set(expected)), "expected cardinality")
            require(len(forbidden) == len(set(forbidden)), "duplicate forbidden id")
            require(set(expected) <= DOC_IDS and set(forbidden) == DOC_IDS - set(expected), "relevance partition")
            found_positive += bool(expected)
        require(found_positive == positive, f"{name}: positive count")
        require(found_chinese == chinese, f"{name}: language balance")
        require({r["expected_document_ids"][0] for r in rows if r["expected_document_ids"]} == DOC_IDS, f"{name}: source coverage")
        summaries.append(f"{name}: {count} cases, {positive} positive, {count-positive} unsupported, {chinese} Chinese, {count-chinese} English")
    print("PASS: frozen byte hashes, UTF-8/LF canonical JSONL, fields, source keys, label partitions, distinct queries, coverage, and counts")
    print(f"corpus.jsonl: {len(corpus)} documents, maximum content length {max(len(d['content']) for d in corpus)} characters")
    print("\n".join(summaries))
    print("No retrieval, inference, network access, or tokenizer claim was performed.")


if __name__ == "__main__":
    validate()

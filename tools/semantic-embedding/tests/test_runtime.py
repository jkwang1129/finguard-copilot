import hashlib
import json
from types import SimpleNamespace

import numpy as np
import pytest

from finguard_semantic_embedding.runtime import (
    DIMENSIONS, MAX_TEXT_CHARS, EmbeddingError, Engine, canonical_bytes,
    masked_mean_normalize, validate_request, verify_manifest,
)


def test_masked_mean_ignores_padding_and_normalizes():
    hidden = np.zeros((1, 3, 384), dtype=np.float32)
    hidden[0, 0, :2] = [3, 0]
    hidden[0, 1, :2] = [0, 4]
    hidden[0, 2, :] = 1000
    result = masked_mean_normalize(hidden, np.array([[1, 1, 0]]))
    assert result.shape == (384,)
    np.testing.assert_allclose(result[:2], [.6, .8], atol=1e-7)
    assert np.linalg.norm(result) == pytest.approx(1)


@pytest.mark.parametrize("hidden,mask", [
    (np.ones((1, 2, 383)), np.ones((1, 2))),
    (np.ones((1, 2, 384)), np.zeros((1, 2))),
    (np.zeros((1, 2, 384)), np.ones((1, 2))),
    (np.full((1, 2, 384), np.nan), np.ones((1, 2))),
    (np.ones((1, 2, 384)), np.ones((1, 3))),
])
def test_bad_output_fails_closed(hidden, mask):
    with pytest.raises(EmbeddingError):
        masked_mean_normalize(hidden, mask)


def request(**kwargs):
    return {"embeddingVersion": "test-v1", "kind": "QUERY", "text": "退款审核", **kwargs}


@pytest.mark.parametrize("body", [[], request(extra=True), request(embeddingVersion="other"),
    request(kind="query"), request(text=" "), request(text=4), request(text="x" * (MAX_TEXT_CHARS + 1))])
def test_contract_rejects_invalid_requests(body):
    with pytest.raises(EmbeddingError):
        validate_request(body, "test-v1")


def test_prefixes_are_distinct_even_for_chinese():
    assert validate_request(request(), "test-v1") == "query: 退款审核"
    assert validate_request(request(kind="DOCUMENT"), "test-v1") == "passage: 退款审核"


class RecordingTokenizer:
    def __init__(self, count):
        self.count = count
        self.seen = []

    def encode(self, text):
        self.seen.append(text)
        return SimpleNamespace(ids=list(range(self.count)), attention_mask=[1] * self.count,
                               type_ids=[0] * self.count)


class RecordingSession:
    def __init__(self):
        self.feeds = []

    def get_inputs(self):
        return [SimpleNamespace(name=n) for n in ("input_ids", "attention_mask", "token_type_ids")]

    def run(self, _, feed):
        self.feeds.append(feed)
        return [np.ones((1, feed["input_ids"].shape[1], DIMENSIONS), dtype=np.float32)]


def engine(count):
    instance = Engine.__new__(Engine)
    instance.tokenizer = RecordingTokenizer(count)
    instance.session = RecordingSession()
    instance.version = "test-v1"
    instance.manifest_sha256 = "a" * 64
    import threading
    instance.lock = threading.Lock()
    return instance


def test_real_recipe_feeds_and_contract_fields():
    instance = engine(512)
    result = instance.embed(request(kind="DOCUMENT"))
    assert set(result) == {"embeddingVersion", "dimensions", "values", "artifactManifestSha256"}
    assert result["embeddingVersion"] == "test-v1"
    assert result["artifactManifestSha256"] == "a" * 64
    assert len(result["values"]) == result["dimensions"] == 384
    assert np.linalg.norm(result["values"]) == pytest.approx(1)
    assert instance.tokenizer.seen == ["passage: 退款审核"]
    assert instance.session.feeds[0]["input_ids"].dtype == np.int64


def test_513_tokens_rejected_before_model_call():
    instance = engine(513)
    with pytest.raises(EmbeddingError, match="token_limit"):
        instance.embed(request())
    assert not instance.session.feeds


def test_manifest_hash_and_files_verified_before_loading(tmp_path):
    from finguard_semantic_embedding.runtime import FILES, RECIPE, SOURCE, runtime_versions
    entries = {}
    for name in FILES:
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(name.encode())
        entries[name] = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "size": path.stat().st_size}
    manifest = {"schemaVersion": 1, "source": SOURCE, "files": entries,
                "recipe": RECIPE, "runtime": runtime_versions()}
    raw = canonical_bytes(manifest)
    (tmp_path / "manifest.json").write_bytes(raw)
    digest = hashlib.sha256(raw).hexdigest()
    data, actual = verify_manifest(tmp_path, digest)
    assert data == manifest and actual == digest
    with pytest.raises(EmbeddingError, match="manifest_hash"):
        verify_manifest(tmp_path, "0" * 64)
    (tmp_path / "tokenizer.json").write_text("tampered")
    with pytest.raises(EmbeddingError, match="artifact_hash"):
        verify_manifest(tmp_path, digest)

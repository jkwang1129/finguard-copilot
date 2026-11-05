"""Opt-in real-model smoke, with synthetic controls fixed before any output."""
import os
from pathlib import Path

import numpy as np
import pytest

from finguard_semantic_embedding.runtime import EmbeddingError, Engine


@pytest.fixture(scope="module")
def real_engine():
    directory = os.environ.get("FINGUARD_SEMANTIC_MODEL_DIR")
    digest = os.environ.get("FINGUARD_SEMANTIC_MANIFEST_SHA256")
    if not directory or not digest:
        pytest.skip("set explicit model directory and frozen manifest SHA to run actual CPU inference")
    # Real inference must succeed without the downloader/network helper.
    import urllib.request
    with pytest.MonkeyPatch.context() as patch:
        def forbidden(*args, **kwargs):
            raise AssertionError("inference attempted network access")
        patch.setattr(urllib.request, "urlopen", forbidden)
        yield Engine(Path(directory), digest)


def embed(engine, text, kind="QUERY"):
    return np.asarray(engine.embed({"embeddingVersion": engine.version, "kind": kind, "text": text})["values"])


def test_real_multilingual_semantics_and_distinct_prefixes(real_engine):
    query = embed(real_engine, "大额交易需要主管审批")
    positive = embed(real_engine, "Large transactions require supervisor approval before processing.", "DOCUMENT")
    negative = embed(real_engine, "Lunch includes rice and fresh vegetables.", "DOCUMENT")
    document_prefix = embed(real_engine, "大额交易需要主管审批", "DOCUMENT")
    assert query.shape == (384,)
    assert np.isfinite(query).all() and np.linalg.norm(query) == pytest.approx(1, abs=1e-6)
    assert float(query @ positive) > float(query @ negative)
    assert not np.allclose(query, document_prefix, atol=1e-5)


def test_real_tokenizer_does_not_truncate(real_engine):
    with pytest.raises(EmbeddingError, match="token_limit"):
        embed(real_engine, "银行交易审核" * 400)

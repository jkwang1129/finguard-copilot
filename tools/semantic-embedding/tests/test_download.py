import hashlib
import io
import json

import pytest

from finguard_semantic_embedding import download
from finguard_semantic_embedding.runtime import EmbeddingError, FILES, SOURCE, canonical_bytes


def mock_source(monkeypatch, *, corrupt=False):
    assets = {name: (name + " content").encode() for name in FILES}
    siblings = []
    for name, raw in assets.items():
        item = {"rfilename": name, "size": len(raw),
                "blobId": hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()}
        if name.endswith(".onnx"):
            item["lfs"] = {"sha256": hashlib.sha256(raw).hexdigest(), "size": len(raw), "pointerSize": 123}
        siblings.append(item)
    metadata = {"id": SOURCE["repository"], "sha": SOURCE["revision"],
                "downloads": 123, "siblings": siblings}
    def open_url(url, timeout):
        if "/api/" in url:
            return io.BytesIO(json.dumps(metadata).encode())
        name = url.split(SOURCE["revision"] + "/")[1].split("?")[0]
        return io.BytesIO((b"corrupt" if corrupt else assets[name]))
    monkeypatch.setattr(download.urllib.request, "urlopen", open_url)
    return metadata


def test_download_checks_official_lfs_and_blob_hashes_and_freezes_stable_metadata(tmp_path, monkeypatch):
    metadata = mock_source(monkeypatch)
    result = download.prepare(tmp_path)
    manifest = json.loads((tmp_path / "manifest.json").read_bytes())
    assert result["artifactManifestSha256"] == hashlib.sha256((tmp_path / "manifest.json").read_bytes()).hexdigest()
    assert "downloads" not in json.loads((tmp_path / "source-metadata.json").read_bytes())
    second = tmp_path / "second"
    metadata["downloads"] += 1
    assert download.prepare(second) == result
    with pytest.raises(EmbeddingError, match="manifest_already_exists"):
        download.prepare(tmp_path)


def test_corrupt_download_never_creates_manifest(tmp_path, monkeypatch):
    mock_source(monkeypatch, corrupt=True)
    with pytest.raises(EmbeddingError, match="download_size|download_hash"):
        download.prepare(tmp_path)
    assert not (tmp_path / "manifest.json").exists()

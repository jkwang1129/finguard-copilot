"""Explicit preparation command only; never imported by the inference engine."""
import hashlib
import json
import os
import urllib.request
from pathlib import Path

from .runtime import FILES, RECIPE, SOURCE, EmbeddingError, canonical_bytes, file_sha256, runtime_versions


def prepare(directory: Path):
    directory.mkdir(parents=True, exist_ok=True)
    if (directory / "manifest.json").exists():
        raise EmbeddingError("manifest_already_exists")
    base = "https://huggingface.co"
    repo, revision = SOURCE["repository"], SOURCE["revision"]
    url = f"{base}/api/models/{repo}/revision/{revision}?blobs=true"
    # Uses the process environment/default OS proxy configuration, never changes it.
    with urllib.request.urlopen(url, timeout=60) as response:
        metadata_raw = response.read()
    metadata = json.loads(metadata_raw)
    if metadata["sha"] != revision or metadata["id"] != repo:
        raise EmbeddingError("source_revision")
    siblings = {item["rfilename"]: item for item in metadata["siblings"]}
    # Freeze only immutable revision/asset facts; live download/like counters
    # must not change embedding identity between preparations.
    metadata_raw = canonical_bytes({"id": repo, "sha": revision,
                                    "siblings": [siblings[name] for name in FILES]})
    records = {}
    for name in FILES:
        info = siblings[name]
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + ".partial")
        with urllib.request.urlopen(f"{base}/{repo}/resolve/{revision}/{name}?download=true", timeout=180) as response:
            with temporary.open("wb") as stream:
                while chunk := response.read(1024 * 1024):
                    stream.write(chunk)
        digest = file_sha256(temporary)
        if temporary.stat().st_size != info["size"]:
            raise EmbeddingError("download_size")
        if "lfs" in info:
            verified = digest == info["lfs"]["sha256"]
        else:
            raw = temporary.read_bytes()
            verified = hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest() == info["blobId"]
        if not verified:
            raise EmbeddingError("download_hash")
        os.replace(temporary, path)
        records[name] = {"sha256": digest, "size": info["size"], "officialBlobId": info["blobId"]}
        if "lfs" in info:
            records[name]["officialLfsSha256"] = info["lfs"]["sha256"]
        print(json.dumps({"downloaded": name, "sha256": digest}), flush=True)
    (directory / "source-metadata.json").write_bytes(metadata_raw)
    manifest = {"schemaVersion": 1, "source": SOURCE, "files": records, "runtime": runtime_versions(),
                "recipe": RECIPE, "sourceMetadataSha256": hashlib.sha256(metadata_raw).hexdigest()}
    raw = canonical_bytes(manifest)
    # Exclusive creation prevents silently replacing a frozen identity.
    with (directory / "manifest.json").open("xb") as stream:
        stream.write(raw)
    digest = hashlib.sha256(raw).hexdigest()
    return {"artifactManifestSha256": digest, "embeddingVersion": "e5-small-int8-" + digest[:24]}

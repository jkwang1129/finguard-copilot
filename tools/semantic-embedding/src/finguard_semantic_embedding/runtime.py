"""Local-only inference. Model identity includes source, recipe and runtime."""
from __future__ import annotations

import hashlib
import importlib.metadata
import json
import platform
import re
import threading
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

DIMENSIONS = 384
MAX_TOKENS = 512
MAX_TEXT_CHARS = 8192
MAX_BODY_BYTES = 65536
SOURCE = {"repository": "Xenova/multilingual-e5-small",
          "revision": "761b726dd34fb83930e26aab4e9ac3899aa1fa78",
          "baseModel": "intfloat/multilingual-e5-small"}
FILES = ("onnx/model_quantized.onnx", "tokenizer.json", "config.json",
         "tokenizer_config.json", "special_tokens_map.json", "quant_config.json", "README.md")
RECIPE = {"dimensions": DIMENSIONS, "maxTokens": MAX_TOKENS,
          "maxTextChars": MAX_TEXT_CHARS, "maxBodyBytes": MAX_BODY_BYTES,
          "queryPrefix": "query: ", "documentPrefix": "passage: ",
          "pooling": "attention-mask-mean", "normalization": "L2",
          "truncation": "reject", "specialTokens": True,
          "provider": "CPUExecutionProvider", "intraOpThreads": 1,
          "interOpThreads": 1, "executionMode": "sequential", "parallelRequests": 1}


class EmbeddingError(ValueError):
    """Safe code-only error; never contains submitted text or model internals."""


def canonical_bytes(value) -> bytes:
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"),
                       allow_nan=False) + "\n").encode("utf-8")


def runtime_versions() -> dict:
    return {"python": platform.python_version(), "implementation": platform.python_implementation(),
            "platform": platform.system(), "machine": platform.machine(),
            **{name: importlib.metadata.version(name) for name in ("onnxruntime", "tokenizers", "numpy")}}


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def strict_json(raw: bytes):
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise EmbeddingError("invalid_json")
            result[key] = value
        return result
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(EmbeddingError("invalid_json")))
    except (ValueError, UnicodeError, RecursionError):
        raise EmbeddingError("invalid_json") from None


def verify_manifest(directory: Path, expected_sha256: str):
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise EmbeddingError("manifest_hash")
    try:
        raw = (directory / "manifest.json").read_bytes()
        digest = hashlib.sha256(raw).hexdigest()
        if digest != expected_sha256:
            raise EmbeddingError("manifest_hash")
        manifest = strict_json(raw)
        if (manifest.get("schemaVersion") != 1 or manifest.get("source") != SOURCE
                or manifest.get("recipe") != RECIPE or manifest.get("runtime") != runtime_versions()
                or set(manifest.get("files", {})) != set(FILES)):
            raise EmbeddingError("manifest_recipe")
        if "sourceMetadataSha256" in manifest and file_sha256(directory / "source-metadata.json") != manifest["sourceMetadataSha256"]:
            raise EmbeddingError("artifact_hash")
        for name in FILES:
            record = manifest["files"][name]
            path = directory / name
            if (path.stat().st_size != record["size"] or file_sha256(path) != record["sha256"]):
                raise EmbeddingError("artifact_hash")
        return manifest, digest
    except (OSError, KeyError, TypeError, AttributeError):
        raise EmbeddingError("artifact_invalid") from None


def validate_request(body, version: str) -> str:
    if not isinstance(body, dict) or set(body) != {"embeddingVersion", "kind", "text"}:
        raise EmbeddingError("invalid_request")
    if not isinstance(body["embeddingVersion"], str) or body["embeddingVersion"] != version:
        raise EmbeddingError("embedding_version")
    if not isinstance(body["kind"], str) or body["kind"] not in ("QUERY", "DOCUMENT"):
        raise EmbeddingError("invalid_kind")
    text = body["text"]
    if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT_CHARS:
        raise EmbeddingError("text_limit")
    # Reject unpaired surrogates rather than letting tokenizer/native errors leak.
    try:
        text.encode("utf-8")
    except UnicodeError:
        raise EmbeddingError("invalid_text") from None
    return ("query: " if body["kind"] == "QUERY" else "passage: ") + text


def masked_mean_normalize(hidden: np.ndarray, mask: np.ndarray) -> np.ndarray:
    if (hidden.ndim != 3 or hidden.shape[0] != 1 or hidden.shape[2] != DIMENSIONS
            or mask.shape != hidden.shape[:2] or not np.isfinite(hidden).all()
            or not np.isin(mask, [0, 1]).all() or mask.sum() <= 0):
        raise EmbeddingError("invalid_model_output")
    pooled = (hidden.astype(np.float64) * mask[:, :, None]).sum(axis=1)[0] / mask.sum()
    norm = np.linalg.norm(pooled)
    if not np.isfinite(norm) or norm <= 1e-12:
        raise EmbeddingError("invalid_model_output")
    result = pooled / norm
    if not np.isfinite(result).all():
        raise EmbeddingError("invalid_model_output")
    return result


class Engine:
    def __init__(self, directory: Path, expected_sha256: str):
        _, self.manifest_sha256 = verify_manifest(directory, expected_sha256)
        # Manifest has no version field. Version is derived from its immutable bytes.
        self.version = "e5-small-int8-" + self.manifest_sha256[:24]
        self.lock = threading.Lock()
        try:
            self.tokenizer = Tokenizer.from_file(str(directory / "tokenizer.json"))
            self.tokenizer.no_truncation()
            self.tokenizer.no_padding()
            options = ort.SessionOptions()
            options.intra_op_num_threads = 1
            options.inter_op_num_threads = 1
            options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
            options.log_severity_level = 4
            self.session = ort.InferenceSession(str(directory / "onnx/model_quantized.onnx"),
                                               sess_options=options, providers=["CPUExecutionProvider"])
            if self.session.get_providers() != ["CPUExecutionProvider"]:
                raise EmbeddingError("invalid_provider")
            if set(item.name for item in self.session.get_inputs()) - {"input_ids", "attention_mask", "token_type_ids"}:
                raise EmbeddingError("invalid_model_inputs")
        except EmbeddingError:
            raise
        except Exception:
            raise EmbeddingError("model_load_failed") from None

    def health(self):
        return {"status": "UP", "embeddingVersion": self.version, "dimensions": DIMENSIONS,
                "artifactManifestSha256": self.manifest_sha256}

    def embed(self, body):
        text = validate_request(body, self.version)
        with self.lock:
            try:
                encoded = self.tokenizer.encode(text)
                if len(encoded.ids) > MAX_TOKENS:
                    raise EmbeddingError("token_limit")
                values = {"input_ids": encoded.ids, "attention_mask": encoded.attention_mask,
                          "token_type_ids": encoded.type_ids}
                feeds = {item.name: np.asarray([values[item.name]], dtype=np.int64)
                         for item in self.session.get_inputs()}
                hidden = self.session.run(None, feeds)[0]
                vector = masked_mean_normalize(hidden, feeds["attention_mask"])
            except EmbeddingError:
                raise
            except Exception:
                raise EmbeddingError("inference_failed") from None
        return {"embeddingVersion": self.version, "dimensions": DIMENSIONS, "values": vector.tolist(),
                "artifactManifestSha256": self.manifest_sha256}

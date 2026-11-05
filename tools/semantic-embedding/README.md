# Local semantic embedding (explicit opt-in)

This Python 3.12 project runs the pinned Xenova ONNX conversion of
`intfloat/multilingual-e5-small` on the CPU. It is a project evaluation
component, with no production or human-quality claim. It does not use Torch,
remote Python code, pickle, a provider, or a fixed-vector fallback.

From this directory:

```powershell
uv sync --locked --python 3.12.12
uv run --locked pytest -q tests
uv run --locked finguard-semantic prepare --model-dir ../../target/semantic-model/e5-small-int8
uv run --locked finguard-semantic serve --model-dir ../../target/semantic-model/e5-small-int8 --manifest-sha256 <64-hex-SHA-output-by-prepare> --port 8766
```

`prepare` is the only command that accesses the network. It uses the existing
process/OS proxy configuration, verifies pinned HF revision metadata and every
download against official LFS SHA256 or Git blob SHA1 (for non-LFS assets), then
freezes local SHA256 values, runtime versions and recipe in `manifest.json`.
Existing manifests cannot be overwritten by preparation. Weights remain under
ignored `target/semantic-model/`; no model artifacts are checked into Git.

The frozen Windows/AMD64 instance prepared on2026-11-04 has version
`e5-small-int8-55102a6d5c23b708db337173` and manifest SHA256
`55102a6d5c23b708db337173e13e93954b1d9cf0fb7632cfa3d2ce42b643d10d`.
The full manifest and selected official source metadata are retained in
`.superpowers/sdd/2026-11-04-human-calibration-real-rag-quality/embedding-*.json`
from the repository root. A different runtime/platform produces a different
identity and must be explicitly configured by its caller.

Inference requires the manifest SHA explicitly and verifies artifact hashes
and exact Python/platform/runtime versions before loading. For reproducibility,
the manifest contains stable selected revision metadata rather than changing
HF popularity counters. Version is `e5-small-int8-` plus the first 24 hex
characters of the SHA256 of the exact manifest bytes. The manifest contains no
version/hash-of-itself field. Full manifest SHA is also returned by the API.
`uv.lock` pins direct and transitive distributions and their registry hashes.

The service only accepts IPv4 `127.0.0.1`, uses one HTTP request at a time and
one ONNX intra/inter-op CPU thread. It exposes:

* `GET /health`: `status`, `embeddingVersion`, `dimensions`, `artifactManifestSha256`.
* `POST /embed`: exactly `embeddingVersion` (exact server version), `kind`
  (`QUERY` or `DOCUMENT`), `text` (nonempty string).
* Success: exactly `embeddingVersion`, `dimensions` (384), `values` (384 finite,
  L2-normalized floats), `artifactManifestSha256`.

Unknown/duplicate fields, wrong versions/kinds, malformed JSON, non-JSON content,
body >65536 bytes, text >8192 characters, or >512 tokens **including prefix and
special tokens** are rejected. Inputs are never silently truncated. The
tokenizer's saved padding/truncation are disabled. Both English and Chinese
queries get `query: `; indexed documents get `passage: `. The runtime applies
attention-mask mean pooling and L2 normalization to token-level outputs.
Inference errors fail closed. It does not log body text or raw native errors.
This loopback interface is for a trusted local caller, with no public API,
authentication, TLS, or multi-tenant isolation implied.

The [original model card](https://huggingface.co/intfloat/multilingual-e5-small)
declares MIT and documents 384 dimensions, prefixes, masked mean pooling, L2
normalization and 512-token usage. The actual downloaded quantized artifact is
the [Xenova conversion at revision761b726dd34fb83930e26aab4e9ac3899aa1fa78](https://huggingface.co/Xenova/multilingual-e5-small/tree/761b726dd34fb83930e26aab4e9ac3899aa1fa78),
whose README identifies that base model. This does not claim that the conversion
is authored by intfloat or that INT8 quality equals the original model.

Transport tests use explicit test doubles only to isolate HTTP behavior;
production has one `Engine` that loads the verified ONNX model. Tests never
constitute a real semantic-quality measurement. Actual CPU smoke observations,
manifest and hashes are retained separately under the task evidence directory.

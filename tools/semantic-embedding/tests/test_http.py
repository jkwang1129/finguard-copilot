import http.client
import json
import threading

import pytest

from finguard_semantic_embedding.runtime import EmbeddingError, MAX_BODY_BYTES
from finguard_semantic_embedding.server import create_server


class ContractEngine:
    """Test double for transport isolation; production always uses ONNX Engine."""
    def health(self):
        return {"status": "UP", "embeddingVersion": "test-v1", "dimensions": 384,
                "artifactManifestSha256": "a" * 64}

    def embed(self, body):
        from finguard_semantic_embedding.runtime import validate_request
        validate_request(body, "test-v1")
        if body["text"] == "private secret prompt":
            raise RuntimeError("private secret prompt")
        return {"embeddingVersion": "test-v1", "dimensions": 384,
                "values": [1.0] + [0.0] * 383, "artifactManifestSha256": "a" * 64}


@pytest.fixture
def client():
    server = create_server(ContractEngine(), "127.0.0.1", 0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
    yield connection
    connection.close()
    server.shutdown()
    server.server_close()
    thread.join(timeout=3)


def send(client, method="POST", path="/embed", body=None, raw=None, headers=None):
    data = raw if raw is not None else json.dumps(body or {
        "embeddingVersion": "test-v1", "kind": "QUERY", "text": "退款审核"})
    client.request(method, path, data if method == "POST" else None,
                   headers or {"Content-Type": "application/json"})
    response = client.getresponse()
    return response.status, json.loads(response.read())


def test_only_loopback_is_allowed():
    for host in ("0.0.0.0", "localhost", "::1", "192.168.1.1"):
        with pytest.raises(EmbeddingError, match="loopback_only"):
            create_server(ContractEngine(), host, 0)


def test_health_and_embed_exact_fields(client):
    status, body = send(client, "GET", "/health")
    assert status == 200
    assert set(body) == {"status", "embeddingVersion", "dimensions", "artifactManifestSha256"}
    status, body = send(client)
    assert status == 200 and len(body["values"]) == 384
    assert set(body) == {"embeddingVersion", "dimensions", "values", "artifactManifestSha256"}


@pytest.mark.parametrize("raw", ['{"text":1,"text":2}', '{', 'NaN', '[]',
    '{"embeddingVersion":"test-v1","kind":"QUERY","text":"x","unknown":1}'])
def test_invalid_json_and_unknown_fields_rejected(client, raw):
    assert send(client, raw=raw)[0] == 400


def test_errors_do_not_echo_text(client, capsys):
    status, body = send(client, body={"embeddingVersion": "test-v1", "kind": "QUERY", "text": "private secret prompt"})
    assert status == 503 and body == {"error": "inference_failed"}
    assert "private secret prompt" not in str(capsys.readouterr())


def test_body_bound_and_content_type(client):
    assert send(client, raw="x" * (MAX_BODY_BYTES + 1))[0] == 413
    assert send(client, headers={"Content-Type": "text/plain"})[0] == 415


def test_unknown_routes(client):
    assert send(client, "GET", "/embed")[0] == 404
    assert send(client, "POST", "/health")[0] == 404

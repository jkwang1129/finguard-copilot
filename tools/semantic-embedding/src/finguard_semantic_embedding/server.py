"""Single-request HTTP transport bound to IPv4 loopback only."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

from .runtime import EmbeddingError, MAX_BODY_BYTES, strict_json


def create_server(engine, host: str, port: int):
    if host != "127.0.0.1":
        raise EmbeddingError("loopback_only")

    class Handler(BaseHTTPRequestHandler):
        server_version = "FinGuardSemantic/1"
        sys_version = ""

        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, format, *args):
            pass  # No path, request, or submitted text logging.

        def reply(self, status, body):
            raw = json.dumps(body, separators=(",", ":"), allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(raw)
            self.close_connection = True

        def do_GET(self):
            if self.path != "/health":
                return self.reply(404, {"error": "not_found"})
            return self.reply(200, engine.health())

        def do_POST(self):
            if self.path != "/embed":
                return self.reply(404, {"error": "not_found"})
            if self.headers.get("Transfer-Encoding") or len(self.headers.get_all("Content-Length", [])) != 1:
                return self.reply(400, {"error": "invalid_length"})
            try:
                raw_length = self.headers.get("Content-Length", "")
                if not raw_length.isascii() or not raw_length.isdecimal():
                    raise ValueError
                length = int(raw_length)
                if length < 1:
                    return self.reply(413, {"error": "body_limit"})
                if length > MAX_BODY_BYTES:
                    # Drain at most the boundary probe, never an arbitrary
                    # oversized body. This preserves the413 response for a
                    # one-byte-over client on Windows instead of a TCP reset.
                    try:
                        self.rfile.read(min(length, MAX_BODY_BYTES + 1))
                    except (TimeoutError, OSError):
                        pass
                    return self.reply(413, {"error": "body_limit"})
            except ValueError:
                return self.reply(400, {"error": "invalid_length"})
            try:
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise EmbeddingError("invalid_length")
                if self.headers.get_content_type() != "application/json":
                    return self.reply(415, {"error": "content_type"})
                result = engine.embed(strict_json(raw))
            except EmbeddingError as error:
                code = str(error)
                status = 503 if code in {"inference_failed", "invalid_model_output"} else 400
                return self.reply(status, {"error": code})
            except (TimeoutError, OSError):
                return self.reply(408, {"error": "request_timeout"})
            except Exception:
                return self.reply(503, {"error": "inference_failed"})
            return self.reply(200, result)

    # Deliberately not ThreadingHTTPServer; bounded queue and native CPU threads.
    class Server(HTTPServer):
        request_queue_size = 4

        def handle_error(self, request, client_address):
            pass  # Client disconnects must not emit request/native tracebacks.

    return Server((host, port), Handler)

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import subprocess
import threading

import pytest

PWSH = shutil.which("pwsh")
pytestmark = pytest.mark.skipif(PWSH is None, reason="PowerShell boundary")
HELPER = Path(__file__).resolve().parents[3] / "scripts/e2e/wait-rabbit-ack.ps1"


@pytest.mark.parametrize("acks,expected", [([1, 1], False), ([1, 2], True)])
def test_empty_queue_requires_new_ack_not_stale_statistics(acks, expected):
    class Handler(BaseHTTPRequestHandler):
        calls = 0

        def do_GET(self):
            ack = acks[min(type(self).calls, len(acks) - 1)]
            type(self).calls += 1
            body = json.dumps({"messages_ready": 0, "messages_unacknowledged": 0,
                               "message_stats": {"ack": ack}}).encode()
            self.send_response(200)
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        result = subprocess.run(
            [PWSH, "-NoProfile", "-Command",
             f". '{HELPER}'; Wait-RabbitAck -QueueUrl 'http://127.0.0.1:{server.server_port}/queue' "
             "-Headers @{} -MinimumAck 2 -TimeoutSeconds 2 | ConvertTo-Json -Compress"],
            capture_output=True, text=True, encoding="utf-8", timeout=15)
        assert (result.returncode == 0) == expected, result.stderr
        if expected:
            assert json.loads(result.stdout)["message_stats"]["ack"] == 2
        else:
            assert "ACK evidence" in result.stderr
    finally:
        server.shutdown()
        server.server_close()
        thread.join()

import importlib.util
from http.server import HTTPServer
import json
from pathlib import Path
import threading
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import pytest


def test_fixture_reports_actual_http_calls_including_rejected_requests():
    path = Path(__file__).resolve().parents[3] / "scripts/e2e/local_model_stub.py"
    spec = importlib.util.spec_from_file_location("acceptance_stub", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    server = HTTPServer(("127.0.0.1", 0), module.Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = f"http://127.0.0.1:{server.server_port}"
    try:
        with urlopen(url + "/_calls", timeout=3) as response:
            assert json.load(response)["calls"] == 0
        with pytest.raises(HTTPError):
            urlopen(Request(url, data=b"{}", headers={"Content-Type": "application/json"}), timeout=3)
        with urlopen(url + "/_calls", timeout=3) as response:
            assert json.load(response)["calls"] == 1
    finally:
        server.shutdown()
        server.server_close()
        thread.join()

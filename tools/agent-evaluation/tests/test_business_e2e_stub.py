import importlib.util
from http.server import HTTPServer
import json
from pathlib import Path
import threading
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import pytest

def test_native_fixture_selects_real_tool_envelope_then_a_final_candidate():
    path = Path(__file__).resolve().parents[3] / "scripts/e2e/local_model_stub.py"
    spec = importlib.util.spec_from_file_location("native_stub", path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    request={"tools": [{}], "tool_choice":"auto", "messages": [{"role":"system","content":"safe"},{"role":"user","content":"analysisId=test"}]}
    response=module.response_for(request)
    assert response["choices"][0]["finish_reason"]=="tool_calls"
    call=response["choices"][0]["message"]["tool_calls"][0]
    assert call["function"]=={"name":"get_task_state","arguments":"{}"}
    assert response["choices"][0]["message"]["role"]=="assistant"
    facts={"reviewTask":{"ruleCode":"LARGE_AMOUNT","reasonCode":"AMOUNT_AT_OR_ABOVE_THRESHOLD"},
        "transaction":{"id":1,"accountId":1,"amount":120000.10,"currency":"CNY"}}
    evidence=[{"citation":{"documentId":"p","documentVersion":"1","sectionPath":"p.md","chunkId":"chunk","contentChecksum":"hash"},"excerpt":"review policy"}]
    request['tool_choice']='none'
    request['messages'][1]['content']='analysisId=test\nCORE_FACTS\n'+json.dumps(facts)+'\nPOLICY_EVIDENCE\n'+json.dumps(evidence)
    result=json.loads(module.response_for(request)['choices'][0]['message']['content'])
    assert set(result['policyCitations'][0])=={'documentId','version','sourcePath','excerpt'}
    invalid=module.response_for({**request,'tool_choice':'auto'},scenario='unknown_tool')
    assert invalid['choices'][0]['message']['tool_calls'][0]['function']['name']=='http_get'
    repair=module.response_for({**request,'tool_choice':'auto'},scenario='format_repair')
    assert repair['choices'][0]['message']['content']=='broken JSON'


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

import asyncio
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import sys
import threading
import os

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
import pytest

def test_locked_sdk_contains_the_audited_transport_fixes():
    from importlib.metadata import version
    from packaging.version import Version
    assert Version(version('mcp'))>=Version('1.30.0')


def test_target_and_credentials_cannot_be_supplied_as_tool_arguments():
    from finguard_mcp_bridge.server import BridgeClient
    with pytest.raises(ValueError):
        BridgeClient("http://arbitrary.example", "a" * 43)
    with pytest.raises(ValueError):
        BridgeClient("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "core.jwt.token")


def test_local_http_adapter_never_follows_redirects_or_environment_proxies(monkeypatch):
    from finguard_mcp_bridge.server import BridgeClient, BridgeUnavailable
    foreign=[]
    class Local(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            self.send_response(302);self.send_header('Location','http://127.0.0.1:8082/collect');self.end_headers()
    class Foreign(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            foreign.append(self.headers.get('Authorization'));self.send_response(200);self.end_headers()
    servers=[HTTPServer(('127.0.0.1',8081),Local),HTTPServer(('127.0.0.1',8082),Foreign)]
    threads=[threading.Thread(target=s.serve_forever,daemon=True) for s in servers]
    for thread in threads:thread.start()
    monkeypatch.setenv('HTTP_PROXY','http://127.0.0.1:8082');monkeypatch.setenv('NO_PROXY','')
    try:
        with pytest.raises(BridgeUnavailable):BridgeClient('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','a'*43).request()
        assert foreign==[]
    finally:
        for server in servers:server.shutdown();server.server_close()
        for thread in threads:thread.join()


def test_real_stdio_client_initializes_lists_and_calls_without_core_credentials():
    schema = json.loads((Path(__file__).resolve().parents[3] / "src/main/resources/schema/agent-tools-v1.schema.json").read_text())['tools']
    requests=[]
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self):
            requests.append((self.path,self.headers.get('Authorization'),None))
            self.reply(schema)
        def do_POST(self):
            body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            requests.append((self.path,self.headers.get('Authorization'),body))
            if body['arguments']:
                self.send_response(400); self.end_headers();self.wfile.write(b'PRIVATE-ERROR');return
            raw=b'{"toolName":"get_review_facts","data":{"amount":100.123456789012345678},"source":"CORE_SNAPSHOT","checksum":"'+b'a'*64+b'"}'
            self.send_response(200);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
        def reply(self,value):
            raw=json.dumps(value).encode();self.send_response(200);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
    server=HTTPServer(('127.0.0.1',8081),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    async def exercise():
        params=StdioServerParameters(command=sys.executable,args=['-m','finguard_mcp_bridge','--analysis-id','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'],env={'FINGUARD_MCP_CAPABILITY':'a'*43})
        async with stdio_client(params) as (read,write):
            async with ClientSession(read,write) as session:
                hello=await session.initialize();assert hello.capabilities.tools is not None
                tools=await session.list_tools();assert [t.name for t in tools.tools]==[t['name'] for t in schema]
                assert [t.inputSchema for t in tools.tools]==[t['parameters'] for t in schema]
                result=await session.call_tool('get_review_facts',{})
                assert not result.isError and '100.123456789012345678' in result.content[0].text
                error=await session.call_tool('get_review_facts',{'url':'https://arbitrary.example'})
                assert error.isError and 'PRIVATE-ERROR' not in error.content[0].text
    try:asyncio.run(exercise())
    finally:server.shutdown();server.server_close();thread.join()
    assert all(auth=='Bearer '+'a'*43 for _,auth,_ in requests)
    assert all(path.endswith('/mcp-tools') for path,_,_ in requests)

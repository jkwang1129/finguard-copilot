"""STDIO only. Java owns authorization, schema validation, claims and all tool execution."""
import argparse
import asyncio
import hashlib
import json
import logging
import os
import re
import sys
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from urllib.error import HTTPError, URLError
from uuid import UUID

from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server
from mcp import types
from mcp.shared.exceptions import McpError


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class BridgeUnavailable(Exception):
    def __str__(self):
        return "Local tool capability unavailable"


class BridgeClient:
    def __init__(self, analysis_id: str, capability: str):
        try:
            if str(UUID(analysis_id)) != analysis_id:
                raise ValueError()
        except (ValueError, AttributeError, TypeError):
            raise ValueError("Invalid analysis identifier") from None
        if not isinstance(capability, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", capability):
            raise ValueError("A short-lived MCP capability is required")
        self._url = f"http://127.0.0.1:8081/api/copilot/review-analyses/{analysis_id}/mcp-tools"
        self._capability = capability
        # Never forward a capability through an environment proxy or an HTTP redirect.
        self._http = build_opener(ProxyHandler({}), NoRedirects())

    def request(self, body=None, key=None):
        headers = {"Authorization": "Bearer " + self._capability, "Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
            if len(data) > 4096:
                raise BridgeUnavailable()
            headers["Content-Type"] = "application/json"
            headers["Idempotency-Key"] = key
        try:
            with self._http.open(Request(self._url, data=data, headers=headers), timeout=10) as response:
                raw = response.read(131073)
                if len(raw) > 131072:
                    raise BridgeUnavailable()
                return raw.decode("utf-8")
        except (HTTPError, URLError, TimeoutError, OSError, UnicodeError, ValueError):
            raise BridgeUnavailable() from None

    def list_tools(self):
        try:
            schemas = json.loads(self.request())
            if not isinstance(schemas, list) or not 1 <= len(schemas) <= 3:
                raise ValueError()
            return [types.Tool(name=s["name"], description=s["description"], inputSchema=s["parameters"],
                annotations=types.ToolAnnotations(readOnlyHint=True, destructiveHint=False, openWorldHint=False)) for s in schemas]
        except (KeyError, ValueError, TypeError):
            raise BridgeUnavailable() from None


def create_server(client):
    server = Server("finguard-readonly", version="0.1.0")

    @server.list_tools()
    async def list_tools():
        try:
            return await asyncio.to_thread(client.list_tools)
        except BridgeUnavailable:
            raise McpError(types.ErrorData(code=-32000, message="Local tool capability unavailable")) from None

    # Java validates the same shared schemas and audits rejected executions against the four slots.
    @server.call_tool(validate_input=False)
    async def call_tool(name, arguments):
        try:
            key = hashlib.sha256(str(server.request_context.request_id).encode()).hexdigest()
            raw = await asyncio.to_thread(client.request, {"toolName": name, "arguments": arguments}, key)
            # Preserve exact decimal text; do not parse business facts through Python float.
            return types.CallToolResult(content=[types.TextContent(type="text", text=raw)], isError=False)
        except (BridgeUnavailable, ValueError, TypeError):
            return types.CallToolResult(content=[types.TextContent(type="text", text="TOOL_UNAVAILABLE")], isError=True)

    return server


class Parser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "Usage: finguard-mcp-bridge --analysis-id <uuid>\n")


async def run(client):
    server = create_server(client)
    async with stdio_server() as (read, write):
        await server.run(read, write, server.create_initialization_options())


def main():
    logging.disable(logging.CRITICAL)
    parser = Parser(); parser.add_argument("--analysis-id", required=True)
    args = parser.parse_args()
    try:
        client = BridgeClient(args.analysis_id, os.environ.pop("FINGUARD_MCP_CAPABILITY", ""))
        asyncio.run(run(client))
    except (ValueError, BridgeUnavailable):
        print("Local MCP configuration unavailable", file=sys.stderr)
        raise SystemExit(2) from None


if __name__ == "__main__":
    main()

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from typing import Any


def _request(endpoint: str, path: str, payload: dict[str, Any]) -> Any:
    request = urllib.request.Request(
        endpoint.rstrip("/") + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        try:
            parsed = json.loads(detail)
        except json.JSONDecodeError:
            parsed = {}
        message = str(parsed.get("error") or detail)
        raise RuntimeError(message or f"tau2 task server returned HTTP {exc.code}") from exc
    if not body.get("ok"):
        raise RuntimeError(body.get("error") or "tau2 task server failed")
    return body.get("result")


class Tau2McpServer:
    def __init__(self, endpoint: str) -> None:
        self.endpoint = endpoint
        self._tools: list[dict[str, Any]] | None = None

    def tools(self) -> list[dict[str, Any]]:
        if self._tools is None:
            self._tools = _request(self.endpoint, "/tools", {})
        return [
            {
                "name": str(tool["name"]),
                "description": str(tool.get("description") or ""),
                "inputSchema": tool.get("parameters") or {
                    "type": "object",
                    "properties": {},
                },
            }
            for tool in self._tools
        ]

    def handle(self, message: dict[str, Any]) -> dict[str, Any] | None:
        method = str(message.get("method") or "")
        request_id = message.get("id")
        if method == "initialize":
            params = message.get("params") or {}
            return self._result(
                request_id,
                {
                    "protocolVersion": str(
                        params.get("protocolVersion") or "2025-06-18"
                    ),
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": "tau2", "version": "1.0.0"},
                },
            )
        if method.startswith("notifications/"):
            return None
        if method == "ping":
            return self._result(request_id, {})
        if method == "tools/list":
            return self._result(request_id, {"tools": self.tools()})
        if method == "tools/call":
            return self._call_tool(request_id, message.get("params") or {})
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "error": {"code": -32601, "message": f"unsupported method: {method}"},
        }

    def _call_tool(
        self,
        request_id: Any,
        params: dict[str, Any],
    ) -> dict[str, Any]:
        name = str(params.get("name") or "")
        arguments = params.get("arguments") or {}
        if not name or not isinstance(arguments, dict):
            return {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32602, "message": "invalid tool call parameters"},
            }
        try:
            result = _request(
                self.endpoint,
                "/call",
                {"toolName": name, "arguments": arguments},
            )
        except Exception as exc:  # noqa: BLE001 - MCP reports tool failures in-band
            return self._result(
                request_id,
                {
                    "content": [{"type": "text", "text": str(exc)}],
                    "isError": True,
                },
            )
        return self._result(
            request_id,
            {
                "content": [
                    {
                        "type": "text",
                        "text": json.dumps(
                            result,
                            ensure_ascii=False,
                            default=str,
                        ),
                    }
                ],
                "isError": False,
            },
        )

    @staticmethod
    def _result(request_id: Any, result: dict[str, Any]) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "result": result}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", required=True)
    args = parser.parse_args()
    server = Tau2McpServer(args.endpoint)
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            message = json.loads(line)
            if not isinstance(message, dict):
                raise ValueError("MCP message must be an object")
            response = server.handle(message)
        except Exception as exc:  # noqa: BLE001 - stdio protocol boundary
            response = {
                "jsonrpc": "2.0",
                "id": None,
                "error": {"code": -32700, "message": str(exc)},
            }
        if response is not None:
            print(json.dumps(response, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()

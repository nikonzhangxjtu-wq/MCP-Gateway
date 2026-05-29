#!/usr/bin/env python3
import json
import sys


TOOLS = [
    {
        "name": "echo",
        "description": "Echo text",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}}
        }
    }
]


for line in sys.stdin:
    request = json.loads(line)
    method = request.get("method")
    request_id = request.get("id")
    if method == "notifications/initialized":
        continue
    if method == "initialize":
        result = {
            "protocolVersion": "2025-03-26",
            "serverInfo": {"name": "fake-stdio-mcp", "version": "0.1.0"},
            "capabilities": {"tools": {"listChanged": False}}
        }
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        params = request.get("params", {})
        arguments = params.get("arguments", {})
        result = {"content": [{"type": "text", "text": "echo:" + arguments.get("text", "")}]}
    else:
        print(json.dumps({
            "jsonrpc": "2.0",
            "id": request_id,
            "error": {"code": -32601, "message": "Method not found"}
        }), flush=True)
        continue
    print(json.dumps({"jsonrpc": "2.0", "id": request_id, "result": result}), flush=True)

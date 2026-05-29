#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def post_json(url, payload, bearer=None, timeout=20):
    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError as exc:
        raise RuntimeError(f"gateway request failed: {exc}") from exc


def gateway_initialize(gateway, user):
    return post_json(gateway, {
        "jsonrpc": "2.0",
        "id": "initialize",
        "method": "initialize",
        "params": {}
    }, bearer=user)


def gateway_tools(gateway, user):
    response = post_json(gateway, {
        "jsonrpc": "2.0",
        "id": "tools",
        "method": "tools/list",
        "params": {}
    }, bearer=user)
    if "error" in response:
        raise RuntimeError(f"gateway tools/list failed: {response['error']}")
    return response["result"]["tools"]


def gateway_call(gateway, user, tool, arguments):
    arguments = normalize_gateway_arguments(tool, arguments)
    # The agent only calls gateway catalog tools; the gateway decides when to route downstream.
    response = post_json(gateway, {
        "jsonrpc": "2.0",
        "id": tool,
        "method": "tools/call",
        "params": {
            "name": tool,
            "arguments": arguments
        }
    }, bearer=user)
    if "error" in response:
        raise RuntimeError(f"gateway tool call failed: {response['error']}")
    return response


def normalize_gateway_arguments(tool, arguments):
    if tool != "call_mcp_tool":
        return arguments
    normalized = dict(arguments)
    nested = normalized.get("arguments")
    if isinstance(nested, dict) and "text" not in normalized:
        # Models sometimes put downstream tool parameters under a nested arguments object.
        for candidate in ("text", "content", "message"):
            if candidate in nested:
                normalized["text"] = nested[candidate]
                break
    return normalized


def scripted_actions(task):
    lowered = task.lower()
    if "http://" in lowered or "https://" in lowered or "fetch" in lowered or "抓取" in task:
        url = first_url(task) or "https://example.com"
        return [
            {"tool": "search_mcp_services", "arguments": {"query": "fetch"}},
            {"tool": "list_mcp_tools", "arguments": {"service_id": "fetch"}},
            {"tool": "call_mcp_tool", "arguments": {
                "service_id": "fetch",
                "tool_name": "fetch",
                "url": url,
                "max_length": 1000
            }}
        ]
    if "sandbox" in lowered or "read" in lowered or "读取" in task:
        return [
            {"tool": "search_mcp_services", "arguments": {"query": "filesystem"}},
            {"tool": "list_mcp_tools", "arguments": {"service_id": "filesystem"}},
            {"tool": "call_mcp_tool", "arguments": {
                "service_id": "filesystem",
                "tool_name": "read_text_file",
                "path": "./sandbox/test-note.md"
            }}
        ]
    if "time" in lowered or "timezone" in lowered or "时间" in task or "时区" in task:
        return [
            {"tool": "search_mcp_services", "arguments": {"query": "time"}},
            {"tool": "list_mcp_tools", "arguments": {"service_id": "time"}},
            {"tool": "call_mcp_tool", "arguments": {
                "service_id": "time",
                "tool_name": "get_current_time",
                "timezone": "Asia/Shanghai"
            }}
        ]
    text = "hello"
    if "hello" not in task.lower():
        text = task.strip() or "hello"
    return [
        {"tool": "search_mcp_services", "arguments": {"query": "feishu"}},
        {"tool": "list_mcp_tools", "arguments": {"service_id": "feishu"}},
        {"tool": "call_mcp_tool", "arguments": {
            "service_id": "feishu",
            "tool_name": "send_message",
            "text": text
        }}
    ]


def first_url(text):
    for part in text.split():
        if part.startswith("http://") or part.startswith("https://"):
            return part.strip("。,.，")
    return None


def deepseek_actions(task, catalog_tools):
    api_key = os.environ.get("DEEPSEEK_API_KEY")
    if not api_key:
        raise RuntimeError("DEEPSEEK_API_KEY is required in --deepseek mode")
    base_url = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
    model = os.environ.get("DEEPSEEK_MODEL", "deepseek-v4-pro")
    prompt = (
        "You are an agent connected to an MCP gateway. "
        "Only call gateway catalog tools. Return JSON only, with shape "
        "{\"actions\":[{\"tool\":\"...\",\"arguments\":{...}}]}. "
        "To send a Feishu message, discover services, list Feishu tools, then call_mcp_tool. "
        "For call_mcp_tool, put downstream parameters at the top level, for example "
        "{\"service_id\":\"feishu\",\"tool_name\":\"send_message\",\"text\":\"hello\"}."
    )
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": prompt},
            {"role": "user", "content": json.dumps({
                "task": task,
                "catalog_tools": catalog_tools
            }, ensure_ascii=False)}
        ],
        "temperature": 0
    }
    request = urllib.request.Request(
        f"{base_url}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}"
        },
        method="POST"
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        body = json.loads(response.read().decode("utf-8"))
    content = body["choices"][0]["message"]["content"]
    try:
        parsed = json.loads(content)
    except json.JSONDecodeError as exc:
        snippet = content[:500]
        raise RuntimeError(f"DeepSeek did not return valid JSON. Raw snippet: {snippet}") from exc
    actions = parsed.get("actions")
    if not isinstance(actions, list):
        raise RuntimeError(f"DeepSeek JSON missing actions: {parsed}")
    return actions


def run(args):
    gateway_initialize(args.gateway, args.user)
    catalog_tools = gateway_tools(args.gateway, args.user)
    actions = scripted_actions(args.task) if args.scripted else deepseek_actions(args.task, catalog_tools)
    observations = []
    for action in actions:
        tool = action.get("tool")
        arguments = action.get("arguments", {})
        if not tool or not isinstance(arguments, dict):
            raise RuntimeError(f"invalid action: {action}")
        observations.append({
            "action": action,
            "response": gateway_call(args.gateway, args.user, tool, arguments)
        })
    result = {
        "status": "ok",
        "mode": "scripted" if args.scripted else "deepseek",
        "observations": observations,
        "final_response": observations[-1]["response"] if observations else None
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description="DeepSeek-style agent for the Java MCP Gateway demo")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--scripted", action="store_true", help="run a deterministic local tool plan")
    mode.add_argument("--deepseek", action="store_true", help="ask DeepSeek to choose gateway catalog tool calls")
    parser.add_argument("--gateway", default="http://localhost:8088/mcp")
    parser.add_argument("--task", default="send hello to feishu")
    parser.add_argument("--user", default="alice")
    args = parser.parse_args()
    try:
        run(args)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

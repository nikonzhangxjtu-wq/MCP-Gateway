# Java MCP Gateway

This folder contains a Java 17 Spring Boot MCP Gateway prototype for validating the first two implementation phases:

- MCP service registration and capability indexing.
- Agent-side discovery without exposing all downstream tools at once.
- User permission and credential checks before forwarding.
- JSON-RPC routing from an agent-facing MCP endpoint to a mock downstream Feishu MCP service.
- A Python agent demo that can run deterministically or ask DeepSeek to choose gateway catalog tools.

## Architecture

```text
Agent / Python demo
  -> POST /mcp
  -> Java MCP Gateway
       -> catalog tools only
       -> permission + credential checks
       -> StreamableHttpMcpClient
  -> POST /mock/feishu/mcp
  -> Mock Feishu MCP
```

The gateway behaves as an MCP server to the agent and as an MCP client/router to downstream MCP services.

## Public Endpoints

- Gateway MCP endpoint: `POST http://localhost:8088/mcp`
- Mock Feishu MCP endpoint: `POST http://localhost:8088/mock/feishu/mcp`

Default users:

- `Authorization: Bearer alice`: can discover/use Feishu, call `send_message`, and has a mock credential.
- `Authorization: Bearer bob`: can discover/use Feishu but cannot call `send_message`.

## Run Tests

From this folder:

```bash
mvn test
```

The tests cover catalog tool exposure, Feishu service discovery, downstream tool indexing, permission denial, credential denial, and HTTP forwarding to the mock Feishu MCP endpoint.

## Start Gateway

```bash
mvn spring-boot:run
```

Then list agent-visible catalog tools:

```bash
curl -s http://localhost:8088/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}'
```

Call a downstream Feishu tool through the gateway:

```bash
curl -s http://localhost:8088/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"call_mcp_tool","arguments":{"service_id":"feishu","tool_name":"send_message","text":"hello"}}}'
```

## Agent Validation

Scripted mode does not require network access or a model API key:

```bash
python3 agent/deepseek_agent.py --scripted \
  --gateway http://localhost:8088/mcp \
  --task "发送 hello 到飞书"
```

DeepSeek mode asks the model to choose gateway catalog tool calls. Export the key in your local shell; do not write it into source files or logs.

```bash
export DEEPSEEK_API_KEY='...'
python3 agent/deepseek_agent.py --deepseek \
  --gateway http://localhost:8088/mcp \
  --task "发送 hello 到飞书"
```

Optional environment variables:

- `DEEPSEEK_MODEL`: defaults to `deepseek-v4-pro`
- `DEEPSEEK_BASE_URL`: defaults to `https://api.deepseek.com`

## Real Downstream MCP Validation

The `real-mcp` Spring profile can connect to real downstream MCP services. It includes a remote AMap MCP service over Streamable HTTP and local stdio MCP servers for Fetch, Filesystem, and Time:

```bash
export AMAP_MAPS_API_KEY='...'
mvn spring-boot:run -Dspring-boot.run.profiles=real-mcp
```

Then run scripted agent checks:

```bash
python3 agent/deepseek_agent.py --scripted --gateway http://localhost:8088/mcp --task "fetch https://example.com"
python3 agent/deepseek_agent.py --scripted --gateway http://localhost:8088/mcp --task "read ./sandbox/test-note.md"
python3 agent/deepseek_agent.py --scripted --gateway http://localhost:8088/mcp --task "获取 Asia/Shanghai 当前时间"
```

See [REAL_MCP_IMPLEMENTATION.zh-CN.md](REAL_MCP_IMPLEMENTATION.zh-CN.md) for the detailed Chinese implementation notes, validation evidence, and optional GitHub MCP setup.

## Cursor Validation

Start the gateway on a stable local port:

```bash
export AMAP_MAPS_API_KEY='...'
mvn spring-boot:run \
  -Dspring-boot.run.profiles=real-mcp \
  -Dspring-boot.run.arguments=--server.port=8091
```

Add the gateway to `.cursor/mcp.json` or `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "java-mcp-gateway": {
      "url": "http://127.0.0.1:8091/mcp"
    }
  }
}
```

Cursor should see only the gateway catalog tools first. It can then call `search_mcp_services` to discover `amap`, `list_mcp_tools` to inspect the full AMap tool schemas, and `call_mcp_tool` to route calls such as `maps_weather` through the gateway.

See [CURSOR_GATEWAY_VALIDATION.zh-CN.md](CURSOR_GATEWAY_VALIDATION.zh-CN.md) for the full Chinese validation guide.

## Common Questions

- Why does `tools/list` not show `send_message`?
  The agent sees only gateway catalog tools first. It must call `list_mcp_tools` for a selected service before using `call_mcp_tool`.

- Why is the mock Feishu endpoint in the same Spring Boot process?
  It keeps this phase deterministic while still exercising real HTTP JSON-RPC forwarding through `StreamableHttpMcpClient`.

- Where should production permissions live?
  This prototype keeps a small in-memory `PermissionService`. In production, the gateway should call the company runtime/IAM/AuthZ service and cache decisions carefully.

- Where should real credentials live?
  This prototype uses `CredentialStore` in memory. Production should use encrypted storage or Vault/KMS, never plain process memory as the only source of truth.

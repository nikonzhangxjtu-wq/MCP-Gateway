# Java MCP Gateway

This folder contains a Java 17 Spring Boot MCP Gateway prototype. The current validated scenario is using Cursor as the MCP client, mounting only this gateway, and letting the gateway discover and route calls to downstream MCP services such as AMap.

- MCP service registration and capability indexing.
- Cursor/agent-side discovery without exposing all downstream tools at once.
- User permission and credential checks before forwarding.
- JSON-RPC routing from a client-facing MCP endpoint to downstream MCP services.
- Real remote AMap MCP routing through Streamable HTTP.
- A Python agent demo that can run deterministically or ask DeepSeek to choose gateway catalog tools.

## Architecture

```text
Cursor / Agent / Python demo
  -> POST /mcp
  -> Java MCP Gateway
       -> catalog tools only
       -> permission + credential checks
       -> capability index
       -> downstream MCP client
  -> AMap / Fetch / Filesystem / Time / Mock Feishu MCP
```

The gateway behaves as an MCP server to Cursor or an agent, and as an MCP client/router to downstream MCP services.

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
mvn spring-boot:run -Dspring-boot.run.profiles=real-mcp
```

AMap no longer requires `AMAP_MAPS_API_KEY` at process startup. Submit the user's AMap key through the gateway catalog tool `submit_mcp_credential`, then call `refresh_mcp_service`.

Then run scripted agent checks:

```bash
python3 agent/deepseek_agent.py --scripted --gateway http://localhost:8088/mcp --task "fetch https://example.com"
python3 agent/deepseek_agent.py --scripted --gateway http://localhost:8088/mcp --task "read ./sandbox/test-note.md"
python3 agent/deepseek_agent.py --scripted --gateway http://localhost:8088/mcp --task "获取 Asia/Shanghai 当前时间"
```

See [REAL_MCP_IMPLEMENTATION.zh-CN.md](REAL_MCP_IMPLEMENTATION.zh-CN.md) for the detailed Chinese implementation notes, validation evidence, and optional GitHub MCP setup.

## Cursor Validation

This is the main end-to-end validation path for the current phase.

### Start the Gateway

Start the gateway on a stable local port. Do not write the AMap key into source files or shell startup scripts.

```bash
cd /Users/nikonzhang/shixi/mcp-gateway/Unla/java-mcp-gateway

mvn spring-boot:run \
  -Dspring-boot.run.profiles=real-mcp \
  -Dspring-boot.run.arguments=--server.port=8091
```

Before using Cursor, verify the endpoint is alive:

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"init","method":"initialize","params":{}}'
```

### Configure Cursor

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

Project-level configuration is usually easier while developing:

```bash
cd /Users/nikonzhang/shixi/mcp-gateway/Unla
mkdir -p .cursor
```

Then create `.cursor/mcp.json` with the JSON above.

After changing or restarting the gateway, open Cursor settings, go to the MCP page, and reload/reconnect `java-mcp-gateway`. Cursor keeps MCP connection state, so a gateway restart can leave Cursor showing `Not connected` until the MCP server is reloaded.

### What Cursor Should See

Cursor should see only the gateway catalog tools first:

- `search_mcp_services`
- `describe_mcp_service`
- `list_mcp_tools`
- `get_auth_status`
- `get_credential_requirements`
- `submit_mcp_credential`
- `delete_mcp_credential`
- `refresh_mcp_service`
- `call_mcp_tool`

It should not directly see all AMap tools at the top level. AMap tools are discovered only after Cursor calls:

```text
list_mcp_tools(service_id="amap")
```

This keeps the top-level tool list small even when many downstream MCP services are registered.

### Cursor Usage Examples

Ask Cursor:

```text
通过 MCP Gateway 查看当前有哪些 MCP 服务可用
```

Expected behavior:

```text
search_mcp_services
```

Cursor should report services such as:

- `amap`
- `fetch`
- `filesystem`
- `time`
- `feishu`

Ask Cursor:

```text
请你帮我通过高德地图查询一下西安的天气
```

Expected behavior:

```text
search_mcp_services(query="高德地图")
get_auth_status(service_id="amap")
submit_mcp_credential(service_id="amap", credential_type="api_key", credential_value="<用户高德Key>")
refresh_mcp_service(service_id="amap")
list_mcp_tools(service_id="amap")
call_mcp_tool(service_id="amap", tool_name="maps_weather", city="西安市")
```

If the AMap key has already been submitted and refreshed, Cursor can skip the credential steps and directly list/call AMap tools.

Ask Cursor:

```text
请你通过高德地图，查询一下从西安市雁塔区南洋时代到曲江汉华国际中心的步行路线
```

Expected behavior:

```text
maps_text_search
maps_search_detail
maps_direction_walking
```

This validates that Cursor can use one downstream MCP service through the gateway for a multi-step task.

### Manual Cursor-Equivalent Checks

List top-level gateway tools:

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}'
```

Discover AMap:

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"search","method":"tools/call","params":{"name":"search_mcp_services","arguments":{"query":"高德地图"}}}'
```

Check required AMap credentials:

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"requirements","method":"tools/call","params":{"name":"get_credential_requirements","arguments":{"service_id":"amap"}}}'
```

Submit an AMap API key:

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"submit","method":"tools/call","params":{"name":"submit_mcp_credential","arguments":{"service_id":"amap","credential_type":"api_key","credential_value":"你的高德Key"}}}'
```

Refresh AMap after submitting the key:

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"refresh","method":"tools/call","params":{"name":"refresh_mcp_service","arguments":{"service_id":"amap"}}}'
```

List AMap tools with full schemas:

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"list-amap","method":"tools/call","params":{"name":"list_mcp_tools","arguments":{"service_id":"amap"}}}'
```

Call AMap weather:

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"weather","method":"tools/call","params":{"name":"call_mcp_tool","arguments":{"service_id":"amap","tool_name":"maps_weather","city":"西安市"}}}'
```

See [CURSOR_GATEWAY_VALIDATION.zh-CN.md](CURSOR_GATEWAY_VALIDATION.zh-CN.md) for the full Chinese validation guide.
See [USER_CREDENTIAL_FLOW.zh-CN.md](USER_CREDENTIAL_FLOW.zh-CN.md) for the user credential submission and encrypted local storage design.
See [STAGE_SUMMARY_CURSOR_AMAP.zh-CN.md](STAGE_SUMMARY_CURSOR_AMAP.zh-CN.md) for the current stage summary, including the Cursor AMap weather, route planning, and service discovery validation.

## Common Questions

- Why does `tools/list` not show `send_message`?
  The agent sees only gateway catalog tools first. It must call `list_mcp_tools` for a selected service before using `call_mcp_tool`.

- Why is the mock Feishu endpoint in the same Spring Boot process?
  It keeps this phase deterministic while still exercising real HTTP JSON-RPC forwarding through `StreamableHttpMcpClient`.

- Where should production permissions live?
  This prototype keeps a small in-memory `PermissionService`. In production, the gateway should call the company runtime/IAM/AuthZ service and cache decisions carefully.

- Where should real credentials live?
  This prototype uses local AES-GCM encrypted storage at `~/.mcp-gateway/credentials.enc`, protected by `~/.mcp-gateway/master.key`. Production should replace this with Vault/KMS or the company credential service.

- Cursor says `Not connected`.
  The gateway process was probably restarted or stopped after Cursor connected. Verify `http://127.0.0.1:8091/mcp` is alive, then reload/reconnect `java-mcp-gateway` in Cursor's MCP settings.

- AMap appears but `tool_count` is `0`.
  The user has not submitted an AMap `api_key`, or submitted it but has not called `refresh_mcp_service`. Use `get_credential_requirements`, then `submit_mcp_credential`, then `refresh_mcp_service`.

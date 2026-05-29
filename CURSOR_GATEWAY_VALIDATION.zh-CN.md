# Cursor 挂载 MCP Gateway 验证路线

本文记录当前阶段的目标、配置方式和验收命令：让 Cursor 只挂载 `java-mcp-gateway`，再通过 Gateway 发现和使用高德地图 MCP。

## 目标

当前阶段不再以 `deepseek_agent.py` 为主验收对象，而是以 Cursor 作为真实 MCP Client：

```text
Cursor -> java-mcp-gateway -> 高德官方 MCP
```

Cursor 不直接挂载高德 MCP。Cursor 只看到 Gateway 暴露的 catalog tools：

- `search_mcp_services`
- `describe_mcp_service`
- `list_mcp_tools`
- `get_auth_status`
- `get_credential_requirements`
- `submit_mcp_credential`
- `delete_mcp_credential`
- `refresh_mcp_service`
- `call_mcp_tool`

高德的 15 个真实工具由 `list_mcp_tools(service_id=amap)` 按需展开，避免后续接入大量 MCP 服务时造成上下文膨胀。

## 本阶段修正

### 1. 保留完整工具 schema

`ToolSchema` 已从简化的 `Map<String, String>` 升级为完整 JSON Schema：

```json
{
  "name": "maps_weather",
  "description": "根据城市名称或者标准adcode查询指定城市的天气",
  "inputSchema": {
    "type": "object",
    "properties": {
      "city": {
        "type": "string",
        "description": "城市名称或者adcode"
      }
    },
    "required": ["city"]
  }
}
```

这解决了“模型能看到工具，但信息不完整，只能靠猜”的问题。

### 2. 服务发现返回结构化信息

`search_mcp_services` 现在返回结构化 JSON 文本，而不是 Java record 的 `toString()`：

```json
{
  "services": [
    {
      "id": "amap",
      "name": "AMap MCP",
      "description": "Official AMap remote MCP service for maps, POI, weather, routes, and app deep links",
      "tags": ["amap", "gaode", "map", "location", "weather", "route", "高德", "地图", "天气", "路线"],
      "tool_count": 15,
      "available": true,
      "requires_user_credential": true,
      "recommended_tools": [
        {
          "name": "maps_weather",
          "description": "根据城市名称或者标准adcode查询指定城市的天气",
          "inputSchema": {
            "type": "object",
            "properties": {
              "city": {
                "type": "string",
                "description": "城市名称或者adcode"
              }
            },
            "required": ["city"]
          }
        }
      ]
    }
  ]
}
```

### 3. Cursor 初始化兼容

Gateway 已接受 `notifications/initialized`，避免 Cursor 初始化后发送 notification 时被错误拒绝。

## 启动 Gateway

不要把高德 Key 写入仓库，也不再要求启动前 `export AMAP_MAPS_API_KEY`。Gateway 启动后，用户通过 Cursor 调用 `submit_mcp_credential` 提交自己的高德 Key。

```bash
cd /Users/nikonzhang/shixi/mcp-gateway/Unla/java-mcp-gateway

mvn spring-boot:run \
  -Dspring-boot.run.profiles=real-mcp \
  -Dspring-boot.run.arguments=--server.port=8091
```

用户凭证会保存到本机加密文件：

- `~/.mcp-gateway/credentials.enc`
- `~/.mcp-gateway/master.key`

这是本地原型方案。生产环境应替换为公司统一凭证系统、Vault/KMS 或专门授权页。

## Cursor 配置

在项目级 `.cursor/mcp.json` 或全局 `~/.cursor/mcp.json` 中添加：

```json
{
  "mcpServers": {
    "java-mcp-gateway": {
      "url": "http://127.0.0.1:8091/mcp"
    }
  }
}
```

Cursor 文档说明：`url` 形式的 MCP Server 会作为远程/本地 HTTP MCP Server 使用，适合 Gateway 这类后续要部署为多用户服务的形态。

## 手工验收命令

### 1. Cursor 顶层应只看到 Gateway catalog tools

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}'
```

预期包含：

- `search_mcp_services`
- `list_mcp_tools`
- `call_mcp_tool`

不应该直接包含：

- `maps_weather`
- `maps_geo`
- `maps_text_search`

### 2. 发现高德 MCP

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"search","method":"tools/call","params":{"name":"search_mcp_services","arguments":{"query":"高德地图"}}}'
```

预期包含：

- `"id":"amap"`
- `"requires_user_credential":true`
- `"available":true`

如果还没有提交高德 Key，`tool_count` 可能为 `0`，`indexed=false`，这是预期行为。Gateway 仍能发现 `amap` 服务，但不会用空 Key 去拉取高德工具列表。

### 3. 查看高德凭证要求

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"requirements","method":"tools/call","params":{"name":"get_credential_requirements","arguments":{"service_id":"amap"}}}'
```

预期返回：

- `name: api_key`
- `secret: true`
- `description: 高德开放平台 Web 服务 Key`

### 4. 提交高德 Key

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"submit","method":"tools/call","params":{"name":"submit_mcp_credential","arguments":{"service_id":"amap","credential_type":"api_key","credential_value":"你的高德Key"}}}'
```

预期返回 `stored:true`，并且只显示脱敏后的 `masked_value`。

### 5. 刷新高德工具索引

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"refresh","method":"tools/call","params":{"name":"refresh_mcp_service","arguments":{"service_id":"amap"}}}'
```

预期返回：

- `refreshed:true`
- `indexed:true`
- `tool_count:15`

### 6. 查看高德完整工具 schema

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"list-amap","method":"tools/call","params":{"name":"list_mcp_tools","arguments":{"service_id":"amap"}}}'
```

预期 `maps_weather` 包含完整 `inputSchema.properties.city.description`。

### 7. 通过 Gateway 调用高德天气

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"weather","method":"tools/call","params":{"name":"call_mcp_tool","arguments":{"service_id":"amap","tool_name":"maps_weather","city":"北京"}}}'
```

预期返回北京市天气预报。

## Cursor 中的建议测试话术

在 Cursor 中可以直接问：

```text
通过 MCP Gateway 查看当前有哪些 MCP 服务可用
```

然后继续问：

```text
使用高德地图 MCP 查询北京今天的天气
```

理想调用路径：

```text
search_mcp_services(query="高德地图")
get_auth_status(service_id="amap")
get_credential_requirements(service_id="amap")
submit_mcp_credential(service_id="amap", credential_type="api_key", credential_value="...")
refresh_mcp_service(service_id="amap")
list_mcp_tools(service_id="amap")
call_mcp_tool(service_id="amap", tool_name="maps_weather", city="北京")
```

如果已经提交过高德 Key，并且 `refresh_mcp_service` 成功执行过，Cursor 可以直接从 `search_mcp_services` / `list_mcp_tools` 进入工具调用。

## 当前边界

本阶段目标是让 Cursor 能挂载 Gateway 并发现高德 MCP，不是完成生产级网关。

后续还需要继续补：

- Streamable HTTP event-stream 分片解析。
- MCP session header 管理。
- OAuth、二维码、短信验证码等交互式授权流。
- 服务目录动态刷新。
- 高并发下的连接池、限流、熔断和重试。
- 权限系统对接公司 Agent runtime。

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
      "requires_user_credential": false,
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

不要把高德 Key 写入仓库。启动时从环境变量注入：

```bash
cd /Users/nikonzhang/shixi/mcp-gateway/Unla/java-mcp-gateway

export AMAP_MAPS_API_KEY="你的高德 Key"

mvn spring-boot:run \
  -Dspring-boot.run.profiles=real-mcp \
  -Dspring-boot.run.arguments=--server.port=8091
```

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
- `"tool_count":15`
- `"available":true`
- `"recommended_tools"`

### 3. 查看高德完整工具 schema

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"list-amap","method":"tools/call","params":{"name":"list_mcp_tools","arguments":{"service_id":"amap"}}}'
```

预期 `maps_weather` 包含完整 `inputSchema.properties.city.description`。

### 4. 通过 Gateway 调用高德天气

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
list_mcp_tools(service_id="amap")
call_mcp_tool(service_id="amap", tool_name="maps_weather", city="北京")
```

## 当前边界

本阶段目标是让 Cursor 能挂载 Gateway 并发现高德 MCP，不是完成生产级网关。

后续还需要继续补：

- Streamable HTTP event-stream 分片解析。
- MCP session header 管理。
- OAuth 和用户凭证注入。
- 服务目录动态刷新。
- 高并发下的连接池、限流、熔断和重试。
- 权限系统对接公司 Agent runtime。

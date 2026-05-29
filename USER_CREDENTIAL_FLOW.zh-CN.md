# MCP Gateway 用户凭证获取与管理说明

本文说明当前阶段新增的用户凭证链路：Gateway 不再要求启动前手动 `export AMAP_MAPS_API_KEY`，而是让 Cursor/Agent 通过 MCP 工具向 Gateway 提交某个服务所需的凭证。

## 设计目标

旧链路的问题是凭证属于 Gateway 进程级配置：

```text
启动 Gateway 前 export AMAP_MAPS_API_KEY
```

这种方式不适合多用户、多 Agent、高并发场景，因为不同用户可能有不同的高德、GitHub、飞书或公司内部系统凭证。

当前链路改为用户级凭证：

```text
Cursor -> Gateway catalog tools -> 本地加密 CredentialStore -> 下游 MCP endpoint
```

凭证 key 维度为：

```text
tenantId:userId:serviceId
```

Cursor 本地验证时默认用户可以继续使用 `Authorization: Bearer alice`。

## 新增 Gateway Catalog Tools

| Tool | 作用 |
| --- | --- |
| `get_credential_requirements` | 查询某个 MCP 服务需要哪些凭证字段 |
| `submit_mcp_credential` | 提交当前用户对某个服务的凭证 |
| `delete_mcp_credential` | 删除当前用户对某个服务的凭证 |
| `refresh_mcp_service` | 提交凭证后重新拉取下游 MCP 工具列表 |

`tools/list` 仍然只暴露 Gateway catalog tools，不会直接把高德 `maps_weather` 等下游工具暴露给 Cursor 顶层。

## Cursor 中的用户流程

1. 用户询问当前有哪些 MCP 服务可用。
2. Cursor 调用 `search_mcp_services`，发现 `amap`。
3. Cursor 调用 `get_auth_status(service_id="amap")`，发现 `requires_user_credential=true`、`has_credential=false`、`callable=false`。
4. Cursor 调用 `get_credential_requirements(service_id="amap")`，知道需要 `api_key`。
5. 用户提供高德 Key。
6. Cursor 调用 `submit_mcp_credential(service_id="amap", credential_type="api_key", credential_value="...")`。
7. Cursor 调用 `refresh_mcp_service(service_id="amap")`，Gateway 使用该用户 Key 拉取高德 `tools/list`。
8. Cursor 调用 `list_mcp_tools(service_id="amap")` 和 `call_mcp_tool(...)` 完成真实工具调用。

## 高德服务配置

`application-real-mcp.yml` 中高德服务现在使用凭证模板：

```yaml
- id: amap
  name: AMap MCP
  transport: streamable-http
  url: https://mcp.amap.com/mcp?key={api_key}
  requiresUserCredential: true
  credentialRequirements:
    - name: api_key
      description: 高德开放平台 Web 服务 Key
      secret: true
```

`StreamableHttpMcpClient` 在调用下游前会把 `{api_key}` 替换为当前用户提交的 credential value。后续可以按同一模型扩展：

- `Authorization: Bearer {token}`
- Basic Auth
- 自定义 header
- OAuth access token

## 本地加密存储

当前原型使用 `EncryptedFileCredentialStore`：

| 文件 | 作用 |
| --- | --- |
| `~/.mcp-gateway/credentials.enc` | AES-GCM 加密后的凭证数据 |
| `~/.mcp-gateway/master.key` | 本机 master key |

首次启动时如果 master key 不存在，Gateway 会自动生成。代码会尽量限制文件权限为当前用户可读写。

仓库 `.gitignore` 已忽略：

```text
.mcp-gateway/
credentials.enc
master.key
```

日志和返回值不会打印完整凭证，只返回脱敏尾号，例如 `****c43b`。

## 手工验证命令

启动 Gateway：

```bash
cd /Users/nikonzhang/shixi/mcp-gateway/Unla/java-mcp-gateway
mvn spring-boot:run \
  -Dspring-boot.run.profiles=real-mcp \
  -Dspring-boot.run.arguments=--server.port=8091
```

查看高德凭证要求：

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"requirements","method":"tools/call","params":{"name":"get_credential_requirements","arguments":{"service_id":"amap"}}}'
```

提交高德 Key：

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"submit","method":"tools/call","params":{"name":"submit_mcp_credential","arguments":{"service_id":"amap","credential_type":"api_key","credential_value":"你的高德Key"}}}'
```

刷新高德工具索引：

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"refresh","method":"tools/call","params":{"name":"refresh_mcp_service","arguments":{"service_id":"amap"}}}'
```

调用高德天气：

```bash
curl -s http://127.0.0.1:8091/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer alice' \
  -d '{"jsonrpc":"2.0","id":"weather","method":"tools/call","params":{"name":"call_mcp_tool","arguments":{"service_id":"amap","tool_name":"maps_weather","city":"西安市"}}}'
```

## 当前边界

当前版本只覆盖 API Key 类凭证。OAuth、短信验证码、二维码登录、企业 SSO 等交互式授权流还没有实现。

本地加密文件适合开发验证，不适合作为生产凭证系统。生产版本建议把 `CredentialStore` 抽象对接到公司 Vault/KMS，并把 `submit_mcp_credential` 替换或补充为 Web 授权页、设备码授权流或公司统一授权中心。

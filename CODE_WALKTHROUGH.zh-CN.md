# Java MCP Gateway 代码说明

## 1. 整体架构

当前 Java 原型已经升级为 Spring Boot Java 17 服务。它有两个 HTTP JSON-RPC 入口：

- `/mcp`：Agent 连接的 MCP Gateway 入口。
- `/mock/feishu/mcp`：本地模拟的 Feishu MCP Server，用于验证网关确实做了路由转发。

请求链路是：

```text
Agent
  -> POST /mcp tools/call(call_mcp_tool)
  -> McpJsonRpcHandler
  -> GatewayRuntime 权限/凭证检查
  -> StreamableHttpMcpClient
  -> POST /mock/feishu/mcp tools/call(send_message)
  -> 返回 mock Feishu 响应
```

## 2. 启动与默认配置

`McpGatewayApplication` 是 Spring Boot 启动类。

`GatewayConfiguration` 负责装配默认运行时：

- 创建 `RestTemplate`。
- 创建 `StreamableHttpMcpClient`，endpoint 指向 `/mock/feishu/mcp`。
- 注册默认 MCP 服务 `feishu`。
- 为 `alice` 写入 mock Feishu credential。
- 创建 `McpJsonRpcHandler`。

这里的服务注册和 credential 都是内存实现，符合阶段一/二“不引入数据库、OAuth、Vault/KMS”的假设。

## 3. Agent 如何发现 Gateway Tools

Agent 首先调用：

```json
{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}
```

`McpJsonRpcHandler` 会返回 `GatewayRuntime.catalogTools()`，也就是 5 个网关目录工具：

- `search_mcp_services`
- `describe_mcp_service`
- `list_mcp_tools`
- `get_auth_status`
- `call_mcp_tool`

关键点：这里不会直接返回 Feishu 的 `send_message`、`search_docs`。这样可以避免上百个 MCP 服务和上千个 tools 一次性进入 Agent 上下文，降低 token 压力，也让权限和发现流程更可控。

## 4. Gateway 如何发现 MCP 服务与能力

服务注册入口是 `GatewayRuntime.registerService()`：

1. 从 `DownstreamClientRegistry` 找到该服务对应的 `McpClient`。
2. 写入 `ServiceRegistry`。
3. 调用 `CapabilityIndex.index()`，把该服务的 tools 写入能力索引。

当前 Feishu tools 来自 `MockFeishuMcpController.feishuTools()`，包括：

- `send_message`
- `search_docs`

`StreamableHttpMcpClient.listTools()` 返回 bootstrap tools，避免应用启动阶段就依赖 HTTP 服务已完全可用。真正调用工具时仍然会通过 HTTP POST 转发到 mock MCP endpoint。

## 5. 权限与凭证链路

`McpGatewayController` 根据 `Authorization` header 构造 `UserContext`：

- `Bearer alice`：有 `mcp:feishu:discover`、`mcp:feishu:use`、`mcp:feishu:send_message`、`mcp:feishu:search_docs`，并且有 mock credential。
- `Bearer bob`：只有 discover/use，没有 `send_message` tool 权限。

`GatewayRuntime.callTool()` 在转发前做四步检查：

1. 服务是否已注册。
2. 用户是否有服务 use 权限。
3. 用户是否有具体 tool 权限。
4. 如果服务要求用户 credential，检查 `CredentialStore` 是否存在对应凭证。

如果缺权限，返回 `permission_denied`；如果缺 credential，返回 `auth_required`。只有全部通过后才会进入下游转发。

生产环境建议把这里的 `PermissionService` 替换为公司 runtime/IAM/AuthZ 服务，把 `CredentialStore` 替换为加密数据库或 Vault/KMS。

## 6. 核心路由转发

`StreamableHttpMcpClient.callTool()` 是当前阶段的核心转发实现：

1. 把 gateway catalog call 转成下游 MCP JSON-RPC `tools/call`。
2. 将用户 credential 注入到下游请求的 `Authorization` header。
3. 使用 `RestTemplate` POST 到 `/mock/feishu/mcp`。
4. 从下游 JSON-RPC 响应中提取 `content[0].text` 返回给 gateway。

这条链路验证了 gateway 不只是本地 mock 结果，而是真正经过 HTTP MCP client 转发到了下游 MCP endpoint。

## 7. Python Agent 验收链路

`agent/deepseek_agent.py` 有两个模式：

- `--scripted`：固定执行 `search_mcp_services -> list_mcp_tools -> call_mcp_tool`，用于无网络、无 key 的稳定验收。
- `--deepseek`：把用户任务和 catalog tools 发给 DeepSeek，让模型输出 JSON action，再由脚本执行这些 gateway tool calls。

脚本不会硬编码 API key，也不会打印完整 key。DeepSeek 模式只读取：

- `DEEPSEEK_API_KEY`
- `DEEPSEEK_MODEL`
- `DEEPSEEK_BASE_URL`

如果模型返回非 JSON 或缺少 `actions`，脚本会明确报错并输出原始片段，方便定位 prompt 或模型输出问题。

## 8. 后续演进建议

下一阶段可以优先做：

- 把服务注册、能力索引、用户 credential 从内存迁移到持久化存储。
- 接入真实 Feishu MCP Server，补齐 streamable HTTP 初始化、鉴权刷新和错误映射。
- 将权限判断外置到公司 runtime/IAM/AuthZ。
- 增加审计日志，记录允许、拒绝、auth_required、downstream_error。
- 增加服务健康检查、能力索引刷新、超时、重试和熔断。

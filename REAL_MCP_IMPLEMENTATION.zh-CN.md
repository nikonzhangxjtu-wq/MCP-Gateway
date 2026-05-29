# 真实下游 MCP Client 阶段实现说明

## 1. 本阶段目标

本阶段把 `java-mcp-gateway` 从 mock-only 路由推进到真实下游 MCP 调用链路。现在 gateway 不只是调用内存 mock 或同进程 mock endpoint，还可以作为 MCP client 启动外部 stdio MCP server，并通过 MCP JSON-RPC 执行：

1. `initialize`
2. `notifications/initialized`
3. `tools/list`
4. `tools/call`

已验证通过的 3 个真实 MCP 服务：

- `fetch`：网页内容抓取场景。
- `filesystem`：本地文件/知识库访问场景。
- `time`：时间与时区工具场景。

GitHub MCP 的配置也保留了，但默认关闭。原因是当前环境缺少 `GITHUB_PERSONAL_ACCESS_TOKEN`，并且 Docker 拉取 `ghcr.io/github/github-mcp-server` 时遇到 DNS 问题。等本地 token 和镜像都准备好后，可以再单独打开验证。

## 2. 主要修改内容

### 2.1 新增真实 stdio MCP client

新增目录：

```text
java-mcp-gateway/src/main/java/com/example/mcpgateway/mcp/
```

核心类：

- `McpJsonRpcRequest`：封装下游 MCP JSON-RPC request。
- `McpJsonRpcResponse`：封装下游 MCP JSON-RPC response，并把 downstream error 转成异常。
- `McpProtocolException`：表示下游 MCP 协议、进程、超时、解析等错误。
- `McpToolContentExtractor`：从 MCP `tools/call` result 中提取文本内容。
- `StdioProcessTransport`：负责启动外部进程，并通过 stdin/stdout 收发 JSON-RPC。
- `StdioMcpClient`：实现现有 `McpClient` 接口，负责初始化、列工具、调用工具。

### 2.2 新增 real-mcp profile

新增配置：

```text
java-mcp-gateway/src/main/resources/application-real-mcp.yml
```

该 profile 注册真实下游服务：

```yaml
fetch:
  command: uvx
  args: ["mcp-server-fetch"]

filesystem:
  command: npx
  args: ["-y", "@modelcontextprotocol/server-filesystem", "./sandbox"]

time:
  command: uvx
  args: ["mcp-server-time"]
```

GitHub MCP 也在配置里保留，但 `enabled: false`，避免当前环境缺 token 或镜像时影响三个默认真实服务验收。

### 2.3 服务注册与能力索引容错

修改：

- `CapabilityIndex`
- `GatewayRuntime`
- `ServiceSummary`

现在服务注册时，即使某个下游 MCP server 初始化或 `tools/list` 失败，也不会拖垮整个 gateway。失败服务会被标记为：

```text
available=false
lastError=<下游错误信息>
toolCount=0
```

这样 agent 仍然能发现服务，但调用时会收到明确的 `service_unavailable`。

### 2.4 下游调用异常不再导致 HTTP 500

`GatewayRuntime.callTool()` 现在会捕获下游 MCP 调用异常，并返回：

```text
downstream_error
```

这样 `/mcp` 仍保持 JSON-RPC 响应形态，不会直接变成 Spring Boot 500 HTML/JSON 错误。

### 2.5 Agent scripted 模式支持真实服务

修改：

```text
java-mcp-gateway/agent/deepseek_agent.py
```

现在 scripted 模式可以根据任务文本选择：

- `fetch https://example.com` -> `fetch.fetch`
- `read ./sandbox/test-note.md` -> `filesystem.read_text_file`
- `获取 Asia/Shanghai 当前时间` -> `time.get_current_time`
- 其他默认仍走 mock Feishu `send_message`

## 3. 当前请求链路

以 Fetch 为例：

```text
agent/deepseek_agent.py
  -> POST /mcp tools/call search_mcp_services
  -> POST /mcp tools/call list_mcp_tools(fetch)
  -> POST /mcp tools/call call_mcp_tool(fetch, fetch, {url})
  -> GatewayRuntime 权限检查
  -> StdioMcpClient.callTool()
  -> stdin/stdout JSON-RPC
  -> uvx mcp-server-fetch
  -> 返回网页内容
```

Filesystem 和 Time 的链路完全相同，只是下游进程和 tool 参数不同。

## 4. 关键类职责

### `StdioProcessTransport`

职责是进程级通信：

- 使用 `ProcessBuilder` 启动下游 MCP server。
- 向 stdin 写入一行 JSON-RPC。
- 从 stdout 读取 JSON-RPC 响应。
- 捕获 stderr 作为错误诊断。
- 按 request id 等待匹配响应，跳过空行和 notification。
- 超时或进程退出时抛出 `McpProtocolException`。

### `StdioMcpClient`

职责是 MCP 语义层：

- 懒启动下游进程。
- 首次使用时发送 `initialize`。
- 发送 `notifications/initialized`。
- 调用 `tools/list` 并缓存 `ToolSchema`。
- 调用 `tools/call` 并提取文本结果。

### `McpServiceCatalogProperties`

职责是把 YAML 配置绑定成 Java 对象。当前支持：

- `id`
- `name`
- `description`
- `tags`
- `transport`
- `command`
- `args`
- `env`
- `workingDirectory`
- `timeoutMs`
- `requiresUserCredential`
- `enabled`

### `GatewayRuntime`

职责仍然是 gateway 核心编排：

- 服务注册。
- 能力索引。
- 服务发现。
- 工具列表按需暴露。
- 权限检查。
- credential 检查。
- 下游 MCP client 转发。
- 下游错误映射。

## 5. 验证命令与结果

### 5.1 单元与集成测试

命令：

```bash
mvn test
```

当前结果：

```text
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖内容：

- gateway catalog tools 不直接暴露下游 tools。
- mock Feishu HTTP 转发。
- credential 缺失返回 `auth_required`。
- capability indexing 失败不拖垮服务发现。
- downstream call 失败返回 `downstream_error`。
- fake stdio MCP server 的 initialize / tools/list / tools/call。
- MCP content 提取。

### 5.2 启动真实 MCP profile

因为本机 `8088` 已有进程占用，验证时使用了 `8090`：

```bash
mvn spring-boot:run \
  -Dspring-boot.run.profiles=real-mcp \
  -Dspring-boot.run.arguments=--server.port=8090
```

服务发现结果显示：

- `fetch`：`available=true`, `toolCount=1`
- `filesystem`：`available=true`, `toolCount=14`
- `time`：`available=true`, `toolCount=2`

### 5.3 Fetch MCP 验收

命令：

```bash
python3 agent/deepseek_agent.py --scripted \
  --gateway http://localhost:8090/mcp \
  --task "fetch https://example.com"
```

关键结果：

```text
Contents of https://example.com/:
This domain is for use in documentation examples without needing permission.
```

结论：真实 `uvx mcp-server-fetch` 调用通过。

### 5.4 Filesystem MCP 验收

命令：

```bash
python3 agent/deepseek_agent.py --scripted \
  --gateway http://localhost:8090/mcp \
  --task "read ./sandbox/test-note.md"
```

关键结果：

```text
# Gateway Sandbox Note

This file proves the gateway can call a real filesystem MCP server through stdio.
```

结论：真实 `npx @modelcontextprotocol/server-filesystem` 调用通过，并且访问目录限制在 `./sandbox`。

### 5.5 Time MCP 验收

命令：

```bash
python3 agent/deepseek_agent.py --scripted \
  --gateway http://localhost:8090/mcp \
  --task "获取 Asia/Shanghai 当前时间"
```

关键结果：

```json
{
  "timezone": "Asia/Shanghai",
  "datetime": "2026-05-29T10:51:34+08:00",
  "day_of_week": "Friday",
  "is_dst": false
}
```

结论：真实 `uvx mcp-server-time` 调用通过。

## 6. GitHub MCP 当前状态

GitHub MCP 已按官方 Docker 方式预留配置：

```yaml
command: docker
args:
  - run
  - -i
  - --rm
  - -e
  - GITHUB_PERSONAL_ACCESS_TOKEN
  - -e
  - GITHUB_READ_ONLY=1
  - ghcr.io/github/github-mcp-server
enabled: false
```

当前未纳入默认 real-mcp 验收，原因：

1. 当前 shell 中没有 `GITHUB_PERSONAL_ACCESS_TOKEN` 或 `GITHUB_TOKEN`。
2. Docker 拉取 `ghcr.io/github/github-mcp-server` 时 DNS 失败。
3. 本阶段要求“三个真实 MCP 跑通”，所以默认用 `fetch/filesystem/time` 完成闭环。

后续启用 GitHub 的步骤：

```bash
export GITHUB_PERSONAL_ACCESS_TOKEN='your_read_only_token'
docker pull ghcr.io/github/github-mcp-server
```

然后把 `application-real-mcp.yml` 中 GitHub 的 `enabled` 改为 `true`，再启动：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=real-mcp
```

建议 GitHub 仍然先只做 read-only 验收。

## 7. 后续改进建议

1. 将 `ToolSchema.inputSchema` 从 `Map<String, String>` 升级为完整 JSON Schema，避免丢失 array/object/enum 等细节。
2. 将下游 MCP 进程改为懒加载注册，避免启动 gateway 时等待所有下游初始化。
3. 为 stdio client 增加健康检查、重启和空闲回收。
4. 将 GitHub 等需要账号的 MCP 服务接入正式 credential/runtime 权限系统。
5. 为每次 `call_mcp_tool` 增加 audit log，记录用户、服务、工具、耗时、成功/失败原因。

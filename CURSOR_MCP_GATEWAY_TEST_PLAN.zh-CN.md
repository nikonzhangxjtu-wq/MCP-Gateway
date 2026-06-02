# Cursor 挂载 MCP Gateway 测试计划

## 1. 测试目标

本轮测试目标是观察 `java-mcp-gateway` 挂载到 Cursor 后的真实表现，重点验证三类下游 MCP 服务：

| 服务 | 类型 | 验证重点 |
| --- | --- | --- |
| `amap` | 远程 Streamable HTTP MCP | 真实远程服务发现、凭证、工具索引、天气/路线调用 |
| `fetch` | stdio MCP | Gateway 拉起本地 stdio MCP server 并转发网页抓取 |
| `sandbox` | 本地 Streamable HTTP MCP | Agent run 级别 sandbox 生命周期管理 |

最终要证明：

1. Cursor 只挂载一个 `java-mcp-gateway`。
2. Cursor 能通过 Gateway 发现下游 MCP 服务。
3. Cursor 不需要直接挂载高德、fetch、sandbox。
4. Gateway 能按需展开工具并完成真实路由转发。
5. Gateway 的服务状态、错误原因、调用结果可观测、可排查。

## 2. 测试环境

### 2.1 必需环境

| 依赖 | 用途 | 检查命令 |
| --- | --- | --- |
| JDK 17 | 运行 Java Gateway | `java -version` |
| Maven | 启动 Spring Boot 和运行测试 | `mvn -version` |
| Cursor | 作为真实 MCP Client | Cursor Settings -> MCP |
| uvx | 运行 fetch stdio MCP | `uvx --version` |

### 2.2 可选环境

| 依赖 | 用途 | 检查命令 |
| --- | --- | --- |
| Docker | 后续切换 sandbox 真实 Docker 后端 | `docker ps` |
| npx | filesystem MCP，不是本轮重点 | `npx --version` |

## 3. 启动 Gateway

进入工程目录：

```bash
cd /Users/nikonzhang/shixi/mcp-gateway/Unla/java-mcp-gateway
```

先跑测试，确保本地代码是可工作的：

```bash
mvn test
```

预期：

```text
BUILD SUCCESS
Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
```

启动 Gateway：

```bash
mvn spring-boot:run \
  -Dspring-boot.run.profiles=real-mcp \
  -Dspring-boot.run.arguments=--server.port=8091
```

预期 endpoint：

```text
http://127.0.0.1:8091/mcp
```

健康检查：

```bash
curl -s http://127.0.0.1:8091/actuator/health
```

运行状态检查：

```bash
curl -s http://127.0.0.1:8091/internal/status
```

验收点：

- Gateway 进程正常启动。
- `/actuator/health` 可访问。
- `/internal/status` 可看到 `amap`、`fetch`、`sandbox`。
- 如果 `fetch` 因 `uvx` 缺失不可用，需要先安装或修复本地 `uvx`。

## 4. Cursor 配置

在项目级配置文件中添加：

```text
/Users/nikonzhang/shixi/mcp-gateway/Unla/.cursor/mcp.json
```

内容：

```json
{
  "mcpServers": {
    "java-mcp-gateway": {
      "url": "http://127.0.0.1:8091/mcp"
    }
  }
}
```

配置后：

1. 打开 Cursor Settings。
2. 进入 MCP 页面。
3. 找到 `java-mcp-gateway`。
4. 点击 Reload。
5. 确认状态为 connected。

如果 Gateway 重启过，必须在 Cursor MCP 页面 Reload，否则容易出现 `Not connected`。

## 5. 总体发现测试

### 5.1 Cursor 测试话术

```text
通过 MCP Gateway 查看当前有哪些 MCP 服务可用
```

### 5.2 预期调用链

```text
Cursor -> java-mcp-gateway tools/list
Cursor -> search_mcp_services(query="")
```

### 5.3 验收标准

返回服务列表中至少包含：

| service_id | 预期状态 |
| --- | --- |
| `amap` | 可发现；如果未提交高德 Key，状态应提示需要凭证 |
| `fetch` | 可发现；工具数应为 1，工具名通常为 `fetch` |
| `sandbox` | 可发现；工具数应为 3，包含 `connect`、`disconnect`、`status` |

Cursor 顶层工具不应该直接暴露：

```text
maps_weather
fetch
connect
disconnect
status
```

它们必须通过 Gateway catalog tool：

```text
call_mcp_tool
```

间接调用。

## 6. 高德 MCP 测试

### 6.1 测试目标

验证 Cursor 通过 Gateway 调用高德官方远程 MCP，完成：

1. 服务发现。
2. 凭证状态检查。
3. 工具索引刷新。
4. 天气查询。
5. 路线规划。

### 6.2 凭证准备

如果当前用户还没有提交高德 Key，在 Cursor 中输入：

```text
高德地图 MCP 需要什么凭证？
```

预期：

```text
需要 api_key，高德开放平台 Web 服务 Key
```

然后提交：

```text
为 amap 设置 api_key：你的高德Key
```

再刷新：

```text
刷新 amap 服务的工具索引
```

验收点：

- `refresh_mcp_service(service_id="amap")` 成功。
- `amap` 工具数约为 15。
- `list_mcp_tools(service_id="amap")` 能看到 `maps_weather`。

### 6.3 天气查询测试

Cursor 输入：

```text
请通过高德地图 MCP 查询西安市今天的天气
```

预期调用链：

```text
search_mcp_services(query="高德")
list_mcp_tools(service_id="amap")
call_mcp_tool(service_id="amap", tool_name="maps_weather", city="西安市")
```

验收标准：

- Cursor 最终返回西安市天气。
- 结果来自 `amap.maps_weather`。
- 不出现 `Tool not found`。
- 不出现 `auth_required`。
- 不出现空字符串结果。

### 6.4 路线规划测试

Cursor 输入：

```text
请通过高德地图 MCP 查询从西安市雁塔区南洋时代到曲江汉华国际中心的步行路线
```

预期调用链：

```text
maps_text_search
maps_search_detail
maps_direction_walking
```

验收标准：

- Cursor 能先匹配起点和终点 POI。
- Cursor 能使用经纬度调用 `maps_direction_walking`。
- 返回结果包含总距离、预计时间、分段路线。
- 如果 POI 名称有歧义，Cursor 应说明匹配到的地点，而不是静默误用。

## 7. Fetch MCP 测试

### 7.1 测试目标

验证 Gateway 能管理 stdio 类型下游 MCP，Cursor 通过 Gateway 调用 `fetch` 抓取网页内容。

### 7.2 基础网页抓取

Cursor 输入：

```text
请通过 MCP Gateway 使用 fetch 服务抓取 https://example.com 的内容
```

预期调用链：

```text
search_mcp_services(query="fetch")
list_mcp_tools(service_id="fetch")
call_mcp_tool(service_id="fetch", tool_name="fetch", url="https://example.com")
```

验收标准：

- Cursor 返回 example.com 的正文内容。
- 内容中应包含：

```text
This domain is for use in documentation examples
```

- Gateway 不应要求用户凭证。
- 如果返回服务不可用，需要检查 `uvx mcp-server-fetch` 是否能启动。

### 7.3 中文网页抓取

Cursor 输入：

```text
请通过 MCP Gateway 使用 fetch 服务抓取 https://news.qq.com/ 的内容，最多返回 1000 字
```

验收标准：

- Cursor 能调用 `fetch.fetch`。
- 返回网页文本或可解释的抓取失败原因。
- 如果内容过长，返回中可能出现 truncation 提示，这是可接受结果。

### 7.4 Fetch 失败场景

Cursor 输入：

```text
请通过 fetch 服务抓取一个不存在的网址 https://not-existing.example.invalid
```

验收标准：

- Gateway 不崩溃。
- Cursor 能看到明确失败信息。
- `/internal/status` 仍可访问。

## 8. Sandbox MCP 测试

### 8.1 测试目标

验证 Gateway 能把 `sandbox` 作为一个普通下游 MCP 服务管理，完成 agent run 级别的 lazy connect 生命周期。

第一版默认使用 `in-memory` 后端，不强依赖本机 Docker。

### 8.2 Connect 前状态

Cursor 输入：

```text
请通过 MCP Gateway 查看 sandbox 服务中 agent_id=agent-test-001 run_id=run-001 的沙盒状态，tenant_id=default，user_id=alice
```

预期调用：

```text
call_mcp_tool(
  service_id="sandbox",
  tool_name="status",
  tenant_id="default",
  user_id="alice",
  agent_id="agent-test-001",
  run_id="run-001"
)
```

验收标准：

```text
state = not_created
sandbox_id = null
```

### 8.3 首次 Connect

Cursor 输入：

```text
请通过 MCP Gateway 为 tenant_id=default、user_id=alice、agent_id=agent-test-001、run_id=run-001 创建或连接一个 cpu-python sandbox
```

预期调用：

```text
call_mcp_tool(
  service_id="sandbox",
  tool_name="connect",
  tenant_id="default",
  user_id="alice",
  agent_id="agent-test-001",
  run_id="run-001",
  profile="cpu-python"
)
```

验收标准：

```text
state = running
created = true
reused = false
profile = cpu-python
image = python:3.11-slim
workspace = /workspace/...
```

### 8.3.1 Ubuntu 基础 Sandbox

Cursor 输入：

```text
请通过 MCP Gateway 为 tenant_id=default、user_id=alice、agent_id=agent-test-001、run_id=run-ubuntu-001 创建或连接一个 ubuntu-basic sandbox
```

预期调用：

```text
call_mcp_tool(
  service_id="sandbox",
  tool_name="connect",
  tenant_id="default",
  user_id="alice",
  agent_id="agent-test-001",
  run_id="run-ubuntu-001",
  profile="ubuntu-basic"
)
```

验收标准：

```text
state = running
created = true
profile = ubuntu-basic
image = ubuntu:22.04
```

说明：`ubuntu-basic` 使用官方 Ubuntu 基础镜像，包含 `apt/apt-get/dpkg` 等包管理基础能力，但不承诺预装 Python、git、curl 等开发工具。

### 8.4 重复 Connect 幂等性

再次输入同一条 connect 请求。

验收标准：

```text
state = running
created = false
reused = true
sandbox_id 与第一次相同
container_id 与第一次相同
```

### 8.5 不同 Run 隔离

Cursor 输入：

```text
请通过 MCP Gateway 为 tenant_id=default、user_id=alice、agent_id=agent-test-001、run_id=run-002 创建或连接一个 cpu-python sandbox
```

验收标准：

- `run-002` 返回新的 `sandbox_id`。
- `run-002` 返回新的 `container_id`。
- `run-002` 不复用 `run-001` 的 sandbox。

### 8.6 Disconnect

Cursor 输入：

```text
请通过 MCP Gateway 断开并释放 tenant_id=default、user_id=alice、agent_id=agent-test-001、run_id=run-001 的 sandbox
```

预期调用：

```text
call_mcp_tool(
  service_id="sandbox",
  tool_name="disconnect",
  tenant_id="default",
  user_id="alice",
  agent_id="agent-test-001",
  run_id="run-001"
)
```

验收标准：

```text
state = stopped
disconnected = true
released = true
```

### 8.7 Disconnect 后状态

Cursor 输入：

```text
请再次查看 tenant_id=default、user_id=alice、agent_id=agent-test-001、run_id=run-001 的 sandbox 状态
```

验收标准：

```text
state = stopped
sandbox_id 不为空
```

## 9. 观测记录

每一组测试都记录以下信息：

| 字段 | 记录内容 |
| --- | --- |
| 测试时间 | 例如 `2026-06-01 14:30` |
| Cursor MCP 状态 | connected / not connected / errored |
| Gateway profile | `real-mcp` |
| Gateway port | `8091` |
| 服务 ID | `amap` / `fetch` / `sandbox` |
| 调用工具 | 例如 `maps_weather` |
| 是否成功 | yes / no |
| 失败信息 | 原始错误片段 |
| `/internal/status` 状态 | 对应服务的 `state`、`tool_count`、`last_error` |

建议测试表：

| 编号 | 服务 | 场景 | 预期 |
| --- | --- | --- | --- |
| T-001 | Gateway | Cursor connected | 成功连接 |
| T-002 | Gateway | 服务发现 | 看到 `amap`、`fetch`、`sandbox` |
| T-003 | AMap | 凭证状态 | 有 Key 后 callable=true |
| T-004 | AMap | 天气查询 | 返回西安天气 |
| T-005 | AMap | 步行路线 | 返回路线距离和步骤 |
| T-006 | Fetch | example.com | 返回 example.com 正文 |
| T-007 | Fetch | 无效 URL | 返回明确错误，Gateway 不崩溃 |
| T-008 | Sandbox | status before connect | `not_created` |
| T-009 | Sandbox | first connect | `created=true` |
| T-010 | Sandbox | repeat connect | `reused=true` |
| T-011 | Sandbox | different run | 新 sandbox |
| T-012 | Sandbox | disconnect | `released=true` |

## 10. 失败排查

### 10.1 Cursor 报 `Not connected`

优先检查：

1. Gateway 是否还在运行。
2. `http://127.0.0.1:8091/mcp` 是否可访问。
3. Cursor Settings -> MCP 中是否需要 Reload。
4. `.cursor/mcp.json` 中 URL 是否仍是 `8091`。

### 10.2 AMap 工具数为 0

可能原因：

1. 没提交 `api_key`。
2. 提交后没有调用 `refresh_mcp_service`。
3. 高德 Key 无效。
4. 网络无法访问 `https://mcp.amap.com/mcp`。

处理：

```text
get_auth_status(service_id="amap")
get_credential_requirements(service_id="amap")
submit_mcp_credential(...)
refresh_mcp_service(service_id="amap")
list_mcp_tools(service_id="amap")
```

### 10.3 Fetch 不可用

可能原因：

1. 本机没有 `uvx`。
2. `uvx mcp-server-fetch` 首次启动需要下载依赖。
3. 当前网络无法下载依赖。

处理：

```bash
uvx --version
uvx mcp-server-fetch
```

确认本地可启动后，重启 Gateway 并在 Cursor MCP 页面 Reload。

### 10.4 Sandbox connect 失败

如果默认 `in-memory` 后端失败，优先看 Gateway 日志中的：

```text
sandbox operation=connect
tenant_id=...
user_id=...
agent_id=...
run_id=...
success=false
error_code=...
```

如果设置了：

```bash
export SANDBOX_BACKEND=docker-cli
```

还需要检查：

```bash
docker ps
docker images
```

## 11. 最终验收结论模板

测试完成后可以按下面格式记录：

```text
测试时间：
Gateway 版本/分支：
启动命令：
Cursor MCP 状态：

总体结论：
- Gateway 是否能被 Cursor 挂载：
- 是否能发现 amap/fetch/sandbox：
- amap 天气是否成功：
- amap 路线是否成功：
- fetch example.com 是否成功：
- sandbox connect/status/disconnect 是否成功：

主要问题：
1.
2.

下一步建议：
1.
2.
```

# Java MCP Gateway 架构分析

## 项目概述

这是一个 MCP（Model Context Protocol）网关的 Java 实现。网关作为中间代理层，Agent（LLM）不直接看到下游 MCP 服务的原生 tools，而是通过网关暴露的"元工具"来间接发现和调用。同时网关负责权限校验、凭证绑定、请求转发。

## 全量源码文件（14 个）

```
src/main/java/com/example/mcpgateway/
├── GatewayRuntime.java              # 编排层（核心）
├── ServiceRegistry.java             # 状态：服务注册表
├── CapabilityIndex.java             # 状态：能力索引
├── CredentialStore.java             # 状态：凭证库
├── PermissionService.java           # 逻辑：权限校验
├── DownstreamClientRegistry.java    # 状态：下游客户端注册表
├── McpClient.java                   # 接口：下游 MCP 客户端
├── InMemoryMcpClient.java           # 实现：内存 mock 客户端
├── ServiceDefinition.java           # 数据：服务定义
├── ServiceSummary.java              # 数据：服务摘要
├── ToolSchema.java                  # 数据：工具描述
├── UserContext.java                 # 数据：用户上下文
├── Credential.java                  # 数据：用户凭证
├── ToolCallRequest.java             # 数据：工具调用请求
└── ToolCallResult.java              # 数据：工具调用结果
```

## 三层架构

```
┌─────────────────────────────────────────────────────────────────┐
│  第 1 层：数据类（record）                                        │
│  纯数据结构，自己没有任何业务逻辑，只在各层之间传递                   │
│                                                                  │
│  ToolSchema      → 工具描述（名称/说明/入参 schema）               │
│  ServiceDefinition → 服务定义（ID/名称/传输方式/地址/是否要凭证）    │
│  ServiceSummary  → 服务摘要（给 Agent 看的精简版）                 │
│  UserContext     → 用户身份（谁/哪个租户/有哪些权限）               │
│  Credential      → 用户凭证（token 类型/token 值）                │
│  ToolCallRequest → 工具调用请求（调哪个服务/哪个工具/什么参数）     │
│  ToolCallResult  → 工具调用结果（是否允许/返回内容/错误码）         │
└─────────────────────────────────────────────────────────────────┘
                              ↑ 被上层使用
┌─────────────────────────────────────────────────────────────────┐
│  第 2 层：状态 & 逻辑（存储 + 校验）                               │
│  各自管理一块独立状态，互不依赖                                    │
│                                                                  │
│  ServiceRegistry         → Map<服务ID, 服务定义>    增删查       │
│  CapabilityIndex         → Map<服务ID, 工具列表>    增查         │
│  CredentialStore         → Map<key, 凭证>          增查         │
│  DownstreamClientRegistry → Map<服务ID, 下游客户端>  增查        │
│  PermissionService       → 无状态，纯函数，根据 UserContext 判权限 │
└─────────────────────────────────────────────────────────────────┘
                              ↑ 被编排层调用
┌─────────────────────────────────────────────────────────────────┐
│  第 3 层：编排层                                                  │
│                                                                  │
│  GatewayRuntime → 拿到上面所有组件，编排调用顺序                    │
└─────────────────────────────────────────────────────────────────┘
```

## 组件耦合关系图

```
                    GatewayRuntime（编排者，依赖全部 5 个组件）
                   /     |      |     |        \
                  /      |      |     |         \
    ServiceRegistry  Capability  Credential  Permission  DownstreamClient
                     Index       Store       Service     Registry
         │               │           │           │            │
         │               │           │           │            │
         ▼               ▼           ▼           ▼            ▼
  ServiceDefinition  ToolSchema  Credential  UserContext   McpClient
                                                          (接口)
                                                            │
                                                     InMemoryMcpClient
                                                     (测试用 mock)
```

**关键设计：5 个状态组件之间完全解耦，互不知道对方存在。**

- `ServiceRegistry` 不知道 `CapabilityIndex` 的存在
- `CredentialStore` 不知道 `PermissionService` 的存在
- `PermissionService` **无状态**，只是把 `UserContext.scopes` 和权限字符串做匹配
- 所有"谁先谁后、谁调谁"的逻辑全部集中在 `GatewayRuntime` 这一个类里

---

## GatewayRuntime 5 个核心方法调用链

### 1. registerService(service) — 注册服务时调用一次

```
registerService(service)
  │
  ├─ downstreamClients.get(service.id)     ← 找到下游客户端
  ├─ services.register(service)            ← 写入服务注册表
  └─ capabilities.index(service, client)   ← 调 client.listTools() 取下游 tools 写入索引
```

涉及组件：`DownstreamClientRegistry` → `ServiceRegistry` + `CapabilityIndex`

### 2. catalogTools() — Agent 连上来时调用

```
catalogTools()
  │
  └─ 直接 return 5 个硬编码的 ToolSchema  ← 不依赖任何组件，纯静态数据
```

返回 5 个元工具：

| 工具名 | 用途 |
|--------|------|
| `search_mcp_services` | 搜索可用的 MCP 服务 |
| `describe_mcp_service` | 查看单个服务详情（不暴露全部 tools） |
| `list_mcp_tools` | 按需列出某服务的具体 tools |
| `get_auth_status` | 检查用户是否已绑定某服务凭证 |
| `call_mcp_tool` | 真正调用下游服务的某个 tool |

下游 tools 不会出现在 Agent 初始列表里，因为这个方法根本不查 `CapabilityIndex`。

### 3. searchServices(user, query) — Agent 搜索服务

```
searchServices(user, "飞书")
  │
  ├─ services.list()                        ← 取出所有已注册服务
  ├─ permissions.canDiscover(user, id)      ← 按权限过滤
  ├─ matches(service, "飞书")               ← 按关键词匹配 ID/名称/描述/标签
  └─ capabilities.toolsForService(id).size() ← 附上该服务有几个 tools
```

涉及组件：`ServiceRegistry` → `PermissionService` → `CapabilityIndex`（只取数量）

### 4. listTools(user, serviceId) — Agent 按需查看某服务 tools

```
listTools(alice, "feishu")
  │
  ├─ permissions.canDiscover(user, "feishu")  ← 能发现？
  ├─ permissions.canUseService(user, "feishu") ← 能用？
  └─ capabilities.toolsForService("feishu")   ← 从索引中取 tools 列表
```

涉及组件：`PermissionService` → `CapabilityIndex`

### 5. callTool(user, request) — Agent 调用下游工具（最核心方法）

```
callTool(alice, {service:"feishu", tool:"send_message", args:{text:"hello"}})
  │
  ├─ ① services.get("feishu")                      ← 服务存在？
  │     ✗ → service_not_found
  │     ✓ ↓
  ├─ ② permissions.canUseService(user, "feishu")    ← 有 use 权限？
  │   + permissions.canCallTool(user, "feishu",     ← 有 tool 权限？
  │       "send_message")
  │     ✗ → permission_denied
  │     ✓ ↓
  ├─ ③ credentials.get(user, tenant, feishu)        ← 需要凭证且已绑定？
  │     ✗ → auth_required
  │     ✓ ↓
  ├─ ④ downstreamClients.get("feishu")              ← 取出下游客户端
  │     .callTool(feishu, send_message, args, token) ← 转发，带上用户 token
  │     ↓
  └─ return ToolCallResult.success("forwarded:...")
```

涉及组件：全部 5 个状态组件依次被调用。

四步校验链图示：

```
① 服务存在？  →  ② 权限够？   →  ③ 凭证有？   →  ④ 转发
   ✗                ✗               ✗               ✓
   ↓                ↓               ↓               ↓
service_not     permission_     auth_required     success
 _found          denied
```

---

## 完整数据流示例（Agent 发一条飞书消息）

```
Agent 发来请求                    内部校验链                        下游服务
─────────────────────────────────────────────────────────────────────────
ToolCallRequest              GatewayRuntime.callTool()
{                               │
  serviceId: "feishu"   ① ServiceRegistry.get("feishu")
  toolName: "send_msg"       → ServiceDefinition{requiresUserCredential:true}
  arguments: {text:"hi"}     │
                         ② PermissionService
                              → UserContext.scopes 里查
                                "mcp:feishu:use" ✓
                                "mcp:feishu:send_message" ✓
                              │
                         ③ CredentialStore.get("alice","tenant-a","feishu")
                              → Credential{type:"bearer", value:"tok-123"}
                              │
                         ④ DownstreamClientRegistry.get("feishu")
                              │
                         ⑤ InMemoryMcpClient.callTool()
                              → return "forwarded:feishu:send_msg:hi:tok-123"
                              │
                         ⑥ ToolCallResult{allowed:true, content:"forwarded:..."}
                              │
                         返回给 Agent
```

---

## Agent 调用下游工具的懒发现模式

Agent 不直接看到 `send_message`、`create_issue` 这些下游工具，而是通过两步间接调用：

```
Agent                                     Gateway
──────────────────────────────────────────────────────────
① search_mcp_services("飞书")  ──────→  返回 feishu 服务摘要
② list_mcp_tools("feishu")    ──────→  返回 [send_message, search_docs]
③ call_mcp_tool("feishu",     ──────→  权限+凭证校验通过，
   "send_message", {text})             带上用户 token 转发到下游
```

这样设计的好处：

1. **省 Token** — 不把成百上千个下游 tools 全部塞进 system prompt
2. **安全隔离** — tools 只在 Agent 主动发现后才暴露
3. **松耦合** — 增删下游服务不影响 Agent 的基础工具列表

---

## 各组件源码速查

### ServiceRegistry

```java
// Map<serviceId, ServiceDefinition>
// register() / get() / list()
```

### CapabilityIndex

```java
// Map<serviceId, List<ToolSchema>>
// index(service, client) / toolsForService(serviceId)
// index 时会调 client.listTools() 拉取下游 tools 并快照存储
```

### CredentialStore

```java
// Map<"tenantId:userId:serviceId", Credential>
// put(userId, tenantId, serviceId, credential) / get(userId, tenantId, serviceId)
```

### DownstreamClientRegistry

```java
// Map<serviceId, McpClient>
// register(serviceId, client) / get(serviceId)
```

### PermissionService（无状态）

```java
// canDiscover(user, serviceId) → user.hasScope("mcp:{serviceId}:discover")
// canUseService(user, serviceId) → user.hasScope("mcp:{serviceId}:use")
// canCallTool(user, serviceId, toolName) → user.hasScope("mcp:{serviceId}:{toolName}")
```

### McpClient（接口）

```java
// List<ToolSchema> listTools()
// String callTool(serviceId, toolName, arguments, credential)
```

### InMemoryMcpClient（测试用 mock）

```java
// listTools() → 返回构造时传入的固定 tools 列表
// callTool() → 返回 "forwarded:{serviceId}:{toolName}:{text}:{credential}"
//              不发起真实网络请求，纯字符串拼接
```

---

## 一句话总结

> 整个项目的架构就是：**7 个数据类**在 **5 个独立的状态组件**和 **1 个编排类**之间流动。5 个组件之间互不耦合，所有"先查什么再查什么"的顺序逻辑全部集中在 `GatewayRuntime` 的 5 个方法里。你看懂了 `GatewayRuntime` 的 5 个方法，就看懂了整个项目。

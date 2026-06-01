package com.example.mcpgateway;

import java.util.List;
import java.util.Map;

/**
 * MCP Gateway 行为测试 —— 不带 Spring、不启动 HTTP，直接在内存中验证核心逻辑。
 *
 * 测试核心思想：mcp-gateway 作为中间代理层，Agent（LLM）不直接看到下游 MCP 服务的原
 * 生 tools，而是通过网关暴露的"元工具（catalog tools）"来间接发现和调用。同时，网关
 * 负责权限校验、凭证绑定、请求转发。
 */
public final class GatewayBehaviorTest {
	public static void main(String[] args) {
		GatewayBehaviorTest test = new GatewayBehaviorTest();
		test.registeredServiceIsIndexedButAgentOnlySeesCatalogTools();
		test.discoveryFiltersServicesByPermissions();
		test.callRequiresToolPermissionAndUserCredentialBeforeForwarding();
		System.out.println("GatewayBehaviorTest passed");
	}

	// ========================================================================
	// 测试 1：注册 MCP 服务后，下游 tools 被网关内部索引，但 Agent 初始工具列表中
	//         只包含网关的"目录工具"（catalog tools），不包含下游原生 tools。
	//         这是 mcp-gateway "懒发现" 设计模式的核心验证。
	// ========================================================================
	void registeredServiceIsIndexedButAgentOnlySeesCatalogTools() {
		// -------------------- 步骤 1：搭建测试环境 --------------------
		// fixture() 创建下游客户端注册表，预设了两个 mock MCP 服务：
		//   • feishu → 拥有 send_message、search_docs 两个工具
		//   • github → 拥有 create_issue 一个工具
		// 然后用 DownstreamClientRegistry 构建完整的 GatewayRuntime，
		// 内部包含：ServiceRegistry、CapabilityIndex、CredentialStore、PermissionService
		GatewayRuntime runtime = fixture();

		// -------------------- 步骤 2：注册飞书服务 --------------------
		// registerService 内部做了两件事：
		//   1. 将 ServiceDefinition 存到 ServiceRegistry（服务注册表）
		//   2. 从 DownstreamClientRegistry 找到 feishu 对应的 McpClient，
		//      调用 client.listTools() 拿到下游 tools，写入 CapabilityIndex（能力索引）
		//
		// 参数说明：
		//   id               = "feishu"          服务唯一标识
		//   name             = "Feishu MCP"      展示名称
		//   description      = "..."             服务描述
		//   tags             = ["collaboration", "chat"]  标签，用于搜索过滤
		//   url              = "http://feishu-mcp.local/mcp"  下游 MCP 服务地址
		//   requiresUserCredential = true        调用前需要用户绑定自己的 token
		runtime.registerService(ServiceDefinition.streamableHttp(
				"feishu",
				"Feishu MCP",
				"Feishu documents and messaging",
				List.of("collaboration", "chat"),
				"http://feishu-mcp.local/mcp",
				true));

		// -------------------- 步骤 3：验证下游 tools 已被索引 --------------------
		// CapabilityIndex 是一个 Map<String, List<ToolSchema>>，
		// key=服务ID, value=该服务的下游工具列表。
		// fixture 中 feishu 预设了 2 个 mock 工具，注册后应能查到 2 个。
		List<ToolSchema> indexedTools = runtime.capabilities().toolsForService("feishu");
		assertEquals(2, indexedTools.size(), "feishu tools should be indexed");

		// -------------------- 步骤 4：验证 Agent 看到的工具列表 --------------------
		// catalogTools() 返回网关暴露给 Agent（LLM）的 Function Calling 列表。
		// 当前版本包含 5 个元工具（meta-tools）：
		//
		//   ┌─────────────────────────┬──────────────────────────────────┐
		//   │ 工具名                   │ 用途                             │
		//   ├─────────────────────────┼──────────────────────────────────┤
		//   │ search_mcp_services     │ 搜索可用的 MCP 服务               │
		//   │ describe_mcp_service    │ 查看某服务详情（不暴露全部 tools） │
		//   │ list_mcp_tools          │ 按需列出某服务的具体 tools         │
		//   │ get_auth_status         │ 检查用户是否已绑定某服务凭证       │
		//   │ call_mcp_tool           │ 真正调用下游服务的某个 tool        │
		//   └─────────────────────────┴──────────────────────────────────┘
		//
		// Agent 调用下游工具的路径是间接的（两层跳转）：
		//
		//   Agent                                     Gateway
		//   ──────────────────────────────────────────────────────────
		//   ① search_mcp_services("飞书")  ──────→  返回 feishu 服务摘要
		//   ② list_mcp_tools("feishu")    ──────→  返回 send_message, search_docs
		//   ③ call_mcp_tool("feishu",     ──────→  转发到下游 feishu MCP Server
		//      "send_message", {text})
		//
		// 这样设计的好处：
		//   1. 省 Token —— 不把成百上千个下游 tools 全部塞进 system prompt
		//   2. 安全隔离 —— tools 只在 Agent 主动发现后才暴露
		//   3. 松耦合   —— 增删下游服务时 Agent 的基础工具列表不变
		List<ToolSchema> agentTools = runtime.catalogTools();

		// Agent 应看到网关自己的元工具 search_mcp_services
		assertTrue(agentTools.stream().anyMatch(t -> t.name().equals("search_mcp_services")),
				"agent should see search_mcp_services");
		// Agent 应看到网关自己的元工具 call_mcp_tool
		assertTrue(agentTools.stream().anyMatch(t -> t.name().equals("call_mcp_tool")),
				"agent should see call_mcp_tool");
		// ★ 核心断言：Agent 不应直接看到下游原生工具 send_message
		// 下游 tools 被隔离在 CapabilityIndex 中，不直接暴露给 LLM
		assertFalse(agentTools.stream().anyMatch(t -> t.name().equals("send_message")),
				"downstream tools should not be exposed in the initial agent tool list");
	}

	// ========================================================================
	// 测试 2：服务发现按用户权限过滤。
	//         Alice 只有 feishu 的权限，搜索 "mcp" 时应该只看到 feishu，
	//         看不到 github（因为 Alice 没有 github 的 discover 权限）。
	// ========================================================================
	void discoveryFiltersServicesByPermissions() {
		// 搭建环境并注册两个服务
		GatewayRuntime runtime = fixture();
		// 注册飞书（streamable-http 传输）
		runtime.registerService(ServiceDefinition.streamableHttp(
				"feishu",
				"Feishu MCP",
				"Feishu documents and messaging",
				List.of("collaboration"),
				"http://feishu-mcp.local/mcp",
				true));
		// 注册 GitHub（stdio 传输）
		runtime.registerService(ServiceDefinition.stdio(
				"github",
				"GitHub MCP",
				"GitHub repository automation",
				List.of("code"),
				"github-mcp-server",
				List.of("stdio"),
				true));

		// Alice 的权限列表：
		//   mcp:feishu:discover      → 能发现 feishu 服务
		//   mcp:feishu:use           → 能使用 feishu 服务
		//   mcp:feishu:send_message  → 能调用 feishu 的 send_message 工具
		// 注意：Alice 没有任何 github 相关权限
		UserContext alice = new UserContext("alice", "tenant-a", "agent-dev",
				List.of("mcp:feishu:discover", "mcp:feishu:use", "mcp:feishu:send_message"));

		// searchServices 内部逻辑：
		//   1. 遍历所有已注册服务
		//   2. 用 PermissionService.canDiscover() 过滤（核心权限检查）
		//   3. 用关键词 "mcp" 匹配服务 ID/名称/描述/标签
		//   4. 返回 ServiceSummary（包含服务摘要 + 该服务索引了多少个 tools）
		List<ServiceSummary> results = runtime.searchServices(alice, "mcp");

		// 虽然注册了 feishu 和 github 两个服务，
		// 但 Alice 只有 feishu 的 discover 权限，所以只能看到 1 个
		assertEquals(1, results.size(), "alice should only discover permitted services");
		assertEquals("feishu", results.get(0).id(), "alice should discover feishu");
	}

	// ========================================================================
	// 测试 3：完整的工具调用链路 —— 权限校验 → 凭证检查 → 请求转发。
	//         只有同时满足以下条件，调用才会被允许：
	//           1. 服务已注册
	//           2. 用户有 use 权限（mcp:{service}:use）
	//           3. 用户有具体 tool 权限（mcp:{service}:{tool}）
	//           4. 如果服务要求用户凭证，用户必须已绑定凭证
	// ========================================================================
	void callRequiresToolPermissionAndUserCredentialBeforeForwarding() {
		// 搭建环境，只注册飞书服务
		GatewayRuntime runtime = fixture();
		runtime.registerService(ServiceDefinition.streamableHttp(
				"feishu",
				"Feishu MCP",
				"Feishu documents and messaging",
				List.of("collaboration"),
				"http://feishu-mcp.local/mcp",
				true));  // requiresUserCredential = true，需要用户绑定凭证

		// Alice 有 discover、use、send_message 三个权限
		UserContext alice = new UserContext("alice", "tenant-a", "agent-dev",
				List.of("mcp:feishu:discover", "mcp:feishu:use", "mcp:feishu:send_message"));
		// 构造一个调用请求：调用 feishu 服务的 send_message 工具，参数 {text: "hello"}
		ToolCallRequest request = new ToolCallRequest("feishu", "send_message", Map.of("text", "hello"));

		// ------ 场景 A：权限够但缺少凭证 ------
		// Alice 有 send_message 权限，但还没绑定飞书 token
		// callTool 内部流程：
		//   ① 查服务是否存在 → feishu 已注册 ✓
		//   ② 查权限（use + tool）→ Alice 有权限 ✓
		//   ③ 查凭证 → requiresUserCredential=true 且 Alice 没绑凭证 ✗
		//   ④ 返回 auth_required
		ToolCallResult missingCredential = runtime.callTool(alice, request);
		assertFalse(missingCredential.allowed(), "call should be denied before credential binding");
		assertEquals("auth_required", missingCredential.errorCode(),
				"missing credential should return auth_required");

		// ------ 场景 B：绑定凭证后再次调用 ------
		// 将 Alice 的飞书 token 存入 CredentialStore
		// CredentialStore 是三层 Map：userId → tenantId → serviceId → Credential
		runtime.credentials().put("alice", "tenant-a", "feishu",
				new Credential("bearer", "feishu-user-token"));

		// 这次所有条件都满足，调用成功
		// 下游 mock 客户端返回 "forwarded:feishu:send_message:hello:feishu-user-token"
		// 验证了网关确实把用户凭证（feishu-user-token）传给了下游
		ToolCallResult result = runtime.callTool(alice, request);
		assertTrue(result.allowed(), "authorized call should be allowed");
		assertEquals("forwarded:feishu:send_message:hello:feishu-user-token", result.content(),
				"gateway should forward call with the user's credential");

		// ------ 场景 C：有凭证但缺少具体 tool 权限 ------
		// Bob 只有 discover 和 use 权限，没有 send_message 权限
		UserContext bob = new UserContext("bob", "tenant-a", "agent-dev",
				List.of("mcp:feishu:discover", "mcp:feishu:use"));
		runtime.credentials().put("bob", "tenant-a", "feishu",
				new Credential("bearer", "bob-token"));

		// Bob 有凭证但缺少 mcp:feishu:send_message 权限
		// callTool 流程在步骤②失败 → 返回 permission_denied
		ToolCallResult denied = runtime.callTool(bob, request);
		assertFalse(denied.allowed(), "call should be denied without tool permission");
		assertEquals("permission_denied", denied.errorCode(),
				"missing tool scope should be permission_denied");
	}

	// ========================================================================
	// 测试夹具：创建内存中的完整 GatewayRuntime
	// ========================================================================
	private GatewayRuntime fixture() {
		// DownstreamClientRegistry：下游 MCP 客户端注册表
		// 注册了两个 mock 客户端，模拟真实的下游 MCP 服务 获取客户端注册信息类
		DownstreamClientRegistry downstream = new DownstreamClientRegistry();

		// feishu mock 客户端：拥有 send_message 和 search_docs 两个工具
		downstream.register("feishu", new InMemoryMcpClient(List.of(
				new ToolSchema("send_message", "Send a Feishu message",
						Map.of("text", "string")),
				new ToolSchema("search_docs", "Search Feishu documents",
						Map.of("query", "string")))));

		// github mock 客户端：拥有 create_issue 一个工具
		downstream.register("github", new InMemoryMcpClient(List.of(
				new ToolSchema("create_issue", "Create GitHub issue",
						Map.of("title", "string")))));

		// createDefault 用下游客户端注册表构建完整运行时，
		// 同时初始化空的 ServiceRegistry、CapabilityIndex、CredentialStore、PermissionService
		return GatewayRuntime.createDefault(downstream);
	}

	// ========================================================================
	// 最简断言工具（不依赖 JUnit/TestNG，直接 main 跑）
	// ========================================================================

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void assertFalse(boolean condition, String message) {
		if (condition) {
			throw new AssertionError(message);
		}
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
		}
	}
}
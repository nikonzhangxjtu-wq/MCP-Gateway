package com.example.mcpgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GatewayRuntimeUnitTest {
    @Test
    void serviceRegistrationIndexesFeishuToolsAndKeepsCatalogSmall() {
        GatewayRuntime runtime = runtimeWithoutCredentials();
        assertThat(runtime.capabilities().toolsForService("feishu"))
                .extracting(ToolSchema::name)
                .contains("send_message", "search_docs");
        assertThat(runtime.catalogTools())
                .extracting(ToolSchema::name)
                .contains("search_mcp_services", "call_mcp_tool")
                .doesNotContain("send_message");
    }

    @Test
    void missingCredentialReturnsAuthRequiredBeforeForwarding() {
        GatewayRuntime runtime = runtimeWithoutCredentials();
        UserContext alice = new UserContext("alice", "default", "agent-test", List.of(
                "mcp:feishu:discover",
                "mcp:feishu:use",
                "mcp:feishu:send_message"
        ));

        ToolCallResult result = runtime.callTool(alice, new ToolCallRequest(
                "feishu",
                "send_message",
                Map.of("text", "hello")
        ));

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo("auth_required");
    }

    @Test
    void failedCapabilityIndexingDoesNotBreakServiceDiscovery() {
        DownstreamClientRegistry downstream = new DownstreamClientRegistry();
        downstream.register("broken", new McpClient() {
            @Override
            public List<ToolSchema> listTools() {
                throw new RuntimeException("downstream offline");
            }

            @Override
            public String callTool(String serviceId, String toolName, Map<String, Object> arguments, Credential credential) {
                return "unused";
            }
        });
        GatewayRuntime runtime = GatewayRuntime.createDefault(downstream);
        runtime.registerService(ServiceDefinition.streamableHttp(
                "broken",
                "Broken MCP",
                "Unavailable service",
                List.of("broken"),
                "/broken",
                false
        ));
        UserContext alice = new UserContext("alice", "default", "agent-test", List.of(
                "mcp:broken:discover",
                "mcp:broken:use",
                "mcp:broken:*"
        ));

        assertThat(runtime.searchServices(alice, "broken")).hasSize(1);
        assertThat(runtime.listTools(alice, "broken")).isEmpty();
        assertThat(runtime.capabilities().lastError("broken")).contains("downstream offline");

        ToolCallResult result = runtime.callTool(alice, new ToolCallRequest("broken", "anything", Map.of()));
        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo("service_unavailable");
    }

    @Test
    void downstreamCallFailureReturnsDeniedResultInsteadOfThrowing() {
        DownstreamClientRegistry downstream = new DownstreamClientRegistry();
        downstream.register("unstable", new McpClient() {
            @Override
            public List<ToolSchema> listTools() {
                return List.of(new ToolSchema("explode", "Explode", Map.of()));
            }

            @Override
            public String callTool(String serviceId, String toolName, Map<String, Object> arguments, Credential credential) {
                throw new RuntimeException("boom");
            }
        });
        GatewayRuntime runtime = GatewayRuntime.createDefault(downstream);
        runtime.registerService(ServiceDefinition.streamableHttp(
                "unstable",
                "Unstable MCP",
                "Fails on call",
                List.of("unstable"),
                "/unstable",
                false
        ));
        UserContext alice = new UserContext("alice", "default", "agent-test", List.of(
                "mcp:unstable:discover",
                "mcp:unstable:use",
                "mcp:unstable:*"
        ));

        ToolCallResult result = runtime.callTool(alice, new ToolCallRequest("unstable", "explode", Map.of()));

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo("downstream_error");
        assertThat(result.errorMessage()).contains("boom");
    }

    @Test
    void callToolResolvesSimpleToolAliasBeforeForwarding() {
        RecordingMcpClient client = new RecordingMcpClient(List.of(
                new ToolSchema("maps_weather", "Weather", Map.of("city", "string"))
        ));
        DownstreamClientRegistry downstream = new DownstreamClientRegistry();
        downstream.register("amap", client);
        GatewayRuntime runtime = GatewayRuntime.createDefault(downstream);
        runtime.registerService(ServiceDefinition.streamableHttp(
                "amap",
                "AMap MCP",
                "Official AMap remote MCP service for maps and weather",
                List.of("amap", "高德", "地图", "天气"),
                "https://mcp.amap.com/mcp",
                false
        ));
        UserContext alice = new UserContext("alice", "default", "agent-test", List.of(
                "mcp:amap:discover",
                "mcp:amap:use",
                "mcp:amap:*"
        ));

        ToolCallResult result = runtime.callTool(alice, new ToolCallRequest(
                "amap",
                "weather",
                Map.of("city", "北京")
        ));

        assertThat(result.allowed()).isTrue();
        assertThat(client.lastToolName).isEqualTo("maps_weather");
    }

    @Test
    void serviceDiscoveryMatchesCompoundChineseQueriesAndMultiTermQueries() {
        DownstreamClientRegistry downstream = new DownstreamClientRegistry();
        downstream.register("amap", new InMemoryMcpClient(List.of(
                new ToolSchema("maps_weather", "Weather", Map.of("city", "string"))
        )));
        GatewayRuntime runtime = GatewayRuntime.createDefault(downstream);
        runtime.registerService(ServiceDefinition.streamableHttp(
                "amap",
                "AMap MCP",
                "Official AMap remote MCP service for maps and weather",
                List.of("amap", "gaode", "map", "weather", "高德", "地图", "天气"),
                "https://mcp.amap.com/mcp",
                false
        ));
        UserContext alice = new UserContext("alice", "default", "agent-test", List.of(
                "mcp:amap:discover",
                "mcp:amap:use",
                "mcp:amap:*"
        ));

        assertThat(runtime.searchServices(alice, "高德地图"))
                .extracting(ServiceSummary::id)
                .containsExactly("amap");
        assertThat(runtime.searchServices(alice, "map weather"))
                .extracting(ServiceSummary::id)
                .containsExactly("amap");
    }

    private GatewayRuntime runtimeWithoutCredentials() {
        DownstreamClientRegistry downstream = new DownstreamClientRegistry();
        downstream.register("feishu", new InMemoryMcpClient(MockFeishuMcpController.feishuTools()));
        GatewayRuntime runtime = GatewayRuntime.createDefault(downstream);
        runtime.registerService(ServiceDefinition.streamableHttp(
                "feishu",
                "Feishu MCP",
                "Mock Feishu collaboration service",
                List.of("feishu"),
                "/mock/feishu/mcp",
                true
        ));
        return runtime;
    }

    private static final class RecordingMcpClient implements McpClient {
        private final List<ToolSchema> tools;
        private String lastToolName;

        private RecordingMcpClient(List<ToolSchema> tools) {
            this.tools = tools;
        }

        @Override
        public List<ToolSchema> listTools() {
            return tools;
        }

        @Override
        public String callTool(String serviceId, String toolName, Map<String, Object> arguments, Credential credential) {
            lastToolName = toolName;
            return "ok";
        }
    }
}

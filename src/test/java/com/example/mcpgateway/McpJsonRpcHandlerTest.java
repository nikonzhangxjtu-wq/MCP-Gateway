package com.example.mcpgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class McpJsonRpcHandlerTest {
    @Test
    void listMcpToolsReturnsStructuredToolObjects() {
        DownstreamClientRegistry downstream = new DownstreamClientRegistry();
        downstream.register("amap", new InMemoryMcpClient(List.of(
                new ToolSchema("maps_weather", "Weather", Map.<String, Object>of(
                        "type", "object",
                        "properties", Map.<String, Object>of(
                                "city", Map.<String, Object>of(
                                        "type", "string",
                                        "description", "城市名称或者adcode"
                                )
                        ),
                        "required", List.of("city")
                ))
        )));
        GatewayRuntime runtime = GatewayRuntime.createDefault(downstream);
        runtime.registerService(ServiceDefinition.streamableHttp(
                "amap",
                "AMap MCP",
                "Official AMap remote MCP service",
                List.of("amap", "高德"),
                "https://mcp.amap.com/mcp",
                false
        ));
        McpJsonRpcHandler handler = new McpJsonRpcHandler(runtime);
        UserContext alice = new UserContext("alice", "default", "agent-test", List.of(
                "mcp:amap:discover",
                "mcp:amap:use",
                "mcp:amap:*"
        ));

        Map<String, Object> response = handler.handle(alice, Map.of(
                "jsonrpc", "2.0",
                "id", "list",
                "method", "tools/call",
                "params", Map.of(
                        "name", "list_mcp_tools",
                        "arguments", Map.of("service_id", "amap")
                )
        ));

        String text = contentText(response);
        assertThat(text)
                .contains("\"tools\"")
                .contains("\"name\":\"maps_weather\"")
                .contains("\"inputSchema\"")
                .contains("\"description\":\"城市名称或者adcode\"")
                .doesNotContain("ToolSchema[");
    }

    @Test
    void searchMcpServicesReturnsStructuredServiceObjects() {
        DownstreamClientRegistry downstream = new DownstreamClientRegistry();
        downstream.register("amap", new InMemoryMcpClient(List.of(
                new ToolSchema("maps_weather", "Weather", Map.of("city", "string"))
        )));
        GatewayRuntime runtime = GatewayRuntime.createDefault(downstream);
        runtime.registerService(ServiceDefinition.streamableHttp(
                "amap",
                "AMap MCP",
                "Official AMap remote MCP service",
                List.of("amap", "高德", "地图"),
                "https://mcp.amap.com/mcp",
                false
        ));
        McpJsonRpcHandler handler = new McpJsonRpcHandler(runtime);
        UserContext alice = new UserContext("alice", "default", "agent-test", List.of(
                "mcp:amap:discover",
                "mcp:amap:use",
                "mcp:amap:*"
        ));

        Map<String, Object> response = handler.handle(alice, Map.of(
                "jsonrpc", "2.0",
                "id", "search",
                "method", "tools/call",
                "params", Map.of(
                        "name", "search_mcp_services",
                        "arguments", Map.of("query", "高德地图")
                )
        ));

        String text = contentText(response);
        assertThat(text)
                .contains("\"services\"")
                .contains("\"id\":\"amap\"")
                .contains("\"tool_count\":1")
                .contains("\"recommended_tools\"")
                .contains("\"maps_weather\"")
                .doesNotContain("ServiceSummary[");
    }

    @Test
    void authStatusMarksCredentiallessServiceCallable() {
        DownstreamClientRegistry downstream = new DownstreamClientRegistry();
        downstream.register("amap", new InMemoryMcpClient(List.of(
                new ToolSchema("maps_weather", "Weather", Map.of("city", "string"))
        )));
        GatewayRuntime runtime = GatewayRuntime.createDefault(downstream);
        runtime.registerService(ServiceDefinition.streamableHttp(
                "amap",
                "AMap MCP",
                "Official AMap remote MCP service",
                List.of("amap", "高德"),
                "https://mcp.amap.com/mcp",
                false
        ));
        McpJsonRpcHandler handler = new McpJsonRpcHandler(runtime);
        UserContext alice = new UserContext("alice", "default", "agent-test", List.of(
                "mcp:amap:discover",
                "mcp:amap:use",
                "mcp:amap:*"
        ));

        Map<String, Object> response = handler.handle(alice, Map.of(
                "jsonrpc", "2.0",
                "id", "auth",
                "method", "tools/call",
                "params", Map.of(
                        "name", "get_auth_status",
                        "arguments", Map.of("service_id", "amap")
                )
        ));

        assertThat(response.toString())
                .contains("requires_user_credential")
                .contains("callable")
                .contains("true");
    }

    @Test
    void initializedNotificationIsAcceptedForCursorCompatibility() {
        McpJsonRpcHandler handler = new McpJsonRpcHandler(GatewayRuntime.createDefault(new DownstreamClientRegistry()));

        Map<String, Object> response = handler.handle(new UserContext("alice", "default", "agent-test", List.of()), Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized",
                "params", Map.of()
        ));

        assertThat(response.toString()).contains("result");
    }

    @SuppressWarnings("unchecked")
    private String contentText(Map<String, Object> response) {
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return content.get(0).get("text").toString();
    }
}

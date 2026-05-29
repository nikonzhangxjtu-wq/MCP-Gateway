package com.example.mcpgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpGatewayIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void initializeReturnsMcpCapabilities() {
        Map<String, Object> response = postMcp("alice", Map.of(
                "jsonrpc", "2.0",
                "id", "init",
                "method", "initialize",
                "params", Map.of()
        ));

        assertThat(response.toString()).contains("java-mcp-gateway").contains("capabilities");
    }

    @Test
    void mcpEndpointExposesCatalogToolsOnly() {
        Map<String, Object> response = postMcp("alice", Map.of(
                "jsonrpc", "2.0",
                "id", "tools",
                "method", "tools/list",
                "params", Map.of()
        ));

        List<Map<String, Object>> tools = toolsFrom(response);
        assertThat(toolNames(tools)).contains("search_mcp_services", "call_mcp_tool");
        assertThat(toolNames(tools)).doesNotContain("send_message");
    }

    @Test
    void gatewayDiscoversFeishuAndForwardsToolCallToMockMcp() {
        Map<String, Object> search = callCatalogTool("alice", "search_mcp_services", Map.of("query", "feishu"));
        assertThat(contentText(search)).contains("feishu").contains("Feishu MCP");

        Map<String, Object> listTools = callCatalogTool("alice", "list_mcp_tools", Map.of("service_id", "feishu"));
        assertThat(contentText(listTools)).contains("send_message").contains("search_docs");

        Map<String, Object> call = callCatalogTool("alice", "call_mcp_tool", Map.of(
                "service_id", "feishu",
                "tool_name", "send_message",
                "text", "hello"
        ));
        assertThat(contentText(call)).contains("Mock Feishu accepted message: hello");
    }

    @Test
    void gatewayRejectsUnauthorizedToolCall() {
        Map<String, Object> response = callCatalogTool("bob", "call_mcp_tool", Map.of(
                "service_id", "feishu",
                "tool_name", "send_message",
                "text", "hello"
        ));

        assertThat(response).containsKey("error");
        assertThat(response.toString()).contains("permission_denied");
    }

    @Test
    void internalStatusExposesOperationalServiceStateWithoutCredentials() {
        Map<String, Object> response = restTemplate.getForObject(
                "http://localhost:" + port + "/internal/status",
                Map.class
        );

        assertThat(response)
                .containsEntry("name", "java-mcp-gateway")
                .containsKeys("service_count", "indexed_count", "services");
        assertThat(response.toString())
                .contains("feishu")
                .contains("tool_count")
                .doesNotContain("mock-feishu-user-token");
    }

    @Test
    void actuatorHealthIncludesGatewayReadinessDetails() {
        Map<String, Object> response = restTemplate.getForObject(
                "http://localhost:" + port + "/actuator/health",
                Map.class
        );

        assertThat(response).containsKey("status");
        assertThat(response.toString())
                .contains("mcpGateway")
                .contains("service_count")
                .contains("unavailable_count");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toolsFrom(Map<String, Object> response) {
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        return (List<Map<String, Object>>) result.get("tools");
    }

    private List<String> toolNames(List<Map<String, Object>> tools) {
        return tools.stream().map(tool -> tool.get("name").toString()).toList();
    }

    @SuppressWarnings("unchecked")
    private String contentText(Map<String, Object> response) {
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return content.get(0).get("text").toString();
    }

    private Map<String, Object> callCatalogTool(String bearer, String toolName, Map<String, Object> arguments) {
        return postMcp(bearer, Map.of(
                "jsonrpc", "2.0",
                "id", toolName,
                "method", "tools/call",
                "params", Map.of(
                        "name", toolName,
                        "arguments", arguments
                )
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postMcp(String bearer, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        return restTemplate.postForObject("http://localhost:" + port + "/mcp", new HttpEntity<>(body, headers), Map.class);
    }
}

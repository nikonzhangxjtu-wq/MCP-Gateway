package com.example.mcpgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SandboxMcpControllerTest {
    @Test
    void sandboxMcpExposesLifecycleTools() {
        SandboxMcpController controller = new SandboxMcpController(new SandboxRuntime(new InMemorySandboxContainerBackend()));

        Map<String, Object> response = controller.mcp(Map.of(
                "jsonrpc", "2.0",
                "id", "tools",
                "method", "tools/list",
                "params", Map.of()
        )).getBody();

        assertThat(response.toString()).contains("connect").contains("disconnect").contains("status");
    }

    @Test
    void sandboxMcpConnectStatusAndDisconnectRoundTrip() {
        SandboxMcpController controller = new SandboxMcpController(new SandboxRuntime(new InMemorySandboxContainerBackend()));
        Map<String, Object> args = Map.of(
                "tenant_id", "default",
                "user_id", "alice",
                "agent_id", "agent-a",
                "run_id", "run-1",
                "profile", "cpu-python"
        );

        Map<String, Object> before = controller.mcp(toolCall("status", args)).getBody();
        Map<String, Object> connect = controller.mcp(toolCall("connect", args)).getBody();
        Map<String, Object> reconnect = controller.mcp(toolCall("connect", args)).getBody();
        Map<String, Object> disconnect = controller.mcp(toolCall("disconnect", args)).getBody();

        assertThat(contentText(before)).contains("\"state\":\"not_created\"");
        assertThat(contentText(connect)).contains("\"state\":\"running\"").contains("\"created\":true");
        assertThat(contentText(reconnect)).contains("\"reused\":true").contains("\"created\":false");
        assertThat(contentText(disconnect)).contains("\"state\":\"stopped\"").contains("\"released\":true");
    }

    @Test
    void sandboxMcpConnectSupportsUbuntuBasicProfile() {
        SandboxMcpController controller = new SandboxMcpController(new SandboxRuntime(new InMemorySandboxContainerBackend()));
        Map<String, Object> args = Map.of(
                "tenant_id", "default",
                "user_id", "alice",
                "agent_id", "agent-a",
                "run_id", "run-ubuntu",
                "profile", "ubuntu-basic"
        );

        Map<String, Object> connect = controller.mcp(toolCall("connect", args)).getBody();

        assertThat(contentText(connect))
                .contains("\"state\":\"running\"")
                .contains("\"profile\":\"ubuntu-basic\"")
                .contains("\"image\":\"ubuntu:22.04\"");
    }

    private Map<String, Object> toolCall(String name, Map<String, Object> arguments) {
        return Map.of(
                "jsonrpc", "2.0",
                "id", name,
                "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments)
        );
    }

    @SuppressWarnings("unchecked")
    private String contentText(Map<String, Object> response) {
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        return content.get(0).get("text").toString();
    }
}

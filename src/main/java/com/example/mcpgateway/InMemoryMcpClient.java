package com.example.mcpgateway;

import java.util.List;
import java.util.Map;

public final class InMemoryMcpClient implements McpClient {
    private final List<ToolSchema> tools;

    public InMemoryMcpClient(List<ToolSchema> tools) {
        this.tools = List.copyOf(tools);
    }

    @Override
    public List<ToolSchema> listTools() {
        return tools;
    }

    @Override
    public String callTool(String serviceId, String toolName, Map<String, Object> arguments, Credential credential) {
        Object text = arguments.getOrDefault("text", "");
        String credentialValue = credential == null ? "" : credential.value();
        return "forwarded:" + serviceId + ":" + toolName + ":" + text + ":" + credentialValue;
    }
}

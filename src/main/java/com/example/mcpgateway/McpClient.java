package com.example.mcpgateway;

import java.util.List;
import java.util.Map;

public interface McpClient {
    List<ToolSchema> listTools();

    default List<ToolSchema> listTools(Credential credential) {
        return listTools();
    }

    String callTool(String serviceId, String toolName, Map<String, Object> arguments, Credential credential);
}

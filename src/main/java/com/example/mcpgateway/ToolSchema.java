package com.example.mcpgateway;

import java.util.Map;

public record ToolSchema(String name, String description, Map<String, Object> inputSchema) {
}

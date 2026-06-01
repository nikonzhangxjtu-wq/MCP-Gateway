package com.example.mcpgateway;

import java.util.Map;

public record ToolCallRequest(String serviceId, String toolName, Map<String, Object> arguments) {
}

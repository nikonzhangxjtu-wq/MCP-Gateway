package com.example.mcpgateway;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolSchema(String name, String description, Map<String, Object> inputSchema) {
    public ToolSchema {
        inputSchema = normalizeInputSchema(inputSchema);
    }

    private static Map<String, Object> normalizeInputSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        if (schema.containsKey("type") || schema.containsKey("properties")) {
            return Map.copyOf(schema);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        schema.forEach((name, type) -> properties.put(name, Map.of("type", String.valueOf(type))));
        return Map.of("type", "object", "properties", properties);
    }
}

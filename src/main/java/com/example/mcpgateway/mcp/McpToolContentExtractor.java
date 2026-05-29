package com.example.mcpgateway.mcp;

import java.util.List;
import java.util.Map;

public final class McpToolContentExtractor {
    private McpToolContentExtractor() {
    }

    public static String extractText(Map<String, Object> result) {
        Object contentValue = result.get("content");
        if (!(contentValue instanceof List<?> content) || content.isEmpty()) {
            return result.toString();
        }
        Object first = content.get(0);
        if (first instanceof Map<?, ?> item) {
            Object text = item.get("text");
            return text == null ? item.toString() : text.toString();
        }
        return first.toString();
    }
}

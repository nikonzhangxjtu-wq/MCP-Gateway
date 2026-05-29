package com.example.mcpgateway;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MockFeishuMcpController {
    @PostMapping("/mock/feishu/mcp")
    public ResponseEntity<Map<String, Object>> mcp(@RequestBody Map<String, Object> request) {
        Object id = request.getOrDefault("id", "");
        String method = String.valueOf(request.getOrDefault("method", ""));
        if ("initialize".equals(method)) {
            return ResponseEntity.ok(success(id, Map.of(
                    "protocolVersion", "2025-03-26",
                    "serverInfo", Map.of("name", "mock-feishu-mcp", "version", "0.1.0"),
                    "capabilities", Map.of("tools", Map.of("listChanged", false))
            )));
        }
        if ("tools/list".equals(method)) {
            return ResponseEntity.ok(success(id, Map.of("tools", McpJsonRpcHandler.toolList(feishuTools()))));
        }
        if ("tools/call".equals(method)) {
            return ResponseEntity.ok(handleToolCall(id, request));
        }
        return ResponseEntity.ok(error(id, -32601, "Method not found"));
    }

    static List<ToolSchema> feishuTools() {
        return List.of(
                new ToolSchema("send_message",
                        "Send a text message through Feishu",
                        Map.of("text", "string")),
                new ToolSchema("search_docs",
                        "Search Feishu docs by keyword",
                        Map.of("query", "string"))
        );
    }

    private Map<String, Object> handleToolCall(Object id, Map<String, Object> request) {
        Map<String, Object> params = mapValue(request.get("params"));
        String name = String.valueOf(params.getOrDefault("name", ""));
        Map<String, Object> arguments = mapValue(params.get("arguments"));
        if ("send_message".equals(name)) {
            String text = String.valueOf(arguments.getOrDefault("text", ""));
            return success(id, Map.of("content", List.of(Map.of(
                    "type", "text",
                    "text", "Mock Feishu accepted message: " + text
            ))));
        }
        if ("search_docs".equals(name)) {
            String query = String.valueOf(arguments.getOrDefault("query", ""));
            return success(id, Map.of("content", List.of(Map.of(
                    "type", "text",
                    "text", "Mock Feishu docs result for: " + query
            ))));
        }
        return error(id, -32601, "Tool not found");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static Map<String, Object> success(Object id, Object result) {
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        return Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "error", Map.of("code", code, "message", message)
        );
    }
}

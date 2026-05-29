package com.example.mcpgateway.mcp;

import java.util.Map;

public record McpJsonRpcRequest(
        String jsonrpc,
        String id,
        String method,
        Map<String, Object> params
) {
    public static McpJsonRpcRequest of(String id, String method, Map<String, Object> params) {
        return new McpJsonRpcRequest("2.0", id, method, params == null ? Map.of() : params);
    }
}

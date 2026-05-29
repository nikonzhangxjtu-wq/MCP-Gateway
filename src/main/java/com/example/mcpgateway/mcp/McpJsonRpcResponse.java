package com.example.mcpgateway.mcp;

import java.util.Map;

public record McpJsonRpcResponse(
        String jsonrpc,
        Object id,
        Map<String, Object> result,
        Map<String, Object> error
) {
    public Map<String, Object> requireResult() {
        if (error != null && !error.isEmpty()) {
            Object message = error.getOrDefault("message", error.toString());
            throw new McpProtocolException("Downstream MCP error: " + message);
        }
        if (result == null) {
            throw new McpProtocolException("Downstream MCP response did not include result");
        }
        return result;
    }
}

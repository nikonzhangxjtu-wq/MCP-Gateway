package com.example.mcpgateway.mcp;

public class McpProtocolException extends RuntimeException {
    public McpProtocolException(String message) {
        super(message);
    }

    public McpProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}

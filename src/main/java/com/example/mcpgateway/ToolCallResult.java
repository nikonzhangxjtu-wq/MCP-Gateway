package com.example.mcpgateway;

public record ToolCallResult(boolean allowed, String content, String errorCode, String errorMessage) {
    public static ToolCallResult success(String content) {
        return new ToolCallResult(true, content, null, null);
    }

    public static ToolCallResult denied(String code, String message) {
        return new ToolCallResult(false, null, code, message);
    }
}

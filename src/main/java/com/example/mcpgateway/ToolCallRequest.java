package com.example.mcpgateway;

import java.util.Map;
// 工具调用请求：包含要调用哪个服务的哪个工具，以及输入参数
public record ToolCallRequest(String serviceId, String toolName, Map<String, Object> arguments) {
}

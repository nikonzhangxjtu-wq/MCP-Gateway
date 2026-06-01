package com.example.mcpgateway;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class McpJsonRpcHandler {
    private final GatewayRuntime runtime;

    public McpJsonRpcHandler(GatewayRuntime runtime) {
        this.runtime = runtime;
    }

    public Map<String, Object> handle(UserContext user, Map<String, Object> request) {
        Object id = request.getOrDefault("id", "");
        String method = String.valueOf(request.getOrDefault("method", ""));
        if ("initialize".equals(method)) {
            return success(id, Map.of(
                    "protocolVersion", "2025-03-26",
                    "serverInfo", Map.of("name", "java-mcp-gateway", "version", "0.1.0"),
                    "capabilities", Map.of("tools", Map.of("listChanged", true))
            ));
        }
        if ("notifications/initialized".equals(method)) {
            return success(id, Map.of());
        }
        if ("tools/list".equals(method)) {
            // Only catalog tools are exposed to the agent; downstream tools stay indexed behind the gateway.
            return success(id, Map.of("tools", toolList(runtime.catalogTools())));
        }
        if ("tools/call".equals(method)) {
            return handleToolCall(user, id, request);
        }
        return error(id, -32601, "Method not found");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolCall(UserContext user, Object id, Map<String, Object> request) {
        Map<String, Object> params = mapValue(request.get("params"));
        String toolName = String.valueOf(params.getOrDefault("name", ""));
        Map<String, Object> arguments = mapValue(params.get("arguments"));
        Object content;
        if ("search_mcp_services".equals(toolName)) {
            String query = String.valueOf(arguments.getOrDefault("query", ""));
            content = Map.of("services", runtime.searchServices(user, query).stream()
                    .map(summary -> serviceSummary(summary, runtime.listTools(user, summary.id())))
                    .toList());
        } else if ("describe_mcp_service".equals(toolName)) {
            String serviceId = String.valueOf(arguments.getOrDefault("service_id", ""));
            content = runtime.describeService(user, serviceId)
                    .<Object>map(summary -> serviceSummary(summary, runtime.listTools(user, summary.id())))
                    .orElse(Map.of("error", "service_not_found_or_forbidden"));
        } else if ("list_mcp_tools".equals(toolName)) {
            String serviceId = String.valueOf(arguments.getOrDefault("service_id", ""));
            content = Map.of(
                    "service_id", serviceId,
                    "tools", toolList(runtime.listTools(user, serviceId)),
                    "auth_status", runtime.authStatus(user, serviceId)
            );
        } else if ("get_auth_status".equals(toolName)) {
            String serviceId = String.valueOf(arguments.getOrDefault("service_id", ""));
            content = runtime.authStatus(user, serviceId);
        } else if ("get_credential_requirements".equals(toolName)) {
            String serviceId = String.valueOf(arguments.getOrDefault("service_id", ""));
            content = runtime.credentialRequirements(user, serviceId);
        } else if ("submit_mcp_credential".equals(toolName)) {
            String serviceId = String.valueOf(arguments.getOrDefault("service_id", ""));
            String credentialType = String.valueOf(arguments.getOrDefault("credential_type", ""));
            String credentialValue = String.valueOf(arguments.getOrDefault("credential_value", ""));
            ToolCallResult result = runtime.submitCredential(user, serviceId, credentialType, credentialValue);
            if (!result.allowed()) {
                return error(id, -32001, result.errorCode() + ": " + result.errorMessage());
            }
            content = result.content();
        } else if ("delete_mcp_credential".equals(toolName)) {
            String serviceId = String.valueOf(arguments.getOrDefault("service_id", ""));
            ToolCallResult result = runtime.deleteCredential(user, serviceId);
            if (!result.allowed()) {
                return error(id, -32001, result.errorCode() + ": " + result.errorMessage());
            }
            content = result.content();
        } else if ("refresh_mcp_service".equals(toolName)) {
            String serviceId = String.valueOf(arguments.getOrDefault("service_id", ""));
            ToolCallResult result = runtime.refreshService(user, serviceId);
            if (!result.allowed()) {
                return error(id, -32001, result.errorCode() + ": " + result.errorMessage());
            }
            content = result.content();
        } else if ("call_mcp_tool".equals(toolName)) {
            String serviceId = String.valueOf(arguments.getOrDefault("service_id", ""));
            String downstreamTool = String.valueOf(arguments.getOrDefault("tool_name", ""));
            ToolCallResult result = runtime.callTool(user, new ToolCallRequest(serviceId, downstreamTool, arguments));
            if (!result.allowed()) {
                return error(id, -32001, result.errorCode() + ": " + result.errorMessage());
            }
            content = result.content();
        } else {
            return error(id, -32601, "Catalog tool not found");
        }
        return success(id, Map.of("content", List.of(Map.of(
                "type", "text",
                "text", JsonSupport.string(content)
        ))));
    }

    static List<Map<String, Object>> toolList(List<ToolSchema> tools) {
        return tools.stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "inputSchema", normalizeInputSchema(tool.inputSchema())
                ))
                .toList();
    }

    private static Map<String, Object> normalizeInputSchema(Map<String, Object> inputSchema) {
        if ("object".equals(inputSchema.get("type")) && inputSchema.get("properties") instanceof Map<?, ?>) {
            return inputSchema;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        inputSchema.forEach((name, type) -> properties.put(name, schemaProperty(type)));
        return Map.of(
                "type", "object",
                "properties", properties
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaProperty(Object type) {
        if (type instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of("type", String.valueOf(type));
    }

    private static Map<String, Object> serviceSummary(ServiceSummary summary, List<ToolSchema> tools) {
        return Map.of(
                "id", summary.id(),
                "name", summary.name(),
                "description", summary.description(),
                "tags", summary.tags(),
                "requires_user_credential", summary.requiresUserCredential(),
                "tool_count", summary.toolCount(),
                "available", summary.available(),
                "last_error", summary.lastError(),
                "recommended_tools", toolList(tools.stream().limit(5).toList())
        );
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

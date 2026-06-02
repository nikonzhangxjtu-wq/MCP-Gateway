package com.example.mcpgateway;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SandboxMcpController {
    private final SandboxRuntime runtime;

    public SandboxMcpController(SandboxRuntime runtime) {
        this.runtime = runtime;
    }

    @PostMapping("/sandbox/mcp")
    public ResponseEntity<Map<String, Object>> mcp(@RequestBody Map<String, Object> request) {
        Object id = request.getOrDefault("id", "");
        String method = String.valueOf(request.getOrDefault("method", ""));
        if ("initialize".equals(method)) {
            return ResponseEntity.ok(success(id, Map.of(
                    "protocolVersion", "2025-03-26",
                    "serverInfo", Map.of("name", "sandbox-mcp", "version", "0.1.0"),
                    "capabilities", Map.of("tools", Map.of("listChanged", false))
            )));
        }
        if ("tools/list".equals(method)) {
            return ResponseEntity.ok(success(id, Map.of("tools", McpJsonRpcHandler.toolList(sandboxTools()))));
        }
        if ("tools/call".equals(method)) {
            return ResponseEntity.ok(handleToolCall(id, request));
        }
        return ResponseEntity.ok(error(id, -32601, "Method not found"));
    }

    static List<ToolSchema> sandboxTools() {
        return List.of(
                new ToolSchema("connect",
                        "Create or reuse a Docker sandbox for one agent run. Supported profiles: cpu-python, ubuntu-basic",
                        Map.of(
                                "tenant_id", "string",
                                "user_id", "string",
                                "agent_id", "string",
                                "run_id", "string",
                                "profile", "string",
                                "ttl_seconds", "integer"
                        )),
                new ToolSchema("disconnect",
                        "Stop and release the sandbox bound to one agent run",
                        Map.of("tenant_id", "string", "user_id", "string", "agent_id", "string", "run_id", "string")),
                new ToolSchema("status",
                        "Read sandbox state for one agent run",
                        Map.of("tenant_id", "string", "user_id", "string", "agent_id", "string", "run_id", "string"))
        );
    }

    private Map<String, Object> handleToolCall(Object id, Map<String, Object> request) {
        Map<String, Object> params = mapValue(request.get("params"));
        String name = String.valueOf(params.getOrDefault("name", ""));
        SandboxRequest sandboxRequest = sandboxRequest(mapValue(params.get("arguments")));
        try {
            SandboxSession session = switch (name) {
                case "connect" -> runtime.connect(sandboxRequest);
                case "disconnect" -> runtime.disconnect(sandboxRequest);
                case "status" -> runtime.status(sandboxRequest);
                default -> null;
            };
            if (session == null) {
                return error(id, -32601, "Tool not found");
            }
            return success(id, Map.of("content", List.of(Map.of(
                    "type", "text",
                    "text", JsonSupport.string(session.toMap())
            ))));
        } catch (RuntimeException error) {
            return error(id, -32001, error.getMessage());
        }
    }

    private SandboxRequest sandboxRequest(Map<String, Object> arguments) {
        return new SandboxRequest(
                stringValue(arguments, "tenant_id", "default"),
                stringValue(arguments, "user_id", "unknown"),
                stringValue(arguments, "agent_id", "unknown-agent"),
                stringValue(arguments, "run_id", "default-run"),
                stringValue(arguments, "profile", "cpu-python"),
                longValue(arguments.get("ttl_seconds"), 3600)
        );
    }

    private String stringValue(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
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

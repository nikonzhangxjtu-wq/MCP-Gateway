package com.example.mcpgateway.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.mcpgateway.Credential;
import com.example.mcpgateway.McpClient;
import com.example.mcpgateway.ToolSchema;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class StdioMcpClient implements McpClient, AutoCloseable {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String serviceId;
    private final String command;
    private final List<String> args;
    private final Map<String, String> environment;
    private final Path workingDirectory;
    private final Duration timeout;
    private StdioProcessTransport transport;
    private boolean initialized;
    private List<ToolSchema> cachedTools;

    public StdioMcpClient(
            String serviceId,
            String command,
            List<String> args,
            Map<String, String> environment,
            Path workingDirectory,
            Duration timeout
    ) {
        this.serviceId = serviceId;
        this.command = command;
        this.args = List.copyOf(args);
        this.environment = Map.copyOf(environment);
        this.workingDirectory = workingDirectory;
        this.timeout = timeout;
    }

    @Override
    public synchronized List<ToolSchema> listTools() {
        ensureInitialized();
        if (cachedTools != null) {
            return cachedTools;
        }
        McpJsonRpcResponse response = transport.request(McpJsonRpcRequest.of(
                nextId(),
                "tools/list",
                Map.of()
        ));
        Object toolsValue = response.requireResult().get("tools");
        if (!(toolsValue instanceof List<?> tools)) {
            cachedTools = List.of();
            return cachedTools;
        }
        cachedTools = tools.stream()
                .filter(Map.class::isInstance)
                .map(tool -> toToolSchema(castMap(tool)))
                .toList();
        return cachedTools;
    }

    @Override
    public synchronized String callTool(
            String serviceId,
            String toolName,
            Map<String, Object> arguments,
            Credential credential
    ) {
        ensureInitialized();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        McpJsonRpcResponse response = transport.request(McpJsonRpcRequest.of(
                nextId(),
                "tools/call",
                params
        ));
        return McpToolContentExtractor.extractText(response.requireResult());
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        if (transport == null) {
            transport = new StdioProcessTransport(objectMapper, command, args, environment, workingDirectory, timeout);
        }
        transport.request(McpJsonRpcRequest.of(
                nextId(),
                "initialize",
                Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "java-mcp-gateway", "version", "0.1.0")
                )
        )).requireResult();
        // MCP servers expect this notification before normal tool traffic begins.
        transport.notify("notifications/initialized", Map.of());
        initialized = true;
    }

    private ToolSchema toToolSchema(Map<String, Object> tool) {
        String name = String.valueOf(tool.getOrDefault("name", ""));
        String description = String.valueOf(tool.getOrDefault("description", ""));
        return new ToolSchema(name, description, inputSchema(tool.get("inputSchema")));
    }

    private Map<String, Object> inputSchema(Object inputSchemaValue) {
        if (!(inputSchemaValue instanceof Map<?, ?> inputSchema)) {
            return Map.of();
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        inputSchema.forEach((key, value) -> schema.put(String.valueOf(key), value));
        return schema;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private String nextId() {
        return serviceId + "-" + UUID.randomUUID();
    }

    @Override
    public synchronized void close() {
        if (transport != null) {
            transport.close();
            transport = null;
            initialized = false;
        }
    }
}

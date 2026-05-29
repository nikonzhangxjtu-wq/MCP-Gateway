package com.example.mcpgateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

public final class StreamableHttpMcpClient implements McpClient {
    private final String endpoint;
    private final RestTemplate restTemplate;
    private final List<ToolSchema> bootstrapTools;
    private boolean initialized;
    private List<ToolSchema> cachedTools;

    public StreamableHttpMcpClient(String endpoint, RestTemplate restTemplate, List<ToolSchema> bootstrapTools) {
        this.endpoint = endpoint;
        this.restTemplate = restTemplate;
        this.bootstrapTools = List.copyOf(bootstrapTools);
    }

    public StreamableHttpMcpClient(String endpoint, RestTemplate restTemplate) {
        this(endpoint, restTemplate, List.of());
    }

    @Override
    public synchronized List<ToolSchema> listTools() {
        return listTools(null);
    }

    @Override
    public synchronized List<ToolSchema> listTools(Credential credential) {
        if (!bootstrapTools.isEmpty()) {
            // Startup indexing for in-process test endpoints should not depend on the HTTP server accepting traffic.
            return bootstrapTools;
        }
        ensureInitialized(credential);
        if (cachedTools != null) {
            return cachedTools;
        }
        Map<String, Object> response = post(Map.of(
                "jsonrpc", "2.0",
                "id", nextId(),
                "method", "tools/list",
                "params", Map.of()
        ), credential);
        Object resultValue = response.get("result");
        if (!(resultValue instanceof Map<?, ?> result)) {
            cachedTools = List.of();
            return cachedTools;
        }
        Object toolsValue = result.get("tools");
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
    public synchronized String callTool(String serviceId, String toolName, Map<String, Object> arguments, Credential credential) {
        ensureInitialized(credential);
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "id", nextId(),
                "method", "tools/call",
                "params", Map.of(
                        "name", toolName,
                        "arguments", arguments
                )
        );
        Map<String, Object> response = post(request, credential);
        return extractContent(response);
    }

    private void ensureInitialized(Credential credential) {
        if (initialized) {
            return;
        }
        post(Map.of(
                "jsonrpc", "2.0",
                "id", nextId(),
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "java-mcp-gateway", "version", "0.1.0")
                )
        ), credential);
        initialized = true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(Map<String, Object> request, Credential credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        if (credential != null && !credential.value().isBlank() && !usesEndpointPlaceholder(credential)) {
            // The gateway swaps the agent identity for the user's downstream service credential.
            headers.setBearerAuth(credential.value());
        }

        return restTemplate.postForObject(
                resolveEndpoint(credential),
                new HttpEntity<>(request, headers),
                Map.class
        );
    }

    private String resolveEndpoint(Credential credential) {
        String resolved = endpoint;
        if (credential != null && endpoint.contains("{" + credential.type() + "}")) {
            resolved = endpoint.replace(
                    "{" + credential.type() + "}",
                    URLEncoder.encode(credential.value(), StandardCharsets.UTF_8)
            );
        }
        if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
            return resolved;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(resolved)
                .toUriString();
    }

    private boolean usesEndpointPlaceholder(Credential credential) {
        return endpoint.contains("{" + credential.type() + "}");
    }

    private String extractContent(Map<String, Object> response) {
        if (response == null) {
            return "";
        }
        Object error = response.get("error");
        if (error != null) {
            return "downstream_error: " + error;
        }
        Object resultValue = response.get("result");
        if (!(resultValue instanceof Map<?, ?> result)) {
            return "";
        }
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
        return "http-" + UUID.randomUUID();
    }
}

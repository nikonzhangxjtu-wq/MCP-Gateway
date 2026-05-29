package com.example.mcpgateway;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CapabilityIndex {
    private final Map<String, List<ToolSchema>> toolsByService = new HashMap<>();
    private final Map<String, Instant> syncedAtByService = new HashMap<>();
    private final Map<String, String> errorByService = new HashMap<>();

    public void index(ServiceDefinition service, McpClient client) {
        toolsByService.put(service.id(), List.copyOf(client.listTools()));
        syncedAtByService.put(service.id(), Instant.now());
        errorByService.remove(service.id());
    }

    public void index(ServiceDefinition service, McpClient client, Credential credential) {
        toolsByService.put(service.id(), List.copyOf(client.listTools(credential)));
        syncedAtByService.put(service.id(), Instant.now());
        errorByService.remove(service.id());
    }

    public void markAuthRequired(ServiceDefinition service) {
        toolsByService.put(service.id(), List.of());
        errorByService.put(service.id(), "auth_required");
    }

    public void markFailed(ServiceDefinition service, Exception error) {
        toolsByService.put(service.id(), List.of());
        errorByService.put(service.id(), error.getMessage());
    }

    public List<ToolSchema> toolsForService(String serviceId) {
        return toolsByService.getOrDefault(serviceId, List.of());
    }

    public Instant lastSyncedAt(String serviceId) {
        return syncedAtByService.get(serviceId);
    }

    public Optional<String> lastError(String serviceId) {
        return Optional.ofNullable(errorByService.get(serviceId));
    }

    public boolean available(String serviceId) {
        return !errorByService.containsKey(serviceId) || "auth_required".equals(errorByService.get(serviceId));
    }

    public boolean indexed(String serviceId) {
        return toolsByService.containsKey(serviceId) && !errorByService.containsKey(serviceId);
    }
}

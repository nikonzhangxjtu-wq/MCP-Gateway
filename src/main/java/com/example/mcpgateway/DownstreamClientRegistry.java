package com.example.mcpgateway;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class DownstreamClientRegistry {
    private final Map<String, McpClient> clients = new HashMap<>();

    public void register(String serviceId, McpClient client) {
        clients.put(serviceId, client);
    }

    public Optional<McpClient> get(String serviceId) {
        return Optional.ofNullable(clients.get(serviceId));
    }
}

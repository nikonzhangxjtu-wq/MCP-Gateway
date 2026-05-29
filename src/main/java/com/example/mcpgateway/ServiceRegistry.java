package com.example.mcpgateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ServiceRegistry {
    private final Map<String, ServiceDefinition> services = new LinkedHashMap<>();

    public void register(ServiceDefinition service) {
        services.put(service.id(), service);
    }

    public Optional<ServiceDefinition> get(String serviceId) {
        return Optional.ofNullable(services.get(serviceId));
    }

    public List<ServiceDefinition> list() {
        return new ArrayList<>(services.values());
    }
}

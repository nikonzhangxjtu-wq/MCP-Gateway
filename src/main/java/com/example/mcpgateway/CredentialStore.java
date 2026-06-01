package com.example.mcpgateway;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CredentialStore {
    private final Map<String, Credential> credentials = new HashMap<>();

    public void put(String userId, String tenantId, String serviceId, Credential credential) {
        credentials.put(key(userId, tenantId, serviceId), credential);
    }

    public Optional<Credential> get(String userId, String tenantId, String serviceId) {
        return Optional.ofNullable(credentials.get(key(userId, tenantId, serviceId)));
    }

    public void delete(String userId, String tenantId, String serviceId) {
        credentials.remove(key(userId, tenantId, serviceId));
    }

    protected Map<String, Credential> snapshot() {
        return Map.copyOf(credentials);
    }

    protected void replaceAll(Map<String, Credential> values) {
        credentials.clear();
        credentials.putAll(values);
    }

    private String key(String userId, String tenantId, String serviceId) {
        return tenantId + ":" + userId + ":" + serviceId;
    }
}

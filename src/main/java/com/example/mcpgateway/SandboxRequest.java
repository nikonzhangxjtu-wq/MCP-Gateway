package com.example.mcpgateway;

public record SandboxRequest(
        String tenantId,
        String userId,
        String agentId,
        String runId,
        String profile,
        long ttlSeconds
) {
    public String ownershipKey() {
        return tenantId + ":" + userId + ":" + agentId + ":" + runId;
    }
}

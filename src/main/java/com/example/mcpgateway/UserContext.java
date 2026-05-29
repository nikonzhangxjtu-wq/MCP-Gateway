package com.example.mcpgateway;

import java.util.List;

public record UserContext(String userId, String tenantId, String agentId, List<String> scopes) {
    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}

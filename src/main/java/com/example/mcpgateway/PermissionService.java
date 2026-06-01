package com.example.mcpgateway;

public final class PermissionService {
    public boolean canDiscover(UserContext user, String serviceId) {
        return user.hasScope("mcp:" + serviceId + ":discover");
    }

    public boolean canUseService(UserContext user, String serviceId) {
        return user.hasScope("mcp:" + serviceId + ":use");
    }

    public boolean canCallTool(UserContext user, String serviceId, String toolName) {
        return user.hasScope("mcp:" + serviceId + ":" + toolName)
                || user.hasScope("mcp:" + serviceId + ":*");
    }
}

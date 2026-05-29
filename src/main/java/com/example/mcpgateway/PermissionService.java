package com.example.mcpgateway;

public final class PermissionService {
    public boolean canDiscover(UserContext user, String serviceId) {
        return user.hasScope("mcp:" + serviceId + ":discover");
    }

    public boolean canUseService(UserContext user, String serviceId) {
        return user.hasScope("mcp:" + serviceId + ":use");
    }

    public boolean canCallTool(UserContext user, String serviceId, String toolName) {
        if ("github".equals(serviceId) && isWriteLike(toolName)) {
            return false;
        }
        return user.hasScope("mcp:" + serviceId + ":" + toolName)
                || user.hasScope("mcp:" + serviceId + ":*");
    }

    private boolean isWriteLike(String toolName) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("create")
                || normalized.contains("update")
                || normalized.contains("delete")
                || normalized.contains("merge")
                || normalized.contains("push")
                || normalized.contains("write");
    }
}

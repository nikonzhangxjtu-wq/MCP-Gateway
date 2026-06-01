package com.example.mcpgateway;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record SandboxSession(
        String sandboxId,
        String containerId,
        String state,
        boolean created,
        boolean reused,
        boolean disconnected,
        boolean released,
        String workspace,
        String profile,
        String image,
        Instant createdAt,
        Instant lastActiveAt
) {
    public static SandboxSession notCreated() {
        return new SandboxSession(null, null, "not_created", false, false, false, false,
                null, null, null, null, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sandbox_id", sandboxId);
        values.put("container_id", containerId);
        values.put("state", state);
        values.put("created", created);
        values.put("reused", reused);
        values.put("disconnected", disconnected);
        values.put("released", released);
        values.put("workspace", workspace);
        values.put("profile", profile);
        values.put("image", image);
        values.put("created_at", createdAt == null ? "" : createdAt.toString());
        values.put("last_active_at", lastActiveAt == null ? "" : lastActiveAt.toString());
        return values;
    }

    public SandboxSession asReused(Instant activeAt) {
        return new SandboxSession(sandboxId, containerId, state, false, true, false, false,
                workspace, profile, image, createdAt, activeAt);
    }

    public SandboxSession asStopped(Instant activeAt) {
        return new SandboxSession(sandboxId, containerId, "stopped", false, false, true, true,
                workspace, profile, image, createdAt, activeAt);
    }
}

package com.example.mcpgateway;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public final class InMemorySandboxContainerBackend implements SandboxContainerBackend {
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public SandboxSession create(SandboxRequest request) {
        int id = sequence.incrementAndGet();
        Instant now = Instant.now();
        String sandboxId = "sbx_" + id;
        return new SandboxSession(
                sandboxId,
                "docker_" + id,
                "running",
                true,
                false,
                false,
                false,
                "/workspace/" + sandboxId,
                request.profile(),
                imageFor(request.profile()),
                now,
                now
        );
    }

    @Override
    public SandboxSession stop(SandboxSession session) {
        return session.asStopped(Instant.now());
    }

    private String imageFor(String profile) {
        if (profile == null || profile.isBlank() || "cpu-python".equals(profile)) {
            return "python:3.11-slim";
        }
        if ("ubuntu-basic".equals(profile)) {
            return "ubuntu:22.04";
        }
        throw new IllegalArgumentException("Unsupported sandbox profile: " + profile);
    }
}

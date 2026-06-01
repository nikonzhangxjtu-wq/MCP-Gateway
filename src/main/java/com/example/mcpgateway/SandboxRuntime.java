package com.example.mcpgateway;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SandboxRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxRuntime.class);
    private final SandboxContainerBackend backend;
    private final Map<String, SandboxSession> sessions = new ConcurrentHashMap<>();

    public SandboxRuntime(SandboxContainerBackend backend) {
        this.backend = backend;
    }

    public SandboxSession connect(SandboxRequest request) {
        long started = System.nanoTime();
        try {
            SandboxSession session = sessions.compute(request.ownershipKey(), (key, existing) -> {
                if (existing != null && "running".equals(existing.state())) {
                    return existing.asReused(Instant.now());
                }
                return backend.create(request);
            });
            log("connect", request, session, started, true, "");
            return session;
        } catch (RuntimeException error) {
            log("connect", request, SandboxSession.notCreated(), started, false, error.getClass().getSimpleName());
            throw error;
        }
    }

    public SandboxSession disconnect(SandboxRequest request) {
        long started = System.nanoTime();
        SandboxSession existing = sessions.get(request.ownershipKey());
        if (existing == null) {
            SandboxSession notCreated = SandboxSession.notCreated();
            log("disconnect", request, notCreated, started, true, "not_created");
            return notCreated;
        }
        SandboxSession stopped = backend.stop(existing);
        sessions.put(request.ownershipKey(), stopped);
        log("disconnect", request, stopped, started, true, "");
        return stopped;
    }

    public SandboxSession status(SandboxRequest request) {
        long started = System.nanoTime();
        SandboxSession session = sessions.getOrDefault(request.ownershipKey(), SandboxSession.notCreated());
        log("status", request, session, started, true, "");
        return session;
    }

    private void log(String operation, SandboxRequest request, SandboxSession session, long startedNanos,
                     boolean success, String errorCode) {
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
        LOGGER.info(
                "sandbox operation={} tenant_id={} user_id={} agent_id={} run_id={} sandbox_id={} container_id={} duration_ms={} success={} error_code={}",
                operation,
                request.tenantId(),
                request.userId(),
                request.agentId(),
                request.runId(),
                session.sandboxId(),
                session.containerId(),
                durationMs,
                success,
                errorCode
        );
    }
}

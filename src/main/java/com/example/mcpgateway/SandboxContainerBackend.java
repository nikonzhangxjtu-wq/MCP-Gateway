package com.example.mcpgateway;

public interface SandboxContainerBackend {
    SandboxSession create(SandboxRequest request);

    SandboxSession stop(SandboxSession session);
}

package com.example.mcpgateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SandboxRuntimeTest {
    @Test
    void statusBeforeConnectReturnsNotCreated() {
        SandboxRuntime runtime = new SandboxRuntime(new InMemorySandboxContainerBackend());

        SandboxSession status = runtime.status(request("agent-a", "run-1"));

        assertThat(status.state()).isEqualTo("not_created");
        assertThat(status.sandboxId()).isNull();
    }

    @Test
    void firstConnectCreatesSandboxAndSecondConnectReusesIt() {
        SandboxRuntime runtime = new SandboxRuntime(new InMemorySandboxContainerBackend());
        SandboxRequest request = request("agent-a", "run-1");

        SandboxSession first = runtime.connect(request);
        SandboxSession second = runtime.connect(request);

        assertThat(first.state()).isEqualTo("running");
        assertThat(first.created()).isTrue();
        assertThat(first.reused()).isFalse();
        assertThat(second.sandboxId()).isEqualTo(first.sandboxId());
        assertThat(second.containerId()).isEqualTo(first.containerId());
        assertThat(second.created()).isFalse();
        assertThat(second.reused()).isTrue();
    }

    @Test
    void differentAgentRunsReceiveDifferentContainersFromTheSameProfileImage() {
        SandboxRuntime runtime = new SandboxRuntime(new InMemorySandboxContainerBackend());

        SandboxSession first = runtime.connect(request("agent-a", "run-1"));
        SandboxSession second = runtime.connect(request("agent-a", "run-2"));

        assertThat(second.sandboxId()).isNotEqualTo(first.sandboxId());
        assertThat(second.containerId()).isNotEqualTo(first.containerId());
        assertThat(second.image()).isEqualTo(first.image()).isEqualTo("python:3.11-slim");
        assertThat(second.workspace()).isNotEqualTo(first.workspace());
    }

    @Test
    void disconnectStopsAndReleasesOwnedSandbox() {
        SandboxRuntime runtime = new SandboxRuntime(new InMemorySandboxContainerBackend());
        SandboxRequest request = request("agent-a", "run-1");
        SandboxSession connected = runtime.connect(request);

        SandboxSession disconnected = runtime.disconnect(request);
        SandboxSession status = runtime.status(request);

        assertThat(disconnected.sandboxId()).isEqualTo(connected.sandboxId());
        assertThat(disconnected.state()).isEqualTo("stopped");
        assertThat(disconnected.disconnected()).isTrue();
        assertThat(disconnected.released()).isTrue();
        assertThat(status.state()).isEqualTo("stopped");
    }

    private SandboxRequest request(String agentId, String runId) {
        return new SandboxRequest("default", "alice", agentId, runId, "cpu-python", 3600);
    }
}

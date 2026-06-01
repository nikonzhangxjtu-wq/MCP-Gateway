package com.example.mcpgateway;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class DockerCliSandboxContainerBackend implements SandboxContainerBackend {
    @Override
    public SandboxSession create(SandboxRequest request) {
        String sandboxId = "sbx_" + sanitize(request.agentId()) + "_" + sanitize(request.runId());
        String containerName = "mcp_" + sandboxId;
        String image = imageFor(request.profile());
        run(List.of(
                "docker", "run", "-d",
                "--name", containerName,
                "--cpus", "1",
                "--memory", "512m",
                "-e", "AGENT_ID=" + request.agentId(),
                "-e", "RUN_ID=" + request.runId(),
                image,
                "sleep", "infinity"
        ));
        Instant now = Instant.now();
        return new SandboxSession(
                sandboxId,
                containerName,
                "running",
                true,
                false,
                false,
                false,
                "/workspace",
                request.profile(),
                image,
                now,
                now
        );
    }

    @Override
    public SandboxSession stop(SandboxSession session) {
        run(List.of("docker", "rm", "-f", session.containerId()));
        return session.asStopped(Instant.now());
    }

    private void run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Docker command timed out: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IllegalStateException("Docker command failed: " + output);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Docker CLI is not available", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Docker command was interrupted", error);
        }
    }

    private String imageFor(String profile) {
        if (profile == null || profile.isBlank() || "cpu-python".equals(profile)) {
            return "python:3.11-slim";
        }
        throw new IllegalArgumentException("Unsupported sandbox profile: " + profile);
    }

    private String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}

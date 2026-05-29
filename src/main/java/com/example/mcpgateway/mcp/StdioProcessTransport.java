package com.example.mcpgateway.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class StdioProcessTransport implements AutoCloseable {
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final StringBuilder stderr = new StringBuilder();

    public StdioProcessTransport(
            ObjectMapper objectMapper,
            String command,
            List<String> args,
            Map<String, String> environment,
            Path workingDirectory,
            Duration timeout
    ) {
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(commandLine);
            builder.directory(workingDirectory.toFile());
            builder.environment().putAll(environment);
            this.process = builder.start();
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            startStderrCapture();
        } catch (IOException error) {
            throw new McpProtocolException("Failed to start stdio MCP server: " + commandLine, error);
        }
    }

    public synchronized McpJsonRpcResponse request(McpJsonRpcRequest request) {
        send(request);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String line = readLine(Duration.ofNanos(deadline - System.nanoTime()));
            if (line.isBlank()) {
                continue;
            }
            try {
                McpJsonRpcResponse response = objectMapper.readValue(line, McpJsonRpcResponse.class);
                if (request.id().equals(String.valueOf(response.id()))) {
                    return response;
                }
            } catch (IOException error) {
                throw new McpProtocolException("Failed to parse downstream MCP response: " + line, error);
            }
        }
        throw new McpProtocolException("Timed out waiting for downstream MCP response id="
                + request.id() + ". stderr=" + stderr);
    }

    public synchronized void notify(String method, Map<String, Object> params) {
        send(Map.of("jsonrpc", "2.0", "method", method, "params", params));
    }

    private void send(Object message) {
        ensureAlive();
        try {
            writer.write(objectMapper.writeValueAsString(message));
            writer.newLine();
            writer.flush();
        } catch (IOException error) {
            throw new McpProtocolException("Failed to write downstream MCP request", error);
        }
    }

    private String readLine(Duration readTimeout) {
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return reader.readLine();
                } catch (IOException error) {
                    throw new McpProtocolException("Failed to read downstream MCP response", error);
                }
            });
            String line = future.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (line == null) {
                throw new McpProtocolException("Downstream MCP server closed stdout. stderr=" + stderr);
            }
            return line;
        } catch (TimeoutException error) {
            throw new McpProtocolException("Timed out waiting for downstream MCP response. stderr=" + stderr, error);
        } catch (McpProtocolException error) {
            throw error;
        } catch (Exception error) {
            throw new McpProtocolException("Failed to read downstream MCP response", error);
        }
    }

    private void ensureAlive() {
        if (!process.isAlive()) {
            throw new McpProtocolException("Downstream MCP server is not running. exit="
                    + process.exitValue() + " stderr=" + stderr);
        }
    }

    private void startStderrCapture() {
        Thread thread = new Thread(() -> {
            try (BufferedReader stderrReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stderrReader.readLine()) != null) {
                    synchronized (stderr) {
                        if (stderr.length() < 8000) {
                            stderr.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
                // Stderr capture is diagnostic-only and must not break the transport.
            }
        }, "mcp-stdio-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}

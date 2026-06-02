package com.example.mcpgateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StdioProcessTransportTest {
    @Test
    void closesProcessWhenReadTimesOut() {
        StdioProcessTransport transport = new StdioProcessTransport(
                new ObjectMapper(),
                "python3",
                List.of("-c", """
                        import sys, time
                        sys.stdin.readline()
                        time.sleep(30)
                        """),
                Map.of(),
                Path.of(".").toAbsolutePath(),
                Duration.ofMillis(50)
        );

        assertThatThrownBy(() -> transport.request(McpJsonRpcRequest.of("1", "tools/list", Map.of())))
                .isInstanceOf(McpProtocolException.class)
                .hasMessageContaining("Timed out waiting for downstream MCP response");
        assertThat(transport.isRunning()).isFalse();
    }
}

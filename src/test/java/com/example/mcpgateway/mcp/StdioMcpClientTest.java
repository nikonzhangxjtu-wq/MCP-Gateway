package com.example.mcpgateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.mcpgateway.Credential;
import com.example.mcpgateway.ToolSchema;
import org.junit.jupiter.api.Test;

class StdioMcpClientTest {
    @Test
    void initializesListsToolsAndCallsTool() {
        Path server = Path.of("src/test/resources/fake-stdio-mcp-server.py").toAbsolutePath();
        StdioMcpClient client = new StdioMcpClient(
                "fake",
                "python3",
                List.of(server.toString()),
                Map.of(),
                Path.of(".").toAbsolutePath(),
                Duration.ofSeconds(5)
        );

        try {
            List<ToolSchema> tools = client.listTools();
            assertThat(tools).extracting(ToolSchema::name).containsExactly("echo");

            String result = client.callTool("fake", "echo", Map.of("text", "hello"), (Credential) null);
            assertThat(result).isEqualTo("echo:hello");
        } finally {
            client.close();
        }
    }
}

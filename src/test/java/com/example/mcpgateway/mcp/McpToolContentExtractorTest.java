package com.example.mcpgateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class McpToolContentExtractorTest {
    @Test
    void extractsFirstTextContent() {
        Map<String, Object> result = Map.of("content", List.of(Map.of(
                "type", "text",
                "text", "hello from downstream"
        )));

        assertThat(McpToolContentExtractor.extractText(result)).isEqualTo("hello from downstream");
    }

    @Test
    void fallsBackToResultStringWhenContentIsMissing() {
        Map<String, Object> result = Map.of("value", "raw-result");

        assertThat(McpToolContentExtractor.extractText(result)).contains("raw-result");
    }

    @Test
    void raisesProtocolExceptionForDownstreamError() {
        McpJsonRpcResponse response = new McpJsonRpcResponse(
                "2.0",
                "1",
                null,
                Map.of("code", -32601, "message", "Tool not found")
        );

        assertThatThrownBy(response::requireResult)
                .isInstanceOf(McpProtocolException.class)
                .hasMessageContaining("Tool not found");
    }
}

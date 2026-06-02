package com.example.mcpgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class StreamableHttpMcpClientTest {
    @Test
    void discoversRemoteToolsAndForwardsToolCalls() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        StreamableHttpMcpClient client = new StreamableHttpMcpClient("https://remote.example/mcp", restTemplate);

        server.expect(requestTo("https://remote.example/mcp"))
                .andExpect(jsonPath("$.method").value("initialize"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":"init","result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://remote.example/mcp"))
                .andExpect(jsonPath("$.method").value("tools/list"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":"list","result":{"tools":[
                          {"name":"maps_weather","description":"Get weather","inputSchema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://remote.example/mcp"))
                .andExpect(jsonPath("$.method").value("tools/call"))
                .andExpect(jsonPath("$.params.name").value("maps_weather"))
                .andExpect(jsonPath("$.params.arguments.city").value("北京"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":"call","result":{"content":[{"type":"text","text":"sunny"}],"isError":false}}
                        """, MediaType.APPLICATION_JSON));

        List<ToolSchema> tools = client.listTools();
        String result = client.callTool("amap", "maps_weather", Map.of("city", "北京"), null);

        assertThat(tools).extracting(ToolSchema::name).containsExactly("maps_weather");
        assertThat(tools.get(0).inputSchema()).containsEntry("type", "object");
        assertThat(tools.get(0).inputSchema().toString()).contains("city").contains("required");
        assertThat(result).isEqualTo("sunny");
        server.verify();
    }

    @Test
    void injectsApiKeyCredentialIntoEndpointTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                "https://mcp.amap.com/mcp?key={api_key}",
                restTemplate
        );

        server.expect(requestTo("https://mcp.amap.com/mcp?key=secret-key"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$.method").value("initialize"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":"init","result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://mcp.amap.com/mcp?key=secret-key"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$.method").value("tools/list"))
                .andRespond(withSuccess("""
                        {"jsonrpc":"2.0","id":"list","result":{"tools":[]}}
                        """, MediaType.APPLICATION_JSON));

        client.listTools(new Credential("api_key", "secret-key"));

        server.verify();
    }

    @Test
    void createsRestTemplateWithConfiguredTimeouts() throws Exception {
        RestTemplate restTemplate = GatewayConfiguration.restTemplateWithTimeout(1234);

        ClientHttpRequestFactory requestFactory = restTemplate.getRequestFactory();
        assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(readIntField(requestFactory, "connectTimeout")).isEqualTo(1234);
        assertThat(readIntField(requestFactory, "readTimeout")).isEqualTo(1234);
    }

    private int readIntField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }
}

package com.example.mcpgateway;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.mcpgateway.mcp.StdioMcpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(McpServiceCatalogProperties.class)
public class GatewayConfiguration {
    @Bean
    RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    GatewayRuntime gatewayRuntime(RestTemplate restTemplate, McpServiceCatalogProperties properties) {
        List<ToolSchema> feishuTools = MockFeishuMcpController.feishuTools();
        DownstreamClientRegistry downstreamClients = new DownstreamClientRegistry();
        downstreamClients.register("feishu", new StreamableHttpMcpClient(
                "/mock/feishu/mcp",
                restTemplate,
                feishuTools
        ));

        GatewayRuntime runtime = GatewayRuntime.createDefault(downstreamClients, EncryptedFileCredentialStore.defaultStore());
        runtime.registerService(ServiceDefinition.streamableHttp(
                "feishu",
                "Feishu MCP",
                "Mock Feishu collaboration service for local MCP gateway validation",
                List.of("feishu", "messaging", "docs"),
                "/mock/feishu/mcp",
                true
        ));
        runtime.credentials().put("alice", "default", "feishu",
                new Credential("bearer", "mock-feishu-user-token"));
        registerConfiguredServices(properties, downstreamClients, runtime, restTemplate);
        return runtime;
    }

    @Bean
    McpJsonRpcHandler mcpJsonRpcHandler(GatewayRuntime runtime) {
        return new McpJsonRpcHandler(runtime);
    }

    private void registerConfiguredServices(
            McpServiceCatalogProperties properties,
            DownstreamClientRegistry downstreamClients,
            GatewayRuntime runtime,
            RestTemplate restTemplate
    ) {
        for (McpServiceCatalogProperties.ServiceConfig service : properties.getServices()) {
            if (!service.isEnabled()) {
                continue;
            }
            if ("streamable-http".equals(service.getTransport())) {
                downstreamClients.register(service.getId(), new StreamableHttpMcpClient(
                        service.getUrl(),
                        restTemplate
                ));
                runtime.registerService(ServiceDefinition.streamableHttp(
                        service.getId(),
                        service.getName(),
                        service.getDescription(),
                        service.getTags(),
                        service.getUrl(),
                        service.isRequiresUserCredential(),
                        credentialRequirements(service)
                ));
                continue;
            }
            if (!"stdio".equals(service.getTransport())) {
                continue;
            }
            Map<String, String> env = service.getEnv() == null ? Map.of() : service.getEnv();
            downstreamClients.register(service.getId(), new StdioMcpClient(
                    service.getId(),
                    service.getCommand(),
                    service.getArgs(),
                    env,
                    Path.of(service.getWorkingDirectory()).toAbsolutePath(),
                    Duration.ofMillis(service.getTimeoutMs())
            ));
            runtime.registerService(ServiceDefinition.stdio(
                    service.getId(),
                    service.getName(),
                    service.getDescription(),
                    service.getTags(),
                    service.getCommand(),
                    service.getArgs(),
                    env,
                    service.getWorkingDirectory(),
                    service.getTimeoutMs(),
                    service.isRequiresUserCredential()
            ));
        }
    }

    private List<CredentialRequirement> credentialRequirements(McpServiceCatalogProperties.ServiceConfig service) {
        return service.getCredentialRequirements().stream()
                .map(requirement -> new CredentialRequirement(
                        requirement.getName(),
                        requirement.getDescription(),
                        requirement.isSecret()
                ))
                .toList();
    }
}

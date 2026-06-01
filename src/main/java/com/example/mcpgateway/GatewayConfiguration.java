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
    SandboxRuntime sandboxRuntime() {
        String backend = System.getenv().getOrDefault("SANDBOX_BACKEND", "in-memory");
        SandboxContainerBackend containerBackend = "docker-cli".equals(backend)
                ? new DockerCliSandboxContainerBackend()
                : new InMemorySandboxContainerBackend();
        return new SandboxRuntime(containerBackend);
    }

    @Bean
    GatewayRuntime gatewayRuntime(RestTemplate restTemplate, McpServiceCatalogProperties properties) {
        DownstreamClientRegistry downstreamClients = new DownstreamClientRegistry();
        GatewayRuntime runtime = GatewayRuntime.createDefault(downstreamClients, EncryptedFileCredentialStore.defaultStore());
        registerConfiguredServices(properties, downstreamClients, runtime, restTemplate);
        registerDefaultCredentials(properties, runtime);
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
                        restTemplate,
                        bootstrapTools(service)
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

    private void registerDefaultCredentials(McpServiceCatalogProperties properties, GatewayRuntime runtime) {
        for (McpServiceCatalogProperties.ServiceConfig service : properties.getServices()) {
            for (McpServiceCatalogProperties.DefaultCredentialConfig credential : service.getDefaultCredentials()) {
                if (credential.getValue() == null || credential.getValue().isBlank()) {
                    continue;
                }
                runtime.credentials().put(
                        credential.getUserId(),
                        credential.getTenantId(),
                        service.getId(),
                        new Credential(credential.getType(), credential.getValue())
                );
            }
        }
    }

    private List<ToolSchema> bootstrapTools(McpServiceCatalogProperties.ServiceConfig service) {
        if (service.getBootstrapTools() == null || service.getBootstrapTools().isBlank()) {
            return List.of();
        }
        if ("feishu".equals(service.getBootstrapTools())) {
            return MockFeishuMcpController.feishuTools();
        }
        if ("sandbox".equals(service.getBootstrapTools())) {
            return SandboxMcpController.sandboxTools();
        }
        throw new IllegalArgumentException("Unknown bootstrapTools provider: " + service.getBootstrapTools());
    }
}

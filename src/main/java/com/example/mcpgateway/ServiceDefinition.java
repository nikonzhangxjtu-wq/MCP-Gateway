package com.example.mcpgateway;

import java.util.List;
import java.util.Map;

public record ServiceDefinition(
        String id,
        String name,
        String description,
        List<String> tags,
        String transport,
        Map<String, Object> clientConfig,
        boolean requiresUserCredential,
        List<CredentialRequirement> credentialRequirements
) {
    public static ServiceDefinition streamableHttp(
            String id,
            String name,
            String description,
            List<String> tags,
            String url,
            boolean requiresUserCredential
    ) {
        return streamableHttp(id, name, description, tags, url, requiresUserCredential, List.of());
    }

    public static ServiceDefinition streamableHttp(
            String id,
            String name,
            String description,
            List<String> tags,
            String url,
            boolean requiresUserCredential,
            List<CredentialRequirement> credentialRequirements
    ) {
        return new ServiceDefinition(
                id,
                name,
                description,
                tags,
                "streamable-http",
                Map.of("url", url),
                requiresUserCredential,
                List.copyOf(credentialRequirements)
        );
    }

    public static ServiceDefinition stdio(
            String id,
            String name,
            String description,
            List<String> tags,
            String command,
            List<String> args,
            boolean requiresUserCredential
    ) {
        return stdio(id, name, description, tags, command, args, Map.of(), ".", 10000, requiresUserCredential);
    }

    public static ServiceDefinition stdio(
            String id,
            String name,
            String description,
            List<String> tags,
            String command,
            List<String> args,
            Map<String, String> env,
            String workingDirectory,
            long timeoutMs,
            boolean requiresUserCredential
    ) {
        return new ServiceDefinition(
                id,
                name,
                description,
                tags,
                "stdio",
                Map.of(
                        "command", command,
                        "args", args,
                        "env", env,
                        "workingDirectory", workingDirectory,
                        "timeoutMs", timeoutMs
                ),
                requiresUserCredential,
                List.of()
        );
    }
}

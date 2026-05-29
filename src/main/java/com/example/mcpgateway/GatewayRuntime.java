package com.example.mcpgateway;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class GatewayRuntime {
    private final ServiceRegistry services;
    private final CapabilityIndex capabilities;
    private final CredentialStore credentials;
    private final PermissionService permissions;
    private final DownstreamClientRegistry downstreamClients;

    public GatewayRuntime(
            ServiceRegistry services,
            CapabilityIndex capabilities,
            CredentialStore credentials,
            PermissionService permissions,
            DownstreamClientRegistry downstreamClients
    ) {
        this.services = services;
        this.capabilities = capabilities;
        this.credentials = credentials;
        this.permissions = permissions;
        this.downstreamClients = downstreamClients;
    }

    public static GatewayRuntime createDefault(DownstreamClientRegistry downstreamClients) {
        return createDefault(downstreamClients, new CredentialStore());
    }

    public static GatewayRuntime createDefault(DownstreamClientRegistry downstreamClients, CredentialStore credentialStore) {
        return new GatewayRuntime(
                new ServiceRegistry(),
                new CapabilityIndex(),
                credentialStore,
                new PermissionService(),
                downstreamClients
        );
    }

    public void registerService(ServiceDefinition service) {
        McpClient client = downstreamClients.get(service.id())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No downstream MCP client for service: " + service.id()));
        services.register(service);
        if (service.requiresUserCredential() && !service.credentialRequirements().isEmpty()) {
            capabilities.markAuthRequired(service);
            return;
        }
        try {
            capabilities.index(service, client);
        } catch (RuntimeException error) {
            capabilities.markFailed(service, error);
        }
    }

    public CapabilityIndex capabilities() {
        return capabilities;
    }

    public CredentialStore credentials() {
        return credentials;
    }

    public List<ToolSchema> catalogTools() {
        return List.of(
                new ToolSchema("search_mcp_services",
                        "Search MCP services available to the current agent",
                        java.util.Map.of("query", "string")),
                new ToolSchema("describe_mcp_service",
                        "Describe one MCP service without exposing all tools up front",
                        java.util.Map.of("service_id", "string")),
                new ToolSchema("list_mcp_tools",
                        "List tools for one selected MCP service",
                        java.util.Map.of("service_id", "string")),
                new ToolSchema("get_auth_status",
                        "Check whether the current user has credentials for a service",
                        java.util.Map.of("service_id", "string")),
                new ToolSchema("get_credential_requirements",
                        "Describe credentials required by one MCP service",
                        java.util.Map.of("service_id", "string")),
                new ToolSchema("submit_mcp_credential",
                        "Submit a user credential for one MCP service",
                        java.util.Map.of("service_id", "string", "credential_type", "string", "credential_value", "string")),
                new ToolSchema("delete_mcp_credential",
                        "Delete the current user's credential for one MCP service",
                        java.util.Map.of("service_id", "string")),
                new ToolSchema("refresh_mcp_service",
                        "Refresh one MCP service capability index after credentials are configured",
                        java.util.Map.of("service_id", "string")),
                new ToolSchema("call_mcp_tool",
                        "Call a tool on a selected downstream MCP service",
                        java.util.Map.of("service_id", "string", "tool_name", "string"))
        );
    }

    public List<ServiceSummary> searchServices(UserContext user, String query) {
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return services.list().stream()
                .filter(service -> permissions.canDiscover(user, service.id()))
                .filter(service -> matches(service, normalizedQuery))
                .map(this::summaryFor)
                .sorted(Comparator.comparing(ServiceSummary::id))
                .toList();
    }

    public Optional<ServiceSummary> describeService(UserContext user, String serviceId) {
        return services.get(serviceId)
                .filter(service -> permissions.canDiscover(user, service.id()))
                .map(this::summaryFor);
    }

    public List<ToolSchema> listTools(UserContext user, String serviceId) {
        if (!permissions.canDiscover(user, serviceId)
                || !permissions.canUseService(user, serviceId)) {
            return List.of();
        }
        return capabilities.toolsForService(serviceId);
    }

    public Map<String, Object> authStatus(UserContext user, String serviceId) {
        Optional<ServiceDefinition> service = services.get(serviceId);
        boolean hasCredential = credentials.get(user.userId(), user.tenantId(), serviceId).isPresent();
        boolean requiresUserCredential = service
                .map(ServiceDefinition::requiresUserCredential)
                .orElse(true);
        boolean indexed = capabilities.indexed(serviceId);
        boolean callable = service.isPresent()
                && permissions.canUseService(user, serviceId)
                && (!requiresUserCredential || hasCredential)
                && indexed;
        return Map.of(
                "service_id", serviceId,
                "requires_user_credential", requiresUserCredential,
                "has_credential", hasCredential,
                "callable", callable,
                "indexed", indexed,
                "last_error", capabilities.lastError(serviceId).orElse(""),
                "next_action", nextAuthAction(requiresUserCredential, hasCredential, indexed)
        );
    }

    public Map<String, Object> operationalStatus() {
        List<Map<String, Object>> serviceStatuses = services.list().stream()
                .sorted(Comparator.comparing(ServiceDefinition::id))
                .map(this::serviceOperationalStatus)
                .toList();
        long indexedCount = serviceStatuses.stream()
                .filter(status -> Boolean.TRUE.equals(status.get("indexed")))
                .count();
        long authRequiredCount = serviceStatuses.stream()
                .filter(status -> "auth_required".equals(status.get("state")))
                .count();
        long unavailableCount = serviceStatuses.stream()
                .filter(status -> "unavailable".equals(status.get("state")))
                .count();
        return Map.of(
                "name", "java-mcp-gateway",
                "service_count", serviceStatuses.size(),
                "indexed_count", indexedCount,
                "auth_required_count", authRequiredCount,
                "unavailable_count", unavailableCount,
                "catalog_tool_count", catalogTools().size(),
                "services", serviceStatuses
        );
    }

    public Map<String, Object> credentialRequirements(UserContext user, String serviceId) {
        Optional<ServiceDefinition> service = services.get(serviceId);
        if (service.isEmpty() || !permissions.canDiscover(user, serviceId)) {
            return Map.of("service_id", serviceId, "requirements", List.of());
        }
        return Map.of(
                "service_id", serviceId,
                "requirements", service.get().credentialRequirements().stream()
                        .map(requirement -> Map.of(
                                "name", requirement.name(),
                                "description", requirement.description(),
                                "secret", requirement.secret()
                        ))
                        .toList()
        );
    }

    public ToolCallResult submitCredential(UserContext user, String serviceId, String credentialType, String credentialValue) {
        Optional<ServiceDefinition> service = services.get(serviceId);
        if (service.isEmpty()) {
            return ToolCallResult.denied("service_not_found", "MCP service is not registered");
        }
        if (!permissions.canUseService(user, serviceId)) {
            return ToolCallResult.denied("permission_denied", "User cannot configure this MCP service");
        }
        boolean allowedType = service.get().credentialRequirements().stream()
                .anyMatch(requirement -> requirement.name().equals(credentialType));
        if (!allowedType) {
            return ToolCallResult.denied("invalid_credential_type", "Credential type is not required by this service");
        }
        credentials.put(user.userId(), user.tenantId(), serviceId, new Credential(credentialType, credentialValue));
        return ToolCallResult.success(Map.of(
                "service_id", serviceId,
                "credential_type", credentialType,
                "stored", true,
                "masked", mask(credentialValue),
                "next_action", "refresh_mcp_service"
        ));
    }

    public ToolCallResult deleteCredential(UserContext user, String serviceId) {
        if (!permissions.canUseService(user, serviceId)) {
            return ToolCallResult.denied("permission_denied", "User cannot configure this MCP service");
        }
        credentials.delete(user.userId(), user.tenantId(), serviceId);
        services.get(serviceId)
                .filter(service -> service.requiresUserCredential() && !service.credentialRequirements().isEmpty())
                .ifPresent(capabilities::markAuthRequired);
        return ToolCallResult.success(Map.of(
                "service_id", serviceId,
                "deleted", true,
                "next_action", "submit_mcp_credential"
        ));
    }

    public ToolCallResult refreshService(UserContext user, String serviceId) {
        Optional<ServiceDefinition> service = services.get(serviceId);
        if (service.isEmpty()) {
            return ToolCallResult.denied("service_not_found", "MCP service is not registered");
        }
        if (!permissions.canUseService(user, serviceId)) {
            return ToolCallResult.denied("permission_denied", "User cannot refresh this MCP service");
        }
        Optional<Credential> credential = credentials.get(user.userId(), user.tenantId(), serviceId);
        if (service.get().requiresUserCredential() && credential.isEmpty()) {
            return ToolCallResult.denied("auth_required", "User credential is required for this MCP service");
        }
        McpClient client = downstreamClients.get(serviceId)
                .orElseThrow(() -> new IllegalStateException("No downstream MCP client for service: " + serviceId));
        try {
            capabilities.index(service.get(), client, credential.orElse(null));
            return ToolCallResult.success(Map.of(
                    "service_id", serviceId,
                    "indexed", true,
                    "tool_count", capabilities.toolsForService(serviceId).size(),
                    "next_action", "list_mcp_tools"
            ));
        } catch (RuntimeException error) {
            capabilities.markFailed(service.get(), error);
            return ToolCallResult.denied("downstream_error", error.getMessage());
        }
    }

    public ToolCallResult callTool(UserContext user, ToolCallRequest request) {
        Optional<ServiceDefinition> service = services.get(request.serviceId());
        if (service.isEmpty()) {
            return ToolCallResult.denied("service_not_found",
                    "MCP service is not registered");
        }
        // The gateway checks service and tool scopes before any downstream traffic is sent.
        if (!permissions.canUseService(user, request.serviceId())
                || !permissions.canCallTool(user, request.serviceId(), request.toolName())) {
            return ToolCallResult.denied("permission_denied",
                    "User cannot call this MCP tool");
        }

        Optional<Credential> credential = credentials.get(
                user.userId(), user.tenantId(), request.serviceId());
        if (service.get().requiresUserCredential() && credential.isEmpty()) {
            return ToolCallResult.denied("auth_required",
                    "User credential is required for this MCP service");
        }
        if (!capabilities.available(request.serviceId())) {
            return ToolCallResult.denied("service_unavailable",
                    capabilities.lastError(request.serviceId()).orElse("MCP service is unavailable"));
        }
        if (!capabilities.indexed(request.serviceId())) {
            return ToolCallResult.denied("service_not_indexed",
                    "Refresh this MCP service after configuring credentials");
        }

        McpClient client = downstreamClients.get(request.serviceId())
                .orElseThrow(() -> new IllegalStateException(
                        "No downstream MCP client for service: " + request.serviceId()));
        try {
            String content = client.callTool(
                    request.serviceId(), resolveToolName(request.serviceId(), request.toolName()),
                    downstreamArguments(request.arguments()), credential.orElse(null));
            return ToolCallResult.success(content);
        } catch (RuntimeException error) {
            return ToolCallResult.denied("downstream_error", error.getMessage());
        }
    }

    private Map<String, Object> downstreamArguments(Map<String, Object> arguments) {
        Map<String, Object> downstream = new LinkedHashMap<>(arguments);
        downstream.remove("service_id");
        downstream.remove("serviceId");
        downstream.remove("tool_name");
        downstream.remove("toolName");
        return downstream;
    }

    private String resolveToolName(String serviceId, String requestedToolName) {
        List<ToolSchema> tools = capabilities.toolsForService(serviceId);
        String normalizedRequested = normalize(requestedToolName);
        Optional<String> exact = tools.stream()
                .map(ToolSchema::name)
                .filter(toolName -> normalize(toolName).equals(normalizedRequested))
                .findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }
        List<String> suffixMatches = tools.stream()
                .map(ToolSchema::name)
                .filter(toolName -> normalize(toolName).endsWith("_" + normalizedRequested))
                .toList();
        return suffixMatches.size() == 1 ? suffixMatches.get(0) : requestedToolName;
    }

    private String nextAuthAction(boolean requiresUserCredential, boolean hasCredential, boolean indexed) {
        if (requiresUserCredential && !hasCredential) {
            return "submit_mcp_credential";
        }
        if (!indexed) {
            return "refresh_mcp_service";
        }
        return "call_mcp_tool";
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int visible = Math.min(4, value.length());
        return "****" + value.substring(value.length() - visible);
    }

    private ServiceSummary summaryFor(ServiceDefinition service) {
        return new ServiceSummary(
                service.id(),
                service.name(),
                service.description(),
                service.tags(),
                service.requiresUserCredential(),
                capabilities.toolsForService(service.id()).size(),
                capabilities.available(service.id()),
                capabilities.lastError(service.id()).orElse("")
        );
    }

    private Map<String, Object> serviceOperationalStatus(ServiceDefinition service) {
        Map<String, Object> status = new LinkedHashMap<>();
        String lastError = capabilities.lastError(service.id()).orElse("");
        status.put("id", service.id());
        status.put("name", service.name());
        status.put("transport", service.transport());
        status.put("requires_user_credential", service.requiresUserCredential());
        status.put("available", capabilities.available(service.id()));
        status.put("indexed", capabilities.indexed(service.id()));
        status.put("tool_count", capabilities.toolsForService(service.id()).size());
        status.put("last_error", lastError);
        status.put("last_synced_at", lastSyncedAt(service.id()));
        status.put("state", operationalState(service.id(), lastError));
        return status;
    }

    private String operationalState(String serviceId, String lastError) {
        if (capabilities.indexed(serviceId)) {
            return "indexed";
        }
        if ("auth_required".equals(lastError)) {
            return "auth_required";
        }
        if (!lastError.isBlank()) {
            return "unavailable";
        }
        return "registered";
    }

    private String lastSyncedAt(String serviceId) {
        return capabilities.lastSyncedAt(serviceId) == null
                ? ""
                : capabilities.lastSyncedAt(serviceId).toString();
    }

    private boolean matches(ServiceDefinition service, String query) {
        if (query.isBlank()) {
            return true;
        }
        String searchableText = searchableText(service);
        if (searchableText.contains(query)) {
            return true;
        }
        List<String> queryTerms = queryTerms(query);
        if (queryTerms.size() > 1 && queryTerms.stream().allMatch(searchableText::contains)) {
            return true;
        }
        return serviceTokens(service)
                .filter(token -> token.length() >= 2)
                .anyMatch(query::contains);
    }

    private String searchableText(ServiceDefinition service) {
        return serviceTokens(service)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private Stream<String> serviceTokens(ServiceDefinition service) {
        return Stream.concat(
                Stream.of(service.id(), service.name(), service.description()),
                service.tags().stream()
        ).map(this::normalize);
    }

    private List<String> queryTerms(String query) {
        return Stream.of(query.split("[\\s,，;；/|]+"))
                .map(this::normalize)
                .filter(term -> !term.isBlank())
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

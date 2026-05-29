package com.example.mcpgateway;

import java.util.Map;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class McpGatewayHealthIndicator implements HealthIndicator {
    private final GatewayRuntime runtime;

    public McpGatewayHealthIndicator(GatewayRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Health health() {
        Map<String, Object> status = runtime.operationalStatus();
        int serviceCount = number(status.get("service_count"));
        int unavailableCount = number(status.get("unavailable_count"));
        Health.Builder builder;
        if (serviceCount == 0) {
            builder = Health.outOfService();
        } else if (unavailableCount > 0) {
            builder = Health.status("DEGRADED");
        } else {
            builder = Health.up();
        }
        return builder
                .withDetail("service_count", serviceCount)
                .withDetail("indexed_count", status.get("indexed_count"))
                .withDetail("auth_required_count", status.get("auth_required_count"))
                .withDetail("unavailable_count", unavailableCount)
                .build();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

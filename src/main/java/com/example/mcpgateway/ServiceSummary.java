package com.example.mcpgateway;

import java.util.List;

public record ServiceSummary(
        String id,
        String name,
        String description,
        List<String> tags,
        boolean requiresUserCredential,
        int toolCount,
        boolean available,
        String lastError
) {
}

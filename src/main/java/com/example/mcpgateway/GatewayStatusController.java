package com.example.mcpgateway;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayStatusController {
    private final GatewayRuntime runtime;

    public GatewayStatusController(GatewayRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/internal/status")
    public Map<String, Object> status() {
        return runtime.operationalStatus();
    }
}

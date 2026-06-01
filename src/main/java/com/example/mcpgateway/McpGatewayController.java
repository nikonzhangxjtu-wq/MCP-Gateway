package com.example.mcpgateway;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpGatewayController {
    private final McpJsonRpcHandler handler;

    public McpGatewayController(McpJsonRpcHandler handler) {
        this.handler = handler;
    }

    @PostMapping("/mcp")
    public ResponseEntity<Map<String, Object>> mcp(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ResponseEntity.ok(handler.handle(resolveUser(authorization), request));
    }

    private UserContext resolveUser(String authorization) {
        String bearer = "";
        if (authorization != null && authorization.startsWith("Bearer ")) {
            bearer = authorization.substring("Bearer ".length()).trim();
        }
        if ("bob".equals(bearer)) {
            return new UserContext("bob", "default", "agent-demo", List.of(
                    "mcp:feishu:discover",
                    "mcp:feishu:use"
            ));
        }
        return new UserContext("alice", "default", "agent-demo", List.of(
                "mcp:feishu:discover",
                "mcp:feishu:use",
                "mcp:feishu:send_message",
                "mcp:feishu:search_docs",
                "mcp:amap:discover",
                "mcp:amap:use",
                "mcp:amap:*",
                "mcp:fetch:discover",
                "mcp:fetch:use",
                "mcp:fetch:*",
                "mcp:filesystem:discover",
                "mcp:filesystem:use",
                "mcp:filesystem:*",
                "mcp:github:discover",
                "mcp:github:use",
                "mcp:github:*",
                "mcp:time:discover",
                "mcp:time:use",
                "mcp:time:*",
                "mcp:sandbox:discover",
                "mcp:sandbox:use",
                "mcp:sandbox:*"
        ));
    }
}

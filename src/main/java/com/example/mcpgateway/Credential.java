package com.example.mcpgateway;
// 实际上需要加密存储、token 过期检测、refresh_token续期机制，但架构的接入点就在这里——改进 CredentialStore 和 Credential 即可，不影响其他组件。
public record Credential(String type, String value) {
}

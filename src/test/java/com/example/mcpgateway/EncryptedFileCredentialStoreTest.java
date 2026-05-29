package com.example.mcpgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedFileCredentialStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsCredentialsEncryptedAcrossInstances() throws Exception {
        Path credentialFile = tempDir.resolve("credentials.enc");
        Path masterKeyFile = tempDir.resolve("master.key");
        EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(credentialFile, masterKeyFile);

        store.put("alice", "default", "amap", new Credential("api_key", "secret-amap-key"));

        assertThat(Files.readString(credentialFile)).doesNotContain("secret-amap-key");
        EncryptedFileCredentialStore reloaded = new EncryptedFileCredentialStore(credentialFile, masterKeyFile);
        assertThat(reloaded.get("alice", "default", "amap"))
                .contains(new Credential("api_key", "secret-amap-key"));
    }

    @Test
    void deletePersistsRemoval() {
        Path credentialFile = tempDir.resolve("credentials.enc");
        Path masterKeyFile = tempDir.resolve("master.key");
        EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(credentialFile, masterKeyFile);
        store.put("alice", "default", "amap", new Credential("api_key", "secret-amap-key"));

        store.delete("alice", "default", "amap");

        EncryptedFileCredentialStore reloaded = new EncryptedFileCredentialStore(credentialFile, masterKeyFile);
        assertThat(reloaded.get("alice", "default", "amap")).isEmpty();
    }
}

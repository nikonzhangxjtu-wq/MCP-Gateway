package com.example.mcpgateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class EncryptedFileCredentialStore extends CredentialStore {
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private final Path credentialFile;
    private final Path masterKeyFile;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public EncryptedFileCredentialStore(Path credentialFile, Path masterKeyFile) {
        this.credentialFile = credentialFile;
        this.masterKeyFile = masterKeyFile;
        this.key = new SecretKeySpec(loadOrCreateKey(), "AES");
        load();
    }

    public static EncryptedFileCredentialStore defaultStore() {
        Path home = Path.of(System.getProperty("user.home"), ".mcp-gateway");
        return new EncryptedFileCredentialStore(home.resolve("credentials.enc"), home.resolve("master.key"));
    }

    @Override
    public void put(String userId, String tenantId, String serviceId, Credential credential) {
        super.put(userId, tenantId, serviceId, credential);
        save();
    }

    @Override
    public void delete(String userId, String tenantId, String serviceId) {
        super.delete(userId, tenantId, serviceId);
        save();
    }

    private byte[] loadOrCreateKey() {
        try {
            Files.createDirectories(masterKeyFile.getParent());
            if (Files.exists(masterKeyFile)) {
                return Base64.getDecoder().decode(Files.readString(masterKeyFile).trim());
            }
            byte[] bytes = new byte[KEY_BYTES];
            random.nextBytes(bytes);
            Files.writeString(masterKeyFile, Base64.getEncoder().encodeToString(bytes), StandardCharsets.UTF_8);
            masterKeyFile.toFile().setReadable(false, false);
            masterKeyFile.toFile().setWritable(false, false);
            masterKeyFile.toFile().setReadable(true, true);
            masterKeyFile.toFile().setWritable(true, true);
            return bytes;
        } catch (IOException error) {
            throw new IllegalStateException("Failed to initialize credential master key", error);
        }
    }

    private void load() {
        if (!Files.exists(credentialFile)) {
            return;
        }
        try {
            String encoded = Files.readString(credentialFile).trim();
            if (encoded.isBlank()) {
                return;
            }
            String plain = decrypt(Base64.getDecoder().decode(encoded));
            replaceAll(parse(plain));
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read encrypted credentials", error);
        }
    }

    private void save() {
        try {
            Files.createDirectories(credentialFile.getParent());
            byte[] encrypted = encrypt(serialize(snapshot()));
            Files.writeString(credentialFile, Base64.getEncoder().encodeToString(encrypted), StandardCharsets.UTF_8);
            credentialFile.toFile().setReadable(false, false);
            credentialFile.toFile().setWritable(false, false);
            credentialFile.toFile().setReadable(true, true);
            credentialFile.toFile().setWritable(true, true);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to write encrypted credentials", error);
        }
    }

    private byte[] encrypt(String plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[nonce.length + cipherText.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(cipherText, 0, result, nonce.length, cipherText.length);
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Failed to encrypt credentials", error);
        }
    }

    private String decrypt(byte[] encrypted) {
        try {
            byte[] nonce = java.util.Arrays.copyOfRange(encrypted, 0, NONCE_BYTES);
            byte[] cipherText = java.util.Arrays.copyOfRange(encrypted, NONCE_BYTES, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to decrypt credentials", error);
        }
    }

    private String serialize(Map<String, Credential> credentials) {
        StringBuilder builder = new StringBuilder();
        credentials.forEach((key, credential) -> builder
                .append(escape(key)).append('\t')
                .append(escape(credential.type())).append('\t')
                .append(escape(credential.value())).append('\n'));
        return builder.toString();
    }

    private Map<String, Credential> parse(String text) {
        Map<String, Credential> values = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t", 3);
            if (parts.length == 3) {
                values.put(unescape(parts[0]), new Credential(unescape(parts[1]), unescape(parts[2])));
            }
        }
        return values;
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private String unescape(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (char ch : value.toCharArray()) {
            if (escaped) {
                builder.append(switch (ch) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    default -> ch;
                });
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}

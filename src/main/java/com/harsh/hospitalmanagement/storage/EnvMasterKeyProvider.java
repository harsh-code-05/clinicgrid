package com.harsh.hospitalmanagement.storage;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Portable Linux/container master-key provider that loads 256-bit master keys
 * from environment variables or secure configuration and executes RFC 3394 AES Key Wrap.
 * Never logs or exposes raw key material.
 */
public final class EnvMasterKeyProvider implements MasterKeyProvider, AutoCloseable {

    public static final String ENV_VAR_PREFIX = "HMS_DOCUMENT_MASTER_KEY_V";
    private static final int REQUIRED_KEY_BYTES = 32;

    private final int activeVersion;
    private final Map<Integer, byte[]> keys = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public EnvMasterKeyProvider(int activeVersion, String activeKeySecret) throws IOException {
        this(activeVersion, Map.of(activeVersion, activeKeySecret));
    }

    public EnvMasterKeyProvider(int activeVersion, Map<Integer, String> keysByVersion) throws IOException {
        if (activeVersion <= 0) {
            throw new IllegalArgumentException("Invalid active document master-key version: must be positive");
        }
        this.activeVersion = activeVersion;
        if (keysByVersion == null || !keysByVersion.containsKey(activeVersion)) {
            throw new IllegalStateException("Missing master key for active version " + activeVersion);
        }
        for (Map.Entry<Integer, String> entry : keysByVersion.entrySet()) {
            int ver = entry.getKey();
            if (ver <= 0) {
                throw new IllegalArgumentException("Invalid master-key version: must be positive");
            }
            byte[] parsedKey = parseAndValidateKey(entry.getValue());
            this.keys.put(ver, parsedKey);
        }
    }

    public static EnvMasterKeyProvider fromMap(Map<String, String> envMap, int activeVersion) throws IOException {
        if (activeVersion <= 0) {
            throw new IllegalArgumentException("Invalid active document master-key version: must be positive");
        }
        Map<Integer, String> versionMap = new ConcurrentHashMap<>();
        for (Map.Entry<String, String> entry : envMap.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(ENV_VAR_PREFIX)) {
                try {
                    int ver = Integer.parseInt(key.substring(ENV_VAR_PREFIX.length()));
                    versionMap.put(ver, entry.getValue());
                } catch (NumberFormatException ignored) {
                }
            } else if ("HMS_DOCUMENT_MASTER_KEY".equals(key)) {
                versionMap.putIfAbsent(1, entry.getValue());
            }
        }
        if (versionMap.isEmpty() || !versionMap.containsKey(activeVersion)) {
            throw new IllegalStateException("No master key found for active version " + activeVersion);
        }
        return new EnvMasterKeyProvider(activeVersion, versionMap);
    }

    public static EnvMasterKeyProvider fromEnvironment(int activeVersion) throws IOException {
        return fromMap(System.getenv(), activeVersion);
    }

    public boolean hasKey(int version) {
        ensureOpen();
        return keys.containsKey(version);
    }

    @Override
    public int activeVersion() {
        ensureOpen();
        return activeVersion;
    }

    @Override
    public byte[] wrapDataKey(int version, byte[] dataKey) throws IOException, GeneralSecurityException {
        ensureOpen();
        return applyKeyWrap(Cipher.WRAP_MODE, version, dataKey);
    }

    @Override
    public byte[] unwrapDataKey(int version, byte[] wrappedKey) throws IOException, GeneralSecurityException {
        ensureOpen();
        return applyKeyWrap(Cipher.UNWRAP_MODE, version, wrappedKey);
    }

    private byte[] applyKeyWrap(int mode, int version, byte[] input) throws IOException, GeneralSecurityException {
        byte[] masterKey = loadKey(version);
        var cipher = Cipher.getInstance("AES/KW/NoPadding");
        byte[] keyCopy = masterKey.clone();
        try {
            var keySpec = new SecretKeySpec(keyCopy, "AES");
            cipher.init(mode, keySpec);
            if (mode == Cipher.WRAP_MODE) {
                return cipher.wrap(new SecretKeySpec(input, "AES"));
            } else {
                var unwrapped = cipher.unwrap(input, "AES", Cipher.SECRET_KEY);
                return unwrapped.getEncoded();
            }
        } finally {
            Arrays.fill(keyCopy, (byte) 0);
        }
    }

    private byte[] loadKey(int version) throws IOException {
        byte[] key = keys.get(version);
        if (key == null) {
            String envName = ENV_VAR_PREFIX + version;
            String raw = System.getenv(envName);
            if (raw != null && !raw.isBlank()) {
                key = parseAndValidateKey(raw);
                keys.put(version, key);
            }
        }
        if (key == null) {
            throw new IllegalArgumentException("Document master key version " + version + " is not configured");
        }
        return key;
    }

    public static byte[] parseAndValidateKey(String secret) throws IOException {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Master key cannot be null or blank");
        }
        String trimmed = secret.trim();
        byte[] keyBytes;
        try {
            if (trimmed.length() == 64 && trimmed.matches("^[0-9a-fA-F]{64}$")) {
                keyBytes = HexFormat.of().parseHex(trimmed);
            } else {
                keyBytes = Base64.getDecoder().decode(trimmed);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Master key must be valid 64-character hex or 32-byte Base64");
        }
        if (keyBytes.length != REQUIRED_KEY_BYTES) {
            Arrays.fill(keyBytes, (byte) 0);
            throw new IllegalArgumentException("Master key must contain exactly 256 bits (32 bytes)");
        }
        return keyBytes;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("EnvMasterKeyProvider is closed");
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (byte[] key : keys.values()) {
                if (key != null) {
                    Arrays.fill(key, (byte) 0);
                }
            }
            keys.clear();
        }
    }
}

package com.harsh.hospitalmanagement.storage;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvMasterKeyProviderTest {

    @Test
    void rejectsEmptyOrMissingEnvironmentVariables() {
        assertThatThrownBy(() -> EnvMasterKeyProvider.fromMap(Map.of(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No master key found");
    }

    @Test
    void rejectsMasterKeyWithInvalidLength() {
        // 16 bytes instead of 32
        String shortHex = "00112233445566778899aabbccddeeff";
        assertThatThrownBy(() -> EnvMasterKeyProvider.fromMap(Map.of("HMS_DOCUMENT_MASTER_KEY_V1", shortHex), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");

        // 33 bytes
        String longHex = shortHex + shortHex + "00";
        assertThatThrownBy(() -> EnvMasterKeyProvider.fromMap(Map.of("HMS_DOCUMENT_MASTER_KEY_V1", longHex), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void supportsHexEncoded256BitMasterKey() throws Exception {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String hex = HexFormat.of().formatHex(keyBytes);

        try (var provider = EnvMasterKeyProvider.fromMap(Map.of("HMS_DOCUMENT_MASTER_KEY_V1", hex), 1)) {
            assertThat(provider.hasKey(1)).isTrue();
            byte[] dataKey = new byte[32];
            new SecureRandom().nextBytes(dataKey);

            byte[] wrapped = provider.wrapDataKey(1, dataKey);
            assertThat(wrapped).isNotNull().hasSize(40); // 32 bytes data + 8 bytes IV RFC 3394

            byte[] unwrapped = provider.unwrapDataKey(1, wrapped);
            try {
                assertThat(unwrapped).isEqualTo(dataKey);
            } finally {
                Arrays.fill(unwrapped, (byte) 0);
                Arrays.fill(dataKey, (byte) 0);
            }
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    @Test
    void supportsBase64Encoded256BitMasterKey() throws Exception {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String b64 = Base64.getEncoder().encodeToString(keyBytes);

        try (var provider = EnvMasterKeyProvider.fromMap(Map.of("HMS_DOCUMENT_MASTER_KEY_V1", b64), 1)) {
            assertThat(provider.hasKey(1)).isTrue();
            byte[] dataKey = new byte[32];
            new SecureRandom().nextBytes(dataKey);

            byte[] wrapped = provider.wrapDataKey(1, dataKey);
            byte[] unwrapped = provider.unwrapDataKey(1, wrapped);
            try {
                assertThat(unwrapped).isEqualTo(dataKey);
            } finally {
                Arrays.fill(unwrapped, (byte) 0);
                Arrays.fill(dataKey, (byte) 0);
            }
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    @Test
    void compatibleWithStandardJavaAesWrap() throws Exception {
        byte[] masterKey = new byte[32];
        byte[] dataKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        new SecureRandom().nextBytes(dataKey);
        String hex = HexFormat.of().formatHex(masterKey);

        try (var provider = EnvMasterKeyProvider.fromMap(Map.of("HMS_DOCUMENT_MASTER_KEY_V1", hex), 1)) {
            Cipher cipher = Cipher.getInstance("AESWrap");
            cipher.init(Cipher.WRAP_MODE, new SecretKeySpec(masterKey, "AES"));
            byte[] standardWrapped = cipher.wrap(new SecretKeySpec(dataKey, "AES"));

            // Unwrap using provider
            byte[] unwrappedByProvider = provider.unwrapDataKey(1, standardWrapped);
            assertThat(unwrappedByProvider).isEqualTo(dataKey);

            // Wrap using provider and unwrap using standard cipher
            byte[] wrappedByProvider = provider.wrapDataKey(1, dataKey);
            cipher.init(Cipher.UNWRAP_MODE, new SecretKeySpec(masterKey, "AES"));
            SecretKeySpec unwrappedByStandard = (SecretKeySpec) cipher.unwrap(wrappedByProvider, "AES", Cipher.SECRET_KEY);
            assertThat(unwrappedByStandard.getEncoded()).isEqualTo(dataKey);
        } finally {
            Arrays.fill(masterKey, (byte) 0);
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Test
    void supportsMultiVersionKeyRotation() throws Exception {
        byte[] keyV1 = new byte[32];
        byte[] keyV2 = new byte[32];
        new SecureRandom().nextBytes(keyV1);
        new SecureRandom().nextBytes(keyV2);

        Map<String, String> env = Map.of(
                "HMS_DOCUMENT_MASTER_KEY_V1", HexFormat.of().formatHex(keyV1),
                "HMS_DOCUMENT_MASTER_KEY_V2", HexFormat.of().formatHex(keyV2)
        );

        try (var provider = EnvMasterKeyProvider.fromMap(env, 2)) {
            assertThat(provider.hasKey(1)).isTrue();
            assertThat(provider.hasKey(2)).isTrue();
            assertThat(provider.hasKey(3)).isFalse();

            byte[] dataKey = new byte[32];
            new SecureRandom().nextBytes(dataKey);

            // Wrap with current version (2)
            byte[] wrappedV2 = provider.wrapDataKey(2, dataKey);
            byte[] unwrappedV2 = provider.unwrapDataKey(2, wrappedV2);
            assertThat(unwrappedV2).isEqualTo(dataKey);

            // Wrap with old version (1)
            byte[] wrappedV1 = provider.wrapDataKey(1, dataKey);
            byte[] unwrappedV1 = provider.unwrapDataKey(1, wrappedV1);
            assertThat(unwrappedV1).isEqualTo(dataKey);

            // Attempt unwrap with wrong version fails
            assertThatThrownBy(() -> provider.unwrapDataKey(1, wrappedV2))
                    .isInstanceOf(java.security.GeneralSecurityException.class);

            // Attempt unknown version fails
            assertThatThrownBy(() -> provider.unwrapDataKey(3, wrappedV1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("version 3");
        } finally {
            Arrays.fill(keyV1, (byte) 0);
            Arrays.fill(keyV2, (byte) 0);
        }
    }

    @Test
    void rejectsOperationsAfterClose() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        var provider = EnvMasterKeyProvider.fromMap(Map.of("HMS_DOCUMENT_MASTER_KEY_V1", HexFormat.of().formatHex(key)), 1);
        provider.close();

        byte[] dataKey = new byte[32];
        assertThatThrownBy(() -> provider.wrapDataKey(1, dataKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> provider.unwrapDataKey(1, new byte[40]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}

package com.harsh.hospitalmanagement.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvMasterKeyProviderLifecycleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DocumentStorageConfiguration.class);

    @Test
    void managesLifecycleAndZeroizesOnContextShutdown() {
        byte[] rawKey = new byte[32];
        new SecureRandom().nextBytes(rawKey);
        String hexKey = HexFormat.of().formatHex(rawKey);
        Arrays.fill(rawKey, (byte) 0);

        runner.withPropertyValues(
                "hms.document-storage.enabled=true",
                "hms.document-storage.provider=s3",
                "hms.document-storage.s3-bucket=lifecycle-test-bucket",
                "hms.document-storage.s3-endpoint=https://s3.us-east-1.amazonaws.com",
                "hms.document-storage.s3-access-key=dummy-access",
                "hms.document-storage.s3-secret-key=dummy-secret",
                "hms.document-storage.master-key-version=1",
                "hms.document-storage.master-key-secret=" + hexKey
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EnvMasterKeyProvider.class);
            assertThat(context).hasSingleBean(DocumentStorage.class);

            EnvMasterKeyProvider provider = context.getBean(EnvMasterKeyProvider.class);
            assertThat(provider.isClosed()).isFalse();

            byte[] dataKey = new byte[32];
            new SecureRandom().nextBytes(dataKey);
            byte[] wrapped = provider.wrapDataKey(1, dataKey);
            assertThat(wrapped).isNotNull();

            // Simulate application shutdown by closing context
            context.close();

            // Verify lifecycle closure and zeroization
            assertThat(provider.isClosed()).isTrue();
            assertThatThrownBy(() -> provider.wrapDataKey(1, dataKey))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThatThrownBy(() -> provider.unwrapDataKey(1, wrapped))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        });
    }

    @Test
    void envMasterKeyProviderBeanNotRegisteredUnderWindowsProvider() {
        var docFiles = org.mockito.Mockito.mock(PrivateFiles.class);
        org.mockito.Mockito.when(docFiles.volumeId()).thenReturn("VOL_DOCS");
        var keyFiles = org.mockito.Mockito.mock(PrivateFiles.class);
        org.mockito.Mockito.when(keyFiles.volumeId()).thenReturn("VOL_KEYS");

        DocumentStorageConfiguration.PrivateFilesFactory factory = path ->
                path.toString().contains("docs") ? docFiles : keyFiles;

        new ApplicationContextRunner()
                .withBean(DocumentStorageConfiguration.class, () -> new DocumentStorageConfiguration(factory))
                .withPropertyValues(
                        "hms.document-storage.enabled=false",
                        "hms.document-storage.provider=windows"
                ).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EnvMasterKeyProvider.class);
                });
    }
}

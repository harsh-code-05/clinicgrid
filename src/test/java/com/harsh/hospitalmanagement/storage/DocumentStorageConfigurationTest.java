package com.harsh.hospitalmanagement.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStorageConfigurationTest {

    @TempDir Path temp;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DocumentStorageConfiguration.class);

    @Test
    void storageIsDisabledUnlessExplicitlyEnabled() {
        runner.withPropertyValues("hms.document-storage.root=" + temp.resolve("missing"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DocumentStorage.class);
                    assertThat(context).doesNotHaveBean(VerificationImageValidator.class);
                });
    }

    @Test
    void enabledStorageRequiresExplicitKeyConfiguration() {
        runner.withPropertyValues("hms.document-storage.enabled=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Private storage requires a DPAPI key directory and a positive master-key version"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void refusesAKeyDirectoryOnTheDocumentVolume() throws IOException {
        Path documents = WindowsDocumentStorageTest.protectedDirectory(temp.resolve("docs"));
        Path keys = WindowsDocumentStorageTest.protectedDirectory(temp.resolve("keys"));
        runner.withPropertyValues("hms.document-storage.enabled=true",
                        "hms.document-storage.root=" + documents,
                        "hms.document-storage.key-directory=" + keys,
                        "hms.document-storage.master-key-version=1")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("The DPAPI master-key directory must be on a separate NTFS volume"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void startsSuccessfullyWhenConfiguredWithDistinctVolumes() throws Exception {
        byte[] masterKey = new byte[32];
        new java.security.SecureRandom().nextBytes(masterKey);
        byte[] validBlob = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(
                masterKey, WindowsDpapiMasterKeyProvider.purpose(1),
                com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null);

        var docFiles = org.mockito.Mockito.mock(PrivateFiles.class);
        org.mockito.Mockito.when(docFiles.volumeId()).thenReturn("VOLUME_DOCUMENTS_GUID_1");

        var keyFiles = org.mockito.Mockito.mock(PrivateFiles.class);
        org.mockito.Mockito.when(keyFiles.volumeId()).thenReturn("VOLUME_KEYS_GUID_2");
        org.mockito.Mockito.when(keyFiles.read(Path.of("v1.dpapi"), 16 * 1024)).thenReturn(validBlob);

        DocumentStorageConfiguration.PrivateFilesFactory factory = path ->
                path.toString().contains("docs") ? docFiles : keyFiles;

        new ApplicationContextRunner()
                .withBean(DocumentStorageConfiguration.class, () -> new DocumentStorageConfiguration(factory))
                .withPropertyValues(
                        "hms.document-storage.enabled=true",
                        "hms.document-storage.root=C:/HMS/docs",
                        "hms.document-storage.key-directory=D:/HMS/keys",
                        "hms.document-storage.master-key-version=1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DocumentStorage.class);
                    assertThat(context).hasSingleBean(VerificationImageValidator.class);
                    assertThat(context).hasSingleBean(DocumentStorageConfiguration.StagingCleanupScheduler.class);
                });
    }

    @Test
    void schedulingBeansNotLoadedWhenStorageDisabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(DocumentStorageConfiguration.StagingCleanupScheduler.class);
            assertThat(context).doesNotHaveBean(DocumentStorage.class);
        });
    }

    @Test
    void rejectsUnsupportedProvider() {
        runner.withPropertyValues(
                "hms.document-storage.enabled=true",
                "hms.document-storage.provider=unsupported"
        ).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("Unsupported document storage provider: unsupported"));
    }

    @Test
    void s3ProviderRequiresBucket() {
        runner.withPropertyValues(
                "hms.document-storage.enabled=true",
                "hms.document-storage.provider=s3"
        ).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("S3 private document storage requires a configured bucket (hms.document-storage.s3-bucket)"));
    }

    @Test
    void s3ProviderRequiresCredentials() {
        runner.withPropertyValues(
                "hms.document-storage.enabled=true",
                "hms.document-storage.provider=s3",
                "hms.document-storage.s3-bucket=my-bucket",
                "hms.document-storage.s3-endpoint=https://s3.us-east-1.amazonaws.com"
        ).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("S3 document storage requires access-key and secret-key (hms.document-storage.s3-access-key and hms.document-storage.s3-secret-key)"));
    }

    @Test
    void s3ProviderInitializesSuccessfullyWhenConfigured() {
        runner.withPropertyValues(
                "hms.document-storage.enabled=true",
                "hms.document-storage.provider=s3",
                "hms.document-storage.s3-bucket=test-bucket",
                "hms.document-storage.s3-endpoint=https://s3.us-east-1.amazonaws.com",
                "hms.document-storage.s3-access-key=test-access-key",
                "hms.document-storage.s3-secret-key=test-secret-key",
                "hms.document-storage.master-key-version=1",
                "hms.document-storage.master-key-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DocumentStorage.class);
            assertThat(context.getBean(DocumentStorage.class)).isInstanceOf(S3DocumentStorage.class);
            assertThat(context).hasSingleBean(VerificationImageValidator.class);
        });
    }

    @Test
    void productionConfigurationFailsClosedWhenCredentialsMissing() {
        runner.withPropertyValues(
                "hms.document-storage.enabled=true",
                "hms.document-storage.provider=s3"
        ).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("S3 private document storage requires a configured bucket (hms.document-storage.s3-bucket)"));
    }

    @Test
    void malformedConfigurationRejectsInvalidMasterKeyHex() {
        runner.withPropertyValues(
                "hms.document-storage.enabled=true",
                "hms.document-storage.provider=s3",
                "hms.document-storage.s3-bucket=my-bucket",
                "hms.document-storage.s3-endpoint=https://s3.us-east-1.amazonaws.com",
                "hms.document-storage.s3-access-key=my-key",
                "hms.document-storage.s3-secret-key=my-secret",
                "hms.document-storage.master-key-version=1",
                "hms.document-storage.master-key-secret=not-a-valid-hex-secret"
        ).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void malformedConfigurationRejectsNegativeMasterKeyVersion() {
        runner.withPropertyValues(
                "hms.document-storage.enabled=true",
                "hms.document-storage.provider=s3",
                "hms.document-storage.s3-bucket=my-bucket",
                "hms.document-storage.s3-endpoint=https://s3.us-east-1.amazonaws.com",
                "hms.document-storage.s3-access-key=my-key",
                "hms.document-storage.s3-secret-key=my-secret",
                "hms.document-storage.master-key-version=-1",
                "hms.document-storage.master-key-secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        ).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("Private storage requires a positive master-key version"));
    }

    @Test
    void windowsDevelopmentDefaultsToWindowsProvider() {
        runner.withPropertyValues(
                "hms.document-storage.enabled=true"
        ).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseMessage("Private storage requires a DPAPI key directory and a positive master-key version"));
    }

    @Test
    void multiVersionKeyProviderAllowsUnwrappingHistoricalKeys() throws Exception {
        String keyV1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String keyV2 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

        EnvMasterKeyProvider provider = new EnvMasterKeyProvider(2, java.util.Map.of(1, keyV1, 2, keyV2));

        assertThat(provider.activeVersion()).isEqualTo(2);
        assertThat(provider.hasKey(1)).isTrue();
        assertThat(provider.hasKey(2)).isTrue();

        byte[] originalDek = "12345678901234567890123456789012".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        EnvMasterKeyProvider v1Provider = new EnvMasterKeyProvider(1, keyV1);
        byte[] wrappedWithV1 = v1Provider.wrapDataKey(1, originalDek);

        byte[] unwrappedHistorical = provider.unwrapDataKey(1, wrappedWithV1);
        assertThat(unwrappedHistorical).isEqualTo(originalDek);

        byte[] wrappedWithV2 = provider.wrapDataKey(2, originalDek);
        byte[] unwrappedV2 = provider.unwrapDataKey(2, wrappedWithV2);
        assertThat(unwrappedV2).isEqualTo(originalDek);

        provider.close();
        v1Provider.close();
    }
}

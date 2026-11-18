package com.harsh.hospitalmanagement.storage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hms.document-storage", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DocumentStorageConfiguration.Settings.class)
class DocumentStorageConfiguration {

    interface PrivateFilesFactory {
        PrivateFiles create(Path path) throws IOException;
    }

    private final PrivateFilesFactory filesFactory;

    DocumentStorageConfiguration() {
        this(WindowsPrivateFiles::new);
    }

    DocumentStorageConfiguration(PrivateFilesFactory filesFactory) {
        this.filesFactory = filesFactory != null ? filesFactory : WindowsPrivateFiles::new;
    }

    static class S3ProviderCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String provider = context.getEnvironment().getProperty("hms.document-storage.provider", "windows");
            String clean = provider.trim().toLowerCase(Locale.ROOT);
            return "s3".equals(clean) || "neon".equals(clean) || "neon-s3".equals(clean);
        }
    }

    @Bean
    @Conditional(S3ProviderCondition.class)
    @ConditionalOnMissingBean(MasterKeyProvider.class)
    EnvMasterKeyProvider envMasterKeyProvider(Settings settings) throws IOException {
        validateS3Settings(settings);
        return createEnvMasterKeyProvider(settings);
    }

    private static void validateS3Settings(Settings settings) throws IOException {
        if (settings.s3Bucket() == null || settings.s3Bucket().isBlank()) {
            throw new IOException("S3 private document storage requires a configured bucket (hms.document-storage.s3-bucket)");
        }
        if (settings.s3AccessKey() == null || settings.s3AccessKey().isBlank()
                || settings.s3SecretKey() == null || settings.s3SecretKey().isBlank()) {
            throw new IOException("S3 document storage requires access-key and secret-key (hms.document-storage.s3-access-key and hms.document-storage.s3-secret-key)");
        }
    }

    @Bean
    DocumentStorage documentStorage(
            Settings settings,
            ObjectProvider<MasterKeyProvider> masterKeyProviderProvider,
            ObjectProvider<S3Client> s3ClientProvider
    ) throws IOException {
        String provider = settings.provider() != null
                ? settings.provider().trim().toLowerCase(Locale.ROOT) : "windows";

        if (!"windows".equals(provider) && !"s3".equals(provider) && !"neon".equals(provider) && !"neon-s3".equals(provider)) {
            throw new IllegalArgumentException("Unsupported document storage provider: " + provider);
        }

        if ("s3".equals(provider) || "neon".equals(provider) || "neon-s3".equals(provider)) {
            validateS3Settings(settings);

            MasterKeyProvider masterKeyProvider = masterKeyProviderProvider.getIfAvailable();
            if (masterKeyProvider == null) {
                masterKeyProvider = createEnvMasterKeyProvider(settings);
            }

            S3Client s3Client = s3ClientProvider.getIfAvailable();
            if (s3Client == null) {
                String endpoint = (settings.s3Endpoint() != null && !settings.s3Endpoint().isBlank())
                        ? settings.s3Endpoint() : "https://s3." + settings.s3Region() + ".amazonaws.com";
                s3Client = new HttpS3Client(endpoint, settings.s3Region(), settings.s3AccessKey(), settings.s3SecretKey());
            }

            return new S3DocumentStorage(settings.s3Bucket(), s3Client, masterKeyProvider, UUID::randomUUID);
        }

        // Default: Windows DPAPI provider
        if (settings.keyDirectory() == null || settings.masterKeyVersion() <= 0) {
            throw new IOException("Private storage requires a DPAPI key directory and a positive master-key version");
        }
        var documents = filesFactory.create(settings.root());
        var keyFiles = filesFactory.create(settings.keyDirectory());
        if (documents.volumeId().equalsIgnoreCase(keyFiles.volumeId())) {
            throw new IOException("The DPAPI master-key directory must be on a separate NTFS volume");
        }
        return new FilesystemDocumentStorage(documents,
                new WindowsDpapiMasterKeyProvider(keyFiles, settings.masterKeyVersion()), UUID::randomUUID);
    }

    static EnvMasterKeyProvider createEnvMasterKeyProvider(Settings settings) throws IOException {
        if (settings.masterKeyVersion() <= 0) {
            throw new IOException("Private storage requires a positive master-key version");
        }
        int activeVersion = settings.masterKeyVersion();
        Map<Integer, String> keyMap = new java.util.concurrent.ConcurrentHashMap<>();

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String envName = entry.getKey();
            if (envName.startsWith(EnvMasterKeyProvider.ENV_VAR_PREFIX)) {
                try {
                    int ver = Integer.parseInt(envName.substring(EnvMasterKeyProvider.ENV_VAR_PREFIX.length()));
                    if (ver > 0 && entry.getValue() != null && !entry.getValue().isBlank()) {
                        keyMap.put(ver, entry.getValue().trim());
                    }
                } catch (NumberFormatException ignored) {
                }
            } else if ("HMS_DOCUMENT_MASTER_KEY".equals(envName)) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    keyMap.putIfAbsent(1, entry.getValue().trim());
                }
            }
        }

        String secret = settings.masterKeySecret();
        if (secret != null && !secret.isBlank()) {
            keyMap.put(activeVersion, secret.trim());
        }

        if (!keyMap.containsKey(activeVersion)) {
            throw new IOException("S3 document storage requires master key secret (HMS_DOCUMENT_MASTER_KEY_V"
                    + activeVersion + " or hms.document-storage.master-key-secret)");
        }
        return new EnvMasterKeyProvider(activeVersion, keyMap);
    }

    @Bean
    VerificationImageValidator verificationImageValidator() {
        return new VerificationImageValidator();
    }

    @Bean
    StagingCleanupScheduler stagingCleanupScheduler(
            ObjectProvider<MultipartStagingManager> stagingManagerProvider) {
        return new StagingCleanupScheduler(stagingManagerProvider.getIfAvailable());
    }

    static class StagingCleanupScheduler {
        private final MultipartStagingManager stagingManager;

        StagingCleanupScheduler(MultipartStagingManager stagingManager) {
            this.stagingManager = stagingManager;
        }

        @org.springframework.scheduling.annotation.Scheduled(
                cron = "${hms.document-storage.staging-cleanup-cron:0 0 * * * *}")
        public int runPeriodicSweep() {
            if (stagingManager != null) {
                return stagingManager.scheduledCleanup();
            }
            return 0;
        }
    }

    @ConfigurationProperties("hms.document-storage")
    record Settings(
            @DefaultValue("windows") String provider,
            @DefaultValue("C:/ProgramData/HMS/private-documents") Path root,
            Path keyDirectory,
            @DefaultValue("1") int masterKeyVersion,
            String s3Endpoint,
            String s3Bucket,
            @DefaultValue("us-east-1") String s3Region,
            String s3AccessKey,
            String s3SecretKey,
            String masterKeySecret
    ) {
    }
}

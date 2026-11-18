package com.harsh.hospitalmanagement.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
public class OAuthConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GoogleOAuthConfiguration.class);

    // ==========================================
    // 1. Valid Credentials Tests
    // ==========================================

    @Test
    @DisplayName("Valid credentials via GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET: ClientRegistrationRepository created")
    void whenOAuthCredentialsAreSetClientRegistrationRepositoryIsCreatedWithCorrectRedirectUri() {
        contextRunner
                .withPropertyValues(
                        "GOOGLE_CLIENT_ID=real-client-id-12345.apps.googleusercontent.com",
                        "GOOGLE_CLIENT_SECRET=real-client-secret-67890-xyz"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ClientRegistrationRepository.class);
                    ClientRegistrationRepository repo = context.getBean(ClientRegistrationRepository.class);
                    ClientRegistration google = repo.findByRegistrationId("google");
                    assertThat(google).isNotNull();
                    assertThat(google.getClientId()).isEqualTo("real-client-id-12345.apps.googleusercontent.com");
                    assertThat(google.getClientSecret()).isEqualTo("real-client-secret-67890-xyz");
                    assertThat(google.getRedirectUri()).isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
                    assertThat(google.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
                });
    }

    @Test
    @DisplayName("Valid credentials via spring.security.oauth2 property aliases: ClientRegistrationRepository created")
    void whenOAuthCredentialsAreSetViaSpringPropertiesClientRegistrationRepositoryIsCreated() {
        contextRunner
                .withPropertyValues(
                        "spring.security.oauth2.client.registration.google.client-id=my-app-client-id.apps.googleusercontent.com",
                        "spring.security.oauth2.client.registration.google.client-secret=my-app-secret-abcdef"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ClientRegistrationRepository.class);
                    ClientRegistrationRepository repo = context.getBean(ClientRegistrationRepository.class);
                    ClientRegistration google = repo.findByRegistrationId("google");
                    assertThat(google).isNotNull();
                    assertThat(google.getClientId()).isEqualTo("my-app-client-id.apps.googleusercontent.com");
                    assertThat(google.getClientSecret()).isEqualTo("my-app-secret-abcdef");
                });
    }

    // ==========================================
    // 2. Missing Credentials Tests
    // ==========================================

    @Test
    @DisplayName("Missing credentials: both unset -> OAuth disabled, logs missing variable names without exposing values")
    void whenBothOAuthCredentialsAreUnsetOAuthIsSafelyDisabled(CapturedOutput output) {
        contextRunner
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
                    assertThat(output.getAll()).contains("Google OAuth 2.0 is disabled: missing or unconfigured environment properties [GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET]");
                });
    }

    @Test
    @DisplayName("Missing credentials: only GOOGLE_CLIENT_ID set, SECRET unset -> OAuth disabled, logs GOOGLE_CLIENT_SECRET as missing")
    void whenOnlyClientIdIsSetOAuthIsSafelyDisabled(CapturedOutput output) {
        contextRunner
                .withPropertyValues("GOOGLE_CLIENT_ID=real-client-id-12345")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
                    assertThat(output.getAll()).contains("Google OAuth 2.0 is disabled: missing or unconfigured environment properties [GOOGLE_CLIENT_SECRET]");
                });
    }

    @Test
    @DisplayName("Missing credentials: only GOOGLE_CLIENT_SECRET set, ID unset -> OAuth disabled, logs GOOGLE_CLIENT_ID as missing, NEVER logs secret value")
    void whenOnlyClientSecretIsSetOAuthIsSafelyDisabled(CapturedOutput output) {
        String sensitiveSecret = "super-sensitive-secret-value-never-log";
        contextRunner
                .withPropertyValues("GOOGLE_CLIENT_SECRET=" + sensitiveSecret)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
                    assertThat(output.getAll()).contains("Google OAuth 2.0 is disabled: missing or unconfigured environment properties [GOOGLE_CLIENT_ID]");
                    assertThat(output.getAll()).doesNotContain(sensitiveSecret);
                });
    }

    @Test
    @DisplayName("Missing credentials: blank/whitespace values -> OAuth disabled safely")
    void whenCredentialsAreBlankOAuthIsSafelyDisabled() {
        contextRunner
                .withPropertyValues(
                        "GOOGLE_CLIENT_ID=   ",
                        "GOOGLE_CLIENT_SECRET=   "
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
                });
    }

    // ==========================================
    // 3. Dummy / Placeholder Credentials Tests
    // ==========================================

    @Test
    @DisplayName("Dummy credentials: 'dummy-client-id' / 'dummy-client-secret' -> OAuth disabled, does not create bean")
    void whenCredentialsAreDummyClientIdAndSecretOAuthIsSafelyDisabled() {
        contextRunner
                .withPropertyValues(
                        "GOOGLE_CLIENT_ID=dummy-client-id",
                        "GOOGLE_CLIENT_SECRET=dummy-client-secret"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
                });
    }

    @Test
    @DisplayName("Dummy credentials: placeholder variations ('placeholder', 'your-client-id', 'change-me') -> OAuth disabled")
    void whenCredentialsArePlaceholdersOAuthIsSafelyDisabled() {
        contextRunner
                .withPropertyValues(
                        "GOOGLE_CLIENT_ID=placeholder",
                        "GOOGLE_CLIENT_SECRET=your-client-secret"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
                });

        contextRunner
                .withPropertyValues(
                        "GOOGLE_CLIENT_ID=change-me",
                        "GOOGLE_CLIENT_SECRET=changeme"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
                });
    }

    // ==========================================
    // 4. Logging & Secret Safety Verification
    // ==========================================

    @Test
    @DisplayName("Security: valid credentials log success without exposing secret values")
    void whenValidCredentialsConfiguredSecretValuesAreNeverLogged(CapturedOutput output) {
        String sensitiveSecret = "GOCSPX-very-secret-token-123456789";
        contextRunner
                .withPropertyValues(
                        "GOOGLE_CLIENT_ID=123456789-abc.apps.googleusercontent.com",
                        "GOOGLE_CLIENT_SECRET=" + sensitiveSecret
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ClientRegistrationRepository.class);
                    assertThat(output.getAll()).contains("Google OAuth 2.0 is enabled");
                    assertThat(output.getAll()).doesNotContain(sensitiveSecret);
                });
    }
}

package com.harsh.hospitalmanagement.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class GoogleOAuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthConfiguration.class);

    public static boolean isDummyOrPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("dummy")
                || normalized.equals("placeholder")
                || normalized.startsWith("your-")
                || normalized.startsWith("your_")
                || normalized.equals("change-me")
                || normalized.equals("changeme")
                || normalized.equals("null");
    }

    public static String resolveClientId(Environment env) {
        if (env == null) {
            return null;
        }
        String id = env.getProperty("GOOGLE_CLIENT_ID");
        if (id == null || id.isBlank()) {
            id = env.getProperty("spring.security.oauth2.client.registration.google.client-id");
        }
        if (id == null || id.isBlank() || isDummyOrPlaceholder(id)) {
            return null;
        }
        return id.trim();
    }

    public static String resolveClientSecret(Environment env) {
        if (env == null) {
            return null;
        }
        String secret = env.getProperty("GOOGLE_CLIENT_SECRET");
        if (secret == null || secret.isBlank()) {
            secret = env.getProperty("spring.security.oauth2.client.registration.google.client-secret");
        }
        if (secret == null || secret.isBlank() || isDummyOrPlaceholder(secret)) {
            return null;
        }
        return secret.trim();
    }

    @Bean
    @Conditional(GoogleOAuthEnabledCondition.class)
    public ClientRegistrationRepository clientRegistrationRepository(Environment env) {
        String clientId = resolveClientId(env);
        String clientSecret = resolveClientSecret(env);

        log.info("Registering Google OAuth 2.0 ClientRegistration with redirectUri: {baseUrl}/login/oauth2/code/google");

        ClientRegistration googleRegistration = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .scope("openid", "profile", "email")
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .build();

        return new InMemoryClientRegistrationRepository(googleRegistration);
    }

    public static class GoogleOAuthEnabledCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            String clientId = resolveClientId(env);
            String clientSecret = resolveClientSecret(env);

            boolean enabled = clientId != null && clientSecret != null;

            if (!enabled) {
                List<String> missing = new ArrayList<>();
                if (clientId == null) {
                    missing.add("GOOGLE_CLIENT_ID");
                }
                if (clientSecret == null) {
                    missing.add("GOOGLE_CLIENT_SECRET");
                }
                log.info("Google OAuth 2.0 is disabled: missing or unconfigured environment properties [{}].",
                        String.join(", ", missing));
            } else {
                log.info("Google OAuth 2.0 is enabled. Both GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are configured.");
            }
            return enabled;
        }
    }
}

package com.harsh.hospitalmanagement.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live smoke test for Cloudflare Workers AI GLM-4.7-Flash.
 * Requires explicit live-test opt-in and credentials.
 * Free plan only: consumes exactly 1 inference call when executed.
 */
@Tag("smoke")
class CloudflareLiveSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(CloudflareLiveSmokeTest.class);

    @Test
    @EnabledIfEnvironmentVariable(named = "HMS_CLOUDFLARE_LIVE_TEST_ENABLED", matches = "true")
    @EnabledIfEnvironmentVariable(named = "CLOUDFLARE_AI_TOKEN", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "CLOUDFLARE_ACCOUNT_ID", matches = ".+")
    void liveSmokeTestGlmFlash() {
        String accountId = System.getenv("CLOUDFLARE_ACCOUNT_ID");
        String apiToken = System.getenv("CLOUDFLARE_AI_TOKEN");

        CloudflareAiProperties props = new CloudflareAiProperties();
        props.setAccountId(accountId);
        props.setApiToken(apiToken);
        props.setModel("@cf/zai-org/glm-4.7-flash");
        props.setBaseUrl("https://api.cloudflare.com/client/v4");
        props.setTimeoutSeconds(60);
        props.setMaxTokens(1536);
        props.setTemperature(0.2);

        CloudflareAiClient client = new CloudflareAiClient(props, RestClient.builder(), new ObjectMapper());

        log.info("Executing 1 live smoke test query against Cloudflare Workers AI GLM-4.7-Flash...");
        String response = client.generateChatCompletion(
                PatientAiEngine.SYSTEM_PROMPT,
                "What are common symptoms of seasonal allergies, and when should I see a doctor?"
        );

        log.info("Cloudflare Workers AI GLM-4.7-Flash live response:\n{}", response);

        assertThat(response).isNotBlank();
        assertThat(response.length()).isGreaterThan(20);
        assertThat(response.toLowerCase()).containsAnyOf("allergy", "allergies", "symptom", "doctor", "consult", "physician");
    }
}

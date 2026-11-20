package com.harsh.hospitalmanagement.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CloudflareAiClientTest {

    private CloudflareAiProperties properties;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;
    private CloudflareAiClient client;

    @BeforeEach
    void setUp() {
        properties = new CloudflareAiProperties();
        properties.setAccountId("cf-account-1234567890abcdef");
        properties.setApiToken("cf-token-secret-test-value");
        properties.setModel("@cf/zai-org/glm-4.7-flash");
        properties.setBaseUrl("https://api.cloudflare.com/client/v4");
        properties.setTimeoutSeconds(15);
        properties.setMaxTokens(512);
        properties.setMaxConcurrent(2);

        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        objectMapper = new ObjectMapper();
        client = new CloudflareAiClient(properties, restClientBuilder.build(), objectMapper);
    }

    @Test
    void test01_unconfiguredClientThrowsIllegalStateException() {
        CloudflareAiProperties unconfiguredProps = new CloudflareAiProperties();
        CloudflareAiClient unconfiguredClient = new CloudflareAiClient(unconfiguredProps, restClientBuilder.build(), objectMapper);

        assertThatThrownBy(() -> unconfiguredClient.generateChatCompletion("sys", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing account ID or API token");
    }

    @Test
    void test02_successfulChatCompletionsResponse() {
        String mockResponse = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "Hello! I can explain hospital services and appointment procedures."
                      }
                    }
                  ],
                  "success": true
                }
                """;

        mockServer.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/cf-account-1234567890abcdef/ai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer cf-token-secret-test-value"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        String answer = client.generateChatCompletion("You are an assistant", "How do I book?");
        mockServer.verify();

        assertThat(answer).isEqualTo("Hello! I can explain hospital services and appointment procedures.");
    }

    @Test
    void test03_successfulCloudflareNativeResultResponse() {
        String mockResponse = """
                {
                  "result": {
                    "response": "Here is information on hospital clinic hours."
                  },
                  "success": true,
                  "errors": [],
                  "messages": []
                }
                """;

        mockServer.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/cf-account-1234567890abcdef/ai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        String answer = client.generateChatCompletion("You are an assistant", "What are the clinic hours?");
        mockServer.verify();

        assertThat(answer).isEqualTo("Here is information on hospital clinic hours.");
    }

    @Test
    void test04_authFailureThrowsTypedException() {
        mockServer.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/cf-account-1234567890abcdef/ai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"errors\":[{\"message\":\"Invalid token\"}]}"));

        assertThatThrownBy(() -> client.generateChatCompletion("sys", "user"))
                .isInstanceOf(CloudflareAiException.class)
                .satisfies(ex -> {
                    CloudflareAiException cfEx = (CloudflareAiException) ex;
                    assertThat(cfEx.getReason()).isEqualTo(CloudflareAiException.Reason.AUTH_FAILURE);
                    assertThat(cfEx.getStatusCode()).isEqualTo(401);
                });
    }

    @Test
    void test05_quotaExceededThrowsTypedException() {
        mockServer.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/cf-account-1234567890abcdef/ai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"errors\":[{\"message\":\"Daily limit exceeded\"}]}"));

        assertThatThrownBy(() -> client.generateChatCompletion("sys", "user"))
                .isInstanceOf(CloudflareAiException.class)
                .satisfies(ex -> {
                    CloudflareAiException cfEx = (CloudflareAiException) ex;
                    assertThat(cfEx.getReason()).isEqualTo(CloudflareAiException.Reason.QUOTA_EXCEEDED);
                    assertThat(cfEx.getStatusCode()).isEqualTo(429);
                });
    }

    @Test
    void test06_serverErrorThrowsTypedException() {
        mockServer.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/cf-account-1234567890abcdef/ai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError().body("{\"errors\":[{\"message\":\"Internal model error\"}]}"));

        assertThatThrownBy(() -> client.generateChatCompletion("sys", "user"))
                .isInstanceOf(CloudflareAiException.class)
                .satisfies(ex -> {
                    CloudflareAiException cfEx = (CloudflareAiException) ex;
                    assertThat(cfEx.getReason()).isEqualTo(CloudflareAiException.Reason.SERVER_ERROR);
                    assertThat(cfEx.getStatusCode()).isEqualTo(500);
                });
    }

    @Test
    void test07_malformedPayloadThrowsTypedException() {
        mockServer.expect(requestTo("https://api.cloudflare.com/client/v4/accounts/cf-account-1234567890abcdef/ai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"unexpected\":123}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generateChatCompletion("sys", "user"))
                .isInstanceOf(CloudflareAiException.class)
                .satisfies(ex -> {
                    CloudflareAiException cfEx = (CloudflareAiException) ex;
                    assertThat(cfEx.getReason()).isEqualTo(CloudflareAiException.Reason.MALFORMED_RESPONSE);
                });
    }

    @Test
    void test08_accountMasking() {
        assertThat(CloudflareAiClient.maskAccountId("5ed3633631a477cb276573b33e33b1fc"))
                .isEqualTo("5ed3...b1fc");
        assertThat(CloudflareAiClient.maskAccountId("short"))
                .isEqualTo("******");
        assertThat(CloudflareAiClient.maskAccountId(null))
                .isEqualTo("<unset>");
    }
}

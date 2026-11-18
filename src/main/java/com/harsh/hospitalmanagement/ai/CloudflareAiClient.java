package com.harsh.hospitalmanagement.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated, zero-charge client for Cloudflare Workers AI (Free Plan Only).
 * Communicates directly with the official Cloudflare v4 Chat Completions API.
 */
@Component
public class CloudflareAiClient {

    private static final Logger log = LoggerFactory.getLogger(CloudflareAiClient.class);
    private static final int MAX_INPUT_CHARS = 4000;

    private final CloudflareAiProperties properties;
    private final RestClient restClient;
    private final Semaphore inFlightLimiter;
    private final ObjectMapper objectMapper;

    @Autowired
    public CloudflareAiClient(CloudflareAiProperties properties,
                              @Autowired(required = false) RestClient.Builder restClientBuilder,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.inFlightLimiter = new Semaphore(Math.max(1, properties.getMaxConcurrent()));

        RestClient.Builder builder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(Math.max(5, properties.getTimeoutSeconds())).toMillis());

        this.restClient = builder
                .requestFactory(requestFactory)
                .build();
    }

    // Constructor visible for testing with mock RestClient
    CloudflareAiClient(CloudflareAiProperties properties, RestClient restClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.inFlightLimiter = new Semaphore(Math.max(1, properties.getMaxConcurrent()));
        this.restClient = restClient;
    }

    /**
     * Executes Chat Completion against Cloudflare Workers AI.
     *
     * @param systemPrompt system guidance and safety constraints
     * @param userMessage user message content
     * @return generated text response from GLM-4.7-Flash
     */
    public String generateChatCompletion(String systemPrompt, String userMessage) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("Cloudflare Workers AI client is not configured: missing account ID or API token");
        }

        String safePrompt = userMessage != null ? userMessage.strip() : "";
        if (safePrompt.length() > MAX_INPUT_CHARS) {
            safePrompt = safePrompt.substring(0, MAX_INPUT_CHARS);
        }

        boolean acquired = false;
        try {
            acquired = inFlightLimiter.tryAcquire(2, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Cloudflare Workers AI in-flight concurrency limit reached ({})", properties.getMaxConcurrent());
                throw new CloudflareAiException(CloudflareAiException.Reason.CONCURRENCY_LIMIT,
                        "Cloudflare Workers AI concurrency limit reached", 429);
            }

            String url = String.format("%s/accounts/%s/ai/v1/chat/completions",
                    properties.getBaseUrl().replaceAll("/+$", ""),
                    properties.getAccountId().trim());

            log.info("Dispatching Chat Completion to Cloudflare Workers AI model '{}' (account: {})",
                    properties.getModel(), maskAccountId(properties.getAccountId()));

            Map<String, Object> payload = Map.of(
                    "model", properties.getModel().trim(),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", safePrompt)
                    ),
                    "max_tokens", properties.getMaxTokens(),
                    "temperature", properties.getTemperature()
            );

            byte[] responseBytes = restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiToken().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.ALL)
                    .body(payload)
                    .retrieve()
                    .body(byte[].class);

            String responseJson = responseBytes != null ? new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8) : null;
            return parseCompletionContent(responseJson);

        } catch (RestClientResponseException ex) {
            int statusCode = ex.getStatusCode().value();
            log.warn("Cloudflare Workers AI returned HTTP {}: {}", statusCode, sanitizeErrorBody(ex.getResponseBodyAsString()));
            if (statusCode == 401 || statusCode == 403) {
                throw new CloudflareAiException(CloudflareAiException.Reason.AUTH_FAILURE,
                        "Cloudflare Workers AI authentication failed (HTTP " + statusCode + ")", statusCode, ex);
            } else if (statusCode == 429) {
                throw new CloudflareAiException(CloudflareAiException.Reason.QUOTA_EXCEEDED,
                        "Cloudflare Workers AI free daily quota exceeded or rate limited (HTTP 429)", statusCode, ex);
            } else if (statusCode >= 500) {
                throw new CloudflareAiException(CloudflareAiException.Reason.SERVER_ERROR,
                        "Cloudflare Workers AI upstream server error (HTTP " + statusCode + ")", statusCode, ex);
            } else {
                throw new CloudflareAiException(CloudflareAiException.Reason.CLIENT_ERROR,
                        "Cloudflare Workers AI request failed (HTTP " + statusCode + ")", statusCode, ex);
            }
        } catch (CloudflareAiException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            log.warn("Cloudflare Workers AI connection/timeout failure: {}", ex.getMessage());
            throw new CloudflareAiException(CloudflareAiException.Reason.TIMEOUT,
                    "Cloudflare Workers AI connection timed out", 504, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CloudflareAiException(CloudflareAiException.Reason.CONCURRENCY_LIMIT,
                    "Interrupted waiting for Cloudflare concurrency capacity", 503, ex);
        } catch (Exception ex) {
            log.warn("Cloudflare Workers AI unexpected invocation failure: {}", ex.getMessage());
            throw new CloudflareAiException(CloudflareAiException.Reason.CLIENT_ERROR,
                    "Cloudflare Workers AI request failed: " + ex.getMessage(), 500, ex);
        } finally {
            if (acquired) {
                inFlightLimiter.release();
            }
        }
    }

    String parseCompletionContent(String json) {
        if (json == null || json.isBlank()) {
            throw new CloudflareAiException(CloudflareAiException.Reason.MALFORMED_RESPONSE,
                    "Empty response received from Cloudflare Workers AI", 502);
        }
        try {
            JsonNode root = objectMapper.readTree(json);

            // 1. OpenAI Chat Completions standard format: choices[0].message.content
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode messageNode = choices.get(0).get("message");
                if (messageNode != null && messageNode.has("content") && !messageNode.get("content").isNull()) {
                    String content = messageNode.get("content").asText();
                    if (content != null && !content.isBlank() && !"null".equalsIgnoreCase(content.trim())) {
                        return content.strip();
                    }
                }
            }

            // 2. Cloudflare native format: result.response
            JsonNode resultNode = root.get("result");
            if (resultNode != null) {
                if (resultNode.isObject() && resultNode.has("response")) {
                    String content = resultNode.get("response").asText();
                    if (content != null && !content.isBlank()) {
                        return content.strip();
                    }
                } else if (resultNode.isTextual()) {
                    String content = resultNode.asText();
                    if (content != null && !content.isBlank()) {
                        return content.strip();
                    }
                }
            }

            throw new CloudflareAiException(CloudflareAiException.Reason.MALFORMED_RESPONSE,
                    "Could not extract completion content from Cloudflare response payload", 502);
        } catch (CloudflareAiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CloudflareAiException(CloudflareAiException.Reason.MALFORMED_RESPONSE,
                    "Failed to parse Cloudflare JSON response: " + ex.getMessage(), 502, ex);
        }
    }

    static String maskAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) return "<unset>";
        String trimmed = accountId.trim();
        if (trimmed.length() <= 8) return "******";
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    static String sanitizeErrorBody(String body) {
        if (body == null || body.isBlank()) return "<empty>";
        // Truncate and strip any accidental credentials or tokens
        String sanitized = body.replaceAll("(?i)(bearer\\s+)[a-zA-Z0-9_.-]+", "$1[REDACTED]")
                               .replaceAll("(?i)(api[-_]?token\\s*[:=]\\s*)[a-zA-Z0-9_.-]+", "$1[REDACTED]");
        return sanitized.length() > 300 ? sanitized.substring(0, 297) + "..." : sanitized;
    }
}

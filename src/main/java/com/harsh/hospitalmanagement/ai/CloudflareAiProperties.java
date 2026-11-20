package com.harsh.hospitalmanagement.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Cloudflare Workers AI (Free Plan Only).
 */
@Component
@ConfigurationProperties(prefix = "hms.ai.cloudflare")
public class CloudflareAiProperties {

    /**
     * Cloudflare Account ID (from environment CLOUDFLARE_ACCOUNT_ID).
     */
    private String accountId = "";

    /**
     * Cloudflare API Token with Workers AI Read/Edit permissions (from environment CLOUDFLARE_AI_TOKEN).
     */
    private String apiToken = "";

    /**
     * Exact model ID for Cloudflare Workers AI inference.
     * Default: @cf/zai-org/glm-4.7-flash
     */
    private String model = "@cf/zai-org/glm-4.7-flash";

    /**
     * Base URL for Cloudflare API v4.
     */
    private String baseUrl = "https://api.cloudflare.com/client/v4";

    /**
     * Request timeout in seconds (supports reasoning models up to 60s).
     */
    private int timeoutSeconds = 60;

    /**
     * Maximum response tokens to generate (accommodates reasoning and response content).
     */
    private int maxTokens = 1536;

    /**
     * Sampling temperature for health education generation.
     */
    private double temperature = 0.3;

    /**
     * Maximum concurrent in-flight requests to Cloudflare to respect free allocation.
     */
    private int maxConcurrent = 5;

    public boolean isConfigured() {
        return accountId != null && !accountId.isBlank() && apiToken != null && !apiToken.isBlank();
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId != null ? accountId.trim() : "";
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken != null ? apiToken.trim() : "";
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model != null && !model.isBlank() ? model.trim() : "@cf/zai-org/glm-4.7-flash";
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "https://api.cloudflare.com/client/v4";
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 60;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens > 0 ? maxTokens : 512;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature >= 0.0 && temperature <= 2.0 ? temperature : 0.3;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent > 0 ? maxConcurrent : 5;
    }
}

package com.iflytek.hy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Maps to the Node service's OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_MODEL env vars.
 */
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model;
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }
}

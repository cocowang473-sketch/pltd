package com.iflytek.hy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Maps to XFYUN environment variables for TTS and IST services.
 * TTS: XFYUN_TTS_URL, XFYUN_TTS_APP_ID, XFYUN_TTS_API_KEY, XFYUN_TTS_API_SECRET
 * IST: XFYUN_IST_URL, XFYUN_IST_APP_ID, XFYUN_IST_API_KEY, XFYUN_IST_API_SECRET
 */
@ConfigurationProperties(prefix = "xfyun")
public class XfyunProperties {

    private final Tts tts = new Tts();
    private final Ist ist = new Ist();

    public Tts getTts() {
        return tts;
    }

    public Ist getIst() {
        return ist;
    }

    public static class Tts {
        private String url = "ws://tts-api.xfyun.cn/v2/tts";
        private String appId = "";
        private String apiKey = "";
        private String apiSecret = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url == null || url.isBlank() ? this.url : url;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId == null ? "" : appId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret == null ? "" : apiSecret;
        }

        public boolean isConfigured() {
            return !appId.isBlank() && !apiKey.isBlank() && !apiSecret.isBlank();
        }
    }

    public static class Ist {
        private String url = "wss://ist-api.xfyun.cn/v2/ist";
        private String appId = "";
        private String apiKey = "";
        private String apiSecret = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url == null || url.isBlank() ? this.url : url;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId == null ? "" : appId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret == null ? "" : apiSecret;
        }

        public boolean isConfigured() {
            return !appId.isBlank() && !apiKey.isBlank() && !apiSecret.isBlank();
        }
    }
}

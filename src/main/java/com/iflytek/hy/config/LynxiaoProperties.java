package com.iflytek.hy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the iFlytek lynxiao search proxy used by /api/search and the chatbot pipeline.
 * These values were hard-coded in the Node implementation (search-proxy.js); per request they are
 * now externalized to configuration instead.
 */
@ConfigurationProperties(prefix = "lynxiao")
public class LynxiaoProperties {

    private String routeApiUrl = "http://lynxiao-search-api-hf-internal.xf-yun.com/v1/route";
    private String appId = "mia269a0";
    private String apiSecret = "91B71FDDD05A4CE69F231612";
    private String prodCode = "TEST0827";
    private int envType = 1;
    private String routeIdc = "hf";

    public String getRouteApiUrl() {
        return routeApiUrl;
    }

    public void setRouteApiUrl(String routeApiUrl) {
        this.routeApiUrl = routeApiUrl;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getProdCode() {
        return prodCode;
    }

    public void setProdCode(String prodCode) {
        this.prodCode = prodCode;
    }

    public int getEnvType() {
        return envType;
    }

    public void setEnvType(int envType) {
        this.envType = envType;
    }

    public String getRouteIdc() {
        return routeIdc;
    }

    public void setRouteIdc(String routeIdc) {
        this.routeIdc = routeIdc;
    }
}

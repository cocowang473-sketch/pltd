package com.iflytek.hy.service;

import com.iflytek.hy.config.XfyunProperties;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Java port of src/services/ist-service.js.
 * IST service uses its own independent appId/apiKey/apiSecret configuration.
 */
@Service
public class IstService {

    private static final Set<String> IST_LANGUAGES = Set.of("fr", "ar", "en");

    private final XfyunAuthService xfyunAuthService;
    private final XfyunProperties xfyunProperties;

    public IstService(XfyunAuthService xfyunAuthService, XfyunProperties xfyunProperties) {
        this.xfyunAuthService = xfyunAuthService;
        this.xfyunProperties = xfyunProperties;
    }

    public record IstAuthorization(String url, String appId, String language) {
    }

    public static String normalizeIstLanguage(String language) {
        return IST_LANGUAGES.contains(language) ? language : "fr";
    }

    public IstAuthorization createIstAuthorization(String language) {
        return createIstAuthorization(language, ZonedDateTime.now());
    }

    public IstAuthorization createIstAuthorization(String language, ZonedDateTime now) {
        XfyunProperties.Ist ist = xfyunProperties.getIst();
        var authorization = xfyunAuthService.createAuthorization(
                ist.getUrl(), ist.getAppId(), ist.getApiKey(), ist.getApiSecret(),
                "ist service is not configured", now);
        return new IstAuthorization(authorization.url(), authorization.appId(), normalizeIstLanguage(language));
    }
}

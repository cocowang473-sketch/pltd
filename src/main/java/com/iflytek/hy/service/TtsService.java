package com.iflytek.hy.service;

import com.iflytek.hy.config.XfyunProperties;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Java port of src/services/tts-service.js.
 */
@Service
public class TtsService {

    // The supplied document had French and Arabic reversed; these are the confirmed mappings.
    private static final Map<String, String> TTS_VOICES = Map.of(
            "fr", "x2_FrRgM_Jonathan",
            "ar", "x2_ArEn_Rania",
            "en", "x4_EnUk_George_assist"
    );

    private final XfyunAuthService xfyunAuthService;
    private final XfyunProperties xfyunProperties;

    public TtsService(XfyunAuthService xfyunAuthService, XfyunProperties xfyunProperties) {
        this.xfyunAuthService = xfyunAuthService;
        this.xfyunProperties = xfyunProperties;
    }

    public record TtsAuthorization(String url, String appId, String language, String voice) {
    }

    public static String normalizeTtsLanguage(String language) {
        return TTS_VOICES.containsKey(language) ? language : "fr";
    }

    public TtsAuthorization createTtsAuthorization(String language) {
        return createTtsAuthorization(language, ZonedDateTime.now());
    }

    public TtsAuthorization createTtsAuthorization(String language, ZonedDateTime now) {
        XfyunProperties.Tts tts = xfyunProperties.getTts();
        var authorization = xfyunAuthService.createAuthorization(
                tts.getUrl(), tts.getAppId(), tts.getApiKey(), tts.getApiSecret(),
                "tts service is not configured", now);
        String normalizedLanguage = normalizeTtsLanguage(language);
        return new TtsAuthorization(authorization.url(), authorization.appId(), normalizedLanguage,
                TTS_VOICES.get(normalizedLanguage));
    }
}

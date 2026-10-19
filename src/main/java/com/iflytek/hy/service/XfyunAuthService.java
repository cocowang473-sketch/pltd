package com.iflytek.hy.service;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Java port of src/services/xfyun-auth-service.js.
 * Builds the HMAC-SHA256 signed WebSocket URL iFlytek's streaming TTS/IST services require.
 */
@Service
public class XfyunAuthService {

    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    public record XfyunAuthorization(String url, String appId) {
    }

    public XfyunAuthorization createAuthorization(String serviceUrl, String appId, String apiKey, String apiSecret,
                                                    String configError, ZonedDateTime now) {
        if (isBlank(appId) || isBlank(apiKey) || isBlank(apiSecret)) {
            throw new XfyunNotConfiguredException(configError);
        }

        URI target = URI.create(serviceUrl);
        String host = target.getHost() + (target.getPort() >= 0 ? ":" + target.getPort() : "");
        String date = RFC_1123.format(now);
        String path = target.getPath() == null || target.getPath().isEmpty() ? "/" : target.getPath();
        String signatureOrigin = "host: " + host + "\n" + "date: " + date + "\n" + "GET " + path + " HTTP/1.1";
        String signature = hmacSha256Base64(apiSecret, signatureOrigin);

        String authorizationOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\", "
                + "headers=\"host date request-line\", signature=\"" + signature + "\"";
        String authorization = Base64.getEncoder()
                .encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));

        String query = (target.getQuery() == null ? "" : target.getQuery() + "&")
                + "authorization=" + urlEncode(authorization)
                + "&date=" + urlEncode(date)
                + "&host=" + urlEncode(host);

        String signedUrl = target.getScheme() + "://" + target.getAuthority() + path + "?" + query;
        return new XfyunAuthorization(signedUrl, appId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String hmacSha256Base64(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signed);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute HMAC-SHA256 signature", e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static class XfyunNotConfiguredException extends RuntimeException {
        public XfyunNotConfiguredException(String message) {
            super(message);
        }
    }
}

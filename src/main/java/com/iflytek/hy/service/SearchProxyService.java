package com.iflytek.hy.service;

import com.iflytek.hy.config.LynxiaoProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Java port of src/services/search-proxy.js.
 * Calls the iFlytek lynxiao retrieval service with route + search two-step HMAC signature auth.
 */
@Service
public class SearchProxyService {

    private static final Logger log = LoggerFactory.getLogger(SearchProxyService.class);

    private final LynxiaoProperties lynxiaoProperties;
    private final ObjectMapper objectMapper;

    public SearchProxyService(LynxiaoProperties lynxiaoProperties, ObjectMapper objectMapper) {
        this.lynxiaoProperties = lynxiaoProperties;
        this.objectMapper = objectMapper;
    }

    public record SearchDoc(String url, String title, String summary, String content) {
    }

    public record ResultDoc(String title, String summary, String url) {
    }

    public record DocGroup(String name, List<ResultDoc> docs) {
    }

    public static String searchLanguageForQuestion(String question) {
        // Default to English for PLDT Philippines
        return "en";
    }

    public List<SearchDoc> searchDocs(String query) throws Exception {
        return searchDocs(query, searchLanguageForQuestion(query));
    }

    public List<SearchDoc> searchDocs(String query, String language) throws Exception {
        JsonNode searchJson = requestSearch(query);
        List<SearchDoc> docs = extractDocsFromSearchResponse(searchJson, query);
        log.info("大搜搜索结果解析完成: query='{}', 文档数={}", query, docs.size());
        for (int i = 0; i < docs.size(); i++) {
            SearchDoc doc = docs.get(i);
            log.info("大搜文档[{}]: url='{}', title='{}', summaryLength={}, contentLength={}",
                    i + 1, doc.url(), doc.title(), doc.summary().length(), doc.content().length());
        }

        // Return empty list if no results found - let the caller handle this gracefully
        return docs;
    }

    public List<DocGroup> searchDocsGrouped(String query) throws Exception {
        return searchDocsGrouped(query, searchLanguageForQuestion(query));
    }

    public List<DocGroup> searchDocsGrouped(String query, String language) throws Exception {
        JsonNode searchJson = requestSearch(query);
        List<DocGroup> groups = extractGroupsFromSearchResponse(searchJson, query);
        int totalDocs = groups.stream().mapToInt(group -> group.docs().size()).sum();
        log.info("大搜搜索结果解析完成(分组): query='{}', 分组数={}, 文档数={}", query, groups.size(), totalDocs);
        for (int i = 0; i < groups.size(); i++) {
            DocGroup group = groups.get(i);
            log.info("大搜分组[{}]: name='{}', 文档数={}", i + 1, group.name(), group.docs().size());
            for (int j = 0; j < group.docs().size(); j++) {
                ResultDoc doc = group.docs().get(j);
                log.info("大搜分组[{}]文档[{}]: url='{}', title='{}', summaryLength={}",
                        i + 1, j + 1, doc.url(), doc.title(), doc.summary().length());
            }
        }
        return groups;
    }

    private JsonNode requestSearch(String query) throws Exception {
        // Step 1: Call route API to get search strategy
        Map<String, Object> routeBody = Map.of(
                "header", Map.of(
                        "traceId", "test-" + UUID.randomUUID(),
                        "appId", lynxiaoProperties.getAppId(),
                        "prodCode", lynxiaoProperties.getProdCode(),
                        "envType", lynxiaoProperties.getEnvType()
                ),
                "parameter", Map.of(
                        "route_info", Map.of(
                                "idc", lynxiaoProperties.getRouteIdc(),
                                "retrieval_type", 1
                        )
                )
        );

        String routeRequestJson = objectMapper.writeValueAsString(routeBody);
        log.info("大搜路由接口请求: url={}, body={}", lynxiaoProperties.getRouteApiUrl(), routeRequestJson);

        String routeResponseBody = postJson(lynxiaoProperties.getRouteApiUrl(),
                routeRequestJson,
                lynxiaoProperties.getAppId(),
                lynxiaoProperties.getApiSecret());

        log.info("大搜路由接口原始返回: {}", routeResponseBody);

        JsonNode routeJson = objectMapper.readTree(routeResponseBody);

        // Check for error in response header
        JsonNode headerNode = routeJson.path("header");
        if (!headerNode.isMissingNode()) {
            int code = headerNode.path("code").asInt(-1);
            String message = headerNode.path("message").asText("");
            if (code != 0) {
                throw new RuntimeException("route request failed: " + message);
            }
        }

        // Extract route strategy from payload (not from payload.search_strategy)
        JsonNode payloadNode = routeJson.path("payload");
        String searchUrl = payloadNode.path("regionOuterUrl").asText("");
        String token = payloadNode.path("token").asText("");
        String processId = payloadNode.path("processId").asText("");
        String regionCode = payloadNode.path("regionCode").asText("");

        if (searchUrl.isBlank() || token.isBlank() || processId.isBlank()) {
            throw new RuntimeException("route response missing required fields");
        }

        // Step 2: Call search API with the obtained strategy
        Map<String, Object> searchBody = Map.of(
                "header", Map.of(
                        "traceId", "test-" + UUID.randomUUID(),
                        "appId", lynxiaoProperties.getAppId(),
                        "prodCode", lynxiaoProperties.getProdCode(),
                        "token", token
                ),
                "parameter", Map.of(
                        "id", processId
                ),
                "payload", Map.of(
                        "query", List.of(query),
                        "topK", 10,
                        "score", 0.0001
                )
        );

        String searchRequestJson = objectMapper.writeValueAsString(searchBody);
        log.info("大搜搜索接口请求: url={}, body={}", searchUrl, searchRequestJson);

        String searchResponseBody = postJson(searchUrl,
                searchRequestJson,
                lynxiaoProperties.getAppId(),
                lynxiaoProperties.getApiSecret());

        log.info("大搜搜索接口原始返回: {}", searchResponseBody);

        return objectMapper.readTree(searchResponseBody);
    }

    private String postJson(String urlString, String jsonBody, String appId, String apiSecret) throws Exception {
        URL url = new URL(urlString);
        String host = url.getHost();
        String date = httpDate();
        String path = url.getPath().isEmpty() ? "/" : url.getPath();
        String requestLine = "POST " + path + " HTTP/1.1";
        String signatureOrigin = "host: " + host + "\n" + "date: " + date + "\n" + requestLine;
        String signature = hmacSha256Base64(apiSecret, signatureOrigin);
        String authorization = "hmac api_key=\"" + appId + "\", algorithm=\"hmac-sha256\", "
                + "headers=\"host date request-line\", signature=\"" + signature + "\"";

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Content-Type", "application/json;charset=utf-8");
        connection.setRequestProperty("Host", host);
        connection.setRequestProperty("Date", date);
        connection.setRequestProperty("Authorization", authorization);

        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(payload);
        }

        int statusCode = connection.getResponseCode();
        InputStream inputStream = statusCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        String responseBody = inputStream != null ? readAll(inputStream) : "";

        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException("HTTP request failed with status " + statusCode + ": " + responseBody);
        }

        return responseBody;
    }

    private String httpDate() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date());
    }

    private String readAll(InputStream inputStream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int bytesRead;
        try {
            while ((bytesRead = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, bytesRead);
            }
        } finally {
            inputStream.close();
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private List<SearchDoc> extractDocsFromSearchResponse(JsonNode responseJson, String query) {
        // Check response header code
        JsonNode headerNode = responseJson.path("header");
        int code = headerNode.path("code").asInt(-1);
        if (code != 0) {
            log.warn("大搜搜索接口返回异常: code={}, message={}, 原始返回: {}",
                    code, headerNode.path("message").asText(""), responseJson.toString());
            return List.of();
        }

        JsonNode outputNode = responseJson.path("payload").path("output");

        // Parse data field
        List<JsonNode> dataArray = parseSearchOutputData(outputNode.path("data"));

        // Parse app_function_data field
        List<JsonNode> appFunctionDataArray = parseSearchOutputData(outputNode.path("app_function_data"));

        List<SearchDoc> docs = new ArrayList<>();

        // Extract docs from data
        for (JsonNode item : dataArray) {
            JsonNode docsNode = item.path("docs");
            if (docsNode.isArray()) {
                for (JsonNode doc : docsNode) {
                    if (doc != null && doc.isObject()) {
                        docs.add(normalizeSearchDoc(doc, query));
                    }
                }
            }
        }

        // Extract docs from app_function_data
        for (JsonNode item : appFunctionDataArray) {
            JsonNode docsNode = item.path("docs");
            if (docsNode.isArray()) {
                for (JsonNode doc : docsNode) {
                    if (doc != null && doc.isObject()) {
                        docs.add(normalizeAppFunctionDoc(doc, query));
                    }
                }
            }
        }

        // Deduplicate docs
        return dedupeDocs(docs);
    }

    private List<DocGroup> extractGroupsFromSearchResponse(JsonNode responseJson, String query) {
        JsonNode headerNode = responseJson.path("header");
        int code = headerNode.path("code").asInt(-1);
        if (code != 0) {
            log.warn("大搜搜索接口返回异常: code={}, message={}, 原始返回: {}",
                    code, headerNode.path("message").asText(""), responseJson.toString());
            return List.of();
        }

        JsonNode outputNode = responseJson.path("payload").path("output");
        List<JsonNode> dataArray = parseSearchOutputData(outputNode.path("data"));
        List<JsonNode> appFunctionDataArray = parseSearchOutputData(outputNode.path("app_function_data"));

        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTextKeys = new HashSet<>();
        List<DocGroup> groups = new ArrayList<>();

        collectGroupDocs(dataArray, seenUrls, seenTextKeys, groups, false);
        collectGroupDocs(appFunctionDataArray, seenUrls, seenTextKeys, groups, true);

        return groups;
    }

    private void collectGroupDocs(List<JsonNode> items, Set<String> seenUrls, Set<String> seenTextKeys,
                                  List<DocGroup> groups, boolean appFunction) {
        for (JsonNode item : items) {
            JsonNode docsNode = item.path("docs");
            if (!docsNode.isArray()) {
                continue;
            }

            String groupName = item.path("type").isTextual() ? item.path("type").asText() : "";
            List<ResultDoc> docs = new ArrayList<>();

            for (JsonNode doc : docsNode) {
                if (doc == null || !doc.isObject()) {
                    continue;
                }
                String title = doc.path("title").isTextual() ? doc.path("title").asText() : "";
                String summary = doc.path("summary").isTextual() ? doc.path("summary").asText() : "";
                String url = appFunction
                        ? doc.path("navigation").asText("").trim()
                        : doc.path("url").asText("");
                String textKey = (title + summary).trim();

                if (!url.isBlank() && seenUrls.contains(url)) {
                    continue;
                }
                if (!textKey.isBlank() && seenTextKeys.contains(textKey)) {
                    continue;
                }

                docs.add(new ResultDoc(title, summary, url));
                if (!url.isBlank()) {
                    seenUrls.add(url);
                }
                if (!textKey.isBlank()) {
                    seenTextKeys.add(textKey);
                }
            }

            if (!docs.isEmpty()) {
                groups.add(new DocGroup(groupName, docs));
            }
        }
    }

    private List<JsonNode> parseSearchOutputData(JsonNode dataNode) {
        // If it's already an array, return it
        if (dataNode.isArray()) {
            List<JsonNode> result = new ArrayList<>();
            dataNode.forEach(result::add);
            return result;
        }

        // If it's a string, try to parse it as JSON
        if (dataNode.isTextual()) {
            String dataString = dataNode.asText().trim();
            if (dataString.isEmpty()) {
                return List.of();
            }

            try {
                JsonNode parsed = objectMapper.readTree(dataString);
                if (parsed.isArray()) {
                    List<JsonNode> result = new ArrayList<>();
                    parsed.forEach(result::add);
                    return result;
                }
            } catch (Exception e) {
                // Parse failed, return empty
            }
        }

        return List.of();
    }

    private SearchDoc normalizeSearchDoc(JsonNode doc, String query) {
        String title = doc.path("title").isTextual() ? doc.path("title").asText() : "";
        String summary = doc.path("summary").isTextual() ? doc.path("summary").asText() : "";
        String content = doc.path("content").isTextual() ? doc.path("content").asText() : "";
        String url = buildDocUrl(doc);

        return new SearchDoc(url, title, summary, content);
    }

    private SearchDoc normalizeAppFunctionDoc(JsonNode doc, String query) {
        String title = doc.path("title").isTextual() ? doc.path("title").asText() : "";
        String summary = doc.path("summary").isTextual() ? doc.path("summary").asText() : "";
        String url = buildNavigationUrl(doc);

        // app_function_data docs typically don't have content
        return new SearchDoc(url, title, summary, "");
    }

    private String buildDocUrl(JsonNode doc) {
        String url = doc.path("url").asText("");
        if (!url.isBlank()) {
            return url;
        }
        // Fallback: construct from other fields if needed
        return "";
    }

    private String buildNavigationUrl(JsonNode doc) {
        String navigation = doc.path("navigation").asText("").trim();
        if (!navigation.isBlank()) {
            return navigation;
        }
        return "";
    }

    private List<SearchDoc> dedupeDocs(List<SearchDoc> docs) {
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTextKeys = new HashSet<>();
        List<SearchDoc> result = new ArrayList<>();

        for (SearchDoc doc : docs) {
            String textKey = buildDocTextKey(doc);

            // Skip if URL already seen
            if (!doc.url().isBlank() && seenUrls.contains(doc.url())) {
                continue;
            }

            // Skip if text content already seen
            if (!textKey.isBlank() && seenTextKeys.contains(textKey)) {
                continue;
            }

            // Add to result and mark as seen
            result.add(doc);
            if (!doc.url().isBlank()) {
                seenUrls.add(doc.url());
            }
            if (!textKey.isBlank()) {
                seenTextKeys.add(textKey);
            }
        }

        return result;
    }

    private String buildDocTextKey(SearchDoc doc) {
        return (doc.title() + doc.summary() + doc.content()).trim();
    }

    private static String hmacSha256Base64(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signed);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }
}

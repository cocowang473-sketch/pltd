package com.iflytek.hy.service;

import com.iflytek.hy.config.OpenAiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Java port of src/services/chatbot-service.js.
 * Orchestrates query planning, search, streaming answer generation, and citation validation.
 */
@Service
public class ChatbotService {

    private static final Logger log = LoggerFactory.getLogger(ChatbotService.class);

    private static final int ANSWER_SOURCE_LIMIT = 5;
    private static final int CITATION_ANSWER_PREVIEW_LIMIT = 1000;
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("en");
    private static final Map<String, String> STATUS_MESSAGES = Map.of(
            "en", "Understanding your question",
            "searching", "Searching",
            "generating", "Generating an answer"
    );

    private final OpenAiProperties openAiProperties;
    private final SearchProxyService searchProxyService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ChatbotService(OpenAiProperties openAiProperties, SearchProxyService searchProxyService, ObjectMapper objectMapper) {
        this.openAiProperties = openAiProperties;
        this.searchProxyService = searchProxyService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record ChatbotAnswer(String answer, List<Citation> citations, List<SearchProxyService.SearchDoc> results) {
    }

    public record Citation(int index, String url, String title) {
    }

    public record QueryPlan(String query, String answerLanguage) {
    }

    public static class ChatbotException extends RuntimeException {
        public String stage;
        public Map<String, Object> diagnostics;

        public ChatbotException(String message) {
            super(message);
        }
    }

    private void checkConfig() {
        log.info("检查配置: baseUrl={}, model={}, apiKey存在={}",
            openAiProperties.getBaseUrl(),
            openAiProperties.getModel(),
            openAiProperties.getApiKey() != null && !openAiProperties.getApiKey().isEmpty());

        if (!openAiProperties.isConfigured()) {
            log.error("chatbot 配置不完整");
            ChatbotException ex = new ChatbotException("chatbot service is not configured");
            ex.stage = "config";
            throw ex;
        }
    }

    private String getLanguageName(String code) {
        return "English";
    }

    private String localizedStatus(String language, String key) {
        return STATUS_MESSAGES.getOrDefault(key, "");
    }

    public ChatbotAnswer answerChatbotQuestion(String message, String userLanguage, Consumer<Map<String, Object>> onEvent) throws Exception {
        log.info("收到问题: {}, 用户选择语言: {}", message, userLanguage);
        checkConfig();

        String answerLanguage;

        // If user specified a language, use it; otherwise detect from message
        if (userLanguage != null && !userLanguage.isBlank() && SUPPORTED_LANGUAGES.contains(userLanguage.toLowerCase())) {
            answerLanguage = userLanguage.toLowerCase();
            log.info("使用用户指定语言: {}", answerLanguage);
        } else {
            answerLanguage = SearchProxyService.searchLanguageForQuestion(message);
            log.info("自动检测到语言: {}", answerLanguage);
        }

        onEvent.accept(Map.of("type", "status", "message", localizedStatus(answerLanguage, "understanding")));

        // Step 1: Query planning (SKIP - 直接使用用户消息作为搜索关键词)
        // log.info("开始查询规划...");
        // String queryPrompt = String.format(
        //         "Extract concise search keywords for PLDT Philippines. The user wants the answer in English. Return JSON only: {\"query\":\"keywords\",\"answerLanguage\":\"en\"}."
        // );
        // List<Map<String, String>> queryMessages = List.of(
        //         Map.of("role", "system", "content", queryPrompt),
        //         Map.of("role", "user", "content", message)
        // );
        //
        // String queryResponse;
        // try {
        //     queryResponse = requestCompletion(queryMessages);
        //     log.info("查询规划响应: {}", queryResponse);
        // } catch (Exception e) {
        //     log.error("查询规划失败", e);
        //     throw e;
        // }
        //
        // QueryPlan queryPlan = parseQueryPlan(queryResponse);
        // String query = queryPlan.query();

        // 直接使用用户发送的消息作为搜索关键词
        String query = message;
        log.info("搜索关键词（原始消息）: {}, 回答语言: {}", query, answerLanguage);

        onEvent.accept(Map.of("type", "query", "query", query, "answerLanguage", answerLanguage));
        onEvent.accept(Map.of("type", "status", "message", localizedStatus(answerLanguage, "searching")));

        // Step 2: Search for relevant documents
        log.info("开始搜索文档...");
        List<SearchProxyService.SearchDoc> docs;
        try {
            // Use language detected from original message, not from query keywords
            String searchLanguage = SearchProxyService.searchLanguageForQuestion(message);
            docs = searchProxyService.searchDocs(query, searchLanguage);
            log.info("找到 {} 个文档", docs.size());
        } catch (Exception e) {
            log.error("搜索文档失败", e);
            throw e;
        }

        onEvent.accept(Map.of("type", "search", "count", docs.size()));

        List<SearchProxyService.SearchDoc> sourceDocs = docs.stream().limit(ANSWER_SOURCE_LIMIT).toList();

        // 如果没有找到任何文档，直接返回友好提示，不调用 LLM
        if (sourceDocs.isEmpty()) {
            log.warn("未找到任何相关文档，返回默认提示");
            String noResultAnswer = "Hello, and thank you for contacting PLDT! 😊\n\n" +
                    "I apologize, but I couldn't find any relevant information in our knowledge base to answer your question. " +
                    "This might be because:\n\n" +
                    "• Your question is about a topic not covered in our current documentation\n" +
                    "• The search terms didn't match our existing articles\n\n" +
                    "For immediate assistance, I recommend:\n" +
                    "• Contacting our customer service hotline\n" +
                    "• Visiting our website at pldt.com\n" +
                    "• Trying to rephrase your question with more specific details\n\n" +
                    "I'm here to help with any other questions you might have!";

            onEvent.accept(Map.of("type", "token", "text", noResultAnswer));

            return new ChatbotAnswer(noResultAnswer, List.of(), List.of());
        }

        onEvent.accept(Map.of("type", "status", "message", localizedStatus(answerLanguage, "generating")));

        // Step 3: Streaming answer generation
        log.info("开始生成回答...");
        String systemPrompt = String.join(" ",
                "You are a customer-service assistant for PLDT, the Philippines' leading telecommunications provider.",
                "Remain courteous and humble, and communicate in a gentle, patient manner.",
                "Begin each reply with a polite greeting.",
                "When a customer complains, acknowledge and soothe their feelings before offering a solution.",
                "If an issue is outside your authority, politely advise the customer that they will be transferred to human-service representatives.",
                "Use clear, easy-to-follow language and avoid complex technical terms.",
                "Answer only from the supplied sources.",
                "Respond in English.",
                "Write a natural-language answer with [n] source markers for every factual claim.",
                "You MUST include at least one [n] citation in your answer, even if asking for clarification."
        );
        String userPrompt = "Question: " + message + "\n\nSources:\n" + formatDocuments(sourceDocs);
        List<Map<String, String>> answerMessages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        );

        String answer;
        try {
            answer = requestStreamingCompletion(answerMessages, token -> {
                onEvent.accept(Map.of("type", "token", "text", token));
            });
            log.info("生成的回答长度: {} 字符", answer.length());
        } catch (Exception e) {
            log.error("生成回答失败", e);
            throw e;
        }

        // Step 4: Citation extraction and validation
        log.info("提取引用...");
        List<Integer> citationIndexes = extractCitationIndexes(answer);
        List<Citation> citations;
        try {
            citations = selectCitations(answer, citationIndexes, sourceDocs);
        } catch (RuntimeException e) {
            log.error("引用验证失败: answerLength={}, citationIndexes={}, sourceDocsCount={}",
                answer.length(), citationIndexes, sourceDocs.size());
            log.error("实际回答内容:\n{}", answer);

            ChatbotException ex = new ChatbotException(
                "Citation validation failed: " + e.getMessage() +
                " | Answer length: " + answer.length() +
                " | Citation indexes: " + citationIndexes +
                " | Source docs count: " + sourceDocs.size()
            );
            ex.stage = "citation";
            ex.diagnostics = Map.of(
                "answer", answer,
                "citationIndexes", citationIndexes.toString(),
                "sourceDocsCount", sourceDocs.size()
            );
            throw ex;
        }
        List<SearchProxyService.SearchDoc> results = getAllValidResults(docs);

        log.info("完成！返回 {} 个引用, {} 个结果", citations.size(), results.size());
        return new ChatbotAnswer(answer, citations, results);
    }

    private String requestCompletion(List<Map<String, String>> messages) throws Exception {
        String url = openAiProperties.getBaseUrl() + "/chat/completions";
        Map<String, Object> payload = Map.of(
                "model", openAiProperties.getModel(),
                "messages", messages,
                "temperature", 0,
                "thinking", Map.of("type", "disabled")
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + openAiProperties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("chatbot model request failed with status " + response.statusCode());
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode contentNode = json.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || !contentNode.isTextual()) {
            throw new RuntimeException("chatbot model response is invalid");
        }
        return contentNode.asText().trim();
    }

    private String requestStreamingCompletion(List<Map<String, String>> messages, Consumer<String> onToken) throws Exception {
        String url = openAiProperties.getBaseUrl() + "/chat/completions";
        Map<String, Object> payload = Map.of(
                "model", openAiProperties.getModel(),
                "messages", messages,
                "temperature", 0,
                "stream", true,
                "thinking", Map.of("type", "disabled")
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + openAiProperties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("chatbot model request failed with status " + response.statusCode());
        }

        // Parse SSE stream line by line
        StringBuilder answer = new StringBuilder();
        boolean receivedDone = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        receivedDone = true;
                        break;
                    }
                    if (data.isEmpty()) {
                        continue;
                    }
                    try {
                        JsonNode chunk = objectMapper.readTree(data);
                        JsonNode choice = chunk.path("choices").path(0);
                        if ("stop".equals(choice.path("finish_reason").asText(null))) {
                            receivedDone = true;
                        }
                        String text = choice.path("delta").path("content").asText(null);
                        if (text != null && !text.isEmpty()) {
                            answer.append(text);
                            onToken.accept(text);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        if (!receivedDone) {
            ChatbotException ex = new ChatbotException("chatbot model stream ended before completion");
            ex.stage = "answer";
            throw ex;
        }
        return answer.toString().trim();
    }

    private QueryPlan parseQueryPlan(String content) {
        try {
            // Remove markdown code blocks if present
            String cleanedContent = content.trim();
            if (cleanedContent.startsWith("```")) {
                cleanedContent = cleanedContent.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "").trim();
            }

            JsonNode plan = objectMapper.readTree(cleanedContent);
            String query = plan.path("query").asText("").trim();

            // Support both answerLanguage and language fields
            String languageValue = plan.path("answerLanguage").asText("");
            if (languageValue.isEmpty()) {
                languageValue = plan.path("language").asText("");
            }
            String answerLanguage = languageValue.trim().toLowerCase();

            if (!query.isBlank()) {
                answerLanguage = SUPPORTED_LANGUAGES.contains(answerLanguage) ? answerLanguage : "fr";
                return new QueryPlan(query, answerLanguage);
            }
        } catch (Exception e) {
            log.warn("查询规划解析失败，使用原内容作为查询: {}", e.getMessage());
        }

        // Fallback: use the content as-is if parsing fails
        return new QueryPlan(content.trim(), "fr");
    }

    private String formatDocuments(List<SearchProxyService.SearchDoc> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(docs.size(), ANSWER_SOURCE_LIMIT); i++) {
            SearchProxyService.SearchDoc doc = docs.get(i);
            sb.append("[").append(i + 1).append("]\n");
            sb.append("Title: ").append(doc.title()).append("\n");
            sb.append("Summary: ").append(doc.summary()).append("\n");
            sb.append("Content: ").append(doc.content()).append("\n");
            sb.append("URL: ").append(doc.url()).append("\n\n");
        }
        return sb.toString();
    }

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)\\]");

    private List<Integer> extractCitationIndexes(String answer) {
        List<Integer> indexes = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            String group = matcher.group(1);
            for (String part : group.split(",")) {
                try {
                    indexes.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return indexes;
    }

    private boolean isHttpUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return "http".equals(scheme) || "https".equals(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    private List<Citation> validateCitations(List<Integer> citationIndexes, List<SearchProxyService.SearchDoc> docs) {
        if (citationIndexes == null) {
            return List.of();
        }

        Set<Integer> seen = new HashSet<>();
        List<Citation> result = new ArrayList<>();

        for (Integer index : citationIndexes) {
            if (index == null || index < 1 || seen.contains(index)) {
                continue;
            }

            if (index > docs.size()) {
                continue;
            }

            SearchProxyService.SearchDoc doc = docs.get(index - 1);
            if (doc == null || !isHttpUrl(doc.url())) {
                continue;
            }

            seen.add(index);
            String title = doc.title() != null && !doc.title().isBlank() ? doc.title() : "Source";
            result.add(new Citation(index, doc.url(), title));
        }

        return result;
    }

    private List<Citation> selectCitations(String answer, List<Integer> answerMarkerIndexes, List<SearchProxyService.SearchDoc> sourceDocs) {
        // Get all valid citations from source docs
        List<Integer> allIndexes = new ArrayList<>();
        for (int i = 1; i <= sourceDocs.size(); i++) {
            allIndexes.add(i);
        }
        List<Citation> availableCitations = validateCitations(allIndexes, sourceDocs);

        log.info("引用选择: answerMarkerIndexes={}, availableCitationsCount={}, sourceDocsCount={}",
            answerMarkerIndexes, availableCitations.size(), sourceDocs.size());

        // Build a map of index -> citation
        Map<Integer, Citation> citationMap = new HashMap<>();
        for (Citation citation : availableCitations) {
            citationMap.put(citation.index(), citation);
        }

        log.info("CitationMap keys: {}", citationMap.keySet());

        // Select only the citations that appear in the answer
        List<Citation> selected = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();

        for (Integer index : answerMarkerIndexes) {
            Citation citation = citationMap.get(index);
            if (citation == null) {
                log.error("引用验证失败: 请求的索引 {} 不存在于 citationMap 中", index);
                log.error("citationMap 包含的索引: {}", citationMap.keySet());
                log.error("源文档数量: {}", sourceDocs.size());
                log.error("有效引用数量: {}", availableCitations.size());
                throw new RuntimeException("Citation index " + index + " not found in source docs. " +
                    "Available indexes: " + citationMap.keySet() + ", Source docs: " + sourceDocs.size());
            }

            if (!seen.contains(index)) {
                seen.add(index);
                selected.add(citation);
            }
        }

        if (selected.isEmpty()) {
            throw new RuntimeException("chatbot model response is invalid");
        }

        return selected;
    }

    private List<SearchProxyService.SearchDoc> getAllValidResults(List<SearchProxyService.SearchDoc> docs) {
        List<Integer> allIndexes = new ArrayList<>();
        for (int i = 1; i <= docs.size(); i++) {
            allIndexes.add(i);
        }
        List<Citation> validCitations = validateCitations(allIndexes, docs);

        // Convert back to SearchDoc list
        return validCitations.stream()
                .map(c -> docs.get(c.index() - 1))
                .toList();
    }
}

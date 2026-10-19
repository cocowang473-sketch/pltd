package com.iflytek.hy.controller;

import com.iflytek.hy.service.IstService;
import com.iflytek.hy.service.SearchProxyService;
import com.iflytek.hy.service.TtsService;
import com.iflytek.hy.service.XfyunAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST controllers matching the Node router.js routes.
 */
@RestController
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final TtsService ttsService;
    private final IstService istService;
    private final SearchProxyService searchProxyService;
    private final ObjectMapper objectMapper;

    public ApiController(TtsService ttsService, IstService istService,
                         SearchProxyService searchProxyService,
                         ObjectMapper objectMapper) {
        this.ttsService = ttsService;
        this.istService = istService;
        this.searchProxyService = searchProxyService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/api/tts-auth")
    public ResponseEntity<?> ttsAuth(@RequestParam(required = false) String language) {
        try {
            var payload = ttsService.createTtsAuthorization(language);
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(payload);
        } catch (XfyunAuthService.XfyunNotConfiguredException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Cache-Control", "no-store")
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("Cache-Control", "no-store")
                    .body(Map.of("error", "tts authorization failed"));
        }
    }

    @GetMapping("/api/ist-auth")
    public ResponseEntity<?> istAuth(@RequestParam(required = false) String language) {
        try {
            var payload = istService.createIstAuthorization(language);
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(payload);
        } catch (XfyunAuthService.XfyunNotConfiguredException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Cache-Control", "no-store")
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("Cache-Control", "no-store")
                    .body(Map.of("error", "ist authorization failed"));
        }
    }

    @PostMapping("/api/search")
    public ResponseEntity<?> search(@RequestBody Map<String, Object> body) {
        String query = body.get("query") instanceof String q ? q.trim() : "";
        if (query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "la requête est obligatoire"));
        }

        try {
            var docs = searchProxyService.searchDocs(query);
            return ResponseEntity.ok(Map.of("docs", docs));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/api/chatbot", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatbot(@RequestBody Map<String, Object> body) {
        String message = body.get("message") instanceof String m ? m.trim() : "";

        log.info("接收到聊天请求: message='{}'", message);

        SseEmitter emitter = new SseEmitter(0L);
        if (message.isBlank()) {
            log.warn("消息为空");
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("error", "Impossible de répondre à votre question pour le moment")));
                emitter.send(SseEmitter.event().name("done").data(Map.of()));
                emitter.complete();
            } catch (IOException e) {
                log.error("发送错误消息失败", e);
                emitter.completeWithError(e);
            }
            return emitter;
        }

        new Thread(() -> {
            try {
                emitter.send(SseEmitter.event().name("status").data(Map.of(
                        "type", "status", "message", "Understanding your question")));

                emitter.send(SseEmitter.event().name("query").data(Map.of(
                        "type", "query", "query", message)));

                emitter.send(SseEmitter.event().name("status").data(Map.of(
                        "type", "status", "message", "Searching")));

                List<SearchProxyService.DocGroup> groups = searchProxyService.searchDocsGrouped(message);
                int totalDocs = groups.stream().mapToInt(group -> group.docs().size()).sum();

                emitter.send(SseEmitter.event().name("search").data(Map.of(
                        "type", "search", "count", totalDocs)));

                emitter.send(SseEmitter.event().name("results").data(Map.of(
                        "type", "results", "groups", groups)));

                emitter.send(SseEmitter.event().name("done").data(Map.of()));
                emitter.complete();
                log.info("搜索请求处理完成: 分组数={}, 文档数={}", groups.size(), totalDocs);
            } catch (Exception e) {
                log.error("搜索请求处理失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of(
                            "error", e.getMessage() != null ? e.getMessage() : "search failed")));
                    emitter.send(SseEmitter.event().name("done").data(Map.of()));
                    emitter.complete();
                } catch (IOException ex) {
                    log.error("发送异常响应失败", ex);
                    emitter.completeWithError(ex);
                }
            }
        }).start();

        return emitter;
    }
}

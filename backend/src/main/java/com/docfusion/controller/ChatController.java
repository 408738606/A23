package com.docfusion.controller;

import com.docfusion.model.*;
import com.docfusion.service.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Autowired private LlmService llmService;
    @Autowired private ChatMessageRepo chatMessageRepo;
    @Autowired private DocumentExtractService extractService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private static final String SYSTEM_PROMPT =
        "你是DocFusion智能文档助手，专门帮助用户处理文档理解、信息提取和数据融合任务。\n"
        + "你能够：\n1. 理解并分析上传到知识库的文档内容（支持docx、xlsx、md、txt格式）\n"
        + "2. 从非结构化文档中自动提取关键信息、实体数据\n"
        + "3. 根据用户要求智能填写表格模板\n"
        + "4. 对文档内容进行深度语义理解和多源信息整合\n\n"
        + "当用户要求提取信息时，请以结构化格式输出。回答要专业、准确。";

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String message,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) List<Long> selectedDocIds,
            @RequestParam(required = false) Long llmConfigId) {

        SseEmitter emitter = new SseEmitter(180_000L);
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();

        executor.submit(() -> {
            try {
                LlmConfig config = llmService.getConfigById(llmConfigId);
                if (config == null) {
                    emitter.send(SseEmitter.event().name("error").data("未找到模型配置，请先在【设置】中添加大模型配置"));
                    emitter.complete(); return;
                }

                ChatMessage userMsg = new ChatMessage();
                userMsg.setSessionId(sid); userMsg.setRole("user"); userMsg.setContent(message);
                chatMessageRepo.save(userMsg);

                String contextualMessage = message;
                if (selectedDocIds != null && !selectedDocIds.isEmpty()) {
                    contextualMessage = extractService.buildKnowledgeContext(selectedDocIds) + "\n\n用户问题：" + message;
                }

                List<ChatMessage> history = chatMessageRepo.findBySessionIdOrderByCreatedAtAsc(sid);
                int skip = Math.max(0, history.size() - 10);
                List<Map<String, String>> messages = llmService.buildMessages(
                        SYSTEM_PROMPT, history.subList(skip, history.size()), contextualMessage);

                emitter.send(SseEmitter.event().name("sessionId").data(sid));

                StringBuilder collected = new StringBuilder();
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(180, TimeUnit.SECONDS).build();

                Request request = llmService.buildStreamRequest(config, messages);
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        String err = response.body() != null ? response.body().string() : "";
                        emitter.send(SseEmitter.event().name("error").data("模型调用失败(" + response.code() + "): " + err));
                        emitter.complete(); return;
                    }
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String token = llmService.parseStreamToken(line, config.getProvider());
                            if (token == null) continue;
                            if ("[DONE]".equals(token)) break;
                            collected.append(token);
                            emitter.send(SseEmitter.event().name("token").data(token));
                        }
                    }
                }

                ChatMessage assistantMsg = new ChatMessage();
                assistantMsg.setSessionId(sid); assistantMsg.setRole("assistant");
                assistantMsg.setContent(collected.toString());
                chatMessageRepo.save(assistantMsg);

                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE stream error", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("服务器错误: " + e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        });
        return emitter;
    }

    @PostMapping("/send")
    public ResponseEntity<?> sendMessage(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> req) {
        try {
            String sid = (String) req.getOrDefault("sessionId", UUID.randomUUID().toString());
            String message = (String) req.get("message");
            @SuppressWarnings("unchecked")
            List<Integer> rawIds = (List<Integer>) req.getOrDefault("selectedDocIds", List.of());
            List<Long> docIds = rawIds.stream().map(Long::valueOf).toList();
            Object cfgId = req.get("llmConfigId");
            Long llmConfigId = cfgId != null ? Long.valueOf(cfgId.toString()) : null;

            LlmConfig config = llmService.getConfigById(llmConfigId);
            if (config == null)
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "未找到模型配置"));

            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(sid); userMsg.setRole("user"); userMsg.setContent(message);
            chatMessageRepo.save(userMsg);

            String contextualMessage = message;
            if (!docIds.isEmpty()) {
                contextualMessage = extractService.buildKnowledgeContext(docIds) + "\n\n用户问题：" + message;
            }

            List<ChatMessage> history = chatMessageRepo.findBySessionIdOrderByCreatedAtAsc(sid);
            int skip = Math.max(0, history.size() - 10);
            List<Map<String, String>> messages = llmService.buildMessages(
                    SYSTEM_PROMPT, history.subList(skip, history.size()), contextualMessage);

            String response = llmService.chat(config, messages);

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setSessionId(sid); assistantMsg.setRole("assistant"); assistantMsg.setContent(response);
            chatMessageRepo.save(assistantMsg);

            return ResponseEntity.ok(Map.of("success", true, "data",
                    Map.of("sessionId", sid, "content", response)));
        } catch (Exception e) {
            log.error("Chat send error", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<?> getHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", chatMessageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId)));
    }

    @DeleteMapping("/history/{sessionId}")
    public ResponseEntity<?> clearHistory(@PathVariable String sessionId) {
        chatMessageRepo.deleteBySessionId(sessionId);
        return ResponseEntity.ok(Map.of("success", true, "message", "会话历史已清除"));
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> getSessions() {
        List<String> sessions = chatMessageRepo.findAll().stream()
                .map(ChatMessage::getSessionId).distinct().toList();
        return ResponseEntity.ok(Map.of("success", true, "data", sessions));
    }
}

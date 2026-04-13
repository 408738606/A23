package com.docfusion.controller;

import com.docfusion.model.LlmConfig;
import com.docfusion.model.LlmConfigRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/llm-config")
public class LlmConfigController {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigController.class);

    @Autowired private LlmConfigRepo llmConfigRepo;
    @Autowired private ObjectMapper objectMapper;

    @GetMapping("/list")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of("success", true, "data", llmConfigRepo.findAll()));
    }

    @GetMapping("/default")
    public ResponseEntity<?> getDefault() {
        return llmConfigRepo.findByIsDefaultTrue()
                .map(c -> ResponseEntity.ok(Map.of("success", true, "data", c)))
                .orElse(ResponseEntity.ok(Map.of("success", false, "message", "暂无默认配置")));
    }

    @PostMapping("/save")
    public ResponseEntity<?> save(@org.springframework.web.bind.annotation.RequestBody LlmConfig config) {
        try {
            if (Boolean.TRUE.equals(config.getIsDefault())) {
                llmConfigRepo.findByIsDefaultTrue().ifPresent(old -> {
                    if (config.getId() == null || !old.getId().equals(config.getId())) {
                        old.setIsDefault(false);
                        llmConfigRepo.save(old);
                    }
                });
            }
            LlmConfig saved = llmConfigRepo.save(config);
            return ResponseEntity.ok(Map.of("success", true, "data", saved, "message", "保存成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @org.springframework.web.bind.annotation.RequestBody LlmConfig config) {
        if (!llmConfigRepo.existsById(id)) return ResponseEntity.notFound().build();
        config.setId(id);
        if (Boolean.TRUE.equals(config.getIsDefault())) {
            llmConfigRepo.findByIsDefaultTrue().ifPresent(old -> {
                if (!old.getId().equals(id)) { old.setIsDefault(false); llmConfigRepo.save(old); }
            });
        }
        return ResponseEntity.ok(Map.of("success", true, "data", llmConfigRepo.save(config)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!llmConfigRepo.existsById(id)) return ResponseEntity.notFound().build();
        llmConfigRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "已删除"));
    }

    @PostMapping("/{id}/set-default")
    public ResponseEntity<?> setDefault(@PathVariable Long id) {
        return llmConfigRepo.findById(id).map(config -> {
            llmConfigRepo.findByIsDefaultTrue().ifPresent(old -> {
                old.setIsDefault(false); llmConfigRepo.save(old);
            });
            config.setIsDefault(true);
            llmConfigRepo.save(config);
            return ResponseEntity.ok(Map.of("success", true, "message", "已设为默认"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/test")
    public ResponseEntity<?> testConnection(@org.springframework.web.bind.annotation.RequestBody LlmConfig config) {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS).build();

            if ("ollama".equalsIgnoreCase(config.getProvider())) {
                Request req = new Request.Builder().url(config.getBaseUrl() + "/api/tags").get().build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful()) return ResponseEntity.ok(Map.of("success", true, "message", "Ollama连接成功"));
                    return ResponseEntity.ok(Map.of("success", false, "message", "Ollama连接失败: " + resp.code()));
                }
            } else {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", config.getModelName());
                body.put("max_tokens", 5);
                ArrayNode msgs = body.putArray("messages");
                ObjectNode msg = msgs.addObject();
                msg.put("role", "user"); msg.put("content", "hi");

                Request.Builder rb = new Request.Builder()
                        .url(config.getBaseUrl() + "/chat/completions")
                        .post(okhttp3.RequestBody.create(objectMapper.writeValueAsString(body),
                                okhttp3.MediaType.parse("application/json")));
                if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                    rb.header("Authorization", "Bearer " + config.getApiKey());
                }
                try (Response resp = client.newCall(rb.build()).execute()) {
                    if (resp.isSuccessful()) return ResponseEntity.ok(Map.of("success", true, "message", "API连接成功"));
                    String err = resp.body() != null ? resp.body().string() : "";
                    return ResponseEntity.ok(Map.of("success", false, "message", "连接失败(" + resp.code() + "): " + err));
                }
            }
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "连接异常: " + e.getMessage()));
        }
    }
}

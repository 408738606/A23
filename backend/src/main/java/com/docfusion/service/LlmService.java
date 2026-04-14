package com.docfusion.service;

import com.docfusion.model.ChatMessage;
import com.docfusion.model.LlmConfig;
import com.docfusion.model.LlmConfigRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    @Autowired private LlmConfigRepo llmConfigRepo;
    @Autowired private ObjectMapper objectMapper;

    // Default client for streaming / regular chat
    private final OkHttpClient defaultClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    // Long-timeout client for table fill (multi-batch, may take minutes)
    private final OkHttpClient longClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    // ── Public API ──────────────────────────────────────────────────────────

    /** Standard chat call (up to 3min read timeout) */
    public String chat(LlmConfig config, List<Map<String, String>> messages) throws Exception {
        return doChat(config, messages, defaultClient);
    }

    /** Long-running chat call (up to 10min) - for table fill batches */
    public String chatLong(LlmConfig config, List<Map<String, String>> messages) throws Exception {
        return doChat(config, messages, longClient);
    }

    public LlmConfig getDefaultConfig() {
        return llmConfigRepo.findByIsDefaultTrue()
                .orElseGet(() -> {
                    List<LlmConfig> cfgs = llmConfigRepo.findByIsActiveTrue();
                    return cfgs.isEmpty() ? null : cfgs.get(0);
                });
    }

    public LlmConfig getConfigById(Long id) {
        if (id == null) return getDefaultConfig();
        return llmConfigRepo.findById(id).orElse(getDefaultConfig());
    }

    public List<Map<String, String>> buildMessages(String systemPrompt,
                                                    List<ChatMessage> history,
                                                    String userMessage) {
        List<Map<String, String>> msgs = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank())
            msgs.add(Map.of("role", "system", "content", systemPrompt));
        for (ChatMessage h : history)
            msgs.add(Map.of("role", h.getRole(), "content", h.getContent()));
        msgs.add(Map.of("role", "user", "content", userMessage));
        return msgs;
    }

    /** Parse one SSE/NDJSON line → token text, "[DONE]", or null */
    public String parseStreamToken(String line, String provider) {
        try {
            if ("ollama".equalsIgnoreCase(provider)) {
                if (line == null || line.isBlank()) return null;
                JsonNode node = objectMapper.readTree(line);
                if (node.path("done").asBoolean(false)) return "[DONE]";
                String t = node.path("message").path("content").asText();
                return t.isEmpty() ? null : t;
            } else {
                if (line == null || !line.startsWith("data: ")) return null;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) return "[DONE]";
                JsonNode node = objectMapper.readTree(data);
                JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                if (delta.isMissingNode() || delta.isNull()) return null;
                return delta.asText();
            }
        } catch (Exception e) { return null; }
    }

    /** Build OkHttp streaming request */
    public Request buildStreamRequest(LlmConfig config, List<Map<String, String>> messages) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        if ("ollama".equalsIgnoreCase(config.getProvider())) {
            body.put("model", config.getModelName());
            body.put("stream", true);
            ArrayNode msgs = body.putArray("messages");
            for (Map<String, String> m : messages) {
                ObjectNode n = msgs.addObject();
                n.put("role", m.get("role")); n.put("content", m.get("content"));
            }
            ObjectNode opts = body.putObject("options");
            opts.put("temperature", config.getTemperature());
            opts.put("num_predict", config.getMaxTokens());
            return new Request.Builder().url(config.getBaseUrl() + "/api/chat")
                    .post(okhttp3.RequestBody.create(objectMapper.writeValueAsString(body),
                            okhttp3.MediaType.parse("application/json"))).build();
        } else {
            body.put("model", config.getModelName());
            body.put("stream", true);
            body.put("max_tokens", config.getMaxTokens());
            body.put("temperature", config.getTemperature());
            ArrayNode msgs = body.putArray("messages");
            for (Map<String, String> m : messages) {
                ObjectNode n = msgs.addObject();
                n.put("role", m.get("role")); n.put("content", m.get("content"));
            }
            Request.Builder rb = new Request.Builder()
                    .url(config.getBaseUrl() + "/chat/completions")
                    .post(okhttp3.RequestBody.create(objectMapper.writeValueAsString(body),
                            okhttp3.MediaType.parse("application/json")));
            if (config.getApiKey() != null && !config.getApiKey().isBlank())
                rb.header("Authorization", "Bearer " + config.getApiKey());
            return rb.build();
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private String doChat(LlmConfig config, List<Map<String, String>> messages,
                           OkHttpClient client) throws Exception {
        String provider = config.getProvider();
        if ("ollama".equalsIgnoreCase(provider)) {
            return chatOllama(config, messages, client);
        } else {
            return chatOpenAICompatible(config, messages, client);
        }
    }

    private String chatOpenAICompatible(LlmConfig config, List<Map<String, String>> messages,
                                         OkHttpClient client) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getModelName());
        body.put("stream", false);
        body.put("max_tokens", config.getMaxTokens() != null ? config.getMaxTokens() : 4096);
        body.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.3);
        ArrayNode msgs = body.putArray("messages");
        for (Map<String, String> m : messages) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.get("role")); n.put("content", m.get("content"));
        }
        Request.Builder rb = new Request.Builder()
                .url(config.getBaseUrl() + "/chat/completions")
                .post(okhttp3.RequestBody.create(objectMapper.writeValueAsString(body),
                        okhttp3.MediaType.parse("application/json")));
        if (config.getApiKey() != null && !config.getApiKey().isBlank())
            rb.header("Authorization", "Bearer " + config.getApiKey());

        try (Response resp = client.newCall(rb.build()).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
                throw new RuntimeException("LLM error " + resp.code() + ": " + respBody);
            JsonNode json = objectMapper.readTree(respBody);
            return json.path("choices").path(0).path("message").path("content").asText();
        }
    }

    private String chatOllama(LlmConfig config, List<Map<String, String>> messages,
                               OkHttpClient client) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getModelName());
        body.put("stream", false);
        ArrayNode msgs = body.putArray("messages");
        for (Map<String, String> m : messages) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.get("role")); n.put("content", m.get("content"));
        }
        ObjectNode opts = body.putObject("options");
        opts.put("temperature", config.getTemperature() != null ? config.getTemperature() : 0.3);
        opts.put("num_predict", config.getMaxTokens() != null ? config.getMaxTokens() : 4096);

        Request req = new Request.Builder()
                .url(config.getBaseUrl() + "/api/chat")
                .post(okhttp3.RequestBody.create(objectMapper.writeValueAsString(body),
                        okhttp3.MediaType.parse("application/json")))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
                throw new RuntimeException("Ollama error: " + respBody);
            JsonNode json = objectMapper.readTree(respBody);
            return json.path("message").path("content").asText();
        }
    }
}

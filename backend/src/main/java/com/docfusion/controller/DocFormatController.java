package com.docfusion.controller;

import com.docfusion.model.OutputFile;
import com.docfusion.service.DocFormatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/doc-format")
public class DocFormatController {

    @Autowired private DocFormatService docFormatService;

    @PostMapping("/process")
    public ResponseEntity<?> process(@RequestParam(value = "file", required = false) MultipartFile file,
                                     @RequestParam(value = "knowledgeDocId", required = false) Long knowledgeDocId,
                                     @RequestParam("prompt") String prompt,
                                     @RequestParam(value = "outputType", required = false) String outputType,
                                     @RequestParam(value = "llmConfigId", required = false) Long llmConfigId,
                                     @RequestParam(value = "sessionId", required = false) String sessionId) {
        try {
            if (prompt == null || prompt.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请输入文档排版要求"));
            }
            if ((file == null || file.isEmpty()) && knowledgeDocId == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请上传文档或从知识库选择文档"));
            }
            if (sessionId == null || sessionId.isBlank()) sessionId = UUID.randomUUID().toString();

            OutputFile out = docFormatService.formatDocument(file, knowledgeDocId, prompt, outputType, llmConfigId, sessionId);
            return ResponseEntity.ok(Map.of("success", true, "data", out));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "处理失败: " + e.getMessage()));
        }
    }
}

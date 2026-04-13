package com.docfusion.controller;

import com.docfusion.model.OutputFile;
import com.docfusion.model.OutputFileRepo;
import com.docfusion.service.TableFillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/table-fill")
public class TableFillController {

    private static final Logger log = LoggerFactory.getLogger(TableFillController.class);

    @Autowired private TableFillService tableFillService;
    @Autowired private OutputFileRepo outputFileRepo;

    @PostMapping("/fill")
    public ResponseEntity<?> fill(
            @RequestParam("template") MultipartFile template,
            @RequestParam("sourceDocIds") List<Long> sourceDocIds,
            @RequestParam(value = "llmConfigId", required = false) Long llmConfigId,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        try {
            if (sourceDocIds == null || sourceDocIds.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("success", false, "message", "请至少选择一个知识库文档作为数据来源"));
            }
            if (sessionId == null || sessionId.isBlank()) sessionId = UUID.randomUUID().toString();

            log.info("Table fill: template={}, sourceDocs={}", template.getOriginalFilename(), sourceDocIds);
            long start = System.currentTimeMillis();

            OutputFile result = tableFillService.fillTableFromKnowledgeBase(
                    template, sourceDocIds, llmConfigId, sessionId);

            long elapsed = System.currentTimeMillis() - start;
            log.info("Table fill completed in {}ms, rows described: {}", elapsed, result.getDescription());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", result,
                    "elapsedMs", elapsed,
                    "message", result.getDescription() + "，耗时 " + (elapsed / 1000) + " 秒"));
        } catch (Exception e) {
            log.error("Table fill error", e);
            return ResponseEntity.internalServerError().body(
                    Map.of("success", false, "message", "填写失败: " + e.getMessage()));
        }
    }

    @GetMapping("/outputs")
    public ResponseEntity<?> getOutputs(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        List<OutputFile> files = (sessionId != null && !sessionId.isBlank())
                ? outputFileRepo.findBySessionIdOrderByCreatedAtDesc(sessionId)
                : outputFileRepo.findAll();
        return ResponseEntity.ok(Map.of("success", true, "data", files));
    }

    @PostMapping("/outputs/{id}/save-to-kb")
    public ResponseEntity<?> saveToKb(@PathVariable Long id) {
        return outputFileRepo.findById(id).map(file -> {
            file.setSavedToKnowledgeBase(true);
            outputFileRepo.save(file);
            return ResponseEntity.ok(Map.of("success", true, "message", "已保存到知识库"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/outputs/{id}")
    public ResponseEntity<?> deleteOutput(@PathVariable Long id) {
        if (!outputFileRepo.existsById(id)) return ResponseEntity.notFound().build();
        outputFileRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "已删除"));
    }
}

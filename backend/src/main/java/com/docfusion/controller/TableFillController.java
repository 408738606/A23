package com.docfusion.controller;

import com.docfusion.model.OutputFile;
import com.docfusion.model.OutputFileRepo;
import com.docfusion.service.DocumentExtractService;
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
    @Autowired private DocumentExtractService documentExtractService;

    @PostMapping("/fill")
    public ResponseEntity<?> fill(
            @RequestParam(value = "template", required = false) MultipartFile template,
            @RequestParam(value = "templateDocId", required = false) Long templateDocId,
            @RequestParam(value = "sourceDocIds", required = false) List<Long> sourceDocIds,
            @RequestParam(value = "sourceFiles", required = false) MultipartFile[] sourceFiles,
            @RequestParam(value = "requirementDocIds", required = false) List<Long> requirementDocIds,
            @RequestParam(value = "requirementFiles", required = false) MultipartFile[] requirementFiles,
            @RequestParam(value = "outputType", required = false) String outputType,
            @RequestParam(value = "llmConfigId", required = false) Long llmConfigId,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        try {
            boolean hasKbSources = sourceDocIds != null && !sourceDocIds.isEmpty();
            boolean hasLocalSources = sourceFiles != null && sourceFiles.length > 0;
            if (!hasKbSources && !hasLocalSources) {
                return ResponseEntity.badRequest().body(
                        Map.of("success", false, "message", "请至少选择一个数据源文档（知识库或本地上传）"));
            }
            if ((template == null || template.isEmpty()) && templateDocId == null) {
                return ResponseEntity.badRequest().body(
                        Map.of("success", false, "message", "请上传模板或从知识库选择模板文档"));
            }
            if (sessionId == null || sessionId.isBlank()) sessionId = UUID.randomUUID().toString();

            log.info("Table fill: template={}, templateDocId={}, sourceDocs={}, localSources={}, outputType={}",
                    template != null ? template.getOriginalFilename() : "KB",
                    templateDocId, sourceDocIds, sourceFiles != null ? sourceFiles.length : 0, outputType);
            long start = System.currentTimeMillis();

            OutputFile result = tableFillService.fillTableFlexible(
                    template,
                    templateDocId,
                    sourceDocIds != null ? sourceDocIds : List.of(),
                    sourceFiles,
                    requirementDocIds != null ? requirementDocIds : List.of(),
                    requirementFiles,
                    outputType,
                    llmConfigId,
                    sessionId);

            long elapsed = System.currentTimeMillis() - start;
            log.info("Table fill completed in {}ms, rows described: {}", elapsed, result.getDescription());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", result,
                    "elapsedMs", elapsed,
                    "message", result.getDescription() + "，耗时 " + (elapsed / 1000) + " 秒"));
        } catch (Exception e) {
            log.error("Table fill error", e);
            String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
            if (msg.contains("Insufficient Balance")) {
                msg = "模型余额不足（Insufficient Balance），请切换本地模型（如 Ollama）或充值后重试";
            }
            return ResponseEntity.internalServerError().body(
                    Map.of("success", false, "message", "填写失败: " + msg));
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
    public ResponseEntity<?> saveToKb(@PathVariable Long id,
                                      @RequestParam(value = "libraryType", defaultValue = "database") String libraryType,
                                      @RequestParam(value = "subDatabase", required = false) String subDatabase,
                                      @RequestParam(value = "category", defaultValue = "AI结果") String category) {
        return outputFileRepo.findById(id).map(file -> {
            try {
                documentExtractService.importFileToKnowledgeBase(
                        file.getFilePath(),
                        file.getFileName(),
                        category,
                        libraryType,
                        subDatabase);
                file.setSavedToKnowledgeBase(true);
                outputFileRepo.save(file);
                return ResponseEntity.ok(Map.of("success", true, "message", "已保存到知识库"));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(
                        Map.of("success", false, "message", "保存到知识库失败: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.status(404).body(Map.of("success", false, "message", "输出文件不存在")));
    }

    @DeleteMapping("/outputs/{id}")
    public ResponseEntity<?> deleteOutput(@PathVariable Long id) {
        if (!outputFileRepo.existsById(id)) return ResponseEntity.notFound().build();
        outputFileRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "已删除"));
    }
}

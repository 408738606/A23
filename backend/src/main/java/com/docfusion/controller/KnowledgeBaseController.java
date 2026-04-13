package com.docfusion.controller;

import com.docfusion.model.*;
import com.docfusion.service.DocumentExtractService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBaseController {

    @Autowired private KnowledgeDocumentRepo docRepo;
    @Autowired private DocumentExtractService extractService;

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", defaultValue = "默认") String category,
            @RequestParam(value = "libraryType", defaultValue = "database") String libraryType,
            @RequestParam(value = "subDatabase", required = false) String subDatabase) {
        try {
            KnowledgeDocument doc = extractService.saveAndExtract(file, category, libraryType, subDatabase);
            return ResponseEntity.ok(Map.of("success", true, "data", doc, "message", "文件上传成功，正在后台提取文本..."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/upload/batch")
    public ResponseEntity<?> uploadBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "category", defaultValue = "默认") String category,
            @RequestParam(value = "libraryType", defaultValue = "database") String libraryType,
            @RequestParam(value = "subDatabase", required = false) String subDatabase) {
        List<KnowledgeDocument> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                results.add(extractService.saveAndExtract(file, category, libraryType, subDatabase));
            } catch (Exception e) {
                errors.add(file.getOriginalFilename() + ": " + e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "data", results, "errors", errors, "message", "批量上传完成"));
    }

    @GetMapping("/list")
    public ResponseEntity<?> list(
            @RequestParam(value = "libraryType", required = false) String libraryType,
            @RequestParam(value = "subDatabase", required = false) String subDatabase,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "keyword", required = false) String keyword) {
        List<KnowledgeDocument> docs;
        if (keyword != null && !keyword.isBlank()) {
            docs = docRepo.findByFileNameContainingIgnoreCase(keyword);
        } else if (libraryType != null && subDatabase != null && !subDatabase.isBlank()) {
            docs = docRepo.findByLibraryTypeAndSubDatabase(libraryType, subDatabase);
        } else if (libraryType != null && !libraryType.isBlank()) {
            docs = docRepo.findByLibraryType(libraryType);
        } else if (type != null && !type.isBlank()) {
            docs = docRepo.findByFileType(type);
        } else {
            docs = docRepo.findAll();
        }
        return ResponseEntity.ok(Map.of("success", true, "data", docs));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return docRepo.findById(id)
                .map(d -> ResponseEntity.ok(Map.of("success", true, "data", d)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!docRepo.existsById(id)) return ResponseEntity.notFound().build();
        docRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "已删除"));
    }

    @GetMapping("/{id}/text")
    public ResponseEntity<?> getText(@PathVariable Long id) {
        return docRepo.findById(id).map(d -> {
            Map<String, Object> data = new HashMap<>();
            data.put("id", d.getId());
            data.put("fileName", d.getFileName());
            data.put("text", d.getExtractedText() != null ? d.getExtractedText() : "");
            data.put("processed", d.getProcessed());
            return ResponseEntity.ok(Map.of("success", true, "data", data));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/sub-databases")
    public ResponseEntity<?> getSubDatabases() {
        List<String> subs = docRepo.findAllSubDatabases();
        return ResponseEntity.ok(Map.of("success", true, "data", subs));
    }

    @GetMapping("/categories")
    public ResponseEntity<?> getCategories() {
        List<String> cats = docRepo.findAll().stream()
                .map(KnowledgeDocument::getCategory)
                .filter(Objects::nonNull).distinct().toList();
        return ResponseEntity.ok(Map.of("success", true, "data", cats));
    }
}

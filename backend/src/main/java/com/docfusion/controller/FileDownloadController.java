package com.docfusion.controller;

import com.docfusion.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/file")
public class FileDownloadController {

    private static final Logger log = LoggerFactory.getLogger(FileDownloadController.class);

    @Autowired private OutputFileRepo outputFileRepo;
    @Autowired private KnowledgeDocumentRepo knowledgeDocumentRepo;

    @GetMapping("/download/output/{id}")
    public ResponseEntity<Resource> downloadOutput(@PathVariable Long id) {
        return outputFileRepo.findById(id).map(file -> {
            File f = new File(file.getFilePath());
            if (!f.exists()) return ResponseEntity.notFound().<Resource>build();
            Resource resource = new FileSystemResource(f);
            String encodedName = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                    .header(HttpHeaders.CONTENT_TYPE, getContentType(file.getFileType()))
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .<Resource>body(resource);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/download/knowledge/{id}")
    public ResponseEntity<Resource> downloadKnowledge(@PathVariable Long id) {
        return knowledgeDocumentRepo.findById(id).map(doc -> {
            File f = new File(doc.getFilePath());
            if (!f.exists()) return ResponseEntity.notFound().<Resource>build();
            Resource resource = new FileSystemResource(f);
            String encodedName = URLEncoder.encode(doc.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                    .header(HttpHeaders.CONTENT_TYPE, getContentType(doc.getFileType()))
                    .<Resource>body(resource);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/preview/output/{id}")
    public ResponseEntity<Resource> previewOutput(@PathVariable Long id) {
        return outputFileRepo.findById(id).map(file -> {
            File f = new File(file.getFilePath());
            if (!f.exists()) return ResponseEntity.notFound().<Resource>build();
            Resource resource = new FileSystemResource(f);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .header(HttpHeaders.CONTENT_TYPE, getContentType(file.getFileType()))
                    .<Resource>body(resource);
        }).orElse(ResponseEntity.notFound().build());
    }

    private String getContentType(String ext) {
        if (ext == null) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        switch (ext.toLowerCase()) {
            case "xlsx": case "xls": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc": return "application/msword";
            case "pdf": return "application/pdf";
            case "txt": return "text/plain; charset=utf-8";
            case "md": return "text/markdown; charset=utf-8";
            default: return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }
}

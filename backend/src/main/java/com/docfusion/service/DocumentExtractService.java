package com.docfusion.service;

import com.docfusion.config.AppConfig;
import com.docfusion.model.KnowledgeDocument;
import com.docfusion.model.KnowledgeDocumentRepo;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class DocumentExtractService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractService.class);

    @Autowired private KnowledgeDocumentRepo docRepo;
    @Autowired private AppConfig appConfig;

    public KnowledgeDocument saveAndExtract(MultipartFile file, String category,
                                             String libraryType, String subDatabase) throws IOException {
        String originalName = file.getOriginalFilename();
        String ext = getExtension(originalName).toLowerCase();
        String savedPath = saveFile(file, appConfig.getKnowledgeBasePath());

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setFileName(originalName);
        doc.setFileType(ext);
        doc.setFilePath(savedPath);
        doc.setFileSize(file.getSize());
        doc.setCategory(category != null ? category : "默认");
        doc.setLibraryType(libraryType != null ? libraryType : "database");
        doc.setSubDatabase(subDatabase);
        doc.setProcessed(false);
        docRepo.save(doc);

        extractTextAsync(doc.getId(), savedPath, ext);
        return doc;
    }

    // Overload for backward compatibility
    public KnowledgeDocument saveAndExtract(MultipartFile file, String category) throws IOException {
        return saveAndExtract(file, category, "database", null);
    }

    @Async
    public void extractTextAsync(Long docId, String filePath, String ext) {
        try {
            String text = extractText(filePath, ext);
            KnowledgeDocument doc = docRepo.findById(docId).orElseThrow();
            doc.setExtractedText(text);
            doc.setProcessed(true);
            docRepo.save(doc);
            log.info("Text extracted for doc {}: {} chars", docId, text.length());
        } catch (Exception e) {
            log.error("Failed to extract text for doc {}: {}", docId, e.getMessage());
        }
    }

    public String extractText(String filePath, String ext) throws IOException {
        File f = new File(filePath);
        switch (ext) {
            case "docx": return extractDocx(f);
            case "xlsx": case "xls": return extractExcel(f);
            default: return extractPlainText(f);
        }
    }

    private String extractDocx(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(file))) {
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) sb.append(text).append("\n");
            }
            for (XWPFTable table : doc.getTables()) {
                sb.append("\n[表格开始]\n");
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) cells.add(cell.getText().trim());
                    sb.append(String.join(" | ", cells)).append("\n");
                }
                sb.append("[表格结束]\n");
            }
        }
        return sb.toString();
    }

    private String extractExcel(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = WorkbookFactory.create(file)) {
            DataFormatter formatter = new DataFormatter();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                sb.append("【Sheet: ").append(sheet.getSheetName()).append("】\n");
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) cells.add(formatter.formatCellValue(cell));
                    String line = String.join("\t", cells).trim();
                    if (!line.isBlank()) sb.append(line).append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String extractPlainText(File file) throws IOException {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private String saveFile(MultipartFile file, String dir) throws IOException {
        String filename = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path dest = Paths.get(dir, filename);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        return dest.toString();
    }

    public String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    public String buildKnowledgeContext(List<Long> docIds) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("=== 知识库文档内容 ===\n\n");
        for (Long id : docIds) {
            docRepo.findById(id).ifPresent(doc -> {
                ctx.append("【文档: ").append(doc.getFileName()).append("】\n");
                if (doc.getExtractedText() != null) {
                    String text = doc.getExtractedText();
                    if (text.length() > 8000) text = text.substring(0, 8000) + "\n...[内容已截断]";
                    ctx.append(text).append("\n\n");
                }
            });
        }
        return ctx.toString();
    }
}

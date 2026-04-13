package com.docfusion.service;

import com.docfusion.config.AppConfig;
import com.docfusion.model.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DocFormatService {

    @Autowired private LlmService llmService;
    @Autowired private DocumentExtractService extractService;
    @Autowired private KnowledgeDocumentRepo docRepo;
    @Autowired private OutputFileRepo outputFileRepo;
    @Autowired private AppConfig appConfig;

    public OutputFile formatDocument(MultipartFile localFile,
                                     Long knowledgeDocId,
                                     String userPrompt,
                                     String outputType,
                                     Long llmConfigId,
                                     String sessionId) throws Exception {
        LlmConfig config = llmService.getConfigById(llmConfigId);
        if (config == null) throw new RuntimeException("未找到可用模型配置");

        ResolvedInput input = resolveInput(localFile, knowledgeDocId);
        String sourceText = extractService.extractText(input.path, input.ext);
        String targetExt = normalizeOutputType(outputType, input.ext);

        String prompt = "你是文档格式与内容重构助手。请按用户要求对文档进行排版与格式调整。\n\n"
                + "# 用户要求\n" + userPrompt + "\n\n"
                + "# 原始文档内容\n" + sourceText + "\n\n"
                + "输出要求：\n"
                + "1) 保留原文关键信息，不得编造事实\n"
                + "2) 严格按用户要求进行格式重排和表达优化\n"
                + "3) 直接输出最终文档内容，不附加解释\n"
                + "4) 输出目标格式: " + targetExt;

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", "你是专业文档排版与改写助手。"),
                Map.of("role", "user", "content", prompt)
        );

        String generated = llmService.chatLong(config, messages);
        String outputPath = writeOutput(generated, input.originalName, targetExt);

        OutputFile out = new OutputFile();
        out.setSessionId(sessionId);
        out.setFileName("排版后_" + baseName(input.originalName) + "." + targetExt);
        out.setFilePath(outputPath);
        out.setFileType(targetExt);
        out.setFileSize(new File(outputPath).length());
        out.setDescription("文档排版与格式调整已完成");
        outputFileRepo.save(out);

        if (input.cleanup) {
            try { Files.deleteIfExists(Paths.get(input.path)); } catch (Exception ignored) {}
        }
        return out;
    }

    private ResolvedInput resolveInput(MultipartFile localFile, Long knowledgeDocId) throws Exception {
        if (localFile != null && !localFile.isEmpty()) {
            String name = sanitizeFilename(Optional.ofNullable(localFile.getOriginalFilename()).orElse("input.txt"));
            String ext = extractService.getExtension(name).toLowerCase();
            String temp = saveTempFile(localFile);
            return new ResolvedInput(temp, name, ext, true);
        }
        if (knowledgeDocId != null) {
            KnowledgeDocument doc = docRepo.findById(knowledgeDocId)
                    .orElseThrow(() -> new RuntimeException("未找到知识库文档"));
            String ext = extractService.getExtension(doc.getFileName()).toLowerCase();
            return new ResolvedInput(ensureSafePath(doc.getFilePath()), sanitizeFilename(doc.getFileName()), ext, false);
        }
        throw new RuntimeException("请上传文档或从知识库选择文档");
    }

    private String normalizeOutputType(String outputType, String fallbackExt) {
        String t = outputType == null ? "" : outputType.toLowerCase();
        if ("docx".equals(t) || "md".equals(t) || "txt".equals(t)) return t;
        if ("docx".equals(fallbackExt) || "md".equals(fallbackExt) || "txt".equals(fallbackExt)) return fallbackExt;
        return "txt";
    }

    private String writeOutput(String content, String originalName, String ext) throws Exception {
        String safeContent = content == null ? "" : content;
        String filename = sanitizeFilename(System.currentTimeMillis() + "_formatted_" + baseName(originalName) + "." + ext);
        String outputPath = appConfig.getOutputPath() + File.separator + filename;
        if ("docx".equals(ext)) {
            try (XWPFDocument doc = new XWPFDocument()) {
                for (String line : safeContent.split("\\r?\\n")) {
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun run = p.createRun();
                    run.setText(line);
                }
                try (FileOutputStream fos = new FileOutputStream(outputPath)) { doc.write(fos); }
            }
        } else {
            Files.writeString(Paths.get(outputPath), safeContent, StandardCharsets.UTF_8);
        }
        return outputPath;
    }

    private String saveTempFile(MultipartFile file) throws Exception {
        String filename = System.currentTimeMillis() + "_docfmt_" + sanitizeFilename(file.getOriginalFilename());
        Path dest = Paths.get(appConfig.getUploadTempPath(), filename);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        return dest.toString();
    }

    private String baseName(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private String sanitizeFilename(String name) {
        String raw = (name == null || name.isBlank()) ? "file.txt" : name;
        String sanitized = raw.replaceAll("[\\\\/:*?\"<>|]+", "_").replace("..", "_");
        return sanitized.isBlank() ? "file.txt" : sanitized;
    }

    private String ensureSafePath(String rawPath) {
        Path normalized = Paths.get(rawPath).normalize().toAbsolutePath();
        Path kb = Paths.get(appConfig.getKnowledgeBasePath()).normalize().toAbsolutePath();
        Path temp = Paths.get(appConfig.getUploadTempPath()).normalize().toAbsolutePath();
        if (normalized.startsWith(kb) || normalized.startsWith(temp)) return normalized.toString();
        throw new RuntimeException("文档路径不安全，拒绝访问");
    }

    private record ResolvedInput(String path, String originalName, String ext, boolean cleanup) {}
}

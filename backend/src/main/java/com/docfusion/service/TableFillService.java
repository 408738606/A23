package com.docfusion.service;

import com.docfusion.config.AppConfig;
import com.docfusion.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class TableFillService {

    private static final Logger log = LoggerFactory.getLogger(TableFillService.class);
    private static final int CHUNK_SIZE = 6500;
    private static final int CHUNK_OVERLAP = 800;
    private static final int MAX_TEMPLATE_CHARS = 12000;
    private static final int MAX_REQUIREMENT_CHARS = 6000;
    private static final int MAX_REQUIREMENT_ANALYSIS_CHARS = 2400;
    private static final int MAX_SUMMARY_CHARS = 2200;
    private static final int MAX_FACTS_CHARS = 18000;
    private static final int EXTRACTION_CHUNK_SIZE = 3600;
    private static final int REQUIREMENT_ANALYSIS_MAX_TOKENS = 1536;
    private static final int SUMMARY_MAX_TOKENS = 2048;
    private static final int FINAL_GENERATION_MAX_TOKENS = 6144;
    private static final int EXTRACTION_MAX_TOKENS = 8192;
    private static final int RETRY_EXTRACTION_MAX_TOKENS = 4096;

    @Autowired private LlmService llmService;
    @Autowired private DocumentExtractService extractService;
    @Autowired private KnowledgeDocumentRepo docRepo;
    @Autowired private OutputFileRepo outputFileRepo;
    @Autowired private AppConfig appConfig;
    @Autowired private ObjectMapper objectMapper;

    public OutputFile fillTableFromKnowledgeBase(MultipartFile templateFile,
                                                 List<Long> sourceDocIds,
                                                 Long llmConfigId,
                                                 String sessionId) throws Exception {
        return fillTableFlexible(templateFile, null, sourceDocIds, null,
                List.of(), null, null, llmConfigId, sessionId);
    }

    public OutputFile fillTableFlexible(MultipartFile templateUpload,
                                        Long templateDocId,
                                        List<Long> sourceDocIds,
                                        MultipartFile[] sourceFiles,
                                        List<Long> requirementDocIds,
                                        MultipartFile[] requirementFiles,
                                        String outputType,
                                        Long llmConfigId,
                                        String sessionId) throws Exception {
        LlmConfig config = llmService.getConfigById(llmConfigId);
        if (config == null) throw new RuntimeException("未找到可用的模型配置，请先在设置中配置大模型");

        ResolvedFile template = resolveTemplate(templateUpload, templateDocId);
        String templateExt = template.ext;
        String targetExt = normalizeOutputType(outputType, templateExt);

        String sourceText = buildCombinedText(sourceDocIds, sourceFiles, "数据源");
        String requirementText = clampText(
                buildCombinedText(requirementDocIds, requirementFiles, "用户要求"),
                MAX_REQUIREMENT_CHARS, "用户要求");

        String outputPath;
        String description;
        if ("xlsx".equals(templateExt) || "xls".equals(templateExt)) {
            TemplateInfo tmpl = parseTemplateInfo(template.path, templateExt);
            String requirementAnalysis = analyzeRequirementDocumentSafe(config, requirementText, String.join(" | ", tmpl.headers), targetExt);
            String fullSourceText = sourceText;
            if (requirementText != null && !requirementText.isBlank()) {
                fullSourceText = sourceText + "\n\n=== 用户要求 ===\n" + requirementText;
            }
            List<Map<String, String>> allRows = extractAllRows(config, tmpl, fullSourceText, requirementText, requirementAnalysis);
            if ("xlsx".equals(targetExt) || "xls".equals(targetExt)) {
                outputPath = writeRowsToTemplate(template.path, targetExt, tmpl, allRows, template.originalName);
                description = String.format("AI自动填写完成，共提取 %d 行数据", allRows.size());
            } else {
                outputPath = writeStructuredTableOutput(targetExt, tmpl, allRows, template.originalName);
                description = String.format("AI自动填写完成（按%s表格格式输出），共提取 %d 行数据", targetExt, allRows.size());
            }
        } else {
            String templateText = readTemplateAsText(template.path, templateExt);
            String generated = generateNonExcelOutput(config, templateText, sourceText, requirementText, targetExt);
            outputPath = writeGeneratedOutput(targetExt, generated, template.originalName);
            description = "AI自动填写完成，已生成内容文档";
        }

        File outFile = new File(outputPath);
        String finalFileName = "已填写_" + baseName(template.originalName) + "." + targetExt;
        OutputFile output = new OutputFile();
        output.setSessionId(sessionId);
        output.setFileName(finalFileName);
        output.setFilePath(outputPath);
        output.setFileType(targetExt);
        output.setFileSize(outFile.length());
        output.setDescription(description);
        outputFileRepo.save(output);

        if (template.cleanup && template.path != null) {
            try { Files.deleteIfExists(Paths.get(template.path)); } catch (Exception ignored) {}
        }
        return output;
    }

    private ResolvedFile resolveTemplate(MultipartFile templateUpload, Long templateDocId) throws Exception {
        if (templateUpload != null && !templateUpload.isEmpty()) {
            String original = sanitizeFilename(Optional.ofNullable(templateUpload.getOriginalFilename()).orElse("template.txt"));
            String ext = extractService.getExtension(original).toLowerCase();
            String path = saveTempFile(templateUpload);
            return new ResolvedFile(path, original, ext, true);
        }
        if (templateDocId != null) {
            KnowledgeDocument doc = docRepo.findById(templateDocId)
                    .orElseThrow(() -> new RuntimeException("未找到模板文档"));
            String safePath = ensureSafePath(doc.getFilePath());
            String safeName = sanitizeFilename(doc.getFileName());
            return new ResolvedFile(safePath, safeName,
                    extractService.getExtension(doc.getFileName()).toLowerCase(), false);
        }
        throw new RuntimeException("请上传模板文件或从知识库选择模板");
    }

    private String buildCombinedText(List<Long> kbDocIds, MultipartFile[] localFiles, String title) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(title).append(" ===\n\n");

        if (kbDocIds != null) {
            for (Long id : kbDocIds) {
                docRepo.findById(id).ifPresent(doc -> {
                    sb.append("【知识库文档: ").append(doc.getFileName()).append("】\n");
                    sb.append("文件类型: ").append(doc.getFileType()).append("\n");
                    if (doc.getExtractedText() != null && !doc.getExtractedText().isBlank()) {
                        sb.append(doc.getExtractedText()).append("\n\n");
                    } else {
                        sb.append("（文档尚未处理完成）\n\n");
                    }
                });
            }
        }

        if (localFiles != null) {
            for (MultipartFile f : localFiles) {
                if (f == null || f.isEmpty()) continue;
                String name = sanitizeFilename(Optional.ofNullable(f.getOriginalFilename()).orElse("local-file.txt"));
                String ext = extractService.getExtension(name).toLowerCase();
                String tmp = saveTempFile(f);
                try {
                    String txt = extractService.extractText(tmp, ext);
                    sb.append("【本地文件: ").append(name).append("】\n");
                    sb.append("文件类型: ").append(ext).append("\n");
                    sb.append(txt).append("\n\n");
                } finally {
                    try { Files.deleteIfExists(Paths.get(tmp)); } catch (Exception ignored) {}
                }
            }
        }

        return sb.toString();
    }

    private String readTemplateAsText(String templatePath, String ext) throws Exception {
        if ("docx".equals(ext) || "xlsx".equals(ext) || "xls".equals(ext)) {
            return extractService.extractText(templatePath, ext);
        }
        return Files.readString(Paths.get(templatePath), StandardCharsets.UTF_8);
    }

    private String generateNonExcelOutput(LlmConfig config,
                                          String templateText,
                                          String sourceText,
                                          String requirementText,
                                          String ext) throws Exception {
        String templateSnippet = clampText(templateText, MAX_TEMPLATE_CHARS, "模板内容");
        String requirementAnalysis = analyzeRequirementDocumentSafe(config, requirementText, templateSnippet, ext);
        List<String> sourceChunks = splitIntoChunks(sourceText, CHUNK_SIZE);
        if (sourceChunks.isEmpty()) sourceChunks = List.of("");

        StringBuilder condensedFacts = new StringBuilder();
        for (int i = 0; i < sourceChunks.size(); i++) {
            String chunk = sourceChunks.get(i);
            String summarizePrompt = "你是结构化信息抽取助手。请提炼可直接用于填写目标文档的事实数据。\n\n"
                    + ((requirementAnalysis != null && !requirementAnalysis.isBlank()) ? "# 任务要求解析\n" + requirementAnalysis + "\n\n" : "")
                    + "# 模板内容（摘要）\n" + templateSnippet + "\n\n"
                    + "# 数据源片段（第 " + (i + 1) + "/" + sourceChunks.size() + " 段）\n" + chunk + "\n\n"
                    + "输出要求：\n"
                    + "1) 只保留“事实数据”，禁止生成推测内容\n"
                    + "2) 如果内容来自表格（xlsx/xls提取文本），尽量还原为“字段: 值”形式\n"
                    + "3) 按“主题 | 字段 | 值 | 证据片段”输出要点\n"
                    + "4) 若无有效信息仅输出“无有效信息”\n"
                    + "5) 输出不超过 800 字";

            List<Map<String, String>> summarizeMessages = List.of(
                    Map.of("role", "system", "content", "你是严谨的数据抽取助手。"),
                    Map.of("role", "user", "content", summarizePrompt)
            );

            try {
                String summary = llmService.chatLong(cloneConfigWithFixedTokens(config, SUMMARY_MAX_TOKENS), summarizeMessages);
                String cleanedSummary = clampText(summary, MAX_SUMMARY_CHARS, "片段摘要");
                if (!cleanedSummary.isBlank() && !"无有效信息".equals(cleanedSummary.trim())) {
                    condensedFacts.append("## 片段").append(i + 1).append("\n")
                            .append(cleanedSummary).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("片段 {} 摘要失败，使用截断原文兜底: {}", i + 1, e.getMessage());
                String fallbackChunk = clampText(chunk, MAX_SUMMARY_CHARS, "片段兜底");
                if (!fallbackChunk.isBlank()) {
                    condensedFacts.append("## 片段").append(i + 1).append("\n")
                            .append(fallbackChunk).append("\n\n");
                }
            }
        }

        String sourceFacts = condensedFacts.length() > 0
                ? clampText(condensedFacts.toString(), MAX_FACTS_CHARS, "提炼事实")
                : clampText(sourceText, CHUNK_SIZE, "数据源");

        String prompt = "你是文档填写助手。请根据模板结构与用户要求生成最终文档内容。\n\n"
                + ((requirementAnalysis != null && !requirementAnalysis.isBlank()) ? "# 用户要求解析\n" + requirementAnalysis + "\n\n" : "")
                + "# 模板内容\n" + templateSnippet + "\n\n"
                + "# 数据源事实（分段提炼后）\n" + sourceFacts + "\n\n"
                + "输出要求：\n"
                + "1) 严格基于数据源和用户要求，不编造\n"
                + "2) 保持模板的章节/字段语义\n"
                + "3) 直接输出最终文档正文，不要额外解释\n"
                + "4) 输出语言与模板保持一致\n"
                + "5) 若模板是docx/txt/md，按模板逻辑补全内容并输出可直接落盘的正文\n"
                + "6) 当前目标格式: " + ext;

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", "你是专业的文档生成与排版助手。"),
                Map.of("role", "user", "content", prompt)
        );
        try {
            String generated = llmService.chatLong(cloneConfigWithFixedTokens(config, FINAL_GENERATION_MAX_TOKENS), messages);
            return ensureNonEmptyGenerated(generated, templateSnippet, sourceFacts);
        } catch (Exception e) {
            log.warn("最终文档生成失败，启用兜底输出: {}", e.getMessage());
            return buildDeterministicFallbackDocument(templateSnippet, sourceFacts, requirementText, ext);
        }
    }

    private String analyzeRequirementDocument(LlmConfig config,
                                              String requirementText,
                                              String templateSnippet,
                                              String ext) throws Exception {
        if (requirementText == null || requirementText.isBlank()) return "";
        String reqSnippet = clampText(requirementText, MAX_REQUIREMENT_CHARS, "用户要求");
        String prompt = "你是任务分析助手。请分析用户要求文档，并输出可执行的填写规则。\n\n"
                + "# 用户要求文档\n" + reqSnippet + "\n\n"
                + "# 模板摘要\n" + templateSnippet + "\n\n"
                + "输出要求：\n"
                + "1) 总结目标文档类型与核心任务\n"
                + "2) 提取必须满足的字段/结构/格式约束\n"
                + "3) 提取优先级、过滤条件、计算规则（如有）\n"
                + "4) 输出为简洁条目，禁止无关解释\n"
                + "5) 目标输出格式: " + ext;
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", "你是严谨的任务需求分析助手。"),
                Map.of("role", "user", "content", prompt)
        );
        String analyzed = llmService.chatLong(cloneConfigWithFixedTokens(config, REQUIREMENT_ANALYSIS_MAX_TOKENS), messages);
        return clampText(analyzed, MAX_REQUIREMENT_ANALYSIS_CHARS, "用户要求解析");
    }

    private String analyzeRequirementDocumentSafe(LlmConfig config,
                                                  String requirementText,
                                                  String templateSnippet,
                                                  String ext) {
        try {
            return analyzeRequirementDocument(config, requirementText, templateSnippet, ext);
        } catch (Exception e) {
            log.warn("用户要求解析失败，使用空解析继续: {}", e.getMessage());
            return "";
        }
    }

    private String ensureNonEmptyGenerated(String generated, String templateSnippet, String sourceFacts) {
        if (generated != null && !generated.trim().isEmpty()) return generated;
        StringBuilder fallback = new StringBuilder();
        fallback.append("【自动兜底输出】\n");
        if (templateSnippet != null && !templateSnippet.isBlank()) {
            fallback.append("## 模板结构参考\n").append(clampText(templateSnippet, 5000, "模板兜底")).append("\n\n");
        }
        if (sourceFacts != null && !sourceFacts.isBlank()) {
            fallback.append("## 数据源事实\n").append(clampText(sourceFacts, 6000, "事实兜底")).append("\n");
        } else {
            fallback.append("数据源信息不足，请补充后重试。\n");
        }
        return fallback.toString();
    }

    private String writeGeneratedOutput(String ext, String content, String originalName) throws IOException {
        String outName = sanitizeFilename(System.currentTimeMillis() + "_filled_" + baseName(originalName) + "." + ext);
        Path outFile = resolveOutputPath(outName);
        String outPath = outFile.toString();

        if ("docx".equals(ext)) {
            try (XWPFDocument doc = new XWPFDocument()) {
                for (String line : content.split("\\r?\\n")) {
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun run = p.createRun();
                    run.setText(line);
                }
                try (FileOutputStream fos = new FileOutputStream(outPath)) { doc.write(fos); }
            }
        } else {
            Files.writeString(Paths.get(outPath), content == null ? "" : content, StandardCharsets.UTF_8);
        }
        return outPath;
    }

    private String writeStructuredTableOutput(String ext,
                                              TemplateInfo tmpl,
                                              List<Map<String, String>> rows,
                                              String originalName) throws IOException {
        String outName = sanitizeFilename(System.currentTimeMillis() + "_filled_" + baseName(originalName) + "." + ext);
        Path outFile = resolveOutputPath(outName);
        String outPath = outFile.toString();
        if ("docx".equals(ext)) {
            writeDocxTableOutput(outPath, tmpl.headers, rows);
        } else if ("md".equals(ext)) {
            Files.writeString(Paths.get(outPath), buildMarkdownTable(tmpl.headers, rows), StandardCharsets.UTF_8);
        } else {
            Files.writeString(Paths.get(outPath), buildTextTable(tmpl.headers, rows), StandardCharsets.UTF_8);
        }
        return outPath;
    }

    private void writeDocxTableOutput(String outputPath, List<String> headers, List<Map<String, String>> rows) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph title = doc.createParagraph();
            XWPFRun run = title.createRun();
            run.setText("表格填写结果");

            XWPFTable table = doc.createTable(Math.max(1, rows.size() + 1), Math.max(1, headers.size()));
            XWPFTableRow headerRow = table.getRow(0);
            for (int ci = 0; ci < headers.size(); ci++) {
                headerRow.getCell(ci).removeParagraph(0);
                headerRow.getCell(ci).setText(headers.get(ci));
            }

            for (int ri = 0; ri < rows.size(); ri++) {
                Map<String, String> row = rows.get(ri);
                XWPFTableRow tableRow = table.getRow(ri + 1);
                for (int ci = 0; ci < headers.size(); ci++) {
                    String h = headers.get(ci);
                    String value = row.getOrDefault(h, "");
                    tableRow.getCell(ci).removeParagraph(0);
                    tableRow.getCell(ci).setText(value == null ? "" : value);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(outputPath)) { doc.write(fos); }
        }
    }

    private String buildMarkdownTable(List<String> headers, List<Map<String, String>> rows) {
        if (headers == null || headers.isEmpty()) return "| 内容 |\n|---|\n";
        StringBuilder sb = new StringBuilder();
        sb.append("| ");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(escapeMdCell(headers.get(i)));
        }
        sb.append(" |\n| ");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append("---");
        }
        sb.append(" |\n");

        for (Map<String, String> row : rows) {
            sb.append("| ");
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) sb.append(" | ");
                String val = row.getOrDefault(headers.get(i), "");
                sb.append(escapeMdCell(val));
            }
            sb.append(" |\n");
        }
        return sb.toString();
    }

    private String escapeMdCell(String text) {
        if (text == null) return "";
        return text.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private String buildTextTable(List<String> headers, List<Map<String, String>> rows) {
        if (headers == null || headers.isEmpty()) return "内容\n";
        List<Integer> widths = new ArrayList<>();
        for (String h : headers) {
            widths.add(Math.max(4, h == null ? 0 : h.length()));
        }
        for (Map<String, String> row : rows) {
            for (int i = 0; i < headers.size(); i++) {
                String val = row.getOrDefault(headers.get(i), "");
                widths.set(i, Math.min(60, Math.max(widths.get(i), val == null ? 0 : val.length())));
            }
        }

        StringBuilder sb = new StringBuilder();
        appendTextTableRow(sb, headers, widths);
        appendTextTableDivider(sb, widths);
        for (Map<String, String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String h : headers) {
                String v = row.getOrDefault(h, "");
                if (v != null && v.length() > 60) v = v.substring(0, 57) + "...";
                cells.add(v == null ? "" : v.replace("\n", " ").replace("\r", " "));
            }
            appendTextTableRow(sb, cells, widths);
        }
        return sb.toString();
    }

    private void appendTextTableRow(StringBuilder sb, List<String> cells, List<Integer> widths) {
        sb.append("|");
        for (int i = 0; i < widths.size(); i++) {
            String raw = i < cells.size() && cells.get(i) != null ? cells.get(i) : "";
            int width = widths.get(i);
            String cell = raw.length() > width ? raw.substring(0, width) : raw;
            sb.append(" ").append(String.format("%-" + width + "s", cell)).append(" |");
        }
        sb.append("\n");
    }

    private void appendTextTableDivider(StringBuilder sb, List<Integer> widths) {
        sb.append("|");
        for (Integer width : widths) {
            sb.append("-").append("-".repeat(Math.max(2, width))).append("-|");
        }
        sb.append("\n");
    }

    private String normalizeOutputType(String outputType, String fallbackExt) {
        String t = outputType == null ? "" : outputType.toLowerCase().trim();
        if ("auto".equals(t) || t.isBlank()) t = fallbackExt == null ? "" : fallbackExt.toLowerCase().trim();
        if ("word".equals(t) || "doc".equals(t)) return "docx";
        if ("markdown".equals(t)) return "md";
        if ("text".equals(t) || "plain".equals(t)) return "txt";
        if ("xls".equals(t)) return "xlsx";
        if ("xlsx".equals(t) || "docx".equals(t) || "md".equals(t) || "txt".equals(t)) return t;

        String fb = fallbackExt == null ? "" : fallbackExt.toLowerCase().trim();
        if ("xls".equals(fb)) return "xlsx";
        if ("doc".equals(fb)) return "docx";
        if ("xlsx".equals(fb) || "docx".equals(fb) || "md".equals(fb) || "txt".equals(fb)) return fb;
        return "txt";
    }

    private String buildDeterministicFallbackDocument(String templateSnippet,
                                                      String sourceFacts,
                                                      String requirementText,
                                                      String ext) {
        StringBuilder sb = new StringBuilder();
        if ("md".equalsIgnoreCase(ext)) {
            sb.append("# 自动降级输出\n\n");
            sb.append("> 模型调用失败，已降级为规则化输出。\n");
            sb.append("> 失败原因：服务调用异常（详见后端日志）。\n");
            sb.append("\n## 用户要求\n").append(clampText(requirementText, 3000, "要求兜底")).append("\n\n");
            sb.append("## 模板摘要\n").append(clampText(templateSnippet, 5000, "模板兜底")).append("\n\n");
            sb.append("## 数据源事实\n").append(clampText(sourceFacts, 12000, "事实兜底")).append("\n");
            return sb.toString();
        }
        sb.append("【自动降级输出】\n");
        sb.append("模型调用失败，已降级为规则化输出。\n");
        sb.append("失败原因：服务调用异常（详见后端日志）。\n");
        sb.append("\n【用户要求】\n").append(clampText(requirementText, 3000, "要求兜底")).append("\n");
        sb.append("\n【模板摘要】\n").append(clampText(templateSnippet, 5000, "模板兜底")).append("\n");
        sb.append("\n【数据源事实】\n").append(clampText(sourceFacts, 12000, "事实兜底")).append("\n");
        return sb.toString();
    }

    private TemplateInfo parseTemplateInfo(String filePath, String ext) throws IOException {
        TemplateInfo info = new TemplateInfo();
        if ("xlsx".equals(ext) || "xls".equals(ext)) {
            try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
                Sheet sheet = wb.getSheetAt(0);
                info.sheetName = sheet.getSheetName();
                DataFormatter fmt = new DataFormatter();

                for (int ri = 0; ri <= Math.min(sheet.getLastRowNum(), 10); ri++) {
                    Row row = sheet.getRow(ri);
                    if (row == null) continue;
                    List<String> cells = new ArrayList<>();
                    for (int ci = 0; ci < row.getLastCellNum(); ci++) {
                        String val = fmt.formatCellValue(row.getCell(ci)).trim();
                        cells.add(val);
                    }
                    long nonEmpty = cells.stream().filter(s -> !s.isBlank()).count();
                    if (nonEmpty >= 2) {
                        info.headerRowIndex = ri;
                        info.headers = cells;
                        info.dataStartRow = ri + 1;
                        info.templateDataRows = Math.max(0, sheet.getLastRowNum() - ri);
                        break;
                    }
                }

                if (info.headers.isEmpty()) {
                    info.headers = Collections.singletonList("内容");
                    info.headerRowIndex = 0;
                    info.dataStartRow = 1;
                }
            }
        }
        return info;
    }

    private List<Map<String, String>> extractAllRows(LlmConfig config,
                                                      TemplateInfo tmpl,
                                                      String fullSourceText,
                                                      String requirementText,
                                                      String requirementAnalysis) throws Exception {
        List<Map<String, String>> allRows = new ArrayList<>();
        List<String> chunks = splitIntoChunks(fullSourceText, EXTRACTION_CHUNK_SIZE);
        if (chunks.isEmpty()) {
            log.warn("No source text available for extraction");
            return allRows;
        }
        String compactRequirementText = clampText(requirementText, 1200, "抽取用用户要求");

        String headersJson = objectMapper.writeValueAsString(tmpl.headers);
        Set<String> seen = new LinkedHashSet<>();

        for (int ci = 0; ci < chunks.size(); ci++) {
            String chunk = chunks.get(ci);
            String prompt = buildExtractionPrompt(tmpl.headers, headersJson, chunk, ci, chunks.size(), compactRequirementText, requirementAnalysis);

            LlmConfig extractConfig = cloneConfigWithHigherTokens(config, EXTRACTION_MAX_TOKENS);
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是专业的数据提取助手。严格按照要求的JSON格式输出，不添加任何额外说明文字。"
                                    + "输出必须是合法的JSON数组，每个元素是一个对象，键为列头名称，值为对应数据。"),
                    Map.of("role", "user", "content", prompt)
            );

            try {
                String response = llmService.chatLong(extractConfig, messages);
                List<Map<String, String>> rows = parseRowsFromResponse(response, tmpl.headers);
                if (rows.isEmpty()) {
                    String retryPrompt = buildRetryExtractionPrompt(tmpl.headers, headersJson, chunk, compactRequirementText, requirementAnalysis);
                    String retryResp = llmService.chatLong(cloneConfigWithFixedTokens(config, RETRY_EXTRACTION_MAX_TOKENS), List.of(
                            Map.of("role", "system", "content", "你是JSON抽取修复助手，只能输出JSON数组。"),
                            Map.of("role", "user", "content", retryPrompt)
                    ));
                    rows = parseRowsFromResponse(retryResp, tmpl.headers);
                }
                for (Map<String, String> row : rows) {
                    String key = buildRowKey(row, tmpl.headers);
                    if (!key.isBlank() && seen.add(key)) allRows.add(row);
                }
            } catch (Exception e) {
                log.error("Chunk {} extraction failed: {}", ci + 1, e.getMessage());
            }
        }

        if (allRows.isEmpty()) {
            String globalChunk = clampText(fullSourceText, CHUNK_SIZE * 2, "全局提取文本");
            String fallbackPrompt = buildExtractionPrompt(tmpl.headers, headersJson, globalChunk, 0, 1, requirementText, requirementAnalysis)
                    + "\n\n补充要求：若能识别到任意一条记录，请务必至少输出1个对象；若确实无记录再输出[]。";
            try {
                String fallbackResp = llmService.chatLong(cloneConfigWithFixedTokens(config, RETRY_EXTRACTION_MAX_TOKENS), List.of(
                        Map.of("role", "system", "content", "你是高召回表格抽取助手，只输出JSON数组。"),
                        Map.of("role", "user", "content", fallbackPrompt)
                ));
                List<Map<String, String>> rows = parseRowsFromResponse(fallbackResp, tmpl.headers);
                for (Map<String, String> row : rows) {
                    String key = buildRowKey(row, tmpl.headers);
                    if (!key.isBlank() && seen.add(key)) allRows.add(row);
                }
            } catch (Exception e) {
                log.warn("Global fallback extraction failed: {}", e.getMessage());
            }
        }

        return allRows;
    }

    private String buildExtractionPrompt(List<String> headers, String headersJson,
                                         String chunk, int chunkIndex, int totalChunks,
                                         String requirementText,
                                         String requirementAnalysis) {
        return "## 任务\n"
                + "从以下文档片段中提取所有符合表格列头的数据行。\n\n"
                + ((requirementText != null && !requirementText.isBlank()) ? "## 用户要求\n" + requirementText + "\n\n" : "")
                + ((requirementAnalysis != null && !requirementAnalysis.isBlank()) ? "## 用户要求解析\n" + requirementAnalysis + "\n\n" : "")
                + "## 表格列头（共 " + headers.size() + " 列）\n"
                + headersJson + "\n\n"
                + "## 文档内容（第 " + (chunkIndex + 1) + "/" + totalChunks + " 段）\n"
                + chunk + "\n\n"
                + "## 输出要求\n"
                + "1. 提取文档中所有能对应到上述列头的数据，不要遗漏任何一条\n"
                + "2. 每条数据对应一个JSON对象，键必须和列头完全一致\n"
                + "3. 找不到对应值的列填写空字符串 \"\"\n"
                + "4. 优先识别“表格行/列表项/键值对”并映射到列头\n"
                + "5. 只输出JSON数组，不要有任何解释、前缀或后缀文字\n"
                + "6. 如果本段文档中没有符合条件的数据，输出空数组 []\n\n"
                + "输出格式示例：\n"
                + "[{\"" + (headers.isEmpty() ? "列1" : headers.get(0)) + "\": \"值1\", "
                + "\"" + (headers.size() > 1 ? headers.get(1) : "列2") + "\": \"值2\"}]\n\n"
                + "现在输出JSON数组：";
    }

    private String buildRetryExtractionPrompt(List<String> headers, String headersJson,
                                              String chunk, String requirementText, String requirementAnalysis) {
        return "请重新执行一次高召回抽取，必须输出合法JSON数组。\n\n"
                + ((requirementText != null && !requirementText.isBlank()) ? "## 用户要求\n" + requirementText + "\n\n" : "")
                + ((requirementAnalysis != null && !requirementAnalysis.isBlank()) ? "## 用户要求解析\n" + requirementAnalysis + "\n\n" : "")
                + "## 列头\n" + headersJson + "\n\n"
                + "## 输入文本\n" + chunk + "\n\n"
                + "规则：\n"
                + "1) 仅输出 JSON 数组\n"
                + "2) 键名必须严格等于列头\n"
                + "3) 无值填空字符串\n"
                + "4) 先保证能提取到有效记录，再追求完整\n"
                + "5) 若确实无记录输出 []\n"
                + "示例键名首项: " + (headers.isEmpty() ? "列1" : headers.get(0));
    }

    private List<Map<String, String>> parseRowsFromResponse(String response, List<String> headers) {
        try {
            String json = cleanJsonResponse(response);
            if (json.isBlank() || json.equals("[]")) return Collections.emptyList();

            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                List<Map<String, String>> rows = new ArrayList<>();
                for (JsonNode item : root) {
                    if (!item.isObject()) continue;
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String header : headers) {
                        JsonNode val = item.get(header);
                        if (val != null && !val.isNull()) row.put(header, val.asText().trim());
                        else row.put(header, findFieldIgnoreCase(item, header));
                    }
                    boolean hasData = row.values().stream().anyMatch(v -> !v.isBlank());
                    if (hasData) rows.add(row);
                }
                return rows;
            }
        } catch (Exception e) {
            log.warn("Failed to parse rows JSON: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private String findFieldIgnoreCase(JsonNode item, String header) {
        Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            if (e.getKey().equalsIgnoreCase(header) || e.getKey().trim().equals(header.trim())) {
                return e.getValue().asText().trim();
            }
        }
        return "";
    }

    private String cleanJsonResponse(String response) {
        if (response == null) return "[]";
        String s = response.trim();
        if (s.contains("```json")) {
            int start = s.indexOf("```json") + 7;
            int end = s.indexOf("```", start);
            s = end > start ? s.substring(start, end).trim() : s.substring(start).trim();
        } else if (s.contains("```")) {
            int start = s.indexOf("```") + 3;
            int end = s.indexOf("```", start);
            s = end > start ? s.substring(start, end).trim() : s.substring(start).trim();
        }
        int arrStart = s.indexOf('[');
        int arrEnd = s.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) s = s.substring(arrStart, arrEnd + 1);
        return s.isBlank() ? "[]" : s;
    }

    private String buildRowKey(Map<String, String> row, List<String> headers) {
        StringBuilder key = new StringBuilder();
        for (String h : headers) key.append(row.getOrDefault(h, "")).append("|");
        return key.toString();
    }

    private String writeRowsToTemplate(String templatePath, String ext,
                                       TemplateInfo tmpl, List<Map<String, String>> rows,
                                       String originalName) throws IOException {
        String outputFileName = sanitizeFilename(System.currentTimeMillis() + "_filled_" + originalName);
        Path outFile = resolveOutputPath(outputFileName);
        String outputPath = outFile.toString();

        if ("xlsx".equals(ext) || "xls".equals(ext)) writeExcel(templatePath, outputPath, tmpl, rows);
        else if ("docx".equals(ext)) writeDocx(templatePath, outputPath, tmpl, rows);
        else writePlainText(outputPath, tmpl, rows);
        return outputPath;
    }

    private void writeExcel(String templatePath, String outputPath,
                            TemplateInfo tmpl, List<Map<String, String>> rows) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new File(templatePath))) {
            Sheet sheet = wb.getSheet(tmpl.sheetName);
            if (sheet == null) sheet = wb.getSheetAt(0);

            CellStyle dataStyle = null;
            if (sheet.getLastRowNum() >= tmpl.dataStartRow) {
                Row existingRow = sheet.getRow(tmpl.dataStartRow);
                if (existingRow != null && existingRow.getCell(0) != null) dataStyle = existingRow.getCell(0).getCellStyle();
            }

            Map<String, Integer> headerColMap = new LinkedHashMap<>();
            Row headerRow = sheet.getRow(tmpl.headerRowIndex);
            if (headerRow != null) {
                DataFormatter fmt = new DataFormatter();
                for (int ci = 0; ci < headerRow.getLastCellNum(); ci++) {
                    String h = fmt.formatCellValue(headerRow.getCell(ci)).trim();
                    if (!h.isBlank()) headerColMap.put(h, ci);
                }
            } else {
                for (int i = 0; i < tmpl.headers.size(); i++) headerColMap.put(tmpl.headers.get(i), i);
            }

            for (int ri = 0; ri < rows.size(); ri++) {
                int rowIdx = tmpl.dataStartRow + ri;
                Row excelRow = sheet.getRow(rowIdx);
                if (excelRow == null) excelRow = sheet.createRow(rowIdx);

                Map<String, String> dataRow = rows.get(ri);
                for (Map.Entry<String, String> entry : dataRow.entrySet()) {
                    Integer colIdx = headerColMap.get(entry.getKey());
                    if (colIdx == null) {
                        for (Map.Entry<String, Integer> hc : headerColMap.entrySet()) {
                            if (hc.getKey().trim().equalsIgnoreCase(entry.getKey().trim())) {
                                colIdx = hc.getValue();
                                break;
                            }
                        }
                    }
                    if (colIdx != null) {
                        Cell cell = excelRow.getCell(colIdx);
                        if (cell == null) cell = excelRow.createCell(colIdx);
                        cell.setCellValue(entry.getValue());
                        if (dataStyle != null) {
                            try { cell.setCellStyle(dataStyle); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath)) { wb.write(fos); }
        }
    }

    private void writeDocx(String templatePath, String outputPath,
                           TemplateInfo tmpl, List<Map<String, String>> rows) throws IOException {
        Files.copy(Paths.get(templatePath), Paths.get(outputPath), StandardCopyOption.REPLACE_EXISTING);
        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(outputPath))) {
            List<XWPFTable> tables = doc.getTables();
            if (tables.isEmpty() || rows.isEmpty()) {
                try (FileOutputStream fos = new FileOutputStream(outputPath)) { doc.write(fos); }
                return;
            }
            XWPFTable table = tables.get(0);
            XWPFTableRow headerRow = table.getRow(0);
            List<String> docHeaders = new ArrayList<>();
            if (headerRow != null) for (XWPFTableCell c : headerRow.getTableCells()) docHeaders.add(c.getText().trim());

            for (int ri = 0; ri < rows.size(); ri++) {
                int tableRowIdx = tmpl.dataStartRow + ri;
                XWPFTableRow tableRow = tableRowIdx < table.getRows().size() ? table.getRow(tableRowIdx) : table.createRow();
                Map<String, String> dataRow = rows.get(ri);
                for (int ci = 0; ci < docHeaders.size(); ci++) {
                    String value = dataRow.getOrDefault(docHeaders.get(ci), "");
                    if (ci < tableRow.getTableCells().size()) tableRow.getCell(ci).setText(value);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(outputPath)) { doc.write(fos); }
        }
    }

    private void writePlainText(String outputPath, TemplateInfo tmpl, List<Map<String, String>> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", tmpl.headers)).append("\n");
        for (Map<String, String> row : rows) {
            List<String> values = new ArrayList<>();
            for (String h : tmpl.headers) values.add(row.getOrDefault(h, ""));
            sb.append(String.join("\t", values)).append("\n");
        }
        Files.writeString(Paths.get(outputPath), sb.toString(), StandardCharsets.UTF_8);
    }

    private List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;
        int safeChunkSize = Math.max(1, chunkSize);
        int safeOverlap = Math.max(0, Math.min(CHUNK_OVERLAP, safeChunkSize - 1));
        if (text.length() <= chunkSize) {
            chunks.add(text);
            return chunks;
        }
        int start = 0;
        int len = text.length();
        while (start < len) {
            int end = Math.min(start + safeChunkSize, len);
            if (end < len) {
                int nlPos = text.lastIndexOf('\n', end);
                if (nlPos > start + safeChunkSize / 2) end = nlPos + 1;
            }
            chunks.add(text.substring(start, end));
            if (end >= len) break;
            int nextStart = Math.max(end - safeOverlap, start + 1);
            start = nextStart;
        }
        return chunks;
    }

    private LlmConfig cloneConfigWithHigherTokens(LlmConfig original, int tokens) {
        LlmConfig clone = new LlmConfig();
        clone.setId(original.getId());
        clone.setConfigName(original.getConfigName());
        clone.setProvider(original.getProvider());
        clone.setBaseUrl(original.getBaseUrl());
        clone.setApiKey(original.getApiKey());
        clone.setModelName(original.getModelName());
        clone.setTemperature(original.getTemperature());
        clone.setMaxTokens(Math.max(tokens, original.getMaxTokens() != null ? original.getMaxTokens() : 4096));
        clone.setIsDefault(original.getIsDefault());
        clone.setIsActive(original.getIsActive());
        return clone;
    }

    private LlmConfig cloneConfigWithFixedTokens(LlmConfig original, int tokens) {
        LlmConfig clone = new LlmConfig();
        clone.setId(original.getId());
        clone.setConfigName(original.getConfigName());
        clone.setProvider(original.getProvider());
        clone.setBaseUrl(original.getBaseUrl());
        clone.setApiKey(original.getApiKey());
        clone.setModelName(original.getModelName());
        clone.setTemperature(original.getTemperature());
        clone.setMaxTokens(Math.max(256, tokens));
        clone.setIsDefault(original.getIsDefault());
        clone.setIsActive(original.getIsActive());
        return clone;
    }

    private String clampText(String text, int maxChars, String name) {
        if (text == null) return "";
        if (maxChars <= 0) {
            log.warn("{} 的截断配置无效（maxChars={}），按空文本处理", name, maxChars);
            return "";
        }
        if (text.length() <= maxChars) return text;
        log.warn("{} 过长，已截断：{} -> {} 字符", name, text.length(), maxChars);
        return text.substring(0, maxChars) + "\n\n[...内容已截断...]";
    }

    private String saveTempFile(MultipartFile file) throws IOException {
        String filename = System.currentTimeMillis() + "_temp_" + sanitizeFilename(file.getOriginalFilename());
        Path dest = Paths.get(appConfig.getUploadTempPath(), filename);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        return dest.toString();
    }

    private String sanitizeFilename(String name) {
        String raw = (name == null || name.isBlank()) ? "file.txt" : name;
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        String sanitized = normalized
                .replaceAll("[\\p{Cntrl}\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]+", "_")
                .replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        while (sanitized.contains("..")) sanitized = sanitized.replace("..", "_");
        sanitized = sanitized.replaceAll("^\\.+", "");
        return sanitized.isBlank() ? "file.txt" : sanitized;
    }

    private Path resolveOutputPath(String fileName) throws IOException {
        Path outputDir = Paths.get(appConfig.getOutputPath()).normalize().toAbsolutePath();
        Files.createDirectories(outputDir);
        Path resolved = outputDir.resolve(fileName).normalize().toAbsolutePath();
        if (!resolved.startsWith(outputDir)) {
            throw new IOException("输出路径不安全，拒绝写入");
        }
        return resolved;
    }

    private String baseName(String name) {
        if (name == null || name.isBlank()) return "file";
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private String ensureSafePath(String rawPath) {
        Path normalized = Paths.get(rawPath).normalize().toAbsolutePath();
        Path kb = Paths.get(appConfig.getKnowledgeBasePath()).normalize().toAbsolutePath();
        Path temp = Paths.get(appConfig.getUploadTempPath()).normalize().toAbsolutePath();
        if (normalized.startsWith(kb) || normalized.startsWith(temp)) return normalized.toString();
        throw new RuntimeException("模板路径不安全，拒绝访问");
    }

    private record ResolvedFile(String path, String originalName, String ext, boolean cleanup) {}

    private static class TemplateInfo {
        List<String> headers = new ArrayList<>();
        String sheetName = "Sheet1";
        int headerRowIndex = 0;
        int dataStartRow = 1;
        int templateDataRows = 0;
    }
}

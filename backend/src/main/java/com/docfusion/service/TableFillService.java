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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class TableFillService {

    private static final Logger log = LoggerFactory.getLogger(TableFillService.class);
    private static final int CHUNK_SIZE = 12000;

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
                List.of(), null, llmConfigId, sessionId);
    }

    public OutputFile fillTableFlexible(MultipartFile templateUpload,
                                        Long templateDocId,
                                        List<Long> sourceDocIds,
                                        MultipartFile[] sourceFiles,
                                        List<Long> requirementDocIds,
                                        MultipartFile[] requirementFiles,
                                        Long llmConfigId,
                                        String sessionId) throws Exception {
        LlmConfig config = llmService.getConfigById(llmConfigId);
        if (config == null) throw new RuntimeException("未找到可用的模型配置，请先在设置中配置大模型");

        ResolvedFile template = resolveTemplate(templateUpload, templateDocId);
        String ext = template.ext;

        String sourceText = buildCombinedText(sourceDocIds, sourceFiles, "数据源");
        String requirementText = buildCombinedText(requirementDocIds, requirementFiles, "用户要求");

        String outputPath;
        String description;
        if ("xlsx".equals(ext) || "xls".equals(ext)) {
            TemplateInfo tmpl = parseTemplateInfo(template.path, ext);
            String fullSourceText = sourceText;
            if (requirementText != null && !requirementText.isBlank()) {
                fullSourceText = sourceText + "\n\n=== 用户要求 ===\n" + requirementText;
            }
            List<Map<String, String>> allRows = extractAllRows(config, tmpl, fullSourceText, requirementText);
            outputPath = writeRowsToTemplate(template.path, ext, tmpl, allRows, template.originalName);
            description = String.format("AI自动填写完成，共提取 %d 行数据", allRows.size());
        } else {
            String templateText = readTemplateAsText(template.path, ext);
            String generated = generateNonExcelOutput(config, templateText, sourceText, requirementText, ext);
            outputPath = writeGeneratedOutput(ext, generated, template.originalName);
            description = "AI自动填写完成，已生成内容文档";
        }

        File outFile = new File(outputPath);
        OutputFile output = new OutputFile();
        output.setSessionId(sessionId);
        output.setFileName("已填写_" + template.originalName);
        output.setFilePath(outputPath);
        output.setFileType(ext);
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
        String prompt = "你是文档填写助手。请根据模板结构与用户要求生成最终文档内容。\n\n"
                + ((requirementText != null && !requirementText.isBlank()) ? "# 用户要求\n" + requirementText + "\n\n" : "")
                + "# 模板内容\n" + templateText + "\n\n"
                + "# 数据源\n" + sourceText + "\n\n"
                + "输出要求：\n"
                + "1) 严格基于数据源和用户要求，不编造\n"
                + "2) 保持模板的章节/字段语义\n"
                + "3) 直接输出最终文档正文，不要额外解释\n"
                + "4) 输出语言与模板保持一致\n"
                + "5) 当前目标格式: " + ext;

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", "你是专业的文档生成与排版助手。"),
                Map.of("role", "user", "content", prompt)
        );
        return llmService.chatLong(cloneConfigWithHigherTokens(config, 8192), messages);
    }

    private String writeGeneratedOutput(String ext, String content, String originalName) throws IOException {
        String outName = sanitizeFilename(System.currentTimeMillis() + "_filled_" + originalName);
        String outPath = appConfig.getOutputPath() + File.separator + outName;

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
                                                      String requirementText) throws Exception {
        List<Map<String, String>> allRows = new ArrayList<>();
        List<String> chunks = splitIntoChunks(fullSourceText, CHUNK_SIZE);
        if (chunks.isEmpty()) {
            log.warn("No source text available for extraction");
            return allRows;
        }

        String headersJson = objectMapper.writeValueAsString(tmpl.headers);
        Set<String> seen = new LinkedHashSet<>();

        for (int ci = 0; ci < chunks.size(); ci++) {
            String chunk = chunks.get(ci);
            String prompt = buildExtractionPrompt(tmpl.headers, headersJson, chunk, ci, chunks.size(), requirementText);

            LlmConfig extractConfig = cloneConfigWithHigherTokens(config, 8192);
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是专业的数据提取助手。严格按照要求的JSON格式输出，不添加任何额外说明文字。"
                                    + "输出必须是合法的JSON数组，每个元素是一个对象，键为列头名称，值为对应数据。"),
                    Map.of("role", "user", "content", prompt)
            );

            try {
                String response = llmService.chatLong(extractConfig, messages);
                List<Map<String, String>> rows = parseRowsFromResponse(response, tmpl.headers);
                for (Map<String, String> row : rows) {
                    String key = buildRowKey(row, tmpl.headers);
                    if (!key.isBlank() && seen.add(key)) allRows.add(row);
                }
            } catch (Exception e) {
                log.error("Chunk {} extraction failed: {}", ci + 1, e.getMessage());
            }
        }

        return allRows;
    }

    private String buildExtractionPrompt(List<String> headers, String headersJson,
                                         String chunk, int chunkIndex, int totalChunks,
                                         String requirementText) {
        return "## 任务\n"
                + "从以下文档片段中提取所有符合表格列头的数据行。\n\n"
                + ((requirementText != null && !requirementText.isBlank()) ? "## 用户要求\n" + requirementText + "\n\n" : "")
                + "## 表格列头（共 " + headers.size() + " 列）\n"
                + headersJson + "\n\n"
                + "## 文档内容（第 " + (chunkIndex + 1) + "/" + totalChunks + " 段）\n"
                + chunk + "\n\n"
                + "## 输出要求\n"
                + "1. 提取文档中所有能对应到上述列头的数据，不要遗漏任何一条\n"
                + "2. 每条数据对应一个JSON对象，键必须和列头完全一致\n"
                + "3. 找不到对应值的列填写空字符串 \"\"\n"
                + "4. 只输出JSON数组，不要有任何解释、前缀或后缀文字\n"
                + "5. 如果本段文档中没有符合条件的数据，输出空数组 []\n\n"
                + "输出格式示例：\n"
                + "[{\"" + (headers.isEmpty() ? "列1" : headers.get(0)) + "\": \"值1\", "
                + "\"" + (headers.size() > 1 ? headers.get(1) : "列2") + "\": \"值2\"}]\n\n"
                + "现在输出JSON数组：";
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
        String outputPath = appConfig.getOutputPath() + File.separator + outputFileName;

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
        if (text.length() <= chunkSize) {
            chunks.add(text);
            return chunks;
        }
        int overlap = 500;
        int start = 0;
        int len = text.length();
        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            if (end < len) {
                int nlPos = text.lastIndexOf('\n', end);
                if (nlPos > start + chunkSize / 2) end = nlPos + 1;
            }
            chunks.add(text.substring(start, end));
            if (end >= len) break;
            int nextStart = Math.max(end - overlap + 1, start + 1);
            if (nextStart <= start) break;
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

    private String saveTempFile(MultipartFile file) throws IOException {
        String filename = System.currentTimeMillis() + "_temp_" + sanitizeFilename(file.getOriginalFilename());
        Path dest = Paths.get(appConfig.getUploadTempPath(), filename);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        return dest.toString();
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

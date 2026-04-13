package com.docfusion.service;

import com.docfusion.config.AppConfig;
import com.docfusion.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
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

    // How many data rows to request per LLM batch call
    private static final int BATCH_SIZE = 30;
    // Max chars of source text sent per LLM call (no truncation of whole doc)
    private static final int CHUNK_SIZE = 12000;

    @Autowired private LlmService llmService;
    @Autowired private DocumentExtractService extractService;
    @Autowired private KnowledgeDocumentRepo docRepo;
    @Autowired private OutputFileRepo outputFileRepo;
    @Autowired private AppConfig appConfig;
    @Autowired private ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────
    public OutputFile fillTableFromKnowledgeBase(MultipartFile templateFile,
                                                  List<Long> sourceDocIds,
                                                  Long llmConfigId,
                                                  String sessionId) throws Exception {
        LlmConfig config = llmService.getConfigById(llmConfigId);
        if (config == null) throw new RuntimeException("未找到可用的模型配置，请先在设置中配置大模型");

        String ext = extractService.getExtension(templateFile.getOriginalFilename()).toLowerCase();
        String tempPath = saveTempFile(templateFile);

        // ① Parse template headers
        TemplateInfo tmpl = parseTemplateInfo(tempPath, ext);
        log.info("Template headers ({}): {}", tmpl.headers.size(), tmpl.headers);

        // ② Build full source text (no global 8000-char truncation)
        String fullSourceText = buildFullSourceText(sourceDocIds);
        log.info("Full source text length: {} chars", fullSourceText.length());

        // ③ Extract all data rows via batched LLM calls
        List<Map<String, String>> allRows = extractAllRows(config, tmpl, fullSourceText);
        log.info("Total rows extracted: {}", allRows.size());

        // ④ Write rows into template file
        String outputPath = writeRowsToTemplate(tempPath, ext, tmpl, allRows,
                templateFile.getOriginalFilename());

        File outFile = new File(outputPath);
        OutputFile output = new OutputFile();
        output.setSessionId(sessionId);
        output.setFileName("已填写_" + templateFile.getOriginalFilename());
        output.setFilePath(outputPath);
        output.setFileType(ext);
        output.setFileSize(outFile.length());
        output.setDescription(String.format("AI自动填写完成，共提取 %d 行数据", allRows.size()));
        outputFileRepo.save(output);
        return output;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ① Parse template: extract headers, data-start row, sheet name
    // ─────────────────────────────────────────────────────────────────────────
    private TemplateInfo parseTemplateInfo(String filePath, String ext) throws IOException {
        TemplateInfo info = new TemplateInfo();
        if ("xlsx".equals(ext) || "xls".equals(ext)) {
            try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
                Sheet sheet = wb.getSheetAt(0);
                info.sheetName = sheet.getSheetName();
                DataFormatter fmt = new DataFormatter();

                // Find header row: first non-empty row
                for (int ri = 0; ri <= Math.min(sheet.getLastRowNum(), 10); ri++) {
                    Row row = sheet.getRow(ri);
                    if (row == null) continue;
                    List<String> cells = new ArrayList<>();
                    for (int ci = 0; ci < row.getLastCellNum(); ci++) {
                        String val = fmt.formatCellValue(row.getCell(ci)).trim();
                        cells.add(val);
                    }
                    // Header row: has ≥2 non-empty cells
                    long nonEmpty = cells.stream().filter(s -> !s.isBlank()).count();
                    if (nonEmpty >= 2) {
                        info.headerRowIndex = ri;
                        info.headers = cells;
                        info.dataStartRow = ri + 1;

                        // Detect how many existing data rows the template has (pre-allocated rows)
                        int lastRow = sheet.getLastRowNum();
                        info.templateDataRows = Math.max(0, lastRow - ri);
                        log.info("Header row={}, headers={}, existingDataRows={}", ri, cells, info.templateDataRows);
                        break;
                    }
                }

                // Collect all existing row content to detect filled vs blank
                if (info.headers.isEmpty()) {
                    info.headers = Collections.singletonList("内容");
                    info.headerRowIndex = 0;
                    info.dataStartRow = 1;
                }
            }
        } else if ("docx".equals(ext)) {
            try (XWPFDocument doc = new XWPFDocument(new FileInputStream(filePath))) {
                if (!doc.getTables().isEmpty()) {
                    XWPFTable table = doc.getTables().get(0);
                    if (!table.getRows().isEmpty()) {
                        XWPFTableRow headerRow = table.getRow(0);
                        for (XWPFTableCell cell : headerRow.getTableCells()) {
                            info.headers.add(cell.getText().trim());
                        }
                        info.headerRowIndex = 0;
                        info.dataStartRow = 1;
                        info.templateDataRows = table.getRows().size() - 1;
                    }
                }
            }
        }
        return info;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ② Build full source text without aggressive truncation
    // ─────────────────────────────────────────────────────────────────────────
    private String buildFullSourceText(List<Long> docIds) {
        StringBuilder sb = new StringBuilder();
        for (Long id : docIds) {
            docRepo.findById(id).ifPresent(doc -> {
                sb.append("【来源文档: ").append(doc.getFileName()).append("】\n");
                if (doc.getExtractedText() != null && !doc.getExtractedText().isBlank()) {
                    sb.append(doc.getExtractedText()).append("\n\n");
                } else {
                    sb.append("（文档尚未处理完成）\n\n");
                }
            });
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ③ Batched LLM extraction: chunk source text, call LLM per chunk,
    //    deduplicate rows, return all unique rows
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, String>> extractAllRows(LlmConfig config,
                                                      TemplateInfo tmpl,
                                                      String fullSourceText) throws Exception {
        List<Map<String, String>> allRows = new ArrayList<>();
        List<String> chunks = splitIntoChunks(fullSourceText, CHUNK_SIZE);
        log.info("Source text split into {} chunks", chunks.size());

        String headersJson = objectMapper.writeValueAsString(tmpl.headers);
        // Key for deduplication: concatenate all cell values of a row
        Set<String> seen = new LinkedHashSet<>();

        for (int ci = 0; ci < chunks.size(); ci++) {
            String chunk = chunks.get(ci);
            log.info("Processing chunk {}/{}, length={}", ci + 1, chunks.size(), chunk.length());

            String prompt = buildExtractionPrompt(tmpl.headers, headersJson, chunk, ci, chunks.size());

            // Use higher max_tokens for extraction
            LlmConfig extractConfig = cloneConfigWithHigherTokens(config, 8192);
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是专业的数据提取助手。严格按照要求的JSON格式输出，不添加任何额外说明文字。"
                            + "输出必须是合法的JSON数组，每个元素是一个对象，键为列头名称，值为对应数据。"),
                    Map.of("role", "user", "content", prompt)
            );

            try {
                String response = llmService.chatLong(extractConfig, messages);
                log.info("Chunk {} LLM response length: {}", ci + 1, response.length());

                List<Map<String, String>> rows = parseRowsFromResponse(response, tmpl.headers);
                log.info("Chunk {} extracted {} rows", ci + 1, rows.size());

                for (Map<String, String> row : rows) {
                    String key = buildRowKey(row, tmpl.headers);
                    if (!key.isBlank() && seen.add(key)) {
                        allRows.add(row);
                    }
                }
            } catch (Exception e) {
                log.error("Chunk {} extraction failed: {}", ci + 1, e.getMessage());
            }
        }

        return allRows;
    }

    private String buildExtractionPrompt(List<String> headers, String headersJson,
                                          String chunk, int chunkIndex, int totalChunks) {
        return "## 任务\n"
                + "从以下文档片段中提取所有符合表格列头的数据行。\n\n"
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

    // Parse LLM response → list of row maps
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseRowsFromResponse(String response, List<String> headers) {
        try {
            String json = cleanJsonResponse(response);
            if (json.isBlank() || json.equals("[]")) return Collections.emptyList();

            // Try parsing as array of objects
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                List<Map<String, String>> rows = new ArrayList<>();
                for (JsonNode item : root) {
                    if (!item.isObject()) continue;
                    Map<String, String> row = new LinkedHashMap<>();
                    // Map by header order
                    for (String header : headers) {
                        JsonNode val = item.get(header);
                        if (val != null && !val.isNull()) {
                            row.put(header, val.asText().trim());
                        } else {
                            // Try case-insensitive fallback
                            String found = "";
                            Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
                            while (fields.hasNext()) {
                                Map.Entry<String, JsonNode> e = fields.next();
                                if (e.getKey().equalsIgnoreCase(header) ||
                                    e.getKey().trim().equals(header.trim())) {
                                    found = e.getValue().asText().trim();
                                    break;
                                }
                            }
                            row.put(header, found);
                        }
                    }
                    // Only add row if it has at least one non-empty value
                    boolean hasData = row.values().stream().anyMatch(v -> !v.isBlank());
                    if (hasData) rows.add(row);
                }
                return rows;
            }

            // Fallback: try as array of arrays
            if (root.isArray() && root.size() > 0 && root.get(0).isArray()) {
                List<Map<String, String>> rows = new ArrayList<>();
                for (JsonNode rowNode : root) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 0; i < headers.size() && i < rowNode.size(); i++) {
                        row.put(headers.get(i), rowNode.get(i).asText().trim());
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (Exception e) {
            log.warn("Failed to parse rows JSON: {}, raw={}", e.getMessage(),
                    response.length() > 200 ? response.substring(0, 200) : response);
        }
        return Collections.emptyList();
    }

    private String cleanJsonResponse(String response) {
        if (response == null) return "[]";
        String s = response.trim();
        // Remove ```json ... ``` blocks
        if (s.contains("```json")) {
            int start = s.indexOf("```json") + 7;
            int end = s.indexOf("```", start);
            s = end > start ? s.substring(start, end).trim() : s.substring(start).trim();
        } else if (s.contains("```")) {
            int start = s.indexOf("```") + 3;
            int end = s.indexOf("```", start);
            s = end > start ? s.substring(start, end).trim() : s.substring(start).trim();
        }
        // Find JSON array boundaries
        int arrStart = s.indexOf('[');
        int arrEnd = s.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            s = s.substring(arrStart, arrEnd + 1);
        }
        return s.isBlank() ? "[]" : s;
    }

    private String buildRowKey(Map<String, String> row, List<String> headers) {
        StringBuilder key = new StringBuilder();
        for (String h : headers) key.append(row.getOrDefault(h, "")).append("|");
        return key.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ④ Write rows to file
    // ─────────────────────────────────────────────────────────────────────────
    private String writeRowsToTemplate(String templatePath, String ext,
                                        TemplateInfo tmpl, List<Map<String, String>> rows,
                                        String originalName) throws IOException {
        String outputFileName = System.currentTimeMillis() + "_filled_" + originalName;
        String outputPath = appConfig.getOutputPath() + File.separator + outputFileName;

        if ("xlsx".equals(ext) || "xls".equals(ext)) {
            writeExcel(templatePath, outputPath, tmpl, rows);
        } else if ("docx".equals(ext)) {
            writeDocx(templatePath, outputPath, tmpl, rows);
        } else {
            writePlainText(templatePath, outputPath, tmpl, rows);
        }
        return outputPath;
    }

    private void writeExcel(String templatePath, String outputPath,
                             TemplateInfo tmpl, List<Map<String, String>> rows) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new File(templatePath))) {
            Sheet sheet = wb.getSheet(tmpl.sheetName);
            if (sheet == null) sheet = wb.getSheetAt(0);

            // Get or create a cell style based on existing data style
            CellStyle dataStyle = null;
            if (sheet.getLastRowNum() >= tmpl.dataStartRow) {
                Row existingRow = sheet.getRow(tmpl.dataStartRow);
                if (existingRow != null && existingRow.getCell(0) != null) {
                    dataStyle = existingRow.getCell(0).getCellStyle();
                }
            }

            // Build header → column index map (robust, trim + lowercase comparison)
            Map<String, Integer> headerColMap = new LinkedHashMap<>();
            Row headerRow = sheet.getRow(tmpl.headerRowIndex);
            if (headerRow != null) {
                DataFormatter fmt = new DataFormatter();
                for (int ci = 0; ci < headerRow.getLastCellNum(); ci++) {
                    String h = fmt.formatCellValue(headerRow.getCell(ci)).trim();
                    if (!h.isBlank()) headerColMap.put(h, ci);
                }
            } else {
                // Fallback: use positions
                for (int i = 0; i < tmpl.headers.size(); i++) headerColMap.put(tmpl.headers.get(i), i);
            }
            log.info("Header→col map: {}", headerColMap);

            // Write each row
            for (int ri = 0; ri < rows.size(); ri++) {
                int rowIdx = tmpl.dataStartRow + ri;
                Row excelRow = sheet.getRow(rowIdx);
                if (excelRow == null) excelRow = sheet.createRow(rowIdx);

                Map<String, String> dataRow = rows.get(ri);
                for (Map.Entry<String, String> entry : dataRow.entrySet()) {
                    String header = entry.getKey();
                    String value = entry.getValue();
                    Integer colIdx = headerColMap.get(header);

                    // Fuzzy column lookup if exact match fails
                    if (colIdx == null) {
                        for (Map.Entry<String, Integer> hc : headerColMap.entrySet()) {
                            if (hc.getKey().trim().equalsIgnoreCase(header.trim())) {
                                colIdx = hc.getValue(); break;
                            }
                        }
                    }

                    if (colIdx != null) {
                        Cell cell = excelRow.getCell(colIdx);
                        if (cell == null) cell = excelRow.createCell(colIdx);
                        cell.setCellValue(value);
                        if (dataStyle != null) {
                            try { cell.setCellStyle(dataStyle); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath)) { wb.write(fos); }
            log.info("Excel written: {} rows to {}", rows.size(), outputPath);
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
            // Get header row
            XWPFTableRow headerRow = table.getRow(0);
            List<String> docHeaders = new ArrayList<>();
            if (headerRow != null) {
                for (XWPFTableCell c : headerRow.getTableCells()) docHeaders.add(c.getText().trim());
            }

            // For each data row, add or fill a table row
            for (int ri = 0; ri < rows.size(); ri++) {
                int tableRowIdx = tmpl.dataStartRow + ri;
                XWPFTableRow tableRow;
                if (tableRowIdx < table.getRows().size()) {
                    tableRow = table.getRow(tableRowIdx);
                } else {
                    tableRow = table.createRow();
                }
                Map<String, String> dataRow = rows.get(ri);
                for (int ci = 0; ci < docHeaders.size(); ci++) {
                    String header = docHeaders.get(ci);
                    String value = dataRow.getOrDefault(header, "");
                    if (ci < tableRow.getTableCells().size()) {
                        tableRow.getCell(ci).setText(value);
                    }
                }
            }
            try (FileOutputStream fos = new FileOutputStream(outputPath)) { doc.write(fos); }
        }
    }

    private void writePlainText(String templatePath, String outputPath,
                                 TemplateInfo tmpl, List<Map<String, String>> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\t", tmpl.headers)).append("\n");
        for (Map<String, String> row : rows) {
            List<String> values = new ArrayList<>();
            for (String h : tmpl.headers) values.add(row.getOrDefault(h, ""));
            sb.append(String.join("\t", values)).append("\n");
        }
        Files.writeString(Paths.get(outputPath), sb.toString(), StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Split large text into overlapping chunks */
    private List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= chunkSize) {
            chunks.add(text);
            return chunks;
        }
        int overlap = 500; // overlap to avoid splitting records
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            // Try to break at newline
            if (end < text.length()) {
                int nlPos = text.lastIndexOf('\n', end);
                if (nlPos > start + chunkSize / 2) end = nlPos + 1;
            }
            chunks.add(text.substring(start, end));
            start = end - overlap;
            if (start < 0) start = 0;
            if (start >= text.length()) break;
        }
        return chunks;
    }

    /** Clone config but with higher max_tokens for large extractions */
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
        String filename = System.currentTimeMillis() + "_template_" + file.getOriginalFilename();
        Path dest = Paths.get(appConfig.getUploadTempPath(), filename);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        return dest.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal DTO
    // ─────────────────────────────────────────────────────────────────────────
    private static class TemplateInfo {
        List<String> headers = new ArrayList<>();
        String sheetName = "Sheet1";
        int headerRowIndex = 0;
        int dataStartRow = 1;
        int templateDataRows = 0;
    }
}

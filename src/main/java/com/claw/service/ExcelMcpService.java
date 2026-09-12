package com.claw.service;

import com.claw.config.ExcelMcpProperties;
import com.claw.service.ExcelExportReadyEvent;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ExcelMcpService {
    private static final Logger log = LoggerFactory.getLogger(ExcelMcpService.class);
    private static final DateTimeFormatter EXPORT_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern PREVIEW_COUNT_PATTERN = Pattern.compile("前\\s*(\\d+)\\s*行");
    private static final Pattern COLUMN_EXISTS_PATTERN = Pattern.compile("(?:有没有|是否有|有无)\\s*([\\p{L}\\p{N}_\\-\\u4e00-\\u9fa5]+)\\s*列?");
    private static final double WPS_MIN_INFLATE_RATIO = 0.001d;

    private final ExcelMcpProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public ExcelMcpService(ExcelMcpProperties properties, ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    public boolean supportsWorkbook(String fileName) {
        String normalized = blankToNull(fileName);
        if (normalized == null) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv");
    }

    public String storeWorkbook(String fileName, byte[] data) {
        if (!supportsWorkbook(fileName)) {
            throw new IllegalArgumentException("Unsupported workbook file: " + fileName);
        }
        try {
            Path root = workbookRoot();
            Files.createDirectories(root);
            String safeName = sanitizeFileName(fileName);
            Path target = root.resolve(safeName).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("Workbook path escaped root: " + safeName);
            }
            Files.write(target, data);
            return target.getFileName().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store workbook: " + e.getMessage(), e);
        }
    }

    public String queryWorkbook(String workbook, String sheetName, String question) {
        ensureEnabled();
        SheetTable table = loadSheet(resolveWorkbookPath(workbook), sheetName);
        String normalizedQuestion = blankToNull(question);
        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(table.sourceFile()).append('\n');
        sb.append("Sheet: ").append(table.sheetName()).append('\n');
        sb.append("Columns: ").append(String.join(", ", table.headers())).append('\n');
        sb.append("Row count: ").append(table.rows().size());
        if (normalizedQuestion == null) {
            sb.append("\n\nPreview:\n").append(formatPreview(table, properties.getPreviewRowLimit()));
            return sb.toString();
        }
        sb.append("\n\nQuestion: ").append(normalizedQuestion).append('\n');
        sb.append("Result: ").append(answerQuery(table, normalizedQuestion));
        return sb.toString();
    }

    public String summarizeWorkbook(String workbook,
                                    String sheetName,
                                    List<String> groupByColumns,
                                    List<String> metricColumns,
                                    String summaryType,
                                    String question) {
        ensureEnabled();
        SheetTable table = loadSheet(resolveWorkbookPath(workbook), sheetName);
        String normalizedSummaryType = normalizeSummaryType(summaryType, question);
        List<String> groupColumns = resolveColumns(table.headers(), groupByColumns);
        List<String> metricCols = resolveColumns(table.headers(), metricColumns);
        if (!"count".equals(normalizedSummaryType) && metricCols.isEmpty()) {
            throw new IllegalArgumentException("metricColumns is required for summary type " + normalizedSummaryType);
        }
        List<Map<String, String>> summaryRows = aggregate(table, groupColumns, metricCols, normalizedSummaryType);
        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(table.sourceFile()).append('\n');
        sb.append("Sheet: ").append(table.sheetName()).append('\n');
        sb.append("Summary type: ").append(normalizedSummaryType).append('\n');
        sb.append("Group columns: ").append(groupColumns.isEmpty() ? "(none)" : String.join(", ", groupColumns)).append('\n');
        sb.append("Metric columns: ").append(metricCols.isEmpty() ? "(none)" : String.join(", ", metricCols)).append('\n');
        sb.append("Result rows: ").append(summaryRows.size()).append("\n\n");
        sb.append(formatTable(summaryRows));
        return sb.toString();
    }

    public String exportWorkbook(String workbook,
                                 String sheetName,
                                 String targetSheetName,
                                 String outputFile,
                                 List<String> selectedColumns,
                                 String instruction) {
        return exportWorkbook(workbook, sheetName, targetSheetName, outputFile, selectedColumns, null, null, null, instruction);
    }

    public String exportWorkbook(String workbook,
                                 String sheetName,
                                 String targetSheetName,
                                 String outputFile,
                                 List<String> selectedColumns,
                                 String rowFilter,
                                 String sortColumn,
                                 String sortOrder,
                                 String instruction) {
        ensureEnabled();
        String effectiveRowFilter = normalizeRowFilter(firstNonBlank(rowFilter, extractRowFilter(instruction)));
        String effectiveSortColumn = normalizeSortColumn(firstNonBlank(sortColumn, extractSortColumn(instruction)));
        String effectiveSortOrder = normalizeSortOrder(firstNonBlank(sortOrder, extractSortOrder(instruction)));
        Path sourcePath = Paths.get(resolveWorkbookPath(workbook));
        String extension = extensionOf(sourcePath.getFileName().toString());
        log.info("Excel export request resolved. workbook={}, sheetName={}, rowFilter={}, sortColumn={}, sortOrder={}, selectedColumns={}, outputFile={}",
                workbook, sheetName, effectiveRowFilter, effectiveSortColumn, effectiveSortOrder, selectedColumns, outputFile);
        try {
            if ("csv".equals(extension)) {
                return exportCsv(sourcePath, outputFile, selectedColumns, effectiveRowFilter, effectiveSortColumn, effectiveSortOrder);
            }
            return exportExcel(sourcePath, sheetName, targetSheetName, outputFile, selectedColumns, effectiveRowFilter, effectiveSortColumn, effectiveSortOrder, instruction);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Excel export rejected. workbook={}, sheetName={}, outputFile={}", workbook, sheetName, outputFile, e);
            return "Excel导出失败：" + e.getMessage();
        } catch (Exception e) {
            log.error("Excel export failed. workbook={}, sheetName={}, outputFile={}", workbook, sheetName, outputFile, e);
            return "Excel导出失败：无法生成导出文件，请稍后重试。";
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Excel support is disabled.");
        }
    }

    private String answerQuery(SheetTable table, String question) {
        String normalized = normalizeText(question);
        if (containsAny(normalized, "rowcount", "多少行", "几行", "多少条", "几条", "总数", "行数")) {
            return "Rows: " + table.rows().size();
        }
        if (containsAny(normalized, "columns", "headers", "表头", "列名", "字段", "有哪些列", "有哪几列")) {
            return "Columns: " + String.join(", ", table.headers());
        }
        Matcher matcher = COLUMN_EXISTS_PATTERN.matcher(question);
        if (matcher.find()) {
            String candidate = matcher.group(1);
            String matched = resolveSingleColumn(table.headers(), candidate);
            return matched == null ? "Column not found: " + candidate : "Column exists: " + matched;
        }
        int previewRows = previewCount(question);
        if (previewRows > 0 || containsAny(normalized, "preview", "预览", "示例", "看看", "前几行")) {
            return "\n" + formatPreview(table, previewRows > 0 ? previewRows : properties.getPreviewRowLimit());
        }
        return "Supported local query types: row count, column list, column existence, preview.\n\nPreview:\n"
                + formatPreview(table, properties.getPreviewRowLimit());
    }

    private String normalizeSummaryType(String summaryType, String question) {
        String normalized = normalizeText(firstNonBlank(summaryType, question, "count"));
        if (containsAny(normalized, "sum", "求和", "合计", "总和")) {
            return "sum";
        }
        if (containsAny(normalized, "avg", "average", "平均")) {
            return "avg";
        }
        if (containsAny(normalized, "max", "最大")) {
            return "max";
        }
        if (containsAny(normalized, "min", "最小")) {
            return "min";
        }
        return "count";
    }

    private List<Map<String, String>> aggregate(SheetTable table,
                                                List<String> groupColumns,
                                                List<String> metricColumns,
                                                String summaryType) {
        Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
        for (Map<String, String> row : table.rows()) {
            String key = buildGroupKey(row, groupColumns);
            AggregateBucket bucket = buckets.computeIfAbsent(key, ignored -> new AggregateBucket(groupColumns, row, metricColumns));
            bucket.accept(row, metricColumns);
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (AggregateBucket bucket : buckets.values()) {
            Map<String, String> item = new LinkedHashMap<>();
            for (String column : groupColumns) {
                item.put(column, bucket.groupValues().getOrDefault(column, ""));
            }
            if ("count".equals(summaryType) && metricColumns.isEmpty()) {
                item.put("count", String.valueOf(bucket.rowCount()));
            } else {
                for (String metric : metricColumns) {
                    NumericStats stats = bucket.metrics().get(metric);
                    item.put(metric + "_" + summaryType, stats == null ? "" : stats.render(summaryType, bucket.rowCount()));
                }
                if ("count".equals(summaryType)) {
                    item.put("count", String.valueOf(bucket.rowCount()));
                }
            }
            result.add(item);
        }
        if (result.isEmpty()) {
            Map<String, String> empty = new LinkedHashMap<>();
            empty.put("result", "no data");
            result.add(empty);
        }
        return result;
    }

    private String buildGroupKey(Map<String, String> row, List<String> groupColumns) {
        if (groupColumns.isEmpty()) {
            return "__all__";
        }
        List<String> parts = new ArrayList<>();
        for (String groupColumn : groupColumns) {
            parts.add(row.getOrDefault(groupColumn, ""));
        }
        return String.join("\u0001", parts);
    }

    private String formatPreview(SheetTable table, int limit) {
        int effectiveLimit = Math.max(1, limit);
        List<Map<String, String>> preview = new ArrayList<>();
        for (int i = 0; i < Math.min(effectiveLimit, table.rows().size()); i++) {
            preview.add(table.rows().get(i));
        }
        return preview.isEmpty() ? "(empty sheet)" : formatTable(preview);
    }

    private String formatTable(List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "(empty)";
        }
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            columns.addAll(row.keySet());
        }
        List<String> ordered = new ArrayList<>(columns);
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" | ", ordered)).append('\n');
        sb.append("-".repeat(Math.max(8, ordered.size() * 10))).append('\n');
        for (Map<String, String> row : rows) {
            List<String> values = new ArrayList<>();
            for (String column : ordered) {
                values.add(row.getOrDefault(column, ""));
            }
            sb.append(String.join(" | ", values)).append('\n');
        }
        return sb.toString().trim();
    }

    private SheetTable loadSheet(String workbookPath, String sheetName) {
        Path path = Paths.get(workbookPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Workbook not found: " + workbookPath);
        }
        String extension = extensionOf(path.getFileName().toString());
        try {
            configureZipSecurity();
            return switch (extension) {
                case "csv" -> loadCsv(path);
                case "xls", "xlsx" -> loadWorkbookSheet(path, sheetName);
                default -> throw new IllegalArgumentException("Unsupported workbook type: " + extension);
            };
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read workbook: " + e.getMessage(), e);
        }
    }

    private SheetTable loadCsv(Path path) throws IOException {
        List<List<String>> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && lines.size() <= properties.getMaxReadRows()) {
                lines.add(parseCsvLine(line));
            }
        }
        if (lines.isEmpty()) {
            return new SheetTable(path.getFileName().toString(), "Sheet1", List.of(), List.of());
        }
        List<String> headers = normalizeHeaders(lines.get(0));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(toRowMap(headers, lines.get(i)));
        }
        return new SheetTable(path.getFileName().toString(), "Sheet1", headers, rows);
    }

    private SheetTable loadWorkbookSheet(Path path, String sheetName) throws IOException {
        configureZipSecurity();
        try (InputStream inputStream = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = resolveSheet(workbook, sheetName);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> headers = null;
            List<Map<String, String>> rows = new ArrayList<>();
            int count = 0;
            for (Row row : sheet) {
                if (isEmptyRow(row, formatter, evaluator)) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                int lastCell = Math.max(row.getLastCellNum(), 0);
                for (int i = 0; i < lastCell; i++) {
                    values.add(cellValue(row.getCell(i), formatter, evaluator));
                }
                if (headers == null) {
                    headers = normalizeHeaders(values);
                } else {
                    rows.add(toRowMap(headers, values));
                    count++;
                    if (count >= properties.getMaxReadRows()) {
                        break;
                    }
                }
            }
            if (headers == null) {
                headers = List.of();
            }
            return new SheetTable(path.getFileName().toString(), sheet.getSheetName(), headers, rows);
        }
    }

    private Sheet resolveSheet(Workbook workbook, String sheetName) {
        String requested = blankToNull(sheetName);
        if (requested == null) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Workbook contains no sheets.");
            }
            return workbook.getSheetAt(0);
        }
        Sheet sheet = workbook.getSheet(requested);
        if (sheet != null) {
            return sheet;
        }
        for (Sheet candidate : workbook) {
            if (normalizeText(candidate.getSheetName()).equals(normalizeText(requested))) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Sheet not found: " + requested);
    }

    private boolean isEmptyRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) {
            return true;
        }
        int lastCell = Math.max(row.getLastCellNum(), 0);
        for (int i = 0; i < lastCell; i++) {
            if (!cellValue(row.getCell(i), formatter, evaluator).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String cellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            if (cell.getCellType() == CellType.FORMULA) {
                return formatter.formatCellValue(cell, evaluator).trim();
            }
            return formatter.formatCellValue(cell).trim();
        } catch (Exception e) {
            log.debug("Failed to render cell", e);
            return "";
        }
    }

    private List<String> normalizeHeaders(List<String> rawHeaders) {
        List<String> headers = new ArrayList<>();
        Map<String, Integer> duplicates = new LinkedHashMap<>();
        for (int i = 0; i < rawHeaders.size(); i++) {
            String base = blankToNull(rawHeaders.get(i));
            if (base == null) {
                base = "column_" + (i + 1);
            }
            int count = duplicates.getOrDefault(base, 0) + 1;
            duplicates.put(base, count);
            headers.add(count == 1 ? base : base + "_" + count);
        }
        return headers;
    }

    private Map<String, String> toRowMap(List<String> headers, List<String> values) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            row.put(headers.get(i), i < values.size() ? Objects.toString(values.get(i), "") : "");
        }
        return row;
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString().trim());
        return values;
    }

    private List<String> resolveColumns(List<String> headers, List<String> requestedColumns) {
        List<String> resolved = new ArrayList<>();
        if (requestedColumns == null) {
            return resolved;
        }
        for (String requested : requestedColumns) {
            String matched = resolveSingleColumn(headers, requested);
            if (matched == null) {
                throw new IllegalArgumentException("Column not found: " + requested + ". Available: " + headers);
            }
            resolved.add(matched);
        }
        return resolved;
    }

    private String resolveSingleColumn(List<String> headers, String requested) {
        String normalized = normalizeText(requested);
        if (normalized.isBlank()) {
            return null;
        }
        String alias = normalizeText(resolveColumnAlias(normalized));
        for (String header : headers) {
            if (header.equalsIgnoreCase(requested)) {
                return header;
            }
        }
        for (String header : headers) {
            String normalizedHeader = normalizeText(header);
            if (normalizedHeader.equals(normalized) || (!alias.isBlank() && normalizedHeader.equals(alias))) {
                return header;
            }
        }
        for (String header : headers) {
            String normalizedHeader = normalizeText(header);
            if (normalizedHeader.contains(normalized) || normalized.contains(normalizedHeader)
                    || (!alias.isBlank() && (normalizedHeader.contains(alias) || alias.contains(normalizedHeader)))) {
                return header;
            }
        }
        return null;
    }

    private String resolveColumnAlias(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        return switch (requested) {
            case "价格", "价钱", "成本" -> "价格";
            case "销量", "销售量" -> "销量";
            default -> requested;
        };
    }

    private String exportCsv(Path sourcePath,
                             String outputFile,
                             List<String> selectedColumns,
                             String rowFilter,
                             String sortColumn,
                             String sortOrder) {
        Path targetPath = resolveExportTarget(sourcePath, outputFile, "csv");
        SheetTable table = loadSheet(resolveWorkbookPath(sourcePath.toString()), null);
        List<String> outputHeaders = resolveOutputHeaders(table.headers(), selectedColumns);
        List<Map<String, String>> rows = transformRows(table.rows(), table.headers(), selectedColumns, rowFilter, sortColumn, sortOrder);
        try {
            writeCsvTableSafely(targetPath, outputHeaders, rows);
            log.info("Excel export generated csv file: {}", targetPath.toAbsolutePath());
            publishMinimalExportReadyEvent(targetPath, "Sheet1");
            return buildExportSuccessResult(targetPath, "Sheet1");
        } catch (Exception e) {
            log.error("Failed to export csv. sourcePath={}, outputFile={}", sourcePath, outputFile, e);
            return "Excel导出失败：无法生成导出文件，请检查源文件内容或稍后重试。";
        }
    }

    private String exportExcel(Path sourcePath,
                               String sheetName,
                               String targetSheetName,
                               String outputFile,
                               List<String> selectedColumns,
                               String rowFilter,
                               String sortColumn,
                               String sortOrder,
                               String instruction) {
        String effectiveTargetSheet = blankToNull(targetSheetName);
        if (effectiveTargetSheet == null) {
            effectiveTargetSheet = deriveTargetSheetName(instruction, sheetName);
        }
        try {
            SheetTable table = loadSheet(sourcePath.toString(), sheetName);
            List<String> outputHeaders = resolveOutputHeaders(table.headers(), selectedColumns);
            List<Map<String, String>> rows = transformRows(table.rows(), table.headers(), selectedColumns, rowFilter, sortColumn, sortOrder);
            Path targetPath = resolveExportTarget(sourcePath, outputFile, "xlsx");
            try (InputStream inputStream = Files.newInputStream(sourcePath);
                 Workbook source = WorkbookFactory.create(inputStream);
                 XSSFWorkbook result = new XSSFWorkbook()) {
                Sheet sourceSheet = resolveSheet(source, sheetName);
                Sheet newSheet = result.createSheet(safeSheetName(firstNonBlank(effectiveTargetSheet, sourceSheet.getSheetName())));
                writeWorkbookTable(result, newSheet, outputHeaders, rows);
                writeWorkbookSafely(result, targetPath);
                log.info("Excel export generated workbook file: {}", targetPath.toAbsolutePath());
                publishMinimalExportReadyEvent(targetPath, newSheet.getSheetName());
                return buildExportSuccessResult(targetPath, newSheet.getSheetName());
            }
        } catch (Exception e) {
            log.error("Failed to export workbook. sourcePath={}, sheetName={}, outputFile={}", sourcePath, sheetName, outputFile, e);
            return "Excel导出失败：无法生成导出文件，请检查源文件格式或稍后重试。";
        }
    }

    private List<String> resolveOutputHeaders(List<String> headers, List<String> selectedColumns) {
        if (selectedColumns == null || selectedColumns.isEmpty()) {
            return headers;
        }
        List<String> outputHeaders = new ArrayList<>();
        for (String selected : selectedColumns) {
            String matched = resolveSingleColumn(headers, selected);
            if (matched == null) {
                throw new IllegalArgumentException("Column not found: " + selected + ". Available: " + headers);
            }
            outputHeaders.add(matched);
        }
        return outputHeaders;
    }

    private List<Map<String, String>> transformRows(List<Map<String, String>> rows,
                                                    List<String> headers,
                                                    List<String> selectedColumns,
                                                    String rowFilter,
                                                    String sortColumn,
                                                    String sortOrder) {
        int sourceRowCount = rows == null ? 0 : rows.size();
        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (matchesRowFilter(row, headers, rowFilter)) {
                filtered.add(new LinkedHashMap<>(row));
            }
        }
        if (sortColumn != null && !sortColumn.isBlank()) {
            String matched = resolveSingleColumn(headers, sortColumn);
            if (matched == null) {
                throw new IllegalArgumentException("Sort column not found: " + sortColumn + ". Available: " + headers);
            }
            boolean desc = "desc".equalsIgnoreCase(blankToNull(sortOrder));
            filtered.sort(buildRowComparator(matched, desc));
        }
        List<Map<String, String>> projected = new ArrayList<>();
        for (Map<String, String> row : filtered) {
            projected.add(projectRow(row, headers, selectedColumns));
        }
        log.info("Excel export rows transformed. sourceRows={}, filteredRows={}, projectedRows={}, rowFilter={}, sortColumn={}, sortOrder={}, selectedColumns={}",
                sourceRowCount, filtered.size(), projected.size(), rowFilter, sortColumn, sortOrder, selectedColumns);
        return projected;
    }

    private Map<String, String> projectRow(Map<String, String> row, List<String> headers, List<String> selectedColumns) {
        if (selectedColumns == null || selectedColumns.isEmpty()) {
            return new LinkedHashMap<>(row);
        }
        Map<String, String> projected = new LinkedHashMap<>();
        for (String selected : selectedColumns) {
            String matched = resolveSingleColumn(headers, selected);
            if (matched == null) {
                throw new IllegalArgumentException("Column not found: " + selected + ". Available: " + headers);
            }
            projected.put(matched, row.getOrDefault(matched, ""));
        }
        return projected;
    }

    private boolean matchesRowFilter(Map<String, String> row, List<String> headers, String rowFilter) {
        String expression = blankToNull(rowFilter);
        if (expression == null) {
            return true;
        }
        String normalized = expression.replaceAll("\\s+", "");
        String[] operators = {"不等于", "包含", "不包含", "大于等于", "小于等于", "大于", "小于", ">=", "<=", "==", "=", ">", "<"};
        String operator = null;
        int idx = -1;
        for (String candidate : operators) {
            idx = normalized.indexOf(candidate);
            if (idx > 0) {
                operator = candidate;
                break;
            }
        }
        if (operator == null) {
            return row.values().stream().anyMatch(v -> v != null && v.contains(normalized));
        }
        String left = normalized.substring(0, idx);
        String right = normalized.substring(idx + operator.length());
        String column = resolveSingleColumn(headers, left);
        if (column == null) {
            return row.values().stream().anyMatch(v -> v != null && v.contains(normalized));
        }
        return compareCell(row.getOrDefault(column, ""), operator, right);
    }

    private boolean compareCell(String cell, String operator, String expected) {
        String actual = cell == null ? "" : cell.trim();
        String target = expected == null ? "" : expected.trim();
        return switch (operator) {
            case "=", "==" -> actual.equalsIgnoreCase(target);
            case "不等于" -> !actual.equalsIgnoreCase(target);
            case "包含" -> actual.contains(target);
            case "不包含" -> !actual.contains(target);
            case ">", "<", ">=", "<=", "大于", "小于", "大于等于", "小于等于" -> compareNumeric(actual, operator, target);
            default -> false;
        };
    }

    private boolean compareNumeric(String actual, String operator, String expected) {
        try {
            BigDecimal left = new BigDecimal(sanitizeNumericValue(actual));
            BigDecimal right = new BigDecimal(sanitizeNumericValue(expected));
            return switch (operator) {
                case ">", "大于" -> left.compareTo(right) > 0;
                case "<", "小于" -> left.compareTo(right) < 0;
                case ">=", "大于等于" -> left.compareTo(right) >= 0;
                case "<=", "小于等于" -> left.compareTo(right) <= 0;
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    private Comparator<Map<String, String>> buildRowComparator(String sortColumn, boolean desc) {
        Comparator<Map<String, String>> comparator = Comparator.comparing(
                row -> row.getOrDefault(sortColumn, ""),
                this::compareSortValues
        );
        return desc ? comparator.reversed() : comparator;
    }

    private int compareSortValues(String left, String right) {
        BigDecimal leftNum = parseNumber(left);
        BigDecimal rightNum = parseNumber(right);
        if (leftNum != null && rightNum != null) {
            return leftNum.compareTo(rightNum);
        }
        return normalizeText(left).compareTo(normalizeText(right));
    }

    private BigDecimal parseNumber(String value) {
        if (value == null) {
            return null;
        }
        String normalized = sanitizeNumericValue(value);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeWorkbookTable(Workbook workbook, Sheet sheet, List<String> headers, List<Map<String, String>> rows) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            headerRow.createCell(i).setCellValue(headers.get(i));
        }
        int rowIndex = 1;
        for (Map<String, String> row : rows) {
            Row current = sheet.createRow(rowIndex++);
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                current.createCell(i).setCellValue(row.getOrDefault(header, ""));
            }
        }
    }

    private void writeWorkbookSafely(XSSFWorkbook workbook, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        Path tempPath = Files.createTempFile(targetPath.getParent(), stripExtension(targetPath.getFileName().toString()) + "-", ".tmp");
        boolean success = false;
        try (OutputStream outputStream = Files.newOutputStream(tempPath)) {
            workbook.write(outputStream);
            success = true;
        } finally {
            if (!success) {
                Files.deleteIfExists(tempPath);
            }
        }
        moveTempFile(tempPath, targetPath);
    }

    private void writeCsvTableSafely(Path targetPath, List<String> headers, List<Map<String, String>> rows) throws IOException {
        Files.createDirectories(targetPath.getParent());
        Path tempPath = Files.createTempFile(targetPath.getParent(), stripExtension(targetPath.getFileName().toString()) + "-", ".tmp");
        boolean success = false;
        try {
            writeCsvTable(tempPath, headers, rows);
            success = true;
        } finally {
            if (!success) {
                Files.deleteIfExists(tempPath);
            }
        }
        moveTempFile(tempPath, targetPath);
    }

    private void moveTempFile(Path tempPath, Path targetPath) throws IOException {
        try {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String buildExportSuccessResult(Path targetPath, String sheetName) {
        return "fileName=" + targetPath.getFileName()
                + "\nfilePath=" + targetPath.toAbsolutePath()
                + "\nsheetName=" + sheetName;
    }

    private void publishMinimalExportReadyEvent(Path targetPath, String sheetName) {
        UserSessionContext context = ToolExecutionContextHolder.get();
        if (context == null || context.sessionId() == null || context.userId() == null) {
            log.warn("Skip excel export attachment publish because context is missing. filePath={}, sheetName={}",
                    targetPath, sheetName);
            return;
        }
        String recipientId = context.channelUserId() != null && !context.channelUserId().isBlank()
                ? context.channelUserId()
                : String.valueOf(context.userId());
        eventPublisher.publishEvent(new ExcelExportReadyEvent(
                UUID.randomUUID().toString(),
                recipientId,
                context.sessionId(),
                targetPath,
                targetPath.getFileName().toString(),
                buildExportSuccessResult(targetPath, sheetName),
                "Excel export file"
        ));
    }

    private void publishExportReadyEvent(Path targetPath, String sheetName) {
        UserSessionContext context = ToolExecutionContextHolder.get();
        if (context == null || context.sessionId() == null || context.userId() == null) {
            log.warn("Skip excel export attachment publish because context is missing. filePath={}, sheetName={}",
                    targetPath, sheetName);
            return;
        }
        String recipientId = context.channelUserId() != null && !context.channelUserId().isBlank()
                ? context.channelUserId()
                : String.valueOf(context.userId());
        eventPublisher.publishEvent(new ExcelExportReadyEvent(
                UUID.randomUUID().toString(),
                recipientId,
                context.sessionId(),
                targetPath,
                targetPath.getFileName().toString(),
                "Excel 导出完成，文件名：" + targetPath.getFileName() + "，工作表：" + sheetName + "，路径：" + targetPath.toAbsolutePath(),
                "Excel 导出文件"
        ));
    }

    private void writeCsvTable(Path targetPath, List<String> headers, List<Map<String, String>> rows) throws IOException {
        try (java.io.BufferedWriter writer = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8)) {
            writer.write(renderCsvLine(headers));
            writer.newLine();
            for (Map<String, String> row : rows) {
                List<String> values = new ArrayList<>();
                for (String header : headers) {
                    values.add(row.getOrDefault(header, ""));
                }
                writer.write(renderCsvLine(values));
                writer.newLine();
            }
        }
    }

    private String renderCsvLine(List<String> values) {
        List<String> escaped = new ArrayList<>();
        for (String value : values) {
            String cell = value == null ? "" : value;
            if (cell.contains("\"")) {
                cell = cell.replace("\"", "\"\"");
            }
            if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
                cell = "\"" + cell + "\"";
            }
            escaped.add(cell);
        }
        return String.join(",", escaped);
    }

    private String normalizeRowFilter(String rowFilter) {
        String value = blankToNull(rowFilter);
        if (value == null) {
            return null;
        }
        return value.trim()
                .replace("大于等于", ">=")
                .replace("小于等于", "<=")
                .replace("不等于", "!=")
                .replace("不包含", "not_contains")
                .replace("包含", "contains")
                .replace("大于", ">")
                .replace("小于", "<")
                .replace("等于", "=")
                .replaceAll("\\s+", "")
                .replaceAll("(的|的数据|条|条数据)$", "");
    }

    private String normalizeSortColumn(String sortColumn) {
        String value = blankToNull(sortColumn);
        if (value == null) {
            return null;
        }
        String cleaned = value.trim()
                .replaceAll("^(照|按照|按|根据)", "")
                .replaceAll("(升序|降序|排序|的)$", "")
                .replaceAll("(这一列|那一列|这列|那列|列|数据)$", "")
                .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String normalizeSortOrder(String sortOrder) {
        String value = blankToNull(sortOrder);
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("desc") || normalized.contains("降序") || normalized.contains("从大到小")) {
            return "desc";
        }
        if (normalized.contains("asc") || normalized.contains("升序") || normalized.contains("从小到大")) {
            return "asc";
        }
        return normalized;
    }

    private String sanitizeNumericValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(",", "").replaceAll("[^0-9.\\-]", "").trim();
    }

    private String extractRowFilter(String instruction) {
        String normalized = blankToNull(instruction);
        if (normalized == null) {
            return null;
        }
        String value = extractAfterKeyword(normalized, "筛选", "过滤", "只保留", "保留");
        return value == null || value.isBlank() ? null : value;
    }

    private String extractSortColumn(String instruction) {
        String normalized = blankToNull(instruction);
        if (normalized == null) {
            return null;
        }
        String value = extractAfterKeyword(normalized, "按", "按照", "根据");
        if (value == null) {
            return null;
        }
        value = value.replaceAll("(?i)(升序|降序|正序|倒序|排序)$", "").trim();
        return value.isBlank() ? null : value;
    }

    private String extractSortOrder(String instruction) {
        String normalized = blankToNull(instruction);
        if (normalized == null) {
            return null;
        }
        if (containsAny(normalized, "降序", "倒序", "desc")) {
            return "desc";
        }
        if (containsAny(normalized, "升序", "正序", "asc")) {
            return "asc";
        }
        return null;
    }

    private String deriveTargetSheetName(String instruction, String fallbackSheetName) {
        String normalized = blankToNull(instruction);
        if (normalized == null) {
            return firstNonBlank(fallbackSheetName, "Export");
        }
        if (normalized.contains("汇总")) {
            return firstNonBlank(fallbackSheetName, "Export") + "_summary";
        }
        if (normalized.contains("结果")) {
            return firstNonBlank(fallbackSheetName, "Export") + "_result";
        }
        return firstNonBlank(fallbackSheetName, "Export") + "_copy";
    }

    private String safeSheetName(String sheetName) {
        String sanitized = sheetName.replaceAll("[\\\\/*?:\\[\\]]", "_");
        return sanitized.length() > 31 ? sanitized.substring(0, 31) : sanitized;
    }

    private boolean containsAny(String value, String... candidates) {
        return Arrays.stream(candidates).anyMatch(value::contains);
    }

    private String extractAfterKeyword(String value, String... keywords) {
        int bestIndex = Integer.MAX_VALUE;
        for (String keyword : keywords) {
            int index = value.indexOf(keyword);
            if (index >= 0) {
                int start = index + keyword.length();
                if (start < bestIndex) {
                    bestIndex = start;
                }
            }
        }
        if (bestIndex == Integer.MAX_VALUE || bestIndex >= value.length()) {
            return null;
        }
        return value.substring(bestIndex).replaceFirst("^[,，\\s]+", "").trim();
    }

    private String resolveWorkbookPath(String workbook) {
        String rawPath = blankToNull(workbook);
        if (rawPath == null) {
            throw new IllegalArgumentException("Workbook is required.");
        }
        Path root = workbookRoot();
        Path input = Paths.get(rawPath);
        Path resolved = input.isAbsolute() ? input.normalize() : root.resolve(rawPath).normalize();
        if (!resolved.startsWith(root) && !input.isAbsolute()) {
            throw new IllegalArgumentException("Workbook path escaped root: " + rawPath);
        }
        return resolved.toString();
    }

    private int previewCount(String question) {
        String normalized = blankToNull(question);
        if (normalized == null) {
            return 0;
        }
        Matcher matcher = PREVIEW_COUNT_PATTERN.matcher(normalized);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private Path resolveExportTarget(Path sourcePath, String outputFile, String defaultExtension) {
        String requested = blankToNull(outputFile);
        String extension = blankToNull(defaultExtension);
        if (extension == null) {
            extension = extensionOf(sourcePath.getFileName().toString());
        }
        String baseName = requested == null
                ? stripExtension(sourcePath.getFileName().toString()) + "-export-" + EXPORT_TS.format(LocalDateTime.now())
                : stripExtension(Paths.get(requested).getFileName().toString());
        String targetFileName = baseName + "." + extension;
        Path root = workbookRoot();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create export root: " + root, e);
        }
        return root.resolve(targetFileName).normalize();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sanitizeFileName(String value) {
        String fileName = Paths.get(value).getFileName().toString();
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private Path workbookRoot() {
        String configured = blankToNull(properties.getFileRoot());
        if (configured == null) {
            return Paths.get("D:/mcp-server-files/excel").toAbsolutePath().normalize();
        }
        return Paths.get(configured).toAbsolutePath().normalize();
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    private void configureZipSecurity() {
        ZipSecureFile.setMinInflateRatio(WPS_MIN_INFLATE_RATIO);
    }

    private record SheetTable(String sourceFile, String sheetName, List<String> headers, List<Map<String, String>> rows) {
    }

    private static final class AggregateBucket {
        private final Map<String, String> groupValues = new LinkedHashMap<>();
        private final Map<String, NumericStats> metrics = new LinkedHashMap<>();
        private int rowCount;

        private AggregateBucket(List<String> groupColumns, Map<String, String> row, List<String> metricColumns) {
            for (String groupColumn : groupColumns) {
                groupValues.put(groupColumn, row.getOrDefault(groupColumn, ""));
            }
            for (String metricColumn : metricColumns) {
                metrics.put(metricColumn, new NumericStats());
            }
        }

        private void accept(Map<String, String> row, List<String> metricColumns) {
            rowCount++;
            for (String metricColumn : metricColumns) {
                metrics.computeIfAbsent(metricColumn, ignored -> new NumericStats()).accept(row.get(metricColumn));
            }
        }

        private Map<String, String> groupValues() {
            return groupValues;
        }

        private Map<String, NumericStats> metrics() {
            return metrics;
        }

        private int rowCount() {
            return rowCount;
        }
    }

    private static final class NumericStats {
        private BigDecimal sum = BigDecimal.ZERO;
        private BigDecimal min;
        private BigDecimal max;
        private int numericCount;

        private void accept(String value) {
            BigDecimal number = parseNumber(value);
            if (number == null) {
                return;
            }
            numericCount++;
            sum = sum.add(number);
            min = min == null || number.compareTo(min) < 0 ? number : min;
            max = max == null || number.compareTo(max) > 0 ? number : max;
        }

        private String render(String summaryType, int rowCount) {
            return switch (summaryType) {
                case "sum" -> format(sum);
                case "avg" -> numericCount == 0 ? "" : format(sum.divide(BigDecimal.valueOf(numericCount), 4, RoundingMode.HALF_UP));
                case "min" -> min == null ? "" : format(min);
                case "max" -> max == null ? "" : format(max);
                case "count" -> String.valueOf(rowCount);
                default -> String.valueOf(rowCount);
            };
        }

        private static BigDecimal parseNumber(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().replace(",", "");
            if (normalized.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(normalized);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String format(BigDecimal value) {
            BigDecimal stripped = value.stripTrailingZeros();
            return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
        }
    }
}

package com.claw.service;

import com.claw.config.ExcelMcpProperties;
import com.claw.dto.InvoiceFieldData;
import com.claw.dto.InvoiceRecognitionResult;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class InvoiceExcelExportService {
    private static final String SHEET_NAME = "invoice_ledger";
    private static final String DEFAULT_VALUE = "\u65e0";
    private static final DateTimeFormatter FILE_TS_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final List<String> HEADERS = List.of(
            "\u53d1\u7968\u4ee3\u7801",
            "\u53d1\u7968\u53f7\u7801",
            "\u5f00\u7968\u65e5\u671f",
            "\u91d1\u989d",
            "\u4ef7\u7a0e\u5408\u8ba1",
            "\u7a0e\u989d",
            "\u9500\u552e\u65b9\u540d\u79f0",
            "\u8d2d\u4e70\u65b9\u540d\u79f0",
            "\u6821\u9a8c\u7801"
    );

    private final ExcelMcpProperties excelMcpProperties;
    private final ApplicationEventPublisher eventPublisher;

    public InvoiceExcelExportService(ExcelMcpProperties excelMcpProperties, ApplicationEventPublisher eventPublisher) {
        this.excelMcpProperties = excelMcpProperties;
        this.eventPublisher = eventPublisher;
    }

    public Path export(InvoiceRecognitionResult result) {
        return export(result, null);
    }

    public Path export(InvoiceRecognitionResult result, UserSessionContext context) {
        try {
            Path root = root();
            Files.createDirectories(root);
            Path target = root.resolve(buildFileName(result)).toAbsolutePath().normalize();
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                Sheet sheet = getOrCreateSheet(workbook);
                ensureHeader(sheet);
                appendRow(sheet, result == null ? null : result.getInvoice());
                try (OutputStream out = Files.newOutputStream(target)) {
                    workbook.write(out);
                }
            }
            publishExportEvent(target, context, result);
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to export invoice excel", e);
        }
    }

    private Sheet getOrCreateSheet(XSSFWorkbook workbook) {
        Sheet sheet = workbook.getSheet(SHEET_NAME);
        return sheet != null ? sheet : workbook.createSheet(SHEET_NAME);
    }

    private void ensureHeader(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) {
            header = sheet.createRow(0);
        }
        for (int i = 0; i < HEADERS.size(); i++) {
            if (header.getCell(i) == null) {
                header.createCell(i);
            }
            header.getCell(i).setCellValue(HEADERS.get(i));
        }
    }

    private void appendRow(Sheet sheet, InvoiceFieldData invoice) {
        int nextRowIndex = sheet.getPhysicalNumberOfRows() == 0 ? 1 : sheet.getLastRowNum() + 1;
        if (nextRowIndex <= 0) {
            nextRowIndex = 1;
        }
        Row row = sheet.createRow(nextRowIndex);
        row.createCell(0).setCellValue(value(invoice == null ? null : invoice.getInvoiceCode()));
        row.createCell(1).setCellValue(value(invoice == null ? null : invoice.getInvoiceNo()));
        row.createCell(2).setCellValue(value(invoice == null ? null : invoice.getInvoiceDate()));
        row.createCell(3).setCellValue(value(invoice == null ? null : invoice.getAmountWithoutTax()));
        row.createCell(4).setCellValue(value(invoice == null ? null : invoice.getInvoiceSum()));
        row.createCell(5).setCellValue(value(invoice == null ? null : invoice.getTaxAmount()));
        row.createCell(6).setCellValue(value(invoice == null ? null : invoice.getSellerName()));
        row.createCell(7).setCellValue(value(invoice == null ? null : invoice.getBuyerName()));
        row.createCell(8).setCellValue(value(invoice == null ? null : invoice.getVerifyCode()));
    }

    private void publishExportEvent(Path filePath, UserSessionContext context, InvoiceRecognitionResult result) {
        UserSessionContext effectiveContext = context != null ? context : ToolExecutionContextHolder.get();
        if (effectiveContext == null || effectiveContext.sessionId() == null || effectiveContext.userId() == null) {
            return;
        }
        String recipientId = effectiveContext.channelUserId() != null && !effectiveContext.channelUserId().isBlank()
                ? effectiveContext.channelUserId()
                : String.valueOf(effectiveContext.userId());
        eventPublisher.publishEvent(new ExcelExportReadyEvent(
                buildExportId(recipientId, filePath, result),
                recipientId,
                effectiveContext.sessionId(),
                filePath,
                filePath.getFileName().toString(),
                buildMessageText(filePath, result),
                "\u53d1\u7968Excel\u9644\u4ef6"
        ));
    }

    private String buildMessageText(Path filePath, InvoiceRecognitionResult result) {
        return "\u672c\u6b21\u53d1\u7968\u8bc6\u522bExcel\u6587\u4ef6\u5df2\u751f\u6210\uff1a\n"
                + "\u6587\u4ef6\u540d\uff1a" + filePath.getFileName() + "\n"
                + "\u5de5\u4f5c\u8868\uff1a" + SHEET_NAME + "\n"
                + "\u672c\u5730\u8def\u5f84\uff1a" + filePath.toAbsolutePath() + "\n"
                + "\u3010\u53d1\u7968\u6838\u9a8c\u7ed3\u679c\u3011\uff1a" + InvoiceRecognitionService.resolveVerifyConclusion(result);
    }

    private String buildExportId(String recipientId, Path filePath, InvoiceRecognitionResult result) {
        String requestId = result == null ? null : result.getOcrRequestId();
        if (requestId != null && !requestId.isBlank()) {
            return recipientId + "|" + requestId;
        }
        InvoiceFieldData invoice = result == null ? null : result.getInvoice();
        String signature = String.join("|",
                value(invoice == null ? null : invoice.getInvoiceCode()),
                value(invoice == null ? null : invoice.getInvoiceNo()),
                value(invoice == null ? null : invoice.getInvoiceDate()),
                value(invoice == null ? null : invoice.getInvoiceSum()),
                value(invoice == null ? null : invoice.getVerifyCode()));
        String filePart = filePath == null ? "" : filePath.toAbsolutePath().toString();
        return recipientId + "|" + filePart + "|" + signature;
    }

    private String buildFileName(InvoiceRecognitionResult result) {
        InvoiceFieldData invoice = result == null ? null : result.getInvoice();
        String invoiceNo = sanitizeFilePart(invoice == null ? null : invoice.getInvoiceNo());
        String requestId = sanitizeFilePart(result == null ? null : result.getOcrRequestId());
        String suffix = invoiceNo != null ? invoiceNo : requestId;
        if (suffix == null) {
            suffix = FILE_TS_FORMATTER.format(LocalDateTime.now());
        }
        return "invoice_ledger_" + suffix + ".xlsx";
    }

    private String sanitizeFilePart(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = value.replaceAll("[^0-9A-Za-z_-]", "");
        return sanitized.isBlank() ? null : sanitized;
    }

    private String value(String raw) {
        return raw == null || raw.isBlank() ? DEFAULT_VALUE : raw;
    }

    private Path root() {
        String configured = excelMcpProperties.getFileRoot();
        if (configured == null || configured.isBlank()) {
            return Paths.get("D:/mcp-server-files/excel").toAbsolutePath().normalize();
        }
        return Paths.get(configured).toAbsolutePath().normalize();
    }
}

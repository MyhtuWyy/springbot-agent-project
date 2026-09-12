package com.claw.service;

import com.claw.config.AliOcrProperties;
import com.claw.dto.InvoiceExportResponse;
import com.claw.dto.InvoiceFieldData;
import com.claw.dto.InvoiceRecognitionRequest;
import com.claw.dto.InvoiceRecognitionResult;
import com.claw.dto.InvoiceVerifyOverrides;
import com.claw.dto.InvoiceVerifyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class InvoiceRecognitionService {
    private static final Logger log = LoggerFactory.getLogger(InvoiceRecognitionService.class);
    private static final String VERIFY_PASS = "\u771f\u5b9e\u6709\u6548\u53d1\u7968";
    private static final String VERIFY_FAIL = "\u4fe1\u606f\u5f02\u5e38\uff0c\u7591\u4f3c\u865a\u5047\u53d1\u7968";
    private static final String VERIFY_INSUFFICIENT = "\u5173\u952e\u5b57\u6bb5\u4e0d\u8db3\u65e0\u6cd5\u6838\u9a8c";

    private final AliOcrProperties properties;
    private final AliOcrInvoiceClient aliOcrInvoiceClient;
    private final InvoiceExcelExportService invoiceExcelExportService;

    public InvoiceRecognitionService(AliOcrProperties properties,
                                     AliOcrInvoiceClient aliOcrInvoiceClient,
                                     InvoiceExcelExportService invoiceExcelExportService) {
        this.properties = properties;
        this.aliOcrInvoiceClient = aliOcrInvoiceClient;
        this.invoiceExcelExportService = invoiceExcelExportService;
    }

    public InvoiceRecognitionResult recognizeAndVerify(MultipartFile file, InvoiceRecognitionRequest request) {
        InvoiceRecognitionRequest safeRequest = request == null ? new InvoiceRecognitionRequest() : request;
        String fileName = file == null ? null : file.getOriginalFilename();
        try {
            return recognizeAndVerify(readFileBytes(file), fileName, safeRequest);
        } catch (AliOcrInvoiceException e) {
            return InvoiceRecognitionResult.failed(mapStatus(e.getErrorType()), e.getErrorType().name(), e.getMessage(), fileName);
        } catch (IOException e) {
            return InvoiceRecognitionResult.failed("IMAGE_PARSE_FAILED", AliOcrInvoiceErrorType.IMAGE_PARSE_ERROR.name(), "\u56fe\u7247\u8bfb\u53d6\u5931\u8d25: " + e.getMessage(), fileName);
        } catch (Exception e) {
            return InvoiceRecognitionResult.failed("OCR_FAILED", AliOcrInvoiceErrorType.API_ERROR.name(), e.getMessage(), fileName);
        }
    }

    public InvoiceRecognitionResult previewRecognize(byte[] imageBytes, String sourceFileName) {
        log.info("Invoice preview recognize requested: sourceFileName={}, imageBytes={}", sourceFileName, imageBytes == null ? 0 : imageBytes.length);
        return recognizeInternal(imageBytes, sourceFileName, new InvoiceRecognitionRequest(), false);
    }

    public InvoiceRecognitionResult recognizeAndVerify(byte[] imageBytes, String sourceFileName, InvoiceRecognitionRequest request) {
        InvoiceRecognitionRequest safeRequest = request == null ? new InvoiceRecognitionRequest() : request;
        log.info("Invoice recognizeAndVerify requested: sourceFileName={}, verifyEnabled={}", sourceFileName, shouldVerify(safeRequest));
        return recognizeInternal(imageBytes, sourceFileName, safeRequest, true);
    }

    public InvoiceExportResponse recognizeAndExport(byte[] imageBytes, String sourceFileName, InvoiceRecognitionRequest request, UserSessionContext context) {
        InvoiceRecognitionResult result = recognizeAndVerify(imageBytes, sourceFileName, request);
        InvoiceExportResponse response = new InvoiceExportResponse();
        response.setResult(result);
        response.setLedgerRow(com.claw.dto.InvoiceLedgerRow.from(result));
        if (result != null && result.isSuccess()) {
            Path exported = invoiceExcelExportService.export(result, context);
            response.setExcelFileName(exported.getFileName().toString());
            response.setExcelPath(exported.toAbsolutePath().toString());
        }
        return response;
    }

    public InvoiceExportResponse recognizeVerifyAndExport(MultipartFile file, InvoiceRecognitionRequest request) {
        InvoiceRecognitionResult result = recognizeAndVerify(file, request);
        InvoiceExportResponse response = new InvoiceExportResponse();
        response.setResult(result);
        response.setLedgerRow(com.claw.dto.InvoiceLedgerRow.from(result));
        if (result.isSuccess()) {
            Path exported = invoiceExcelExportService.export(result);
            response.setExcelFileName(exported.getFileName().toString());
            response.setExcelPath(exported.toAbsolutePath().toString());
        }
        return response;
    }

    public static String resolveVerifyConclusion(InvoiceRecognitionResult result) {
        if (result == null || result.getVerify() == null) {
            return VERIFY_INSUFFICIENT;
        }
        if (result.getVerify().getMessage() != null && !result.getVerify().getMessage().isBlank()) {
            return result.getVerify().getMessage();
        }
        if (!result.getVerify().isAttempted()) {
            return VERIFY_INSUFFICIENT;
        }
        return result.getVerify().isPassed() ? VERIFY_PASS : VERIFY_FAIL;
    }

    private InvoiceRecognitionResult recognizeInternal(byte[] imageBytes, String sourceFileName, InvoiceRecognitionRequest request, boolean performVerify) {
        if (imageBytes == null || imageBytes.length == 0) {
            return InvoiceRecognitionResult.failed("IMAGE_PARSE_FAILED", AliOcrInvoiceErrorType.IMAGE_PARSE_ERROR.name(), "\u56fe\u7247\u5185\u5bb9\u4e3a\u7a7a", sourceFileName);
        }
        List<String> keys = request.getTicketKeys() == null || request.getTicketKeys().isEmpty()
                ? properties.getDefaultTicketKeys()
                : request.getTicketKeys();
        AliOcrInvoiceClient.OcrResult ocrResult = aliOcrInvoiceClient.recognizeTicket(imageBytes, keys);
        InvoiceRecognitionResult result = buildResult(sourceFileName, ocrResult, request);

        if (performVerify && shouldVerify(request)) {
            InvoiceVerifyResult verifyResult = aliOcrInvoiceClient.verifyInvoice(result.getInvoice(), request.getOverrides());
            result.setVerify(verifyResult);
            result.setStatus(resolveRecognitionStatus(verifyResult));
            return result;
        }

        InvoiceVerifyResult skipped = new InvoiceVerifyResult();
        skipped.setAttempted(false);
        skipped.setPassed(false);
        skipped.setStatus("SKIPPED");
        skipped.setMessage(VERIFY_INSUFFICIENT);
        result.setVerify(skipped);
        result.setStatus(performVerify ? "OCR_ONLY" : "OCR_PREVIEW");
        return result;
    }

    private InvoiceRecognitionResult buildResult(String sourceFileName, AliOcrInvoiceClient.OcrResult ocrResult, InvoiceRecognitionRequest request) {
        InvoiceRecognitionResult result = new InvoiceRecognitionResult();
        result.setSuccess(true);
        result.setStatus("OCR_SUCCESS");
        result.setSourceFileName(sourceFileName);
        result.setOcrRequestId(ocrResult.requestId());
        result.setRawOcrResponse(ocrResult.rawResponse());

        InvoiceFieldData fieldData = mapInvoiceFields(ocrResult.kvData(), request.getOverrides());
        result.setInvoice(fieldData);
        result.setMissingVerifyFields(aliOcrInvoiceClient.findMissingVerifyFields(fieldData, request.getOverrides()));
        result.setWarnings(buildWarnings(fieldData, result.getMissingVerifyFields()));
        log.info("Invoice OCR fields: code={}, no={}, date={}, amount={}, sum={}, tax={}, seller={}, buyer={}, verifyCode={}",
                fieldData.getInvoiceCode(),
                fieldData.getInvoiceNo(),
                fieldData.getInvoiceDate(),
                fieldData.getAmountWithoutTax(),
                fieldData.getInvoiceSum(),
                fieldData.getTaxAmount(),
                fieldData.getSellerName(),
                fieldData.getBuyerName(),
                fieldData.getVerifyCode());
        return result;
    }

    private byte[] readFileBytes(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new AliOcrInvoiceException(AliOcrInvoiceErrorType.IMAGE_PARSE_ERROR, "\u8bf7\u4e0a\u4f20\u53d1\u7968\u56fe\u7247\u6216PDF");
        }
        long maxBytes = properties.getMaxFileSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new AliOcrInvoiceException(AliOcrInvoiceErrorType.IMAGE_PARSE_ERROR, "\u4e0a\u4f20\u6587\u4ef6\u8d85\u8fc7\u5927\u5c0f\u9650\u5236: " + properties.getMaxFileSizeMb() + "MB");
        }
        return file.getBytes();
    }

    private InvoiceFieldData mapInvoiceFields(Map<String, String> rawKv, InvoiceVerifyOverrides overrides) {
        InvoiceVerifyOverrides safeOverrides = overrides == null ? new InvoiceVerifyOverrides() : overrides;
        Map<String, String> normalized = new LinkedHashMap<>();
        if (rawKv != null) {
            for (Map.Entry<String, String> entry : rawKv.entrySet()) {
                normalized.put(cleanKey(entry.getKey()), cleanValue(entry.getValue()));
            }
        }
        boolean hasInvoiceSumOverride = !isBlank(safeOverrides.getInvoiceSum());

        String invoiceCode = firstNonBlank(safeOverrides.getInvoiceCode(), findPreferredValue(normalized,
                List.of("\u53d1\u7968\u4ee3\u7801", "qrinvoicecode", "datainvoicecode", "invoicecode"),
                List.of("\u53d1\u7968\u4ee3\u7801", "\u7968\u636e\u4ee3\u7801", "\u4ee3\u7801")));
        String invoiceNo = firstNonBlank(safeOverrides.getInvoiceNo(), findPreferredValue(normalized,
                List.of("\u53d1\u7968\u53f7\u7801", "qrinvoiceno", "datainvoicenumber", "datainvoiceno", "invoiceno", "invoicenumber"),
                List.of("\u53d1\u7968\u53f7\u7801", "\u7968\u636e\u53f7\u7801", "\u53f7\u7801")));
        String invoiceDate = normalizeInvoiceDate(firstNonBlank(safeOverrides.getInvoiceDate(), findPreferredValue(normalized,
                List.of("\u5f00\u7968\u65e5\u671f", "qrinvoicedate", "datainvoicedate", "invoicedate", "billingdate"),
                List.of("\u5f00\u7968\u65e5\u671f", "\u51fa\u7968\u65e5\u671f", "\u65e5\u671f"))));
        String invoiceSum = normalizeAmount(firstNonBlank(safeOverrides.getInvoiceSum(), findPreferredValue(normalized,
                List.of("datatotalamount", "qrinvoicesum", "\u4ef7\u7a0e\u5408\u8ba1", "invoicesum", "totalamount"),
                List.of("\u4ef7\u7a0e\u5408\u8ba1(\u5c0f\u5199)", "\u4ef7\u7a0e\u5408\u8ba1", "\u7a0e\u4ef7\u5408\u8ba1", "\u5408\u8ba1\u91d1\u989d", "\u5c0f\u5199"))));
        String verifyCode = normalizeVerifyCode(firstNonBlank(safeOverrides.getVerifyCode(), findPreferredValue(normalized,
                List.of("\u6821\u9a8c\u7801", "qrverifycode", "dataverifycode", "verifycode", "checkcode"),
                List.of("\u6821\u9a8c\u7801", "\u9a8c\u8bc1\u7801"))));
        String buyerName = findPreferredValue(normalized,
                List.of("\u8d2d\u4e70\u65b9\u540d\u79f0", "datapurchasername", "buyername", "purchasername"),
                List.of("\u8d2d\u4e70\u65b9\u540d\u79f0", "\u8d2d\u65b9\u540d\u79f0", "\u8d2d\u4e70\u65b9", "\u8d2d\u65b9"));
        String sellerName = findPreferredValue(normalized,
                List.of("\u9500\u552e\u65b9\u540d\u79f0", "datasellername", "sellername", "salesname"),
                List.of("\u9500\u552e\u65b9\u540d\u79f0", "\u9500\u65b9\u540d\u79f0", "\u9500\u552e\u65b9", "\u9500\u65b9"));
        String rawInvoiceType = findPreferredValue(normalized,
                List.of("datainvoicetypecode", "datainvoicetype", "invoicetypecode", "invoicetype"),
                List.of("\u53d1\u7968\u7c7b\u578b\u4ee3\u7801", "\u53d1\u7968\u7c7b\u578b", "\u7968\u636e\u7c7b\u578b"));
        String invoiceTitle = findPreferredValue(normalized,
                List.of("\u53d1\u7968\u540d\u79f0", "datatitle", "datainvoicetype", "invoicetitle", "invoicetype"),
                List.of("\u53d1\u7968\u540d\u79f0", "\u7968\u636e\u540d\u79f0", "\u53d1\u7968\u7c7b\u578b"));
        String amountWithoutTax = normalizeAmount(findPreferredValue(normalized,
                List.of("datainvoiceamountpretax", "\u91d1\u989d", "amountwithouttax"),
                List.of("\u4e0d\u542b\u7a0e\u91d1\u989d", "\u91d1\u989d\u4e0d\u542b\u7a0e", "\u91d1\u989d")));
        String taxAmount = normalizeAmount(findPreferredValue(normalized,
                List.of("datainvoicetax", "\u7a0e\u989d", "taxamount"),
                List.of("\u7a0e\u989d", "\u7a0e\u91d1")));
        String invoiceTypeCode = determineInvoiceTypeCode(rawInvoiceType, invoiceTitle);
        Integer invoiceKind = determineInvoiceKind(invoiceTypeCode, invoiceTitle);
        boolean hasStructuredInvoiceSum = hasExactValue(normalized, List.of("datatotalamount", "totalamount", "invoicesum"));
        invoiceSum = reconcileInvoiceSum(invoiceSum, amountWithoutTax, taxAmount, hasInvoiceSumOverride, hasStructuredInvoiceSum);

        InvoiceFieldData fieldData = new InvoiceFieldData();
        fieldData.setExtractedFields(normalized);
        fieldData.setInvoiceCode(invoiceCode);
        fieldData.setInvoiceNo(invoiceNo);
        fieldData.setInvoiceDate(invoiceDate);
        fieldData.setInvoiceSum(invoiceSum);
        fieldData.setInvoiceTypeCode(invoiceTypeCode);
        fieldData.setInvoiceKind(invoiceKind);
        fieldData.setVerifyCode(verifyCode);
        fieldData.setBuyerName(buyerName);
        fieldData.setSellerName(sellerName);
        fieldData.setInvoiceTitle(invoiceTitle);
        fieldData.setAmountWithoutTax(amountWithoutTax);
        fieldData.setTaxAmount(taxAmount);
        return fieldData;
    }

    private List<String> buildWarnings(InvoiceFieldData fieldData, List<String> missing) {
        List<String> warnings = new ArrayList<>();
        if (missing != null && !missing.isEmpty()) {
            warnings.add("\u53d1\u7968\u6838\u9a8c\u5173\u952e\u5b57\u6bb5\u4e0d\u8db3");
        }
        if (!isBlank(fieldData.getVerifyCode()) && fieldData.getVerifyCode().length() != 6) {
            warnings.add("\u6821\u9a8c\u7801\u5df2\u6309\u89c4\u5219\u6807\u51c6\u5316");
        }
        if (isBlank(fieldData.getInvoiceCode()) && requiresManualInvoiceCodeAttention(fieldData)) {
            warnings.add("\u53d1\u7968\u4ee3\u7801\u8bc6\u522b\u4e3a\u7a7a\uff0c\u5982\u4e3a\u7eb8\u8d28\u53d1\u7968\u8bf7\u8865\u5f55");
        }
        if (isBlank(fieldData.getInvoiceTypeCode())) {
            warnings.add("\u53d1\u7968\u7c7b\u578b\u672a\u660e\u786e\u8bc6\u522b\uff0c\u5c06\u4f7f\u7528\u517c\u5bb9\u6838\u9a8c\u89c4\u5219");
        }
        return warnings;
    }

    private boolean shouldVerify(InvoiceRecognitionRequest request) {
        return request.getVerifyEnabled() != null ? request.getVerifyEnabled() : properties.isAutoVerify();
    }

    private String cleanKey(String key) {
        return key == null ? "" : key.replace("\uff1a", "").replace(":", "").replaceAll("\\s+", "");
    }

    private String cleanValue(String value) {
        return value == null ? null : value.trim();
    }

    private String findValue(Map<String, String> rawFields, String... aliases) {
        if (rawFields == null || rawFields.isEmpty()) {
            return null;
        }
        for (String alias : aliases) {
            String normalizedAlias = normalizeText(alias);
            for (Map.Entry<String, String> entry : rawFields.entrySet()) {
                if (normalizeText(entry.getKey()).contains(normalizedAlias)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private String findPreferredValue(Map<String, String> rawFields, List<String> exactKeys, List<String> fuzzyAliases) {
        String exact = findExactValue(rawFields, exactKeys);
        return exact != null ? exact : findValue(rawFields, fuzzyAliases.toArray(String[]::new));
    }

    private String findExactValue(Map<String, String> rawFields, List<String> exactKeys) {
        if (rawFields == null || rawFields.isEmpty() || exactKeys == null || exactKeys.isEmpty()) {
            return null;
        }
        for (String exactKey : exactKeys) {
            String normalizedExact = normalizeText(exactKey);
            for (Map.Entry<String, String> entry : rawFields.entrySet()) {
                if (normalizeText(entry.getKey()).equals(normalizedExact) && !isBlank(entry.getValue())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private boolean hasExactValue(Map<String, String> rawFields, List<String> exactKeys) {
        return findExactValue(rawFields, exactKeys) != null;
    }

    private String normalizeInvoiceDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() == 8) {
            return digits;
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-M-d"),
                DateTimeFormatter.ofPattern("yyyy/M/d"),
                DateTimeFormatter.ofPattern("yyyy.MM.dd")
        )) {
            try {
                return LocalDate.parse(value, formatter).format(DateTimeFormatter.BASIC_ISO_DATE);
            } catch (DateTimeParseException ignored) {
            }
        }
        return digits.isBlank() ? value : digits;
    }

    private String normalizeAmount(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value
                .replace("\u00a5", "")
                .replace("\uffe5", "")
                .replace("\u5143", "")
                .replace(",", "")
                .trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String reconcileInvoiceSum(String invoiceSum,
                                       String amountWithoutTax,
                                       String taxAmount,
                                       boolean hasInvoiceSumOverride,
                                       boolean hasStructuredInvoiceSum) {
        if (hasInvoiceSumOverride) {
            return invoiceSum;
        }
        BigDecimal amount = parseAmount(amountWithoutTax);
        BigDecimal tax = parseAmount(taxAmount);
        if (amount == null || tax == null) {
            return invoiceSum;
        }
        BigDecimal computed = amount.add(tax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal current = parseAmount(invoiceSum);
        if (current == null) {
            return computed.toPlainString();
        }
        BigDecimal diff = current.subtract(computed).abs();
        if (diff.compareTo(new BigDecimal("0.01")) <= 0) {
            return invoiceSum;
        }
        if (hasStructuredInvoiceSum) {
            log.warn("Invoice sum differs from amount+tax, keeping structured total. amount={}, tax={}, sum={}",
                    amountWithoutTax, taxAmount, invoiceSum);
            return invoiceSum;
        }
        if (current.compareTo(amount) < 0) {
            log.warn("Invoice sum looks invalid, fallback to amount+tax. amount={}, tax={}, originalSum={}, computedSum={}",
                    amountWithoutTax, taxAmount, invoiceSum, computed.toPlainString());
            return computed.toPlainString();
        }
        log.warn("Invoice sum differs from amount+tax without structured total source, keeping original sum. amount={}, tax={}, sum={}",
                amountWithoutTax, taxAmount, invoiceSum);
        return invoiceSum;
    }

    private String normalizeVerifyCode(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value.trim();
        String digits = normalized.replaceAll("[^0-9A-Za-z]", "");
        if (digits.isBlank()) {
            return null;
        }
        return digits.length() > 6 ? digits.substring(digits.length() - 6) : digits;
    }

    private String determineInvoiceTypeCode(String rawInvoiceType, String invoiceTitle) {
        String normalizedRaw = normalizeText(rawInvoiceType);
        if (normalizedRaw.matches("\\d{2}")) {
            return normalizedRaw;
        }
        if (normalizedRaw.matches("\\d+")) {
            return normalizedRaw.length() >= 2 ? normalizedRaw.substring(0, 2) : null;
        }

        String title = normalizeText(invoiceTitle);
        boolean noTraditionalCodeHint = isBlank(rawInvoiceType)
                && !containsAny(invoiceTitle, "\u53d1\u7968\u4ee3\u7801", "\u6821\u9a8c\u7801")
                && containsAny(invoiceTitle, "\u7535\u5b50\u53d1\u7968\uff08\u666e\u901a\u53d1\u7968\uff09", "\u7535\u5b50\u53d1\u7968(\u666e\u901a\u53d1\u7968)");
        if (title.contains("\u533a\u5757\u94fe")) {
            return "01";
        }
        if (title.contains("\u4e8c\u624b\u8f66") && title.contains("\u7535\u5b50")) {
            return "84";
        }
        if (title.contains("\u4e8c\u624b\u8f66")) {
            return "15";
        }
        if (title.contains("\u6570\u7535") || title.contains("\u5168\u7535")) {
            if (title.contains("\u7eb8\u8d28") && title.contains("\u4e13\u7528")) {
                return "85";
            }
            if (title.contains("\u7eb8\u8d28") && title.contains("\u666e\u901a")) {
                return "86";
            }
            if (title.contains("\u4e13\u7528")) {
                return "31";
            }
            if (title.contains("\u666e\u901a")) {
                return "32";
            }
            if (title.contains("\u673a\u52a8\u8f66")) {
                return "83";
            }
        }
        if (title.contains("\u7535\u5b50") && title.contains("\u4e13\u7528")) {
            return "20";
        }
        if (title.contains("\u7535\u5b50") && title.contains("\u901a\u884c\u8d39")) {
            return "14";
        }
        if (title.contains("\u5377")) {
            return "11";
        }
        if (title.contains("\u94c1\u8def") || title.contains("\u5ba2\u7968")) {
            return "51";
        }
        if (title.contains("\u822a\u7a7a") || title.contains("\u884c\u7a0b\u5355")) {
            return "61";
        }
        if (title.contains("\u673a\u52a8\u8f66")) {
            return "03";
        }
        if (title.contains("\u7535\u5b50") && title.contains("\u666e\u901a")) {
            if (noTraditionalCodeHint) {
                return "32";
            }
            return "10";
        }
        if (title.contains("\u4e13\u7528")) {
            return "01";
        }
        if (title.contains("\u666e\u901a")) {
            return "04";
        }
        return null;
    }

    private Integer determineInvoiceKind(String invoiceTypeCode, String invoiceTitle) {
        if ("01".equals(invoiceTypeCode) && normalizeText(invoiceTitle).contains("\u533a\u5757\u94fe")) {
            return 1;
        }
        return null;
    }

    private String resolveRecognitionStatus(InvoiceVerifyResult verifyResult) {
        if (verifyResult == null) {
            return "OCR_ONLY";
        }
        if (!verifyResult.isAttempted()) {
            return switch (verifyResult.getStatus()) {
                case "INSUFFICIENT" -> "VERIFY_PENDING";
                default -> "OCR_ONLY";
            };
        }
        return verifyResult.isPassed() ? "VERIFIED" : "VERIFY_FAILED";
    }

    private boolean requiresManualInvoiceCodeAttention(InvoiceFieldData fieldData) {
        String invoiceTypeCode = fieldData == null ? null : fieldData.getInvoiceTypeCode();
        return !"31".equals(invoiceTypeCode)
                && !"32".equals(invoiceTypeCode)
                && !"51".equals(invoiceTypeCode)
                && !"61".equals(invoiceTypeCode)
                && !"83".equals(invoiceTypeCode)
                && !"84".equals(invoiceTypeCode);
    }

    private boolean containsAny(String value, String... candidates) {
        if (isBlank(value) || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (!isBlank(candidate) && value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal parseAmount(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String mapStatus(AliOcrInvoiceErrorType errorType) {
        return switch (errorType) {
            case CONFIG_ERROR -> "OCR_CONFIG_ERROR";
            case IMAGE_PARSE_ERROR -> "IMAGE_PARSE_FAILED";
            case PARAM_MISSING -> "VERIFY_PARAM_MISSING";
            case RATE_LIMIT -> "OCR_RATE_LIMIT";
            case NETWORK_ERROR -> "OCR_NETWORK_ERROR";
            case API_ERROR -> "OCR_FAILED";
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (String token : Arrays.asList("\u589e\u503c\u7a0e", "\u53d1\u7968", "\u7edf\u4e00", "\u4e2d\u56fd")) {
            normalized = normalized.replace(token, "");
        }
        return normalized;
    }
}

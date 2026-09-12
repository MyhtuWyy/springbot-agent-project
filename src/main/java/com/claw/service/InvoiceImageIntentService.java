package com.claw.service;

import com.claw.dto.InvoiceRecognitionResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class InvoiceImageIntentService {
    public enum ImageKind {
        INVOICE,
        GENERAL_IMAGE,
        UNKNOWN
    }

    public enum ImageAction {
        WAIT_USER_INPUT,
        RECOGNIZE,
        EXPORT_EXCEL,
        GENERAL_QA
    }

    public record ImageIntentDecision(ImageKind kind, ImageAction action, String reason, InvoiceRecognitionResult preview) {
    }

    private static final List<String> INVOICE_KEYWORDS = List.of(
            "发票", "票据", "核验", "查验", "校验码", "发票代码", "发票号码", "价税合计", "报销", "电子票", "增值税"
    );
    private static final List<String> EXPORT_KEYWORDS = List.of(
            "导出", "excel", "xlsx", "表格", "台账", "生成文件", "附件", "发送附件"
    );
    private static final List<String> RECOGNIZE_KEYWORDS = List.of(
            "识别", "提取", "ocr", "核验", "查验", "验真", "真伪", "真假", "校验"
    );
    private static final List<String> GENERAL_IMAGE_KEYWORDS = List.of(
            "图片识别", "识别图片", "看图", "这是什么图", "图片内容", "图里是什么", "识别文字", "看这张图", "图片"
    );

    private final InvoiceRecognitionService invoiceRecognitionService;

    public InvoiceImageIntentService(InvoiceRecognitionService invoiceRecognitionService) {
        this.invoiceRecognitionService = invoiceRecognitionService;
    }

    public ImageIntentDecision detect(String text, byte[] imageBytes, String sourceName) {
        ImageAction action = detectAction(text);
        ImageKind textKind = detectTextKind(text);
        if (textKind != ImageKind.UNKNOWN) {
            return new ImageIntentDecision(textKind, normalizeAction(textKind, action), "text", null);
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return new ImageIntentDecision(ImageKind.UNKNOWN, action, "empty-image", null);
        }
        InvoiceRecognitionResult preview = invoiceRecognitionService.previewRecognize(imageBytes, sourceName);
        if (looksLikeInvoicePreview(preview)) {
            return new ImageIntentDecision(ImageKind.INVOICE, normalizeAction(ImageKind.INVOICE, action), "ocr-preview", preview);
        }
        return new ImageIntentDecision(ImageKind.GENERAL_IMAGE, normalizeAction(ImageKind.GENERAL_IMAGE, action), "ocr-preview-general", preview);
    }

    public ImageKind detectTextKind(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return ImageKind.UNKNOWN;
        }
        if (containsAny(normalized, INVOICE_KEYWORDS)) {
            return ImageKind.INVOICE;
        }
        if (containsAny(normalized, GENERAL_IMAGE_KEYWORDS)) {
            return ImageKind.GENERAL_IMAGE;
        }
        return ImageKind.UNKNOWN;
    }

    public ImageAction detectAction(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return ImageAction.WAIT_USER_INPUT;
        }
        if (containsAny(normalized, EXPORT_KEYWORDS)) {
            return ImageAction.EXPORT_EXCEL;
        }
        if (containsAny(normalized, RECOGNIZE_KEYWORDS)) {
            return ImageAction.RECOGNIZE;
        }
        if (containsAny(normalized, GENERAL_IMAGE_KEYWORDS)) {
            return ImageAction.GENERAL_QA;
        }
        return ImageAction.WAIT_USER_INPUT;
    }

    public boolean isInvoiceText(String text) {
        return detectTextKind(text) == ImageKind.INVOICE;
    }

    public boolean isGeneralImageText(String text) {
        return detectTextKind(text) == ImageKind.GENERAL_IMAGE;
    }

    private boolean looksLikeInvoicePreview(InvoiceRecognitionResult preview) {
        if (preview == null || preview.getInvoice() == null) {
            return false;
        }
        int score = 0;
        if (!isBlank(preview.getInvoice().getInvoiceCode())) score++;
        if (!isBlank(preview.getInvoice().getInvoiceNo())) score++;
        if (!isBlank(preview.getInvoice().getInvoiceDate())) score++;
        if (!isBlank(preview.getInvoice().getInvoiceSum())) score++;
        if (!isBlank(preview.getInvoice().getVerifyCode())) score++;
        return score >= 3;
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private ImageAction normalizeAction(ImageKind kind, ImageAction action) {
        if (kind == ImageKind.INVOICE) {
            return action == ImageAction.WAIT_USER_INPUT || action == ImageAction.GENERAL_QA
                    ? ImageAction.RECOGNIZE
                    : action;
        }
        if (kind == ImageKind.GENERAL_IMAGE) {
            return action == ImageAction.WAIT_USER_INPUT ? ImageAction.GENERAL_QA : action;
        }
        return action;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package com.claw.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DesktopImageSessionService {
    private static final List<String> IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif");
    private static final List<String> GENERAL_IMAGE_KEYWORDS = List.of(
            "图片", "照片", "截图", "描述", "看看", "分析", "识别", "文字", "内容", "图里", "画面", "重点"
    );
    private static final List<String> INVOICE_KEYWORDS = List.of("发票", "票据", "校验", "验票", "验证码");

    private final Map<String, SessionImage> activeImages = new ConcurrentHashMap<>();

    public boolean isSupportedImage(String fileName, String contentType) {
        String normalizedContentType = contentType == null ? "" : contentType.trim().toLowerCase();
        if (normalizedContentType.startsWith("image/")) {
            return true;
        }
        String normalizedFileName = fileName == null ? "" : fileName.trim().toLowerCase();
        for (String extension : IMAGE_EXTENSIONS) {
            if (normalizedFileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    public void cacheImage(String sessionId, String fileName, String contentType, byte[] bytes) {
        if (sessionId == null || sessionId.isBlank() || bytes == null || bytes.length == 0) {
            return;
        }
        activeImages.put(sessionId, new SessionImage(fileName, normalizeContentType(contentType), bytes));
    }

    public SessionImage getActiveImage(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return activeImages.get(sessionId);
    }

    public boolean shouldHandleGeneralImageQuestion(String sessionId, String question) {
        return hasImage(sessionId) && containsAny(question, GENERAL_IMAGE_KEYWORDS);
    }

    public boolean shouldHandleInvoiceQuestion(String sessionId, String question) {
        return hasImage(sessionId) && containsAny(question, INVOICE_KEYWORDS);
    }

    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        activeImages.remove(sessionId);
    }

    private boolean hasImage(String sessionId) {
        return getActiveImage(sessionId) != null;
    }

    private boolean containsAny(String question, List<String> keywords) {
        String normalized = question == null ? "" : question.trim().toLowerCase();
        if (normalized.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && normalized.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "image/jpeg";
        }
        return contentType.trim().toLowerCase();
    }

    public record SessionImage(String fileName, String contentType, byte[] bytes) {
    }
}

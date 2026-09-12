package com.claw.dto;

public record DesktopFileUploadResponse(
        String sessionId,
        String fileName,
        String fileType,
        boolean parseable,
        String preview,
        String message
) {
}

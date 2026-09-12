package com.claw.dto;

public record ResumeUploadResponse(
        Long documentId,
        Long userId,
        String resumeName,
        int chunkCount,
        String message
) {
}

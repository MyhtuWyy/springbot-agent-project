package com.claw.dto;

public record SessionStatusResponse(
        String sessionId,
        boolean hasMemory,
        int memorySize
) {
}

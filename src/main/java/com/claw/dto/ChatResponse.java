package com.claw.dto;

public record ChatResponse(
        String sessionId,
        String message,
        String reply
) {
}

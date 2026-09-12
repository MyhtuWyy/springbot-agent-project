package com.claw.dto;

public record DesktopChatStreamEvent(
        String type,
        String sessionId,
        String message,
        String delta,
        String reply,
        String error
) {
}

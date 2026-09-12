package com.claw.dto;

import java.time.LocalDateTime;

public record DesktopSessionSummaryResponse(
        String sessionId,
        String title,
        String preview,
        LocalDateTime lastActive,
        int messageCount,
        String currentMode
) {
}

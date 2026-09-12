package com.claw.dto;

import java.time.Instant;

public record DesktopMonitorRecentRequestResponse(
        String requestId,
        String sessionId,
        String channel,
        String requestType,
        String routeType,
        String routeTarget,
        String model,
        boolean stream,
        boolean success,
        long durationMs,
        Long firstTokenMs,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        int toolCallCount,
        Instant startedAt,
        String errorMessage
) {
}

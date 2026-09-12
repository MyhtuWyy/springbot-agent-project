package com.claw.dto;

import java.util.List;

public record DesktopMonitorSummaryResponse(
        String currentChatModel,
        long totalRequests,
        long successRequests,
        long failedRequests,
        long averageDurationMs,
        Long averageFirstTokenMs,
        long totalPromptTokens,
        long totalCompletionTokens,
        long totalTokens,
        List<DesktopMonitorRecentRequestResponse> recentRequests
) {
}

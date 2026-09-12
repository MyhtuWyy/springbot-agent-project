package com.claw.dto;

public record DesktopOverviewResponse(
        String appName,
        boolean backendReady,
        boolean hasDashScopeKey,
        boolean weChatRunning,
        String ttsModel,
        String ttsVoiceId
) {
}

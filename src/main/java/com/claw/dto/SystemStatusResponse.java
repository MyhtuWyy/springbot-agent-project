package com.claw.dto;

public record SystemStatusResponse(
        boolean dashScopeConfigured,
        String envSource,
        String ttsModel,
        String ttsVoiceId,
        boolean weChatRunning
) {
}

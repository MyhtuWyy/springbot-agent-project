package com.claw.service;

import java.util.Locale;

public record UserSessionContext(
        Long userId,
        String sessionId,
        String channelType,
        String channelUserId,
        String displayName
) {
    public static UserSessionContext of(Long userId, String channelType, String channelUserId, String displayName) {
        String safeChannelType = channelType == null || channelType.isBlank() ? "unknown" : channelType.trim().toLowerCase(Locale.ROOT);
        String safeChannelUserId = channelUserId == null ? "" : channelUserId.trim();
        String sessionId = safeChannelType + ":" + userId + ":" + safeChannelUserId;
        return new UserSessionContext(userId, sessionId, safeChannelType, safeChannelUserId, displayName);
    }

    public static UserSessionContext fromSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (sessionId.startsWith("desktop:")) {
            String normalized = sessionId.trim();
            String[] desktopParts = normalized.split(":", 3);
            Long userId = desktopParts.length >= 2 ? parseUserId(desktopParts[1]) : null;
            if (userId != null) {
                String channelUserId = desktopParts.length >= 3 ? desktopParts[2].trim() : normalized;
                return new UserSessionContext(userId, normalized, "desktop", channelUserId, "妗岄潰鍔╂墜");
            }
            long syntheticUserId = Integer.toUnsignedLong(normalized.hashCode());
            return new UserSessionContext(syntheticUserId, normalized, "desktop", normalized, "桌面助手");
        }
        String[] parts = sessionId.trim().split(":", 3);
        if (parts.length < 2) {
            return null;
        }
        Long userId = parseUserId(parts[1]);
        String channelType = parts[0].isBlank() ? "unknown" : parts[0].trim().toLowerCase(Locale.ROOT);
        String channelUserId = parts.length >= 3 ? parts[2].trim() : "";
        return new UserSessionContext(userId, sessionId.trim(), channelType, channelUserId, null);
    }

    private static Long parseUserId(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}

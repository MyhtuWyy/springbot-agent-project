package com.claw.service;

import com.claw.util.ConfigUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DesktopVoiceControlService {
    private final Map<String, String> sessionVoiceIds = new ConcurrentHashMap<>();
    private final Map<String, PendingVoiceSelection> pendingSelections = new ConcurrentHashMap<>();

    public String resolveVoiceId(String sessionId) {
        String voiceId = sessionId == null ? null : sessionVoiceIds.get(sessionId);
        if (voiceId != null && !voiceId.isBlank()) {
            return voiceId;
        }
        return ConfigUtil.getTtsVoiceId();
    }

    public String handleMessage(String sessionId, String text) {
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            return null;
        }

        String normalized = normalize(text);
        PendingVoiceSelection pending = pendingSelections.get(sessionId);
        if (pending != null) {
            for (Map.Entry<String, String> entry : pending.options().entrySet()) {
                if (matches(normalized, entry.getKey(), entry.getValue())) {
                    sessionVoiceIds.put(sessionId, entry.getValue());
                    pendingSelections.remove(sessionId);
                    return "已切换到" + entry.getKey();
                }
            }
            return null;
        }

        if (isQueryVoice(normalized)) {
            return buildCurrentVoiceReply(sessionId);
        }
        if (isResetVoice(normalized)) {
            sessionVoiceIds.remove(sessionId);
            pendingSelections.remove(sessionId);
            return "已恢复默认音色：" + ConfigUtil.getTtsVoiceId();
        }
        if (isChangeVoice(normalized)) {
            PendingVoiceSelection selection = buildSelection();
            pendingSelections.put(sessionId, selection);
            return selection.prompt();
        }
        return null;
    }

    private String buildCurrentVoiceReply(String sessionId) {
        String current = resolveVoiceId(sessionId);
        String alias = findAlias(current);
        return alias == null ? "当前音色：" + current : "当前音色：" + alias + " (" + current + ")";
    }

    private PendingVoiceSelection buildSelection() {
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        Map<String, String> verified = ConfigUtil.getTtsVerifiedVoiceAliases();
        if (verified != null && !verified.isEmpty()) {
            options.putAll(verified);
        } else {
            Map<String, String> aliases = ConfigUtil.getTtsVoiceAliases();
            if (aliases != null && !aliases.isEmpty()) {
                options.putAll(aliases);
            }
        }
        if (options.isEmpty()) {
            options.put("默认音色", ConfigUtil.getTtsVoiceId());
        }
        StringBuilder prompt = new StringBuilder("请选择音色：");
        boolean first = true;
        for (String alias : options.keySet()) {
            if (!first) {
                prompt.append("，");
            }
            prompt.append(alias);
            first = false;
        }
        prompt.append("。直接回复名称即可。");
        return new PendingVoiceSelection(options, prompt.toString());
    }

    private String findAlias(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) {
            return null;
        }
        Map<String, String> aliases = ConfigUtil.getTtsVerifiedVoiceAliases();
        if (aliases != null) {
            for (Map.Entry<String, String> entry : aliases.entrySet()) {
                if (voiceId.equals(entry.getValue())) {
                    return entry.getKey();
                }
            }
        }
        aliases = ConfigUtil.getTtsVoiceAliases();
        if (aliases != null) {
            for (Map.Entry<String, String> entry : aliases.entrySet()) {
                if (voiceId.equals(entry.getValue())) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private boolean isChangeVoice(String normalized) {
        return containsAny(normalized, "切换音色", "更换音色", "修改音色", "换音色", "改音色", "音色切换");
    }

    private boolean isQueryVoice(String normalized) {
        return containsAny(normalized, "当前音色", "查询音色", "看看音色", "现在音色", "默认音色");
    }

    private boolean isResetVoice(String normalized) {
        return containsAny(normalized, "恢复默认音色", "重置音色", "还原音色");
    }

    private boolean matches(String normalized, String alias, String voiceId) {
        return normalized.equals(normalize(alias)) || normalized.equals(normalize(voiceId));
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private record PendingVoiceSelection(LinkedHashMap<String, String> options, String prompt) {
    }
}

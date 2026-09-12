package com.claw.skills.core;

import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.Map;

public record SkillRequest(
        String skillName,
        String sessionId,
        JSONObject arguments,
        String rawArguments,
        long timeoutMs,
        Map<String, Object> context
) {
    public SkillRequest {
        arguments = arguments == null ? new JSONObject() : arguments;
        rawArguments = rawArguments == null ? "" : rawArguments;
        context = context == null ? Map.of() : Collections.unmodifiableMap(context);
    }
}

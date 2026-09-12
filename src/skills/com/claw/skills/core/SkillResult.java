package com.claw.skills.core;

import com.alibaba.fastjson2.JSONObject;

public record SkillResult(
        boolean success,
        String skillName,
        String message,
        JSONObject data,
        String error,
        long durationMs,
        boolean timeout
) {
    public static SkillResult success(String skillName, String message, JSONObject data, long durationMs) {
        return new SkillResult(true, skillName, message, data == null ? new JSONObject() : data, null, durationMs, false);
    }

    public static SkillResult failure(String skillName, String message, String error, long durationMs, boolean timeout) {
        return new SkillResult(false, skillName, message, new JSONObject(), error, durationMs, timeout);
    }

    public String toJsonString() {
        JSONObject json = new JSONObject();
        json.put("success", success);
        json.put("skillName", skillName);
        json.put("message", message);
        json.put("data", data == null ? new JSONObject() : data);
        json.put("error", error);
        json.put("durationMs", durationMs);
        json.put("timeout", timeout);
        return json.toJSONString();
    }
}

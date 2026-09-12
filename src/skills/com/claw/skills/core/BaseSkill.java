package com.claw.skills.core;

import com.alibaba.fastjson2.JSONObject;

public interface BaseSkill {
    String skillName();

    String description();

    JSONObject parametersSchema();

    default long timeoutMs() {
        SkillDefine define = getClass().getAnnotation(SkillDefine.class);
        return define == null ? 30000L : define.timeoutMs();
    }

    SkillResult execute(SkillRequest request);
}

package com.claw.tools;

import com.alibaba.fastjson2.JSONArray;
import com.claw.skills.core.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final SkillManager skillManager;

    public ToolRegistry(SkillManager skillManager) {
        this.skillManager = skillManager;
        log.info("ToolRegistry delegated to SkillManager, skillCount={}", skillManager.size());
    }

    public JSONArray buildToolsJson() {
        return skillManager.buildToolsJson();
    }

    public JSONArray buildToolsJson(List<String> onlyNames) {
        return skillManager.buildToolsJson(onlyNames);
    }

    public List<String> getRegisteredToolNames() {
        return skillManager.getRegisteredSkillNames();
    }

    public String executeToolCall(String functionName, String argumentsStr) {
        return skillManager.executeAsJson(functionName, argumentsStr);
    }

    public boolean hasTools() {
        return skillManager.size() > 0;
    }

    public boolean hasTool(String toolName) {
        return skillManager.hasSkill(toolName);
    }

    public int size() {
        return skillManager.size();
    }
}

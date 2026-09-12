package com.claw.skills.core;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.tools.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.claw.service.ToolExecutionContextHolder;
import com.claw.service.UserSkillSettingService;
import com.claw.service.McpToolCatalogService;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class SkillManager {
    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final Map<String, BaseSkill> skillMap = new LinkedHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "skill-executor-" + System.nanoTime());
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, String> sourceMap = new ConcurrentHashMap<>();
    private final Map<Long, java.util.Set<String>> userDisabled = new ConcurrentHashMap<>();
    private final UserSkillSettingService userSkillSettingService;
    private final McpToolCatalogService mcpToolCatalogService;

    @Autowired
    public SkillManager(List<BaseSkill> skills, List<ToolDefinition> tools, UserSkillSettingService userSkillSettingService, McpToolCatalogService mcpToolCatalogService) {
        this.userSkillSettingService = userSkillSettingService;
        this.mcpToolCatalogService = mcpToolCatalogService;
        registerAll(skills, tools);
    }

    public SkillManager(List<BaseSkill> skills, List<ToolDefinition> tools) {
        this.userSkillSettingService = null;
        this.mcpToolCatalogService = null;
        registerAll(skills, tools);
    }

    private void registerAll(List<BaseSkill> skills, List<ToolDefinition> tools) {
        if (skills != null) {
            for (BaseSkill skill : skills) {
                register(skill, "skill");
            }
        }
        if (tools != null) {
            for (ToolDefinition tool : tools) {
                register(new ToolDefinitionSkillAdapter(tool), "tool");
            }
        }
        log.info("skill manager ready, skills={}", skillMap.size());
    }

    public JSONArray buildToolsJson() {
        return buildToolsJson(null);
    }

    public JSONArray buildToolsJson(List<String> onlyNames) {
        return buildToolsJson(onlyNames, currentUserId());
    }
    public JSONArray buildToolsJson(List<String> onlyNames, Long userId) {
        JSONArray tools = new JSONArray();
        java.util.Set<String> disabled = disabledForUser(userId);
        List<String> allowList = onlyNames == null ? List.of() : onlyNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .toList();

        for (BaseSkill skill : skillMap.values()) {
            if (disabled.contains(skill.skillName())) continue;
            if (!allowList.isEmpty() && !allowList.contains(skill.skillName())) {
                continue;
            }
            JSONObject toolObj = new JSONObject();
            toolObj.put("type", "function");

            JSONObject function = new JSONObject();
            function.put("name", skill.skillName());
            function.put("description", skill.description());
            function.put("parameters", skill.parametersSchema());

            toolObj.put("function", function);
            tools.add(toolObj);
        }
        if (mcpToolCatalogService != null && userId != null) {
            for (var remote : mcpToolCatalogService.list(userId)) {
                if (disabled.contains(remote.name()) || (!allowList.isEmpty() && !allowList.contains(remote.name()))) continue;
                JSONObject toolObj = new JSONObject(); toolObj.put("type", "function");
                toolObj.put("function", JSONObject.of("name", remote.name(), "description", remote.description(), "parameters", remote.schema()));
                tools.add(toolObj);
            }
        }
        return tools;
    }

    public boolean hasSkill(String skillName) {
        return skillMap.containsKey(skillName) || (mcpToolCatalogService != null && mcpToolCatalogService.resolve(currentUserId(), skillName).isPresent());
    }

    public List<String> getRegisteredSkillNames() {
        return List.copyOf(skillMap.keySet());
    }

    public int size() {
        return skillMap.size();
    }

    public SkillResult execute(String skillName, String rawArguments) {
        Long userId = currentUserId();
        BaseSkill skill = skillMap.get(skillName);
        if (skill != null && disabledForUser(userId).contains(skillName)) return SkillResult.failure(skillName, "skill disabled", "Skill is disabled for this user", 0L, false);
        if (skill == null && mcpToolCatalogService != null) skill = mcpToolCatalogService.resolve(userId, skillName).map(remote -> new McpDynamicSkillAdapter(mcpToolCatalogService, remote)).orElse(null);
        if (skill == null) {
            return SkillResult.failure(skillName, "unknown skill", "Unknown skill: " + skillName, 0L, false);
        }

        long timeoutMs = skill.timeoutMs() > 0 ? skill.timeoutMs() : 30000L;
        long startedAt = System.currentTimeMillis();
        JSONObject arguments = parseArguments(rawArguments);
        String validationError = validate(skill, arguments);
        if (validationError != null) {
            long durationMs = System.currentTimeMillis() - startedAt;
            return SkillResult.failure(skillName, validationError, validationError, durationMs, false);
        }

        SkillRequest request = new SkillRequest(skillName, arguments.getString("sessionId"), arguments, rawArguments, timeoutMs, Map.of());
        var executionContext = ToolExecutionContextHolder.get();
        BaseSkill executableSkill = skill;
        Future<SkillResult> future = executorService.submit(() -> {
            var previous = ToolExecutionContextHolder.get();
            ToolExecutionContextHolder.set(executionContext);
            try { return executableSkill.execute(request); }
            finally { ToolExecutionContextHolder.set(previous); }
        });
        try {
            SkillResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long durationMs = System.currentTimeMillis() - startedAt;
            SkillResult finalResult = result == null
                    ? SkillResult.failure(skillName, "empty result", "skill returned null", durationMs, false)
                    : new SkillResult(result.success(), result.skillName(), result.message(), result.data(), result.error(), durationMs, result.timeout());
            log.info("skill executed, name={}, durationMs={}, source={}", skillName, durationMs, sourceMap.getOrDefault(skillName, "unknown"));
            return finalResult;
        } catch (TimeoutException e) {
            future.cancel(true);
            long durationMs = System.currentTimeMillis() - startedAt;
            log.warn("skill timeout, name={}, timeoutMs={}", skillName, timeoutMs);
            return SkillResult.failure(skillName, "skill timeout", e.getMessage(), durationMs, true);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedAt;
            log.error("skill execution failed, name={}", skillName, e);
            return SkillResult.failure(skillName, "skill execution failed", e.getMessage(), durationMs, false);
        }
    }

    public boolean isEnabled(String skillName, Long userId) {
        boolean known=skillMap.containsKey(skillName)||(mcpToolCatalogService!=null&&mcpToolCatalogService.resolve(userId,skillName).isPresent());
        return known&&!disabledForUser(userId).contains(skillName);
    }
    public java.util.Set<String> disabledForUser(Long userId) {
        if (userId == null) return java.util.Set.of();
        if (userSkillSettingService != null) return userSkillSettingService.disabledSkills(userId);
        return userDisabled.getOrDefault(userId, java.util.Set.of());
    }
    public void setEnabled(Long userId, String skillName, boolean enabled) {
        if (userId == null || !skillMap.containsKey(skillName)) throw new IllegalArgumentException("Unknown skill: " + skillName);
        if (userSkillSettingService != null) { userSkillSettingService.setEnabled(userId, skillName, enabled); return; }
        userDisabled.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet());
        if (enabled) userDisabled.get(userId).remove(skillName); else userDisabled.get(userId).add(skillName);
    }
    private Long currentUserId() { var context = ToolExecutionContextHolder.get(); return context == null ? null : context.userId(); }

    public String executeAsJson(String skillName, String rawArguments) {
        return execute(skillName, rawArguments).toJsonString();
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    private void register(BaseSkill skill, String source) {
        if (skill == null || skill.skillName() == null || skill.skillName().isBlank()) {
            return;
        }
        if ("tool".equals(source) && skillMap.containsKey(skill.skillName())) {
            log.info("skip tool adapter registration because explicit skill already exists, name={}", skill.skillName());
            return;
        }
        if (skillMap.containsKey(skill.skillName())) {
            log.warn("duplicate skill name detected, overriding, name={}", skill.skillName());
        }
        skillMap.put(skill.skillName(), skill);
        sourceMap.put(skill.skillName(), source);
        log.info("skill registered, name={}, type={}, class={}", skill.skillName(), source, skill.getClass().getSimpleName());
    }

    private JSONObject parseArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return new JSONObject();
        }
        try {
            return JSONObject.parseObject(rawArguments);
        } catch (Exception e) {
            JSONObject fallback = new JSONObject();
            fallback.put("raw", rawArguments);
            return fallback;
        }
    }

    private String validate(BaseSkill skill, JSONObject arguments) {
        JSONObject schema = skill.parametersSchema();
        if (schema == null) {
            return null;
        }
        JSONArray required = schema.getJSONArray("required");
        if (required == null || required.isEmpty()) {
            return null;
        }
        for (int i = 0; i < required.size(); i++) {
            String key = required.getString(i);
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = arguments.get(key);
            if (value == null || Objects.toString(value, "").isBlank()) {
                return "missing required field: " + key;
            }
        }
        return null;
    }

    private static final class ToolDefinitionSkillAdapter implements BaseSkill {
        private final ToolDefinition toolDefinition;

        private ToolDefinitionSkillAdapter(ToolDefinition toolDefinition) {
            this.toolDefinition = toolDefinition;
        }

        @Override
        public String skillName() {
            return toolDefinition.name();
        }

        @Override
        public String description() {
            return toolDefinition.description();
        }

        @Override
        public JSONObject parametersSchema() {
            return toolDefinition.parametersSchema();
        }

        @Override
        public SkillResult execute(SkillRequest request) {
            String output = toolDefinition.execute(request.rawArguments());
            JSONObject data = new JSONObject();
            data.put("output", output);
            return SkillResult.success(skillName(), output, data, 0L);
        }
    }

    private static final class McpDynamicSkillAdapter implements BaseSkill {
        private final McpToolCatalogService catalog; private final McpToolCatalogService.DynamicTool tool;
        private McpDynamicSkillAdapter(McpToolCatalogService catalog,McpToolCatalogService.DynamicTool tool){this.catalog=catalog;this.tool=tool;}
        public String skillName(){return tool.name();} public String description(){return tool.description();} public JSONObject parametersSchema(){return tool.schema();}
        public SkillResult execute(SkillRequest request){String output=catalog.call(tool,request.arguments()); JSONObject data=new JSONObject();data.put("output",output);return SkillResult.success(skillName(),output,data,0L);}
    }
}

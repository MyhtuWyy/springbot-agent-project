package com.claw.service;

import com.alibaba.fastjson2.JSONObject;
import com.claw.skills.core.BaseSkill;
import com.claw.skills.core.SkillManager;
import com.claw.skills.core.SkillRequest;
import com.claw.skills.core.SkillResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillAuthorizationTest {
    private SkillManager manager;

    @AfterEach void cleanup() {
        ToolExecutionContextHolder.clear();
        if (manager != null) manager.shutdown();
    }

    @Test void disabledSkillIsHiddenAndCannotExecuteForThatUserOnly() {
        manager = new SkillManager(List.of(new EchoSkill()), List.of());
        manager.setEnabled(1L, "echo", false);

        assertTrue(manager.buildToolsJson(null, 1L).isEmpty());
        assertFalse(manager.buildToolsJson(null, 2L).isEmpty());

        ToolExecutionContextHolder.set(UserSessionContext.of(1L, "desktop", "a", "A"));
        SkillResult denied = manager.execute("echo", "{}");
        assertFalse(denied.success());
        assertEquals("skill disabled", denied.message());

        ToolExecutionContextHolder.set(UserSessionContext.of(2L, "desktop", "b", "B"));
        assertTrue(manager.execute("echo", "{}").success());
    }

    @Test void executionPropagatesUserContextToSkillThread() {
        manager = new SkillManager(List.of(new ContextSkill()), List.of());
        ToolExecutionContextHolder.set(UserSessionContext.of(42L, "desktop", "u", "U"));
        assertEquals("42", manager.execute("context", "{}").message());
    }

    @Test void discoveredMcpToolIsExposedAndExecuted() {
        UserSkillSettingService settings=mock(UserSkillSettingService.class); when(settings.disabledSkills(7L)).thenReturn(java.util.Set.of());
        McpToolCatalogService catalog=mock(McpToolCatalogService.class);
        var tool=new McpToolCatalogService.DynamicTool("mcp__demo__echo","demo","echo","Remote echo",JSONObject.of("type","object"));
        when(catalog.list(7L)).thenReturn(List.of(tool)); when(catalog.resolve(7L,tool.name())).thenReturn(java.util.Optional.of(tool)); when(catalog.call(eq(tool),any())).thenReturn("remote-ok");
        manager=new SkillManager(List.of(),List.of(),settings,catalog);
        ToolExecutionContextHolder.set(UserSessionContext.of(7L,"desktop","u","U"));
        assertEquals(tool.name(),manager.buildToolsJson().getJSONObject(0).getJSONObject("function").getString("name"));
        assertTrue(manager.execute(tool.name(),"{}").success());
        verify(catalog).call(eq(tool),any());
    }

    private static final class EchoSkill implements BaseSkill {
        public String skillName() { return "echo"; }
        public String description() { return "echo"; }
        public JSONObject parametersSchema() { return JSONObject.of("type", "object"); }
        public SkillResult execute(SkillRequest request) { return SkillResult.success("echo", "ok", new JSONObject(), 0); }
    }
    private static final class ContextSkill implements BaseSkill {
        public String skillName() { return "context"; }
        public String description() { return "context"; }
        public JSONObject parametersSchema() { return new JSONObject(); }
        public SkillResult execute(SkillRequest request) { var context = ToolExecutionContextHolder.get(); return SkillResult.success("context", String.valueOf(context.userId()), new JSONObject(), 0); }
    }
}

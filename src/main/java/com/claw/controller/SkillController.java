package com.claw.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.skills.core.SkillManager;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import com.claw.service.AuthenticatedUser;
import com.claw.service.ToolExecutionContextHolder;
import com.claw.service.UserSessionContext;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
    private final SkillManager skillManager;

    public SkillController(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @GetMapping
    public JSONArray listSkills(HttpServletRequest request) {
        Long userId = user(request).userId();
        java.util.Set<String> disabled = skillManager.disabledForUser(userId);
        JSONArray list = new JSONArray();
        for (String name : skillManager.getRegisteredSkillNames()) {
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("available", true);
            item.put("enabled", !disabled.contains(name));
            list.add(item);
        }
        return list;
    }

    @PostMapping("/{skillName}/execute")
    public JSONObject executeSkill(@PathVariable @NotBlank String skillName,
                                   @RequestBody(required = false) Map<String, Object> body,
                                   HttpServletRequest request) {
        JSONObject args = new JSONObject();
        if (body != null) {
            args.putAll(body);
        }
        AuthenticatedUser user = user(request);
        ToolExecutionContextHolder.set(UserSessionContext.of(user.userId(), "desktop", user.username(), user.displayName()));
        try { return JSONObject.parseObject(skillManager.executeAsJson(skillName, args.toJSONString())); }
        finally { ToolExecutionContextHolder.clear(); }
    }

    @PutMapping("/{skillName}/enabled")
    public JSONObject setEnabled(@PathVariable @NotBlank String skillName, @RequestBody Map<String, Boolean> body, HttpServletRequest request) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        skillManager.setEnabled(user(request).userId(), skillName, enabled);
        return JSONObject.of("name", skillName, "enabled", enabled);
    }

    private AuthenticatedUser user(HttpServletRequest request) {
        return (AuthenticatedUser) request.getAttribute(AuthController.USER_ATTRIBUTE);
    }
}

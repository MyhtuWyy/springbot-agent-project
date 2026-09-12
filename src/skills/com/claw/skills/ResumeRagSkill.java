package com.claw.skills;

import com.alibaba.fastjson2.JSONObject;
import com.claw.skills.core.BaseSkill;
import com.claw.skills.core.SkillDefine;
import com.claw.skills.core.SkillRequest;
import com.claw.skills.core.SkillResult;
import com.claw.tools.ResumeRagTool;
import org.springframework.stereotype.Component;

@Component
@SkillDefine(
        name = "resume_rag",
        description = "Search, build context, ingest and delete resume knowledge entries.",
        timeoutMs = 20000L
)
public class ResumeRagSkill implements BaseSkill {
    private final ResumeRagTool delegate;

    public ResumeRagSkill(ResumeRagTool delegate) {
        this.delegate = delegate;
    }

    @Override
    public String skillName() {
        return "resume_rag";
    }

    @Override
    public String description() {
        return "Search, build context, ingest and delete resume knowledge entries.";
    }

    @Override
    public JSONObject parametersSchema() {
        return delegate.parametersSchema();
    }

    @Override
    public SkillResult execute(SkillRequest request) {
        String output = delegate.execute(request.rawArguments());
        JSONObject data = new JSONObject();
        data.put("output", output);
        return SkillResult.success(skillName(), output, data, 0L);
    }
}

package com.claw.skills;

import com.alibaba.fastjson2.JSONObject;
import com.claw.skills.core.BaseSkill;
import com.claw.skills.core.SkillDefine;
import com.claw.skills.core.SkillRequest;
import com.claw.skills.core.SkillResult;
import com.claw.tools.FileParseTool;
import org.springframework.stereotype.Component;

@Component
@SkillDefine(
        name = "parse_file",
        description = "Parse the current session file for preview, full text and chunked reading.",
        timeoutMs = 15000L
)
public class FileParseSkill implements BaseSkill {
    private final FileParseTool delegate;

    public FileParseSkill(FileParseTool delegate) {
        this.delegate = delegate;
    }

    @Override
    public String skillName() {
        return "parse_file";
    }

    @Override
    public String description() {
        return "Parse the current session file for preview, full text and chunked reading.";
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

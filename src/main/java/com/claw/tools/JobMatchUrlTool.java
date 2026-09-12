package com.claw.tools;

import com.alibaba.fastjson2.JSONObject;
import com.claw.entity.JobPostingEntity;
import com.claw.service.JobMatchService;
import org.springframework.stereotype.Component;

@Component
public class JobMatchUrlTool implements ToolDefinition {
    private final JobMatchService jobMatchService;

    public JobMatchUrlTool(JobMatchService jobMatchService) {
        this.jobMatchService = jobMatchService;
    }

    @Override
    public String name() {
        return "job_match_url";
    }

    @Override
    public String description() {
        return "根据岗位链接结合用户简历知识库做岗位匹配，并返回匹配分数、摘要和召回上下文。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("userId", prop("integer", "内部用户ID"));
        props.put("jobUrl", prop("string", "岗位链接"));
        props.put("platform", prop("string", "平台名，可选"));
        props.put("jobTitle", prop("string", "岗位标题，可选"));
        props.put("companyName", prop("string", "公司名，可选"));
        props.put("city", prop("string", "城市，可选"));
        props.put("jobDescription", prop("string", "岗位描述文本，可选；有正文时匹配效果更好"));
        props.put("resumeDocumentId", prop("integer", "指定简历ID，可选"));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(String arguments) {
        JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSONObject.parseObject(arguments);
        JobPostingEntity entity = jobMatchService.matchUrl(
                args.getLong("userId"),
                args.getString("platform"),
                args.getString("jobTitle"),
                args.getString("companyName"),
                args.getString("city"),
                args.getString("jobUrl"),
                args.getString("jobDescription"),
                args.getLong("resumeDocumentId")
        );

        JSONObject json = new JSONObject();
        json.put("id", entity.getId());
        json.put("userId", entity.getUserId());
        json.put("platform", entity.getPlatform());
        json.put("jobTitle", entity.getJobTitle());
        json.put("companyName", entity.getCompanyName());
        json.put("city", entity.getCity());
        json.put("jobUrl", entity.getJobUrl());
        json.put("resumeDocumentId", entity.getResumeDocumentId());
        json.put("status", entity.getStatus());
        json.put("matchScore", entity.getMatchScore());
        json.put("matchSummary", entity.getMatchSummary());
        json.put("ragContext", entity.getRagContext());
        return json.toJSONString();
    }

    private JSONObject prop(String type, String description) {
        JSONObject property = new JSONObject();
        property.put("type", type);
        property.put("description", description);
        return property;
    }
}

package com.claw.tools;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.entity.ResumeDocumentEntity;
import com.claw.service.ResumeKnowledgeService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ResumeListTool implements ToolDefinition {
    private final ResumeKnowledgeService resumeKnowledgeService;

    public ResumeListTool(ResumeKnowledgeService resumeKnowledgeService) {
        this.resumeKnowledgeService = resumeKnowledgeService;
    }

    @Override
    public String name() {
        return "resume_list";
    }

    @Override
    public String description() {
        return "列出当前用户已上传的简历。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject props = new JSONObject();
        JSONObject userId = new JSONObject();
        userId.put("type", "integer");
        userId.put("description", "内部用户ID");
        props.put("userId", userId);
        schema.put("properties", props);
        JSONArray required = new JSONArray();
        required.add("userId");
        schema.put("required", required);
        return schema;
    }

    @Override
    public String execute(String arguments) {
        JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSONObject.parseObject(arguments);
        List<ResumeDocumentEntity> documents = resumeKnowledgeService.listResumes(args.getLong("userId"));
        JSONArray array = new JSONArray();
        for (ResumeDocumentEntity document : documents) {
            JSONObject item = new JSONObject();
            item.put("id", document.getId());
            item.put("resumeName", document.getResumeName());
            item.put("sourceFileName", document.getSourceFileName());
            item.put("sourceType", document.getSourceType());
            item.put("chunkCount", document.getChunkCount());
            item.put("updatedAt", document.getUpdatedAt() != null ? document.getUpdatedAt().toString() : null);
            array.add(item);
        }
        return array.toJSONString();
    }
}

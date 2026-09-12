package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.entity.ResumeDocumentEntity;
import com.claw.service.ResumeKnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ResumeRagTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(ResumeRagTool.class);

    private final ResumeKnowledgeService resumeKnowledgeService;

    public ResumeRagTool(ResumeKnowledgeService resumeKnowledgeService) {
        this.resumeKnowledgeService = resumeKnowledgeService;
    }

    @Override
    public String name() {
        return "resume_rag";
    }

    @Override
    public String description() {
        return "构建和查询简历知识库，用于岗位匹配、简历召回和投递前审核。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject props = new JSONObject();

        JSONObject action = new JSONObject();
        action.put("type", "string");
        action.put("description", "动作：ingest, search, list, delete, context");
        props.put("action", action);

        JSONObject userId = new JSONObject();
        userId.put("type", "integer");
        userId.put("description", "内部用户ID");
        props.put("userId", userId);

        JSONObject text = new JSONObject();
        text.put("type", "string");
        text.put("description", "简历文本或检索问题");
        props.put("text", text);

        JSONObject fileName = new JSONObject();
        fileName.put("type", "string");
        fileName.put("description", "简历文件名");
        props.put("fileName", fileName);

        JSONObject topK = new JSONObject();
        topK.put("type", "integer");
        topK.put("description", "返回条数，默认 5");
        props.put("topK", topK);

        JSONObject resumeName = new JSONObject();
        resumeName.put("type", "string");
        resumeName.put("description", "简历名称");
        props.put("resumeName", resumeName);

        JSONObject documentId = new JSONObject();
        documentId.put("type", "integer");
        documentId.put("description", "简历文档ID");
        props.put("documentId", documentId);

        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            String action = args.getString("action");
            Long userId = args.getLong("userId");
            String text = args.getString("text");
            String fileName = args.getString("fileName");
            String resumeName = args.getString("resumeName");
            int topK = args.getIntValue("topK") > 0 ? args.getIntValue("topK") : 5;

            return switch (action == null ? "" : action.trim().toLowerCase()) {
                case "ingest" -> {
                    ResumeDocumentEntity doc = resumeKnowledgeService.ingestResumeText(userId, resumeName, fileName, "text", text);
                    yield "OK:" + doc.getId();
                }
                case "search" -> {
                    List<ResumeKnowledgeService.ResumeSearchHit> hits = resumeKnowledgeService.search(userId, text, topK);
                    yield toJson(hits);
                }
                case "context" -> resumeKnowledgeService.buildRagContext(userId, text, topK);
                case "list" -> toJsonDocuments(resumeKnowledgeService.listResumes(userId));
                case "delete" -> {
                    Long documentId = args.getLong("documentId");
                    yield resumeKnowledgeService.deleteResume(userId, documentId) ? "OK" : "NOT_FOUND";
                }
                default -> "UNKNOWN_ACTION";
            };
        } catch (Exception e) {
            log.error("resume_rag tool error", e);
            return "RESUME_RAG_ERROR: " + e.getMessage();
        }
    }

    private String toJson(List<ResumeKnowledgeService.ResumeSearchHit> hits) {
        JSONArray arr = new JSONArray();
        for (ResumeKnowledgeService.ResumeSearchHit hit : hits) {
            JSONObject obj = new JSONObject();
            obj.put("documentId", hit.chunk().getDocumentId());
            obj.put("chunkId", hit.chunk().getId());
            obj.put("chunkIndex", hit.chunk().getChunkIndex());
            obj.put("score", hit.score());
            obj.put("content", hit.chunk().getContent());
            arr.add(obj);
        }
        return arr.toJSONString();
    }

    private String toJsonDocuments(List<ResumeDocumentEntity> docs) {
        JSONArray arr = new JSONArray();
        for (ResumeDocumentEntity doc : docs) {
            JSONObject obj = new JSONObject();
            obj.put("id", doc.getId());
            obj.put("resumeName", doc.getResumeName());
            obj.put("sourceFileName", doc.getSourceFileName());
            obj.put("sourceType", doc.getSourceType());
            obj.put("chunkCount", doc.getChunkCount());
            obj.put("updatedAt", doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : null);
            arr.add(obj);
        }
        return arr.toJSONString();
    }
}

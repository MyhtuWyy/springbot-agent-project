package com.claw.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.service.FileParseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FileParseTool implements ToolDefinition {
    private static final Logger log = LoggerFactory.getLogger(FileParseTool.class);

    private final FileParseService fileParseService;

    public FileParseTool(FileParseService fileParseService) {
        this.fileParseService = fileParseService;
    }

    @Override
    public String name() {
        return "parse_file";
    }

    @Override
    public String description() {
        return "解析当前会话文件，支持预览、分片查看和基于文件内容的精准问答。";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject properties = new JSONObject();
        JSONObject action = new JSONObject();
        action.put("type", "string");
        action.put("description", "info=文件信息和预览，full=完整内容首段，chunk=查看指定分片，qa=基于文件内容回答问题。");
        action.put("enum", new JSONArray().fluentAdd("info").fluentAdd("full").fluentAdd("chunk").fluentAdd("qa"));
        properties.put("action", action);
        properties.put("session_id", property("string", "当前会话 sessionId，用于定位当前活跃文件。"));
        properties.put("file_name", property("string", "可选，文件名；不传时优先使用当前会话最近发送的文件。"));
        properties.put("chunk_index", property("integer", "分片序号，从 1 开始，仅 action=chunk 时使用。"));
        properties.put("question", property("string", "文件问题，仅 action=qa 时使用。"));
        schema.put("properties", properties);
        schema.put("required", new JSONArray().fluentAdd("action"));
        return schema;
    }

    @Override
    public String execute(String arguments) {
        try {
            JSONObject args = arguments == null || arguments.isBlank() ? new JSONObject() : JSON.parseObject(arguments);
            String action = args == null || args.getString("action") == null || args.getString("action").isBlank()
                    ? "info" : args.getString("action").trim().toLowerCase();
            String sessionId = args == null ? null : args.getString("session_id");
            String fileName = args == null ? null : args.getString("file_name");
            Integer chunkIndex = args == null ? null : args.getInteger("chunk_index");
            String question = args == null ? null : args.getString("question");

            return switch (action) {
                case "info" -> fileParseService.getSessionFileInfo(sessionId, fileName);
                case "full" -> fileParseService.getSessionFileFull(sessionId, fileName);
                case "chunk" -> fileParseService.getSessionFileChunk(sessionId, fileName, chunkIndex);
                case "qa" -> fileParseService.answerQuestion(sessionId, fileName, question);
                default -> "未知 action 参数：" + action + "，支持 info、full、chunk、qa。";
            };
        } catch (Exception e) {
            log.error("文件解析工具执行异常", e);
            return "文件解析出错：" + e.getMessage();
        }
    }

    private JSONObject property(String type, String description) {
        JSONObject property = new JSONObject();
        property.put("type", type);
        property.put("description", description);
        return property;
    }
}

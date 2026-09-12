package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.entity.ReminderTaskEntity;
import com.claw.tools.ToolRegistry;
import com.claw.util.ConfigUtil;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ScheduledTaskAgentService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskAgentService.class);
    private static final String API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_STEPS = 6;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ToolRegistry toolRegistry;

    public ScheduledTaskAgentService(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public String execute(ReminderTaskEntity task) {
        if (task == null) {
            return "";
        }
        WorkflowData workflow = parseWorkflow(task);
        if (workflow == null || workflow.candidateTools().isEmpty()) {
            return legacyText(task);
        }

        String apiKey = ConfigUtil.getBailianKey();
        if (apiKey.isBlank()) {
            return legacyText(task);
        }

        try {
            JSONArray messages = new JSONArray();
            messages.add(systemMessage("""
                    You are a scheduled-task execution agent.
                    You receive one goal and a set of available tools.
                    Decide which tool to call first, whether to continue with more tools, and return a final concise Chinese answer when enough information is collected.
                    Use only available tools. Do not invent results or explain internal workflow.
                    """));
            messages.add(userMessage(buildUserPrompt(task, workflow)));

            JSONArray tools = toolRegistry.buildToolsJson(workflow.candidateTools());
            for (int step = 1; step <= MAX_STEPS; step++) {
                JSONObject assistantMessage = callForMessage(messages, tools, 0.3, 2000);
                if (assistantMessage == null) {
                    break;
                }

                JSONArray toolCalls = assistantMessage.getJSONArray("tool_calls");
                if (toolCalls == null || toolCalls.isEmpty()) {
                    String content = assistantMessage.getString("content");
                    if (content != null && !content.isBlank()) {
                        return content.trim();
                    }
                    break;
                }

                assistantMessage.put("role", "assistant");
                messages.add(assistantMessage);

                boolean executed = false;
                for (int i = 0; i < toolCalls.size(); i++) {
                    JSONObject toolCall = toolCalls.getJSONObject(i);
                    if (toolCall == null) {
                        continue;
                    }
                    JSONObject function = toolCall.getJSONObject("function");
                    if (function == null) {
                        continue;
                    }
                    String functionName = function.getString("name");
                    String argumentsStr = function.getString("arguments");
                    if (functionName == null || functionName.isBlank()) {
                        continue;
                    }

                    String result = toolRegistry.executeToolCall(functionName, argumentsStr);
                    JSONObject toolMsg = new JSONObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCall.getString("id"));
                    toolMsg.put("content", result == null ? "" : result);
                    messages.add(toolMsg);
                    executed = true;
                }

                if (!executed) {
                    break;
                }
            }

            String finalReply = callForContent(messages, 0.3, 2000);
            return finalReply != null && !finalReply.isBlank() ? finalReply.trim() : legacyText(task);
        } catch (Exception e) {
            log.warn("scheduled task agent execution failed, fallback to legacy text. taskId={}", task.getId(), e);
            return legacyText(task);
        }
    }

    private WorkflowData parseWorkflow(ReminderTaskEntity task) {
        String workflowSpecJson = task.getWorkflowSpecJson();
        if (workflowSpecJson == null || workflowSpecJson.isBlank()) {
            return null;
        }
        JSONObject spec = JSON.parseObject(workflowSpecJson);
        if (spec == null) {
            return null;
        }
        JSONArray actions = spec.getJSONArray("actions");
        if (actions == null || actions.isEmpty()) {
            return null;
        }

        List<String> candidateTools = actions.stream()
                .map(item -> item instanceof JSONObject obj ? obj.getString("toolName") : null)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        return new WorkflowData(spec, candidateTools);
    }

    private String buildUserPrompt(ReminderTaskEntity task, WorkflowData workflow) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task title: ").append(task.getTitle()).append('\n');
        sb.append("Original text: ").append(task.getMessage()).append('\n');
        sb.append("Rule: ").append(workflow.spec().getString("ruleText")).append('\n');
        sb.append("Available tools: ").append(String.join(", ", workflow.candidateTools())).append('\n');
        sb.append("Select tools automatically and use multiple rounds if needed.");
        return sb.toString();
    }

    private JSONObject callForMessage(JSONArray messages, JSONArray tools, double temperature, int maxTokens) throws Exception {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", ConfigUtil.getBailianModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("tools", tools);

        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + ConfigUtil.getBailianKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.warn("scheduled task agent request failed, status={}, body={}", response.code(), errorBody);
                return null;
            }
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = JSON.parseObject(body);
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            return choices.getJSONObject(0).getJSONObject("message");
        }
    }

    private String callForContent(JSONArray messages, double temperature, int maxTokens) throws Exception {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", ConfigUtil.getBailianModel());
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);

        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + ConfigUtil.getBailianKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toJSONString(), JSON_TYPE))
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = JSON.parseObject(body);
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            return choices.getJSONObject(0).getJSONObject("message").getString("content");
        }
    }

    private JSONObject systemMessage(String content) {
        JSONObject message = new JSONObject();
        message.put("role", "system");
        message.put("content", content);
        return message;
    }

    private JSONObject userMessage(String content) {
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", content);
        return message;
    }

    private String legacyText(ReminderTaskEntity task) {
        return "Reminder: " + task.getTitle() + "\n" + task.getMessage();
    }

    private record WorkflowData(JSONObject spec, List<String> candidateTools) {
    }
}

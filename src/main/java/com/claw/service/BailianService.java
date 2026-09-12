package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.tools.ToolRegistry;
import com.claw.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InterruptedIOException;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

@Service
public class BailianService {
    private static final Logger log = LoggerFactory.getLogger(BailianService.class);
    private static final int AGENT_MAX_STEPS = 6;
    private static final int MAX_HISTORY_MESSAGES = 12;
    private static final int MAX_MESSAGE_CONTENT_CHARS = 2500;
    private static final int MAX_RENDER_TOOL_RESULT_CHARS = 3500;
    private static final String ACCESS_DENIED_HINT =
            "DashScope access was denied. Check account status, model availability, and API permissions.";
    private static final String TOOL_RESULT_RENDER_PROMPT = """
            You are a Chinese assistant.
            Answer directly based on the tool result without describing the tool call process.
            Keep the reply concise and well-structured in Chinese.
            Always return clean Markdown. Use short headings, lists, tables, blockquotes, and bold labels when they improve readability.
            You may use a small number of friendly emojis when helpful, but do not overuse them.
            Do not output HTML tags.
            """;

    private static final String CONTROL_PROMPT = """
            You extract control intent from a user message.
            Return JSON only.
            Output format:
            {
              \"voice_id_action\":\"set|reset|query|unchanged\",
              \"voice_id\":\"string or empty\",
              \"voice_reply_action\":\"enable_session|disable_session|query|enable_current|disable_current|unchanged\",
              \"cleaned_text\":\"remaining user request after removing control instructions\",
              \"confidence\":\"high|medium|low\"
            }
            Rules:
            1. Set voice_id_action=set only when the user clearly gives a usable voice id.
            2. Status questions should use query or unchanged.
            3. \"reply by voice from now on\" => enable_session.
            4. \"reply to this one by voice\" => enable_current.
            5. \"reply by text from now on\" => disable_session.
            6. \"reply to this one by text\" => disable_current.
            7. If there is no clear control intent, keep all actions unchanged and return the original message in cleaned_text.
            """;

    private static final String SYSTEM_PROMPT = """
            You are a Chinese AI assistant with tool calling.
            Use tools whenever the request depends on external facts, files, resume knowledge, or job matching actions.

            Mandatory tool rules:
            1. Weather requests must use get_weather.
            2. News or current affairs requests must use search_news.
            3. Logistics or tracking requests must use query_logistics.
            4. File QA, summary, or extraction must use parse_file unless the full file content is already provided.
            5. Copywriting requests must use generate_copywriting.
            6. POI, food, attractions, and nearby recommendations must use search_poi.
            7. Travel planning requests must use plan_travel.
            8. Route requests must use query_route.
            9. Emotional support requests must use emotional_support.
            10. Resume knowledge lookup requests must use resume_rag or resume_list.
            11. Resume and job-link matching requests must use job_match_url.
            12. Excel workbook question answering must use excel_query_file.
            13. Excel aggregation, grouping, or column summary requests must use excel_summarize_columns.
            14. Excel result sheet creation or export requests must use excel_export_result.
            15. Full travel planning requests should combine plan_travel, search_poi, get_weather, and query_train_tickets. Use query_route only when the user explicitly asks for a concrete route and both specific origin and destination are available.
            16. Reuse existing travel context from history when available and never re-ask confirmed origin, destination, or travel date.
            17. If origin, destination, travel date, or transport preference is missing, ask only for the missing parts.
            18. For full travel planning, include query_train_tickets by default; if the user mentions morning, afternoon, or evening, pass that time preference through.
            19. When the user clearly wants a full trip plan, continue tool calls until weather, attractions, food, and tickets are covered or explicitly unavailable. Add route details only for explicit route requests.
            20. If the user asks a focused follow-up such as only weather, only tickets, only route, only food, or only attractions, prefer the single most relevant tool instead of the full travel workflow.
            21. Invoice OCR, invoice export, and invoice verification requests must use invoice_skill.
            22. Voice transcription and speech synthesis requests must use speech_skill.
            23. Reminder, alarm, scheduled push, and recurring notification requests must use scheduled_task_skill.
            24. Today is 2026-08-05 in Asia/Shanghai. Convert relative dates like today, tomorrow, and the day after tomorrow into exact dates and do not invent another year.

            Tool argument rules:
            1. Keep user location wording as faithfully as possible.
            2. Do not include filler words such as \"help me\", \"check\", or \"take a look\" in tool arguments.
            3. If required information is missing, ask a concise follow-up question instead of fabricating values.

            Response rules:
            1. After a tool returns, answer in concise natural Chinese.
            2. Always format replies as clean Markdown. For multiple items, use short headings and valid continuous lists; use tables, blockquotes, and bold labels when helpful.
            3. You may use a small number of friendly emojis when it improves readability, but keep the tone professional and do not overuse them.
            4. If tools are not needed, reply directly.
            5. You may call multiple tools when necessary.
            4. Do not mention tool calls, tool names, workflow status, or phrases like “已完成工具调用”, “基于工具结果”, or “工具返回”.
            5. Do not output HTML tags such as <br>; use plain text line breaks only.
            """;

    private static final String AGENT_HINT_PREFIX = """
            You are in guided tool-selection mode.
            Prefer tools over guessing when the task depends on external data or business actions.
            You may call multiple tools in sequence when needed.
            """;

    private static final String VOICE_MATCH_PROMPT = """
            You are a voice candidate matcher.
            Choose the best matching candidate from the provided list.
            Never invent a new voice id.
            Return JSON only.
            Output format:
            {
              \"matched\": true,
              \"voice_id\": \"candidate voice id or empty\",
              \"alias\": \"candidate alias or empty\",
              \"confidence\": \"high|medium|low\",
              \"reason\": \"short reason\"
            }
            If unsure, return matched=false.
            """;

    private final ToolRegistry toolRegistry;
    private final AiMonitorService aiMonitorService;
    private final ModelProfileService modelProfileService;
    private final ModelClient modelClient;

    @Autowired
    public BailianService(ToolRegistry toolRegistry, AiMonitorService aiMonitorService, ModelProfileService modelProfileService, ModelClient modelClient) {
        this.toolRegistry = toolRegistry;
        this.aiMonitorService = aiMonitorService;
        this.modelProfileService = modelProfileService;
        this.modelClient = modelClient;
    }

    public BailianService(ToolRegistry toolRegistry, AiMonitorService aiMonitorService, ModelProfileService modelProfileService) {
        this(toolRegistry, aiMonitorService, modelProfileService, new OkHttpModelClient());
    }

    /** Compatibility constructor for isolated unit tests and CLI callers. */
    public BailianService(ToolRegistry toolRegistry) {
        this(toolRegistry, new AiMonitorService(), null, new OkHttpModelClient());
    }

    public String chatWithTools(List<ConversationMemoryService.ConversationMessage> history, String userMessage) {
        return chatWithTools(history, userMessage, null);
    }

    public String chatWithTools(List<ConversationMemoryService.ConversationMessage> history,
                                String userMessage,
                                ToolIntentRouter.ToolRoute preferredRoute) {
        String apiKey = runtimeConfig().apiKey();
        if (apiKey.isBlank()) {
            return "DashScope API key is missing.";
        }

        try {
            JSONArray messages = new JSONArray();
            messages.add(systemMessage(SYSTEM_PROMPT));

            if (preferredRoute != null) {
                messages.add(systemMessage(buildAgentHint(preferredRoute)));
            }

            if (history != null) {
                int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
                for (int idx = start; idx < history.size(); idx++) {
                    ConversationMemoryService.ConversationMessage message = history.get(idx);
                    if (message == null || message.content() == null || message.content().isBlank()) {
                        continue;
                    }
                    JSONObject historyMsg = new JSONObject();
                    historyMsg.put("role", message.role());
                    historyMsg.put("content", safeContent(message.content()));
                    messages.add(historyMsg);
                }
            }

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", safeContent(userMessage));
            messages.add(userMsg);

            JSONArray tools = resolveCandidateTools(preferredRoute);
            if (isTravelPlanningRoute(preferredRoute)) {
                return runDeterministicTravelWorkflow(messages, userMessage, preferredRoute);
            }
            for (int step = 1; step <= AGENT_MAX_STEPS; step++) {
                JSONObject assistantMessage = callForMessage(
                        messages,
                        tools,
                        0.7,
                        2000,
                        shouldForceInitialToolCall(preferredRoute, step),
                        step == 1 && preferredRoute != null ? preferredRoute.functionName() : null
                );
                if (assistantMessage == null) {
                    return lastApiFailureMessage();
                }

                JSONArray toolCalls = assistantMessage.getJSONArray("tool_calls");
                if (toolCalls == null || toolCalls.isEmpty()) {
                    if (step == 1 && preferredRoute != null && shouldForceInitialToolCall(preferredRoute, step)) {
                        log.warn("Preferred route did not produce a tool call on the first step. preferredRoute={}, content={}",
                                preferredRoute.functionName(),
                                assistantMessage.getString("content"));
                        return runPreferredToolFallback(messages, preferredRoute);
                    }
                    String content = assistantMessage.getString("content");
                    log.info("Bailian reply without tool call: step={}, preferredRoute={}, content={}",
                            step,
                            preferredRoute != null ? preferredRoute.functionName() : "none",
                            content);
                    return content != null ? content.trim() : "AI did not return valid content.";
                }

                assistantMessage.put("role", "assistant");
                messages.add(assistantMessage);

                boolean executedTool = false;
                for (int i = 0; i < toolCalls.size(); i++) {
                    JSONObject toolCall = toolCalls.getJSONObject(i);
                    if (toolCall == null) {
                        continue;
                    }

                    String callId = toolCall.getString("id");
                    JSONObject function = toolCall.getJSONObject("function");
                    if (function == null) {
                        continue;
                    }

                    String functionName = function.getString("name");
                    String argumentsStr = function.getString("arguments");
                    if (functionName == null || functionName.isBlank()) {
                        continue;
                    }

                    log.info("AI requested tool call: step={}, function={}, arguments={}", step, functionName, argumentsStr);
                    String toolResult = executeToolWithMonitoring(functionName, argumentsStr);

                    JSONObject toolMsg = new JSONObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", callId);
                    toolMsg.put("content", toolResult);
                    messages.add(toolMsg);
                    executedTool = true;
                }

                if (!executedTool) {
                    break;
                }
            }

            String finalReply = callForContent(messages, 0.7, 2000);
            log.info("Bailian final reply after tool loop: preferredRoute={}, reply={}",
                    preferredRoute != null ? preferredRoute.functionName() : "none",
                    finalReply);
            return finalReply != null ? finalReply.trim() : lastApiFailureMessage();
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            log.info("Bailian Function Calling request interrupted by caller. preferredRoute={}",
                    preferredRoute != null ? preferredRoute.functionName() : "none");
            return lastApiFailureMessage();
        } catch (Exception e) {
            if (isInterruptedRequest(e)) {
                Thread.currentThread().interrupt();
                log.info("Bailian Function Calling request interrupted by caller. preferredRoute={}",
                        preferredRoute != null ? preferredRoute.functionName() : "none");
                return lastApiFailureMessage();
            }
            log.error("Bailian Function Calling request failed", e);
            return friendlyExceptionMessage(e);
        }
    }

    private JSONObject callForMessage(JSONArray messages,
                                      JSONArray tools,
                                      double temperature,
                                      int maxTokens,
                                      boolean requireToolCall,
                                      String preferredFunctionName) throws Exception {
        String apiKey = runtimeConfig().apiKey();
        String model = runtimeConfig().model();
        aiMonitorService.recordModelSelection(model);
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
            if (requireToolCall) {
                if (preferredFunctionName != null && !preferredFunctionName.isBlank()) {
                    JSONObject toolChoice = new JSONObject();
                    toolChoice.put("type", "function");
                    JSONObject function = new JSONObject();
                    function.put("name", preferredFunctionName);
                    toolChoice.put("function", function);
                    requestBody.put("tool_choice", toolChoice);
                } else {
                    requestBody.put("tool_choice", "required");
                }
            }
        }

        try {
            JSONObject json = modelClient.complete(runtimeConfig(), requestBody);
            recordUsage(json, model);
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }

            return choices.getJSONObject(0).getJSONObject("message");
        } catch (ModelClient.RequestException e) {
            log.warn("Model request failed, status={}, body={}", e.status(), e.getMessage());
            return null;
        }
    }

    private boolean shouldForceInitialToolCall(ToolIntentRouter.ToolRoute preferredRoute, int step) {
        return step == 1
                && preferredRoute != null
                && preferredRoute.routeMode() == ToolIntentRouter.RouteMode.PREFER_AGENT
                && isTravelPlanningRoute(preferredRoute);
    }

    private boolean isTravelPlanningRoute(ToolIntentRouter.ToolRoute preferredRoute) {
        if (preferredRoute == null || preferredRoute.candidateTools() == null) {
            return false;
        }
        List<String> candidates = preferredRoute.candidateTools();
        return candidates.contains("plan_travel")
                && candidates.contains("get_weather")
                && candidates.contains("query_train_tickets");
    }

    private String runDeterministicTravelWorkflow(JSONArray messages,
                                                  String userMessage,
                                                  ToolIntentRouter.ToolRoute preferredRoute) throws Exception {
        JSONObject routeArgs = preferredRoute.arguments() == null ? new JSONObject() : preferredRoute.arguments();

        String origin = trimToNull(routeArgs.getString("origin"));
        String destination = trimToNull(routeArgs.getString("destination"));
        String city = trimToNull(routeArgs.getString("city"));
        String travelDate = firstNonBlank(routeArgs.getString("travelDate"), routeArgs.getString("travel_date"));
        String timePreference = firstNonBlank(routeArgs.getString("timePreference"), routeArgs.getString("time_preference"));
        Integer tripDays = firstNonBlankInteger(routeArgs.getInteger("tripDays"), routeArgs.getInteger("trip_days"));
        boolean needTickets = routeArgs.getBooleanValue("needTickets");

        String missingReply = buildTravelMissingArgsReply(origin, destination, city, travelDate, tripDays, needTickets);
        if (missingReply != null) {
            return missingReply;
        }
        log.info("Running deterministic travel workflow: city={}, origin={}, destination={}, travelDate={}, tripDays={}, timePreference={}",
                city, origin, destination, travelDate, tripDays, timePreference);

        boolean includeConcreteRoute = shouldIncludeConcreteRouteInTravelWorkflow(userMessage, origin, destination);

        if (isSpecificRoutePoint(origin) && isSpecificRoutePoint(destination) && !samePlace(origin, destination)) {
            JSONObject ticketArgs = new JSONObject();
            ticketArgs.put("origin", origin);
            ticketArgs.put("destination", destination);
            ticketArgs.put("travel_date", travelDate);
            String sessionId = trimToNull(routeArgs.getString("session_id"));
            if (sessionId != null) {
                ticketArgs.put("session_id", sessionId);
            }
            ticketArgs.put("travel_workflow", true);
            if (timePreference != null) {
                ticketArgs.put("time_preference", timePreference);
            }
            String ticketResult = executeWorkflowTool(messages, "query_train_tickets", ticketArgs);
            if (isStationClarificationReply(ticketResult)) {
                return ticketResult;
            }
        }

        String weatherCity = firstNonBlank(city, destination);
        int weatherDays = tripDays == null ? 1 : Math.max(1, Math.min(tripDays, 14));
        for (int day = 0; day < weatherDays; day++) {
            JSONObject weatherArgs = new JSONObject();
            weatherArgs.put("city", weatherCity);
            weatherArgs.put("date", java.time.LocalDate.parse(travelDate).plusDays(day).toString());
            executeWorkflowTool(messages, "get_weather", weatherArgs);
        }

        JSONObject travelArgs = new JSONObject();
        if (city != null) {
            travelArgs.put("city", city);
        }
        if (destination != null) {
            travelArgs.put("destination", destination);
        }
        travelArgs.put("travel_date", travelDate);
        if (tripDays != null) {
            travelArgs.put("trip_days", tripDays);
        }
        travelArgs.put("travel_type", "both");
        executeWorkflowTool(messages, "plan_travel", travelArgs);

        if (includeConcreteRoute) {
            JSONObject routeToolArgs = new JSONObject();
            routeToolArgs.put("origin", origin);
            routeToolArgs.put("destination", destination);
            if (city != null) {
                routeToolArgs.put("city", city);
            }
            executeWorkflowTool(messages, "query_route", routeToolArgs);
        }

        JSONArray summaryMessages = new JSONArray();
        summaryMessages.add(systemMessage("""
                You are a Chinese travel planning assistant.
                Summarize only from the factual results already provided.
                Do not claim any real-time data that is not present in the provided results.
                If ticket lookup failed, say it failed plainly and continue with the rest of the plan.
                Keep the answer practical, concise, and easy to scan.
                The final answer must use exactly this order:
                1. 天气情况
                2. 合适的高铁票（优先整理为 3 班，若结果不足就如实说明）
                3. 攻略规划
                4. 出行小贴士
                Build a day-by-day itinerary with exactly %d days in the 攻略规划 section.
                For each day, include recommended attractions, meal suggestions, and a sensible visit order.
                Use short section titles and clear bullet-style wording in Chinese.
                The content should be clear, direct, and easy to understand at a glance.
                If no concrete route was explicitly requested, do not mention route lookup, missing route parameters, or route lookup failures.
                You may end with one short sentence inviting the user to ask for a specific route between exact places if useful.
                Do not mention tool calls, tool names, workflows, or any internal execution process.
                Do not say “已完成工具调用”, “基于工具结果”, “工具返回”, or similar wording.
                Do not output HTML tags such as <br>; use plain text line breaks only.
                """.formatted(tripDays)));
        for (int i = 1; i < messages.size(); i++) {
            summaryMessages.add(messages.getJSONObject(i));
        }

        String finalReply = callForContent(summaryMessages, 0.4, 2200);
        log.info("Deterministic travel workflow final reply: preferredRoute={}, reply={}",
                preferredRoute.functionName(), finalReply);
        return finalReply != null && !finalReply.isBlank() ? finalReply.trim() : "\u51fa\u884c\u89c4\u5212\u751f\u6210\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
    }

    private String buildTravelMissingArgsReply(String origin,
                                               String destination,
                                               String city,
                                               String travelDate,
                                               Integer tripDays,
                                               boolean needTickets) {
        if (needTickets && origin == null) {
            return "还缺出发地。请直接告诉我你从哪里出发，例如“从洛阳出发”。";
        }
        if (destination == null && city == null) {
            return "还缺目的地。请直接告诉我你想去哪个城市或景点。";
        }
        if (travelDate == null) {
            return "还缺出行日期。请直接告诉我具体日期，或说今天、明天、后天。";
        }
        if (tripDays == null) {
            return "还缺游玩天数。请直接告诉我是几日游，例如“2日游”或“玩3天”。";
        }
        return null;
    }

    private String executeWorkflowTool(JSONArray messages, String functionName, JSONObject arguments) {
        if (!toolRegistry.hasTool(functionName)) {
            log.warn("Workflow skipped missing tool: function={}", functionName);
            return "";
        }

        String argumentsStr = arguments == null ? "{}" : arguments.toJSONString();
        log.info("Deterministic workflow tool call: function={}, arguments={}", functionName, argumentsStr);
        String toolResult = executeToolWithMonitoring(functionName, argumentsStr);
        String displayResult = formatToolResultForDisplay(toolResult);

        JSONObject resultMessage = new JSONObject();
        resultMessage.put("role", "assistant");
        resultMessage.put("content", "参考结果：\n" + (displayResult == null ? "" : displayResult));
        messages.add(resultMessage);
        return displayResult;
    }

    private boolean isStationClarificationReply(String text) {
        return text != null && text.contains("确认具体出发站和到达站");
    }

    private boolean shouldIncludeConcreteRouteInTravelWorkflow(String userMessage,
                                                               String origin,
                                                               String destination) {
        if (!looksLikeExplicitRouteRequest(userMessage)) {
            return false;
        }
        return isSpecificRoutePoint(origin)
                && isSpecificRoutePoint(destination)
                && !samePlace(origin, destination);
    }

    private boolean looksLikeExplicitRouteRequest(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String normalized = userMessage.toLowerCase();
        return normalized.contains("路线")
                || normalized.contains("怎么走")
                || normalized.contains("如何走")
                || normalized.contains("导航")
                || normalized.contains("公交")
                || normalized.contains("地铁")
                || normalized.contains("打车")
                || normalized.contains("驾车")
                || normalized.contains("步行")
                || normalized.contains("骑行");
    }

    private String runPreferredToolFallback(JSONArray messages, ToolIntentRouter.ToolRoute preferredRoute) throws Exception {
        String arguments = preferredRoute.arguments() == null ? "{}" : preferredRoute.arguments().toJSONString();
        String toolResult = executeToolWithMonitoring(preferredRoute.functionName(), arguments);
        log.info("Preferred route fallback executed: function={}, arguments={}, resultLength={}",
                preferredRoute.functionName(), arguments, toolResult != null ? toolResult.length() : 0);

        JSONObject assistantMessage = new JSONObject();
        assistantMessage.put("role", "assistant");
        JSONArray toolCalls = new JSONArray();
        JSONObject toolCall = new JSONObject();
        toolCall.put("id", "fallback-" + preferredRoute.functionName());
        toolCall.put("type", "function");
        JSONObject function = new JSONObject();
        function.put("name", preferredRoute.functionName());
        function.put("arguments", arguments);
        toolCall.put("function", function);
        toolCalls.add(toolCall);
        assistantMessage.put("tool_calls", toolCalls);
        messages.add(assistantMessage);

        JSONObject toolMessage = new JSONObject();
        toolMessage.put("role", "tool");
        toolMessage.put("tool_call_id", "fallback-" + preferredRoute.functionName());
        toolMessage.put("content", toolResult);
        messages.add(toolMessage);

        String finalReply = callForContent(messages, 0.7, 2000);
        return finalReply != null ? finalReply.trim() : toolResult;
    }

    public String chatWithImage(byte[] imageData, String prompt) {
        String apiKey = runtimeConfig().apiKey();
        if (apiKey.isBlank()) {
            return "DashScope API key is missing.";
        }

        try {
            String base64Image = Base64.getEncoder().encodeToString(imageData);
            String imageDataUrl = "data:image/jpeg;base64," + base64Image;

            JSONArray messages = new JSONArray();
            JSONArray contentArray = new JSONArray();

            JSONObject textContent = new JSONObject();
            textContent.put("type", "text");
            textContent.put("text", prompt != null && !prompt.isBlank() ? prompt : "Describe this image.");
            contentArray.add(textContent);

            JSONObject imageContent = new JSONObject();
            imageContent.put("type", "image_url");
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", imageDataUrl);
            imageContent.put("image_url", imageUrl);
            contentArray.add(imageContent);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", contentArray);
            messages.add(userMsg);

            String model = ConfigUtil.getVisionModel();
            aiMonitorService.recordModelSelection(model);
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 1000);

            JSONObject json = modelClient.complete(runtimeConfig(), requestBody);
            recordUsage(json, model);
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return "AI did not return a valid image analysis result.";
            String content = choices.getJSONObject(0).getJSONObject("message").getString("content").trim();
            log.info("Bailian image reply: {}", content);
            return content;
        } catch (ModelClient.RequestException e) {
            log.error("Vision request failed, status={}, body={}", e.status(), e.getMessage());
            return mapApiFailureMessage(e.status(), e.getMessage(), "Vision service is temporarily unavailable.");
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            log.info("Bailian vision request interrupted by caller");
            return lastApiFailureMessage();
        } catch (Exception e) {
            if (isInterruptedRequest(e)) {
                Thread.currentThread().interrupt();
                log.info("Bailian vision request interrupted by caller");
                return lastApiFailureMessage();
            }
            log.error("Bailian vision request failed", e);
            return friendlyExceptionMessage(e);
        }
    }

    public record ControlIntent(String voiceIdAction, String voiceId, String voiceReplyAction,
                                String cleanedText, String confidence) {
        public boolean isLowConfidence() {
            return "low".equalsIgnoreCase(confidence);
        }
    }

    public record VoiceCandidate(String alias, String voiceId) {
    }

    public record VoiceMatchResult(boolean matched, String voiceId, String alias, String confidence, String reason) {
        public boolean isMatched() {
            return matched && voiceId != null && !voiceId.isBlank();
        }
    }

    public ControlIntent analyzeControlIntent(String userMessage) {
        String apiKey = runtimeConfig().apiKey();
        if (apiKey.isBlank() || userMessage == null || userMessage.isBlank()) {
            return defaultControlIntent(userMessage);
        }

        try {
            JSONArray messages = new JSONArray();
            messages.add(systemMessage(CONTROL_PROMPT));

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);

            String content = callForContent(messages, 0.1, 300);
            if (content == null || content.isBlank()) {
                return defaultControlIntent(userMessage);
            }

            JSONObject result = JSON.parseObject(content);
            if (result == null) {
                return defaultControlIntent(userMessage);
            }

            return new ControlIntent(
                    result.getString("voice_id_action"),
                    result.getString("voice_id"),
                    result.getString("voice_reply_action"),
                    result.getString("cleaned_text"),
                    result.getString("confidence")
            );
        } catch (Exception e) {
            log.warn("Control intent analysis failed, falling back to default behavior", e);
            return defaultControlIntent(userMessage);
        }
    }

    public VoiceMatchResult matchVoiceCandidate(String userMessage, List<VoiceCandidate> candidates) {
        if (userMessage == null || userMessage.isBlank() || candidates == null || candidates.isEmpty()) {
            return new VoiceMatchResult(false, "", "", "low", "no candidates");
        }

        String apiKey = runtimeConfig().apiKey();
        if (apiKey.isBlank()) {
            return new VoiceMatchResult(false, "", "", "low", "api key missing");
        }

        try {
            JSONArray candidateArray = new JSONArray();
            for (VoiceCandidate candidate : candidates) {
                if (candidate == null || candidate.voiceId() == null || candidate.voiceId().isBlank()) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("alias", candidate.alias());
                item.put("voice_id", candidate.voiceId());
                candidateArray.add(item);
            }
            if (candidateArray.isEmpty()) {
                return new VoiceMatchResult(false, "", "", "low", "empty candidate array");
            }

            JSONArray messages = new JSONArray();
            messages.add(systemMessage(VOICE_MATCH_PROMPT));

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", "User description: " + userMessage + "\nCandidates: " + candidateArray.toJSONString());
            messages.add(userMsg);

            String content = callForContent(messages, 0.1, 300);
            if (content == null || content.isBlank()) {
                return new VoiceMatchResult(false, "", "", "low", "empty response");
            }

            JSONObject result = JSON.parseObject(content);
            if (result == null) {
                return new VoiceMatchResult(false, "", "", "low", "invalid json");
            }

            VoiceMatchResult match = new VoiceMatchResult(
                    result.getBooleanValue("matched"),
                    result.getString("voice_id"),
                    result.getString("alias"),
                    result.getString("confidence"),
                    result.getString("reason")
            );
            log.info("Voice match result: userMessage={}, result={}", userMessage, content);
            return match;
        } catch (Exception e) {
            log.warn("Voice candidate match failed. userMessage={}", userMessage, e);
            return new VoiceMatchResult(false, "", "", "low", e.getMessage());
        }
    }

    private String callForContent(JSONArray messages, double temperature, int maxTokens) throws Exception {
        String apiKey = runtimeConfig().apiKey();
        String model = runtimeConfig().model();
        aiMonitorService.recordModelSelection(model);
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);

        try {
            JSONObject json = modelClient.complete(runtimeConfig(), requestBody);
            recordUsage(json, model);
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }

            return choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
        } catch (ModelClient.RequestException e) {
            log.warn("Model request failed, status={}, body={}", e.status(), e.getMessage());
            return null;
        }
    }

    private void streamContent(JSONArray messages,
                               double temperature,
                               int maxTokens,
                               Consumer<String> onDelta) throws Exception {
        String model = runtimeConfig().model();
        aiMonitorService.recordModelSelection(model);
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);
        modelClient.stream(runtimeConfig(), requestBody, onDelta == null ? ignored -> { } : onDelta);
    }

    private ControlIntent defaultControlIntent(String userMessage) {
        return new ControlIntent("unchanged", "", "unchanged",
                userMessage == null ? "" : userMessage.trim(), "low");
    }

    public String replyWithForcedTool(String userMessage, String functionName, JSONObject arguments, boolean renderWithModel) {
        return replyWithForcedTool(ToolExecutionContextHolder.get(), userMessage, functionName, arguments, renderWithModel);
    }

    public String replyWithForcedTool(UserSessionContext context, String userMessage, String functionName, JSONObject arguments, boolean renderWithModel) {
        UserSessionContext previous = ToolExecutionContextHolder.get();
        if (context != null) {
            ToolExecutionContextHolder.set(context);
        }
        try {
            String toolResult = executeToolWithMonitoring(functionName, arguments.toJSONString());
            return renderForcedToolResult(userMessage, functionName, toolResult, renderWithModel);
        } finally {
            ToolExecutionContextHolder.set(previous);
        }
    }

    public String replyWithForcedToolStream(UserSessionContext context,
                                            String userMessage,
                                            String functionName,
                                            JSONObject arguments,
                                            boolean renderWithModel,
                                            Consumer<String> onDelta) {
        UserSessionContext previous = ToolExecutionContextHolder.get();
        if (context != null) {
            ToolExecutionContextHolder.set(context);
        }
        try {
            String toolResult = executeToolWithMonitoring(functionName, arguments.toJSONString());
            if (!renderWithModel) {
                String displayText = formatToolResultForDisplay(toolResult);
                if (displayText != null && !displayText.isBlank() && onDelta != null) {
                    onDelta.accept(displayText);
                }
                return displayText;
            }
            return streamForcedToolResult(userMessage, functionName, toolResult, onDelta);
        } finally {
            ToolExecutionContextHolder.set(previous);
        }
    }

    public String streamPlainChat(List<ConversationMemoryService.ConversationMessage> history,
                                  String userMessage,
                                  Consumer<String> onDelta) {
        String apiKey = runtimeConfig().apiKey();
        if (apiKey.isBlank()) {
            return "DashScope API key is missing.";
        }

        StringBuilder reply = new StringBuilder();
        try {
            JSONArray messages = new JSONArray();
            messages.add(systemMessage(SYSTEM_PROMPT));

            if (history != null) {
                int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
                for (int idx = start; idx < history.size(); idx++) {
                    ConversationMemoryService.ConversationMessage message = history.get(idx);
                    if (message == null || message.content() == null || message.content().isBlank()) {
                        continue;
                    }
                    JSONObject historyMsg = new JSONObject();
                    historyMsg.put("role", message.role());
                    historyMsg.put("content", safeContent(message.content()));
                    messages.add(historyMsg);
                }
            }

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", safeContent(userMessage));
            messages.add(userMsg);

            streamContent(messages, 0.7, 2000, chunk -> {
                reply.append(chunk);
                if (onDelta != null) {
                    onDelta.accept(chunk);
                }
            });
            return reply.toString().trim();
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            if (!reply.isEmpty()) {
                return reply.toString().trim();
            }
            log.info("Bailian streaming request interrupted by caller");
            return lastApiFailureMessage();
        } catch (Exception e) {
            if (isInterruptedRequest(e)) {
                Thread.currentThread().interrupt();
                if (!reply.isEmpty()) {
                    return reply.toString().trim();
                }
                log.info("Bailian streaming request interrupted by caller");
                return lastApiFailureMessage();
            }
            log.error("Bailian streaming request failed", e);
            if (!reply.isEmpty()) {
                return reply.toString().trim();
            }
            return friendlyExceptionMessage(e);
        }
    }

    private String renderForcedToolResult(String userMessage, String functionName, String toolResult, boolean renderWithModel) {
        if (!renderWithModel) {
            return formatToolResultForDisplay(toolResult);
        }
        if ("parse_file".equals(functionName)) {
            return formatToolResultForDisplay(toolResult);
        }
        if (toolResult == null || toolResult.isBlank()) {
            return "Tool returned no usable result.";
        }
        if (toolResult.length() > MAX_RENDER_TOOL_RESULT_CHARS) {
            log.info("Skip model polishing for large tool result. functionName={}, resultLength={}", functionName, toolResult.length());
            return toolResult;
        }

        try {
            JSONArray messages = new JSONArray();
            messages.add(systemMessage(TOOL_RESULT_RENDER_PROMPT));

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", safeContent("Original question: " + userMessage + "\nTool result: " + toolResult));
            messages.add(userMsg);

            String reply = callForContent(messages, 0.7, 2000);
            return reply != null && !reply.isBlank() ? reply.trim() : toolResult;
        } catch (Exception e) {
            log.warn("Forced tool rendering failed. functionName={}", functionName, e);
            return toolResult;
        }
    }

    private String streamForcedToolResult(String userMessage,
                                          String functionName,
                                          String toolResult,
                                          Consumer<String> onDelta) {
        if ("parse_file".equals(functionName)) {
            String displayText = formatToolResultForDisplay(toolResult);
            if (onDelta != null) {
                onDelta.accept(displayText);
            }
            return displayText;
        }
        if (toolResult == null || toolResult.isBlank()) {
            String fallback = "Tool returned no usable result.";
            if (onDelta != null) {
                onDelta.accept(fallback);
            }
            return fallback;
        }
        if (toolResult.length() > MAX_RENDER_TOOL_RESULT_CHARS) {
            log.info("Skip model streaming polish for large tool result. functionName={}, resultLength={}", functionName, toolResult.length());
            String displayText = formatToolResultForDisplay(toolResult);
            if (onDelta != null) {
                onDelta.accept(displayText);
            }
            return displayText;
        }

        StringBuilder reply = new StringBuilder();
        try {
            JSONArray messages = new JSONArray();
            messages.add(systemMessage(TOOL_RESULT_RENDER_PROMPT));

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", safeContent("Original question: " + userMessage + "\nTool result: " + toolResult));
            messages.add(userMsg);

            streamContent(messages, 0.7, 2000, chunk -> {
                reply.append(chunk);
                if (onDelta != null) {
                    onDelta.accept(chunk);
                }
            });
            return reply.isEmpty() ? toolResult : reply.toString().trim();
        } catch (Exception e) {
            log.warn("Forced tool streaming render failed. functionName={}", functionName, e);
            if (reply.isEmpty() && onDelta != null) {
                onDelta.accept(toolResult);
            }
            return reply.isEmpty() ? toolResult : reply.toString().trim();
        }
    }

    private String buildAgentHint(ToolIntentRouter.ToolRoute preferredRoute) {
        StringBuilder hint = new StringBuilder(AGENT_HINT_PREFIX);
        hint.append("\nPreferred tool: ").append(preferredRoute.functionName());
        hint.append("\nRoute mode: ").append(preferredRoute.routeMode());

        if (preferredRoute.arguments() != null && !preferredRoute.arguments().isEmpty()) {
            hint.append("\nSuggested initial arguments: ").append(preferredRoute.arguments().toJSONString());
        }

        if (preferredRoute.candidateTools() != null && !preferredRoute.candidateTools().isEmpty()) {
            hint.append("\nPreferred tool set: ").append(String.join(", ", preferredRoute.candidateTools()));
        }

        hint.append("\nIf the request is ambiguous, start with the most likely tool and continue with follow-up tool calls only when necessary.");
        return hint.toString();
    }

    private JSONArray resolveCandidateTools(ToolIntentRouter.ToolRoute preferredRoute) {
        if (!toolRegistry.hasTools()) {
            return null;
        }
        if (preferredRoute == null) {
            return toolRegistry.buildToolsJson();
        }

        List<String> candidates = preferredRoute.candidateTools();
        if (candidates == null || candidates.isEmpty()) {
            return toolRegistry.buildToolsJson(List.of(preferredRoute.functionName()));
        }

        List<String> validCandidates = candidates.stream()
                .filter(toolRegistry::hasTool)
                .distinct()
                .toList();

        if (validCandidates.isEmpty()) {
            return toolRegistry.buildToolsJson();
        }
        return toolRegistry.buildToolsJson(validCandidates);
    }

    private JSONObject systemMessage(String content) {
        JSONObject message = new JSONObject();
        message.put("role", "system");
        message.put("content", safeContent(content));
        return message;
    }

    private String executeToolWithMonitoring(String functionName, String arguments) {
        aiMonitorService.recordToolCall(functionName);
        return toolRegistry.executeToolCall(functionName, arguments);
    }

    private void recordUsage(JSONObject responseJson, String model) {
        UsageSnapshot usage = parseUsage(responseJson);
        if (usage == null) {
            return;
        }
        aiMonitorService.recordTokenUsage(model, usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }

    private UsageSnapshot parseUsage(JSONObject responseJson) {
        if (responseJson == null) {
            return null;
        }
        JSONObject usage = responseJson.getJSONObject("usage");
        if (usage == null) {
            return null;
        }
        Integer promptTokens = usage.getInteger("prompt_tokens");
        Integer completionTokens = usage.getInteger("completion_tokens");
        Integer totalTokenCount = usage.getInteger("total_tokens");
        if (promptTokens == null && completionTokens == null && totalTokenCount == null) {
            return null;
        }
        return new UsageSnapshot(promptTokens, completionTokens, totalTokenCount);
    }

    private String lastApiFailureMessage() {
        return "DashScope is temporarily unavailable. Please try again later.";
    }

    private boolean isInterruptedRequest(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedIOException || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String friendlyExceptionMessage(Exception e) {
        String message = e.getMessage();
        if (message != null && message.toLowerCase().contains("access denied")) {
            return ACCESS_DENIED_HINT;
        }
        return "AI processing failed: " + message;
    }

    private String mapApiFailureMessage(int statusCode, String errorBody, String fallback) {
        if (errorBody != null && errorBody.toLowerCase().contains("access denied")) {
            return ACCESS_DENIED_HINT;
        }
        if (statusCode == 401 || statusCode == 403) {
            return "DashScope authentication failed. Check API key and account permissions.";
        }
        if (statusCode == 400) {
            return "DashScope rejected the request. Check account status, model permission, or request content.";
        }
        return fallback;
    }

    private String safeContent(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content
                .replace('\u0000', ' ')
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > MAX_MESSAGE_CONTENT_CHARS) {
            return cleaned.substring(0, MAX_MESSAGE_CONTENT_CHARS) + " ...";
        }
        return cleaned;
    }

    private String formatToolResultForDisplay(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return "Tool returned no usable result.";
        }

        String trimmed = toolResult.trim();
        try {
            JSONObject json = JSON.parseObject(trimmed);
            if (json == null) {
                return trimmed;
            }

            String message = trimToNull(json.getString("message"));
            if (message != null) {
                return message;
            }

            JSONObject data = json.getJSONObject("data");
            if (data != null) {
                String output = trimToNull(data.getString("output"));
                if (output != null) {
                    return output;
                }
            }

            String error = trimToNull(json.getString("error"));
            if (error != null) {
                return error;
            }

            return trimmed;
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private ModelRuntimeConfig runtimeConfig() {
        return modelProfileService == null
                ? new ModelRuntimeConfig(ConfigUtil.getDashscopeBaseUrl(), ConfigUtil.getBailianModel(), ConfigUtil.getBailianKey(), 0.7, 2000)
                : modelProfileService.resolve(ToolExecutionContextHolder.get());
    }

    private Integer firstNonBlankInteger(Integer... values) {
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    private boolean isSpecificRoutePoint(String value) {
        String trimmed = trimToNull(value);
        return trimmed != null && trimmed.length() >= 2;
    }

    private boolean samePlace(String left, String right) {
        String a = trimToNull(left);
        String b = trimToNull(right);
        if (a == null || b == null) {
            return false;
        }
        return a.equalsIgnoreCase(b);
    }

    private record UsageSnapshot(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }
}


package com.claw.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class MessageRoutingService {
    private static final long TASK_STATE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private enum TaskMode {
        NORMAL,
        FILE,
        EXCEL
    }

    private static final class SessionState {
        private TaskMode taskMode = TaskMode.NORMAL;
        private String boundFileName;
        private long lastIntentAt = System.currentTimeMillis();
    }

    public record RouteResult(boolean normalChat,
                              String replyText,
                              ToolIntentRouter.ToolRoute preferredToolRoute,
                              ToolIntentRouter.ToolRoute forcedToolRoute) {
        public static RouteResult allowNormalChat() {
            return new RouteResult(true, null, null, null);
        }

        public static RouteResult handled(String replyText) {
            return new RouteResult(false, replyText, null, null);
        }

        public static RouteResult preferAgent(ToolIntentRouter.ToolRoute route) {
            return new RouteResult(false, null, route, null);
        }

        public static RouteResult forcedTool(ToolIntentRouter.ToolRoute route) {
            return new RouteResult(false, null, null, route);
        }
    }

    private final Map<String, SessionState> stateMap = new ConcurrentHashMap<>();
    private final ConversationMemoryService conversationMemoryService;
    private final BailianService bailianService;
    private final ToolIntentRouter toolIntentRouter;
    private final FileParseService fileParseService;
    private final ResumeKnowledgeService resumeKnowledgeService;
    private final ExcelMcpService excelMcpService;
    private final ScheduledTaskMessageRouter scheduledTaskMessageRouter;
    private final ReminderMessageRouter reminderMessageRouter;

    public MessageRoutingService(ConversationMemoryService conversationMemoryService,
                                 BailianService bailianService,
                                 ToolIntentRouter toolIntentRouter,
                                 FileParseService fileParseService,
                                 ResumeKnowledgeService resumeKnowledgeService,
                                 ExcelMcpService excelMcpService,
                                 ScheduledTaskMessageRouter scheduledTaskMessageRouter,
                                 ReminderMessageRouter reminderMessageRouter) {
        this.conversationMemoryService = conversationMemoryService;
        this.bailianService = bailianService;
        this.toolIntentRouter = toolIntentRouter;
        this.fileParseService = fileParseService;
        this.resumeKnowledgeService = resumeKnowledgeService;
        this.excelMcpService = excelMcpService;
        this.scheduledTaskMessageRouter = scheduledTaskMessageRouter;
        this.reminderMessageRouter = reminderMessageRouter;
    }

    public RouteResult route(String sessionId, String userMessage) {
        return route(UserSessionContext.fromSessionId(sessionId), userMessage);
    }

    public RouteResult route(UserSessionContext context, String userMessage) {
        String sessionId = context != null && context.sessionId() != null ? context.sessionId() : "";
        SessionState state = stateMap.computeIfAbsent(sessionId, key -> new SessionState());
        expireTaskStateIfNeeded(state);

        String normalized = normalize(userMessage);
        if (normalized.isEmpty()) {
            return RouteResult.allowNormalChat();
        }

        if (looksLikeTopicSwitch(normalized)) {
            clearTaskState(state);
        }

        // Stage 1: stateful / session-bound hard routes.
        RouteResult reminderResult = routeReminder(sessionId, userMessage);
        if (reminderResult != null) {
            clearTaskState(state);
            return reminderResult;
        }

        RouteResult excelResult = routeExcel(context, sessionId, userMessage, normalized, state);
        if (excelResult != null) {
            return excelResult;
        }

        RouteResult resumeResult = routeResume(sessionId, userMessage);
        if (resumeResult != null) {
            return resumeResult;
        }

        // Stage 2: generic tool intent routing.
        ToolIntentRouter.ToolRoute toolRoute = toolIntentRouter.match(sessionId, userMessage);
        if (toolRoute != null) {
            clearTaskState(state);
            if (toolRoute.routeMode() == ToolIntentRouter.RouteMode.FORCE_TOOL) {
                return RouteResult.forcedTool(toolRoute);
            }
            return RouteResult.preferAgent(toolRoute);
        }

        RouteResult fileResult = routeFile(sessionId, userMessage, normalized, state);
        if (fileResult != null) {
            return fileResult;
        }

        return RouteResult.allowNormalChat();
    }

    public void clearSessionState(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            stateMap.remove(sessionId);
        }
    }

    public void bindFileSession(String sessionId, String fileName) {
        if (sessionId == null || sessionId.isBlank() || fileName == null || fileName.isBlank()) {
            return;
        }
        SessionState state = stateMap.computeIfAbsent(sessionId, key -> new SessionState());
        bindTaskState(state, TaskMode.FILE, fileName);
    }

    public void bindExcelSession(String sessionId, String workbook) {
        if (sessionId == null || sessionId.isBlank() || workbook == null || workbook.isBlank()) {
            return;
        }
        SessionState state = stateMap.computeIfAbsent(sessionId, key -> new SessionState());
        bindTaskState(state, TaskMode.EXCEL, workbook);
    }

    private RouteResult routeReminder(String sessionId, String userMessage) {
        UserSessionContext context = UserSessionContext.fromSessionId(sessionId);
        String workflowReply = scheduledTaskMessageRouter.route(context, userMessage);
        if (workflowReply != null) {
            return RouteResult.handled(workflowReply);
        }
        String reply = reminderMessageRouter.route(context, userMessage);
        return reply == null ? null : RouteResult.handled(reply);
    }

    private RouteResult routeResume(String sessionId, String userMessage) {
        String normalized = normalize(userMessage);
        if (!normalized.contains("继续") && !normalized.contains("恢复") && !normalized.contains("resume")) {
            return null;
        }
        ConversationMemoryService.ResumeSnapshot snapshot = conversationMemoryService.forceResumeSession(sessionId);
        if (snapshot == null) {
            return null;
        }
        if (snapshot.hasEmotionState()) {
            return RouteResult.handled(conversationMemoryService.buildEmotionResumeSummary(sessionId));
        }
        if (snapshot.hasMessages()) {
            return RouteResult.handled("已恢复上次对话记录，可以继续。");
        }
        return RouteResult.handled("已恢复历史会话。");
    }

    private RouteResult routeExcel(UserSessionContext context, String sessionId, String userMessage, String normalized, SessionState state) {
        if (excelMcpService == null) {
            return null;
        }

        String workbook = extractExcelWorkbook(sessionId, userMessage);
        boolean explicit = hasExcelIntentKeywords(normalized);
        boolean continueCurrent = state.taskMode == TaskMode.EXCEL
                && workbook != null
                && workbook.equals(state.boundFileName)
                && !looksLikeTopicSwitch(normalized);
        if (workbook == null || (!explicit && !continueCurrent)) {
            return null;
        }

        JSONObject args = new JSONObject();
        args.put("workbook", workbook);
        String sheetName = extractExcelSheetName(userMessage);
        if (sheetName != null) {
            args.put("sheetName", sheetName);
        }

        bindTaskState(state, TaskMode.EXCEL, workbook);
        if (containsAny(normalized, "导出", "输出文件", "另存为", "生成结果sheet", "生成sheet", "写入sheet")) {
            String targetSheetName = extractTargetSheetName(userMessage);
            String outputFile = extractOutputFile(userMessage);
            JSONArray selectedColumns = extractSelectedColumns(userMessage);
            String rowFilter = normalizeExcelRowFilter(extractExcelRowFilter(userMessage), userMessage);
            String sortColumn = normalizeExcelSortColumn(extractExcelSortColumn(userMessage), userMessage);
            String sortOrder = normalizeExcelSortOrder(extractExcelSortOrder(userMessage), userMessage);
            if (targetSheetName != null) {
                args.put("targetSheetName", targetSheetName);
            }
            if (outputFile != null) {
                args.put("outputFile", outputFile);
            }
            if (selectedColumns != null && !selectedColumns.isEmpty()) {
                args.put("selectedColumns", selectedColumns);
            }
            if (rowFilter != null) {
                args.put("rowFilter", rowFilter);
            }
            if (sortColumn != null) {
                args.put("sortColumn", sortColumn);
            }
            if (sortOrder != null) {
                args.put("sortOrder", sortOrder);
            }
            args.put("instruction", userMessage);
            return RouteResult.forcedTool(forceToolRoute("excel_export_result", args, false));
        }

        if (containsAny(normalized, "汇总", "统计", "分组", "求和", "平均", "按列")) {
            args.put("question", userMessage);
            return RouteResult.forcedTool(forceToolRoute("excel_summarize_columns", args, true));
        }

        args.put("question", userMessage);
        return RouteResult.forcedTool(forceToolRoute("excel_query_file", args, true));
    }

    private RouteResult routeFile(String sessionId, String userMessage, String normalized, SessionState state) {
        if (fileParseService == null || !fileParseService.hasActiveFile(sessionId)) {
            return null;
        }
        if (containsAny(normalized, "excel", "xlsx", "xls", "csv", "表格", "sheet")) {
            return null;
        }

        boolean explicit = containsAny(normalized,
                "文件", "文档", "内容", "总结", "概括", "提取", "分析", "解释",
                "pdf", "word", "doc", "docx", "这份文件", "这个文件", "这份文档", "这个文档");
        boolean continueCurrent = state.taskMode == TaskMode.FILE
                && fileParseService.getActiveFileName(sessionId) != null
                && fileParseService.getActiveFileName(sessionId).equals(state.boundFileName)
                && !looksLikeTopicSwitch(normalized);
        boolean likelyFileFollowUp = continueCurrent && looksLikeFileFollowUp(normalized);
        if (!explicit && !likelyFileFollowUp) {
            return null;
        }

        bindTaskState(state, TaskMode.FILE, fileParseService.getActiveFileName(sessionId));
        JSONObject args = new JSONObject();
        args.put("action", "qa");
        args.put("session_id", sessionId);
        args.put("question", userMessage);
        return RouteResult.forcedTool(forceToolRoute("parse_file", args, false));
    }

    private boolean looksLikeFileFollowUp(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (normalized.length() <= 24) {
            return true;
        }
        return containsAny(normalized,
                "第一周", "第二周", "第三周", "第四周", "第1周", "第2周", "第3周", "第4周",
                "哪天", "哪些", "什么", "几点", "哪里", "安排", "课程", "老师", "地点",
                "周一", "周二", "周三", "周四", "周五", "周六", "周日");
    }

    private ToolIntentRouter.ToolRoute forceToolRoute(String functionName, JSONObject args, boolean renderWithModel) {
        return new ToolIntentRouter.ToolRoute(
                functionName,
                args,
                renderWithModel,
                ToolIntentRouter.RouteMode.FORCE_TOOL,
                java.util.List.of(functionName)
        );
    }

    private void bindTaskState(SessionState state, TaskMode mode, String fileName) {
        state.taskMode = mode;
        state.boundFileName = fileName;
        state.lastIntentAt = System.currentTimeMillis();
    }

    private void clearTaskState(SessionState state) {
        state.taskMode = TaskMode.NORMAL;
        state.boundFileName = null;
        state.lastIntentAt = System.currentTimeMillis();
    }

    private void expireTaskStateIfNeeded(SessionState state) {
        if (state.taskMode != TaskMode.NORMAL
                && System.currentTimeMillis() - state.lastIntentAt > TASK_STATE_TTL_MILLIS) {
            clearTaskState(state);
        }
    }

    private String extractExcelWorkbook(String sessionId, String userMessage) {
        String explicit = firstNonBlank(extractExcelPath(userMessage), extractExcelFileName(userMessage));
        if (explicit != null) {
            return explicit;
        }
        String active = fileParseService == null ? null : fileParseService.getActiveFileName(sessionId);
        if (active != null && excelMcpService != null && excelMcpService.supportsWorkbook(active)) {
            return active;
        }
        String latest = fileParseService == null ? null : fileParseService.getLatestSessionFileName(sessionId);
        if (latest != null && excelMcpService != null && excelMcpService.supportsWorkbook(latest)) {
            return latest;
        }
        return null;
    }

    private String extractExcelPath(String text) {
        if (text == null) {
            return null;
        }
        for (String token : text.split("\\s+")) {
            if (token.matches("(?i).+\\.(xlsx|xls|csv)$")) {
                return token.replaceAll("[《》\"'，。；;]$", "");
            }
        }
        return null;
    }

    private String extractExcelFileName(String text) {
        if (text == null) {
            return null;
        }
        for (String token : text.split("\\s+")) {
            if (token.matches("(?i)[^/\\\\]+\\.(xlsx|xls|csv)$")) {
                return token.replaceAll("[《》\"'，。；;]$", "");
            }
        }
        return null;
    }

    private String extractExcelSheetName(String text) {
        return extractAfterKeyword(text, "sheet", "工作表");
    }

    private String extractTargetSheetName(String text) {
        return extractAfterKeyword(text, "导出到", "另存为", "输出到");
    }

    private String extractOutputFile(String text) {
        if (text == null) {
            return null;
        }
        for (String token : text.split("\\s+")) {
            if (token.matches("(?i).+\\.(xlsx|xls|csv)$")) {
                return token.replaceAll("[《》\"'，。；;]$", "");
            }
        }
        return null;
    }

    private JSONArray extractSelectedColumns(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String column = extractSingleColumnName(text);
        if (column == null || column.isBlank()) {
            return null;
        }

        JSONArray columns = new JSONArray();
        columns.add(column.trim());
        return columns;
    }

    private String extractSingleColumnName(String text) {
        String normalized = text == null ? "" : text.trim();
        if (!containsAny(normalized, "\u4e00\u5217", "\u5355\u5217", "\u5217\u6570\u636e", "\u8fd9\u4e00\u5217", "\u90a3\u4e00\u5217")) {
            return null;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?:\\u5bfc\\u51fa|\\u53ea\\u5bfc\\u51fa|\\u63d0\\u53d6|\\u8f93\\u51fa|\\u751f\\u6210)?\\s*([\\p{IsHan}\\p{L}\\p{N}_-]{1,20}?)(?:\\u8fd9\\u4e00\\u5217\\u6570\\u636e|\\u8fd9\\u4e00\\u5217|\\u90a3\\u4e00\\u5217\\u6570\\u636e|\\u90a3\\u4e00\\u5217|\\u4e00\\u5217\\u6570\\u636e|\\u4e00\\u5217|\\u5217\\u6570\\u636e|\\u5217|\\u6570\\u636e)?$");
        java.util.regex.Matcher matcher = pattern.matcher(normalized);
        if (matcher.find()) {
            String candidate = trimColumnPhrase(matcher.group(1));
            if (candidate != null) {
                return candidate;
            }
        }

        String quoted = extractQuotedText(normalized);
        return trimColumnPhrase(quoted);
    }

    private String trimColumnPhrase(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim()
                .replaceAll("(\\u8fd9\\u4e00|\\u90a3\\u4e00)$", "")
                .replaceAll("(\\u8fd9\\u4e00\\u5217\\u6570\\u636e|\\u8fd9\\u4e00\\u5217|\\u90a3\\u4e00\\u5217\\u6570\\u636e|\\u90a3\\u4e00\\u5217|\\u4e00\\u5217\\u6570\\u636e|\\u4e00\\u5217|\\u5217\\u6570\\u636e|\\u5217|\\u6570\\u636e)$", "")
                .replaceAll("^(\\u5bfc\\u51fa|\\u53ea\\u5bfc\\u51fa|\\u63d0\\u53d6|\\u8f93\\u51fa|\\u751f\\u6210)", "")
                .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String extractQuotedText(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\u201c\\\"\\u300a](.+?)[\\u201d\\\"\\u300b]")
                .matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String extractExcelRowFilter(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "([\\p{IsHan}\\p{L}\\p{N}_-]{1,20})\\s*(大于等于|小于等于|不等于|大于|小于|等于|包含|不包含|>=|<=|==|=|>|<)\\s*([\\p{IsHan}\\p{L}\\p{N}_.%-]{1,40})"
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String column = trimExcelFieldName(matcher.group(1));
            String operator = normalizeExcelFilterOperator(matcher.group(2));
            String value = matcher.group(3) == null ? null : matcher.group(3).trim();
            if (column == null || operator == null || value == null || value.isBlank()) {
                continue;
            }
            if (looksLikeExcelSortPhrase(column) || looksLikeExcelSortPhrase(value)) {
                continue;
            }
            return column + operator + value;
        }
        return null;
    }

    private String extractExcelSortColumn(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?:按|按照|根据)\\s*([\\p{IsHan}\\p{L}\\p{N}_-]{1,20})\\s*(?:升序|降序|从小到大|从大到小|排序)?"
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);
        return matcher.find() ? trimExcelFieldName(matcher.group(1)) : null;
    }

    private String extractExcelSortOrder(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.contains("降序") || text.contains("从大到小") || text.toLowerCase().contains("desc")) {
            return "desc";
        }
        if (text.contains("升序") || text.contains("从小到大") || text.toLowerCase().contains("asc")) {
            return "asc";
        }
        return null;
    }

    private String normalizeExcelFilterOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return null;
        }
        return switch (operator.trim()) {
            case "大于" -> ">";
            case "小于" -> "<";
            case "大于等于" -> ">=";
            case "小于等于" -> "<=";
            case "等于" -> "=";
            case "不等于" -> "!=";
            case "包含" -> "包含";
            case "不包含" -> "不包含";
            case ">=", "<=", "==", "=", ">", "<" -> operator.trim();
            default -> null;
        };
    }

    private String trimExcelFieldName(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim()
                .replaceAll("^(找出|筛选|过滤|查询|保留)", "")
                .replaceAll("(的数据|的数据行|的数据记录|的数据结果|的数据内容)$", "")
                .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private boolean looksLikeExcelSortPhrase(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.contains("升序")
                || value.contains("降序")
                || value.contains("排序")
                || value.contains("从小到大")
                || value.contains("从大到小");
    }

    private String extractAfterKeyword(String text, String... keywords) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        for (String keyword : keywords) {
            int idx = normalized.indexOf(keyword);
            if (idx >= 0) {
                String tail = normalized.substring(idx + keyword.length()).trim();
                if (!tail.isEmpty()) {
                    return tail.replaceAll("^[：:\\s]+", "").replaceAll("[《》\"'，。；;]+$", "");
                }
            }
        }
        return null;
    }

    private boolean hasExcelIntentKeywords(String normalized) {
        return containsAny(normalized, "excel", "xlsx", "xls", "csv", "表格", "工作表", "sheet", "读取", "查询", "看下数据", "回答问题");
    }

    private boolean looksLikeTopicSwitch(String normalized) {
        return containsAny(normalized, "换个话题", "先不看这个", "不看这个", "另外", "重新开始", "暂时不看");
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalizeExcelRowFilter(String extracted, String userMessage) {
        String candidate = firstNonBlank(extracted, findRegexGroup(userMessage,
                "([\\p{IsHan}\\p{L}\\p{N}_-]{1,20}\\s*(?:大于等于|小于等于|不等于|大于|小于|等于|>=|<=|!=|==|=|>|<)\\s*[\\p{IsHan}\\p{L}\\p{N}_.%,-]{1,40})",
                1));
        if (candidate == null) {
            return null;
        }
        String normalized = candidate
                .replace("大于等于", ">=")
                .replace("小于等于", "<=")
                .replace("不等于", "!=")
                .replace("大于", ">")
                .replace("小于", "<")
                .replace("等于", "=")
                .replaceAll("\\s+", "")
                .replaceAll("(的|的数据|条|条数据)$", "");
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeExcelSortColumn(String extracted, String userMessage) {
        String candidate = firstNonBlank(extracted, findRegexGroup(userMessage,
                "(?:按照|按|根据)\\s*([\\p{IsHan}\\p{L}\\p{N}_-]{1,20})\\s*(?:升序|降序|从小到大|从大到小|排序)?",
                1));
        if (candidate == null) {
            return null;
        }
        String normalized = candidate
                .replaceAll("^(照|按照|按|根据)", "")
                .replaceAll("(升序|降序|排序|的)$", "")
                .replaceAll("(这一列|那一列|这列|那列|列|数据)$", "")
                .trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeExcelSortOrder(String extracted, String userMessage) {
        if (extracted != null && !extracted.isBlank()) {
            return extracted;
        }
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        if (userMessage.contains("降序") || userMessage.contains("从大到小") || userMessage.toLowerCase().contains("desc")) {
            return "desc";
        }
        if (userMessage.contains("升序") || userMessage.contains("从小到大") || userMessage.toLowerCase().contains("asc")) {
            return "asc";
        }
        return null;
    }

    private String findRegexGroup(String text, String regex, int group) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(group);
        return value == null || value.isBlank() ? null : value.trim();
    }
}

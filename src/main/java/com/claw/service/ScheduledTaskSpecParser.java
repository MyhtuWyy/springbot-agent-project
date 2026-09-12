package com.claw.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScheduledTaskSpecParser {
    private static final Pattern WEATHER_CITY_PATTERN = Pattern.compile("([\\p{IsHan}A-Za-z0-9]{2,20})\\u5929\\u6c14");
    private static final Pattern LOGISTICS_NUMBER_PATTERN = Pattern.compile("(?:\\u5355\\u53f7|\\u8fd0\\u5355\\u53f7|\\u5feb\\u9012\\u5355\\u53f7)[:\\s]*([A-Za-z0-9-]{8,30})");
    private static final Pattern TRACKING_NUMBER_PATTERN = Pattern.compile("[A-Za-z0-9-]{8,30}");

    private static final List<String> NEWS_KEYWORDS = List.of("\u65b0\u95fb", "\u8d44\u8baf", "\u70ed\u70b9", "\u5934\u6761", "\u65f6\u4e8b", "\u6700\u65b0\u6d88\u606f");
    private static final List<String> WEATHER_KEYWORDS = List.of("\u5929\u6c14", "\u6c14\u6e29", "\u6e29\u5ea6", "\u4e0b\u96e8", "\u4e0b\u96ea");
    private static final List<String> LOGISTICS_KEYWORDS = List.of("\u7269\u6d41", "\u5feb\u9012", "\u5305\u88f9", "\u5355\u53f7", "\u8fd0\u5355", "\u7b7e\u6536");

    private final ReminderTimeParser reminderTimeParser;

    public ScheduledTaskSpecParser(ReminderTimeParser reminderTimeParser) {
        this.reminderTimeParser = reminderTimeParser;
    }

    public ParsedScheduledTask parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("\u4efb\u52a1\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a");
        }

        ReminderTimeParser.ParsedReminder schedule = reminderTimeParser.parse(text);
        List<ScheduledTaskAction> actions = new ArrayList<>();
        String normalized = normalize(text);

        if (containsAny(normalized, NEWS_KEYWORDS)) {
            JSONObject args = new JSONObject();
            String keyword = extractNewsKeyword(text);
            if (keyword != null && !keyword.isBlank()) {
                args.put("keyword", keyword);
            }
            actions.add(new ScheduledTaskAction("search_news", "\u65b0\u95fb", args));
        }

        if (containsAny(normalized, WEATHER_KEYWORDS)) {
            String city = extractWeatherCity(text);
            if (city == null || city.isBlank()) {
                throw new IllegalArgumentException("\u8bf7\u8865\u5145\u5929\u6c14\u57ce\u5e02\uff0c\u4f8b\u5982\uff1a\u6bcf\u5929\u65e9\u4e0a7\u70b9\u63a8\u9001\u676d\u5dde\u5929\u6c14");
            }
            JSONObject args = new JSONObject();
            args.put("city", city);
            actions.add(new ScheduledTaskAction("get_weather", "\u5929\u6c14", args));
        }

        if (containsAny(normalized, LOGISTICS_KEYWORDS) || extractTrackingNumber(text) != null) {
            String number = firstNonBlank(extractLogisticsNumber(text), extractTrackingNumber(text));
            if (number == null || number.isBlank()) {
                throw new IllegalArgumentException("\u8bf7\u8865\u5145\u7269\u6d41\u5355\u53f7\uff0c\u4f8b\u5982\uff1a\u660e\u592915\u70b9\u5e2e\u6211\u67e5\u8be2\u7269\u6d41\u5355\u53f7XXXX");
            }
            JSONObject args = new JSONObject();
            args.put("action", "query");
            args.put("number", number);
            String company = extractExplicitLogisticsCompany(text);
            if (company != null) {
                args.put("company", company);
            }
            String phoneLast4 = extractPhoneLast4(text);
            if (phoneLast4 != null) {
                args.put("sender_phone_last4", phoneLast4);
            }
            actions.add(new ScheduledTaskAction("query_logistics", "\u7269\u6d41", args));
        }

        if (actions.isEmpty()) {
            throw new IllegalArgumentException("\u6682\u4e0d\u652f\u6301\u8be5\u5b9a\u65f6\u5185\u5bb9\uff0c\u8bf7\u8865\u5145\u65b0\u95fb\u3001\u5929\u6c14\u6216\u7269\u6d41\u7b49\u53ef\u6267\u884c\u52a8\u4f5c");
        }

        JSONObject workflow = new JSONObject();
        workflow.put("version", 1);
        workflow.put("sourceText", text.trim());
        workflow.put("scheduleType", schedule.taskType());
        workflow.put("ruleText", schedule.ruleText());
        workflow.put("title", schedule.title());
        workflow.put("timezone", schedule.timezone());
        workflow.put("actions", toJson(actions));

        return new ParsedScheduledTask(schedule, actions, workflow.toJSONString(), buildSummary(actions));
    }

    private JSONArray toJson(List<ScheduledTaskAction> actions) {
        JSONArray array = new JSONArray();
        for (ScheduledTaskAction action : actions) {
            JSONObject item = new JSONObject();
            item.put("toolName", action.toolName());
            item.put("label", action.label());
            item.put("arguments", action.arguments());
            array.add(item);
        }
        return array;
    }

    private String buildSummary(List<ScheduledTaskAction> actions) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) {
                sb.append(" + ");
            }
            sb.append(actions.get(i).label());
        }
        return sb.toString();
    }

    private String extractNewsKeyword(String text) {
        String cleaned = text
                .replace("\u63a8\u9001", " ")
                .replace("\u67e5\u8be2", " ")
                .replace("\u63d0\u9192", " ")
                .replace("\u6700\u65b0", " ")
                .replace("\u4eca\u5929", " ")
                .replace("\u5f53\u65e5", " ")
                .replaceAll("[,，。！？;；+]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        for (String keyword : NEWS_KEYWORDS) {
            cleaned = cleaned.replace(keyword, " ");
        }
        cleaned = cleaned.trim();
        return cleaned.isBlank() ? "" : cleaned;
    }

    private String extractWeatherCity(String text) {
        Matcher matcher = WEATHER_CITY_PATTERN.matcher(text);
        if (matcher.find()) {
            String city = matcher.group(1).trim();
            return city.length() <= 1 ? null : city;
        }
        return null;
    }

    private String extractLogisticsNumber(String text) {
        Matcher matcher = LOGISTICS_NUMBER_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractExplicitLogisticsCompany(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = normalize(text);
        if (!normalized.contains("快递") && !normalized.contains("物流") && !normalized.contains("包裹")) {
            return null;
        }
        return extractLogisticsCompany(text);
    }

    private String extractLogisticsCompany(String text) {
        String normalized = normalize(text);
        for (String company : List.of("\u987a\u4e30", "\u4e2d\u901a", "\u5706\u901a", "\u7533\u901a", "\u97f5\u8fbe", "\u4eac\u4e1c", "ems", "\u6781\u5154", "\u5fb7\u90a6", "\u83dc\u9e1f", "\u767e\u4e16", "\u5929\u5929")) {
            if (normalized.contains(company.toLowerCase(Locale.ROOT))) {
                return company;
            }
        }
        return null;
    }

    private String extractPhoneLast4(String text) {
        Matcher matcher = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)").matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractTrackingNumber(String text) {
        Matcher matcher = TRACKING_NUMBER_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String candidate = matcher.group().trim();
            if (candidate.length() >= 8 && !candidate.matches("\\d{4}")) {
                return candidate;
            }
        }
        return null;
    }

    private boolean containsAny(String normalized, List<String> keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public record ScheduledTaskAction(String toolName, String label, JSONObject arguments) {
    }

    public record ParsedScheduledTask(ReminderTimeParser.ParsedReminder schedule,
                                      List<ScheduledTaskAction> actions,
                                      String workflowSpecJson,
                                      String actionSummary) {
    }
}

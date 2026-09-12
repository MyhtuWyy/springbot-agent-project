package com.claw.service;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReminderTimeParser {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Pattern MINUTES_AFTER = Pattern.compile("(\\d{1,3})\\s*分钟后");
    private static final Pattern HOURS_AFTER = Pattern.compile("(\\d{1,3})\\s*小时后");
    private static final Pattern ISO_DATE_TIME = Pattern.compile("(\\d{4}-\\d{1,2}-\\d{1,2})\\s*(\\d{1,2}(?::|点)\\d{1,2}|\\d{1,2}点半|\\d{1,2})");
    private static final Pattern MONTH_DAY_TIME = Pattern.compile("(\\d{1,2})月(\\d{1,2})日?\\s*(\\d{1,2}(?::|点)\\d{1,2}|\\d{1,2}点半|\\d{1,2})?");
    private static final Pattern DAILY = Pattern.compile("(?:每天|每日)\\s*(\\d{1,2}(?::|点)\\d{1,2}|\\d{1,2}点半|\\d{1,2})");
    private static final Pattern WEEKLY = Pattern.compile("每周([一二三四五六日天])\\s*(\\d{1,2}(?::|点)\\d{1,2}|\\d{1,2}点半|\\d{1,2})");
    private static final Pattern TIME_ONLY = Pattern.compile("(?:(凌晨|早上|上午|中午|下午|晚上|傍晚|今晚))?\\s*(\\d{1,2})(?:(?:[:点](\\d{1,2}))|点半)?");

    public ParsedReminder parse(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("提醒内容不能为空");
        }

        Matcher daily = DAILY.matcher(normalized);
        if (daily.find()) {
            LocalTime time = parseTime(daily.group(1), null);
            String cronExpr = String.format("0 %d %d * * *", time.getMinute(), time.getHour());
            return recurring(text, cronExpr, next(cronExpr), "每天 " + formatTime(time));
        }

        Matcher weekly = WEEKLY.matcher(normalized);
        if (weekly.find()) {
            LocalTime time = parseTime(weekly.group(2), null);
            int dayOfWeek = toCronDayOfWeek(weekly.group(1));
            String cronExpr = String.format("0 %d %d * * %d", time.getMinute(), time.getHour(), dayOfWeek);
            return recurring(text, cronExpr, next(cronExpr), "每周" + weekly.group(1) + " " + formatTime(time));
        }

        Matcher minutes = MINUTES_AFTER.matcher(normalized);
        if (minutes.find()) {
            long delta = Long.parseLong(minutes.group(1));
            LocalDateTime triggerAt = LocalDateTime.now(DEFAULT_ZONE).plusMinutes(delta);
            return oneTime(text, triggerAt, minutes.group(1) + "分钟后");
        }

        Matcher hours = HOURS_AFTER.matcher(normalized);
        if (hours.find()) {
            long delta = Long.parseLong(hours.group(1));
            LocalDateTime triggerAt = LocalDateTime.now(DEFAULT_ZONE).plusHours(delta);
            return oneTime(text, triggerAt, hours.group(1) + "小时后");
        }

        Matcher iso = ISO_DATE_TIME.matcher(normalized);
        if (iso.find()) {
            LocalDate date = LocalDate.parse(iso.group(1), DateTimeFormatter.ofPattern("yyyy-M-d"));
            LocalTime time = parseTime(iso.group(2), null);
            return oneTime(text, LocalDateTime.of(date, time), iso.group());
        }

        Matcher monthDay = MONTH_DAY_TIME.matcher(normalized);
        if (monthDay.find()) {
            LocalDate date = LocalDate.of(
                    LocalDate.now(DEFAULT_ZONE).getYear(),
                    Integer.parseInt(monthDay.group(1)),
                    Integer.parseInt(monthDay.group(2))
            );
            LocalTime time = monthDay.group(3) == null ? requireTime(normalized) : parseTime(monthDay.group(3), null);
            LocalDateTime triggerAt = LocalDateTime.of(date, time);
            if (!triggerAt.isAfter(LocalDateTime.now(DEFAULT_ZONE))) {
                triggerAt = triggerAt.plusYears(1);
            }
            return oneTime(text, triggerAt, monthDay.group());
        }

        if (normalized.contains("明天") || normalized.contains("后天") || normalized.contains("今天") || normalized.contains("今晚")) {
            LocalDate date = LocalDate.now(DEFAULT_ZONE);
            if (normalized.contains("明天")) {
                date = date.plusDays(1);
            } else if (normalized.contains("后天")) {
                date = date.plusDays(2);
            }
            LocalTime time = requireTime(normalized);
            LocalDateTime triggerAt = LocalDateTime.of(date, time);
            if (normalized.contains("今天") && !triggerAt.isAfter(LocalDateTime.now(DEFAULT_ZONE))) {
                throw new IllegalArgumentException("今天这个时间已经过去了，请换一个未来时间");
            }
            return oneTime(text, triggerAt, normalized.contains("明天") ? "明天" : normalized.contains("后天") ? "后天" : normalized.contains("今晚") ? "今晚" : "今天");
        }

        throw new IllegalArgumentException("我没看懂提醒时间。比如：明天 9点 提醒我开会，或者 每天 8点 打卡");
    }

    public String extractReminderMessage(String text) {
        String result = normalize(text)
                .replace("提醒我", " ")
                .replace("提醒", " ")
                .replace("闹钟", " ")
                .replace("叫我", " ")
                .replace("设个", " ")
                .replace("设置个", " ")
                .replace("分钟后", " ")
                .replace("小时后", " ")
                .replaceAll("每周[一二三四五六日天]", " ")
                .replaceAll("每天|每日", " ")
                .replaceAll("\\d{4}-\\d{1,2}-\\d{1,2}", " ")
                .replaceAll("\\d{1,2}月\\d{1,2}日?", " ")
                .replaceAll("\\d{1,3}\\s*分钟后", " ")
                .replaceAll("\\d{1,3}\\s*小时后", " ")
                .replaceAll("(凌晨|早上|上午|中午|下午|晚上|傍晚|今晚|今天|明天|后天)", " ")
                .replaceAll("\\d{1,2}(?::|点)\\d{1,2}", " ")
                .replaceAll("\\d{1,2}点半", " ")
                .replaceAll("\\d{1,2}点", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return result.isBlank() ? normalize(text) : result;
    }

    public String buildTitle(String message) {
        if (message == null || message.isBlank()) {
            return "提醒";
        }
        return message.length() <= 32 ? message : message.substring(0, 32);
    }

    private ParsedReminder oneTime(String source, LocalDateTime triggerAt, String ruleText) {
        if (!triggerAt.isAfter(LocalDateTime.now(DEFAULT_ZONE))) {
            throw new IllegalArgumentException("提醒时间必须是未来时间");
        }
        String message = extractReminderMessage(source);
        return new ParsedReminder("one_time", triggerAt, null, null, ruleText, message, buildTitle(message), DEFAULT_ZONE.getId());
    }

    private ParsedReminder recurring(String source, String cronExpr, LocalDateTime nextTriggerAt, String ruleText) {
        String message = extractReminderMessage(source);
        return new ParsedReminder("recurring", null, cronExpr, nextTriggerAt, ruleText, message, buildTitle(message), DEFAULT_ZONE.getId());
    }

    private LocalDateTime next(String cronExpr) {
        return CronExpression.parse(cronExpr).next(LocalDateTime.now(DEFAULT_ZONE));
    }

    private LocalTime requireTime(String text) {
        Matcher matcher = TIME_ONLY.matcher(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException("缺少具体时间，比如：9点30 或 18:00");
        }
        return parseTime(matcher.group(0), matcher.group(1));
    }

    private LocalTime parseTime(String text, String prefix) {
        String normalized = normalize(text);
        Matcher matcher = TIME_ONLY.matcher(normalized);
        if (!matcher.find()) {
            throw new IllegalArgumentException("无法识别时间: " + text);
        }
        String effectivePrefix = prefix != null ? prefix : matcher.group(1);
        int hour = Integer.parseInt(matcher.group(2));
        int minute = 0;
        if (matcher.group(3) != null && !matcher.group(3).isBlank()) {
            minute = Integer.parseInt(matcher.group(3));
        } else if (normalized.contains("点半")) {
            minute = 30;
        }

        if ("下午".equals(effectivePrefix) || "晚上".equals(effectivePrefix) || "傍晚".equals(effectivePrefix) || "今晚".equals(effectivePrefix)) {
            if (hour < 12) hour += 12;
        } else if ("中午".equals(effectivePrefix) && hour < 11) {
            hour += 12;
        } else if ("凌晨".equals(effectivePrefix) && hour == 12) {
            hour = 0;
        }

        return LocalTime.of(hour, minute);
    }

    private int toCronDayOfWeek(String weekday) {
        return switch (weekday) {
            case "一" -> DayOfWeek.MONDAY.getValue();
            case "二" -> DayOfWeek.TUESDAY.getValue();
            case "三" -> DayOfWeek.WEDNESDAY.getValue();
            case "四" -> DayOfWeek.THURSDAY.getValue();
            case "五" -> DayOfWeek.FRIDAY.getValue();
            case "六" -> DayOfWeek.SATURDAY.getValue();
            case "日", "天" -> DayOfWeek.SUNDAY.getValue();
            default -> throw new IllegalArgumentException("无法识别星期: " + weekday);
        };
    }

    private String formatTime(LocalTime time) {
        return time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim()
                .replace('：', ':')
                .replace('，', ' ')
                .replace('；', ' ')
                .replace('。', ' ')
                .toLowerCase(Locale.ROOT);
    }

    public record ParsedReminder(
            String taskType,
            LocalDateTime triggerAt,
            String cronExpr,
            LocalDateTime nextTriggerAt,
            String ruleText,
            String message,
            String title,
            String timezone
    ) {
    }
}

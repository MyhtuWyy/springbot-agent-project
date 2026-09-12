package com.claw.service;

import com.claw.entity.ReminderTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReminderMessageRouter {
    private static final Logger log = LoggerFactory.getLogger(ReminderMessageRouter.class);
    private static final Pattern REMINDER_ID_PATTERN = Pattern.compile("(?:提醒id|闹钟id|id)[:：\\s-]*?(\\d{1,12})", Pattern.CASE_INSENSITIVE);

    private final ReminderTimeParser reminderTimeParser;
    private final ReminderTaskService reminderTaskService;
    private final ReminderSchedulerService reminderSchedulerService;

    public ReminderMessageRouter(ReminderTimeParser reminderTimeParser,
                                 ReminderTaskService reminderTaskService,
                                 ReminderSchedulerService reminderSchedulerService) {
        this.reminderTimeParser = reminderTimeParser;
        this.reminderTaskService = reminderTaskService;
        this.reminderSchedulerService = reminderSchedulerService;
    }

    public boolean canRoute(String text) {
        String normalized = normalize(text);
        return normalized.contains("提醒") || normalized.contains("闹钟") || normalized.contains("定时");
    }

    public String route(UserSessionContext context, String text) {
        if (context == null || context.userId() == null || text == null || text.isBlank()) {
            return null;
        }
        if (!canRoute(text)) {
            return null;
        }

        String normalized = normalize(text);
        log.info("reminder route hit: userId={}, sessionId={}, text={}", context.userId(), context.sessionId(), text);
        try {
            if (looksLikeList(normalized)) {
                String reply = renderList(context.userId());
                log.info("reminder list handled: userId={}, countReply={}", context.userId(), reply);
                return reply;
            }
            if (looksLikeCancel(normalized)) {
                Long reminderId = extractReminderId(normalized);
                if (reminderId == null) {
                    return "请带上提醒ID，例如：取消提醒 12";
                }
                ReminderTaskEntity entity = reminderTaskService.cancel(context.userId(), reminderId);
                reminderSchedulerService.cancel(entity.getId());
                log.info("reminder cancelled: userId={}, reminderId={}, title={}", context.userId(), entity.getId(), entity.getTitle());
                return "已取消提醒 #" + entity.getId() + "：" + entity.getTitle();
            }
            return createReminder(context, text);
        } catch (Exception e) {
            log.error("reminder route failed: userId={}, text={}", context.userId(), text, e);
            return "提醒处理失败：" + e.getMessage();
        }
    }

    private String createReminder(UserSessionContext context, String text) {
        ReminderTimeParser.ParsedReminder parsed = reminderTimeParser.parse(text);
        ReminderTaskEntity entity;
        if ("recurring".equalsIgnoreCase(parsed.taskType())) {
            entity = reminderTaskService.createRecurring(
                    context,
                    parsed.title(),
                    parsed.message(),
                    parsed.cronExpr(),
                    parsed.nextTriggerAt(),
                    parsed.timezone()
            );
        } else {
            entity = reminderTaskService.createOneTime(
                    context,
                    parsed.title(),
                    parsed.message(),
                    parsed.triggerAt(),
                    parsed.timezone()
            );
        }
        reminderSchedulerService.schedule(entity);
        log.info("reminder created: id={}, userId={}, type={}, timezone={}, nextTriggerAt={}, rule={}, title={}",
                entity.getId(), context.userId(), entity.getTaskType(), entity.getTimezone(), entity.getNextTriggerAt(), parsed.ruleText(), entity.getTitle());
        return "已创建" + ("recurring".equalsIgnoreCase(parsed.taskType()) ? "周期" : "一次性")
                + "提醒 #" + entity.getId()
                + "，规则：" + parsed.ruleText()
                + "，内容：" + entity.getMessage();
    }

    private String renderList(Long userId) {
        List<ReminderTaskEntity> reminders = reminderTaskService.list(userId);
        if (reminders.isEmpty()) {
            return "当前没有提醒。";
        }
        StringBuilder sb = new StringBuilder("你的提醒：");
        for (ReminderTaskEntity reminder : reminders) {
            sb.append("\n#").append(reminder.getId())
                    .append(" / ").append(reminder.getTaskType())
                    .append(" / ").append(reminder.getStatus())
                    .append(" / ").append(reminder.getTitle())
                    .append(" / ").append(reminder.getNextTriggerAt() != null ? reminder.getNextTriggerAt() : reminder.getTriggerAt());
        }
        return sb.toString();
    }

    private boolean looksLikeList(String text) {
        return text.contains("查看") || text.contains("列表") || text.contains("我的提醒");
    }

    private boolean looksLikeCancel(String text) {
        return text.contains("取消") || text.contains("删除") || text.contains("移除");
    }

    private Long extractReminderId(String text) {
        Matcher matcher = REMINDER_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        Matcher digits = Pattern.compile("(?<!\\d)(\\d{1,12})(?!\\d)").matcher(text);
        return digits.find() ? Long.parseLong(digits.group(1)) : null;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().replace('：', ':').toLowerCase(Locale.ROOT);
    }
}

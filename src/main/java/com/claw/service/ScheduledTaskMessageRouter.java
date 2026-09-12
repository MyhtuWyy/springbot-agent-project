package com.claw.service;

import com.claw.entity.ReminderTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class ScheduledTaskMessageRouter {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskMessageRouter.class);

    private static final List<String> ACTION_KEYWORDS = List.of(
            "\u65b0\u95fb", "\u8d44\u8baf", "\u70ed\u70b9", "\u5934\u6761", "\u65f6\u4e8b",
            "\u5929\u6c14", "\u6c14\u6e29", "\u6e29\u5ea6", "\u4e0b\u96e8", "\u4e0b\u96ea",
            "\u7269\u6d41", "\u5feb\u9012", "\u5305\u88f9", "\u5355\u53f7", "\u8fd0\u5355", "\u7b7e\u6536"
    );

    private static final List<String> SCHEDULE_KEYWORDS = List.of(
            "\u6bcf\u5929", "\u6bcf\u5468", "\u660e\u5929", "\u540e\u5929", "\u4eca\u5929", "\u4eca\u665a",
            "\u65e9\u4e0a", "\u4e0a\u5348", "\u4e2d\u5348", "\u4e0b\u5348", "\u665a\u4e0a",
            "\u70b9", "\u5b9a\u65f6", "\u63d0\u9192", "\u95f9\u949f"
    );
    private static final List<String> EXPLICIT_SCHEDULE_INTENT_KEYWORDS = List.of(
            "\u63d0\u9192", "\u95f9\u949f", "\u5b9a\u65f6", "\u5b9a\u4e2a", "\u8bbe\u4e2a",
            "\u63a8\u9001", "\u64ad\u62a5", "\u8ba2\u9605", "\u6bcf\u5929", "\u6bcf\u5468", "\u6bcf\u6708"
    );
    private static final Pattern RELATIVE_DELAY_PATTERN = Pattern.compile("\\d{1,3}\\s*(?:\u5206\u949f\u540e|\u5c0f\u65f6\u540e)");

    private final ScheduledTaskSpecParser specParser;
    private final ReminderTaskService reminderTaskService;
    private final ReminderSchedulerService reminderSchedulerService;

    public ScheduledTaskMessageRouter(ScheduledTaskSpecParser specParser,
                                      ReminderTaskService reminderTaskService,
                                      ReminderSchedulerService reminderSchedulerService) {
        this.specParser = specParser;
        this.reminderTaskService = reminderTaskService;
        this.reminderSchedulerService = reminderSchedulerService;
    }

    public boolean canRoute(String text) {
        String normalized = normalize(text);
        return containsAny(normalized, ACTION_KEYWORDS)
                && containsScheduleIntent(normalized);
    }

    public String route(UserSessionContext context, String text) {
        if (context == null || context.userId() == null || text == null || text.isBlank()) {
            return null;
        }
        if (!canRoute(text)) {
            return null;
        }

        try {
            ScheduledTaskSpecParser.ParsedScheduledTask parsed = specParser.parse(text);
            ReminderTaskEntity entity;
            if ("recurring".equalsIgnoreCase(parsed.schedule().taskType())) {
                entity = reminderTaskService.createRecurring(
                        context,
                        parsed.schedule().title(),
                        parsed.schedule().message(),
                        parsed.schedule().cronExpr(),
                        parsed.schedule().nextTriggerAt(),
                        parsed.schedule().timezone(),
                        parsed.workflowSpecJson()
                );
            } else {
                entity = reminderTaskService.createOneTime(
                        context,
                        parsed.schedule().title(),
                        parsed.schedule().message(),
                        parsed.schedule().triggerAt(),
                        parsed.schedule().timezone(),
                        parsed.workflowSpecJson()
                );
            }
            reminderSchedulerService.schedule(entity);
            log.info("scheduled workflow created: id={}, userId={}, type={}, rule={}, actions={}",
                    entity.getId(), context.userId(), entity.getTaskType(), parsed.schedule().ruleText(), parsed.actionSummary());
            return "\u5df2\u521b\u5efa" + ("recurring".equalsIgnoreCase(parsed.schedule().taskType()) ? "\u5468\u671f" : "\u4e00\u6b21\u6027")
                    + "\u4efb\u52a1 #" + entity.getId()
                    + "\uff0c\u89c4\u5219\uff1a" + parsed.schedule().ruleText()
                    + "\uff0c\u52a8\u4f5c\uff1a" + parsed.actionSummary();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.info("scheduled workflow route failed, fall back to legacy reminder router. userId={}, text={}", context.userId(), text, e);
            return null;
        }
    }

    private boolean containsAny(String normalized, List<String> keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsScheduleIntent(String normalized) {
        return containsAny(normalized, EXPLICIT_SCHEDULE_INTENT_KEYWORDS)
                || RELATIVE_DELAY_PATTERN.matcher(normalized).find()
                || (containsAny(normalized, SCHEDULE_KEYWORDS) && containsAny(normalized, List.of("\u63d0\u9192", "\u95f9\u949f", "\u63a8\u9001", "\u64ad\u62a5")));
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}

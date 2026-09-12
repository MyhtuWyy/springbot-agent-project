package com.claw.skills;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.entity.ReminderTaskEntity;
import com.claw.service.ReminderTaskService;
import com.claw.service.ScheduledTaskCommandEvent;
import com.claw.service.ScheduledTaskSpecParser;
import com.claw.service.ToolExecutionContextHolder;
import com.claw.service.UserSessionContext;
import com.claw.skills.core.BaseSkill;
import com.claw.skills.core.SkillDefine;
import com.claw.skills.core.SkillRequest;
import com.claw.skills.core.SkillResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
@SkillDefine(
        name = "scheduled_task_skill",
        description = "Create, list, pause, resume and cancel scheduled reminder tasks.",
        timeoutMs = 20000L
)
public class ScheduledTaskSkill implements BaseSkill {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScheduledTaskSpecParser scheduledTaskSpecParser;
    private final ReminderTaskService reminderTaskService;
    private final ApplicationEventPublisher eventPublisher;

    public ScheduledTaskSkill(ScheduledTaskSpecParser scheduledTaskSpecParser,
                              ReminderTaskService reminderTaskService,
                              ApplicationEventPublisher eventPublisher) {
        this.scheduledTaskSpecParser = scheduledTaskSpecParser;
        this.reminderTaskService = reminderTaskService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public String skillName() {
        return "scheduled_task_skill";
    }

    @Override
    public String description() {
        return "Create, list, pause, resume and cancel scheduled reminder tasks.";
    }

    @Override
    public JSONObject parametersSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");

        JSONObject properties = new JSONObject();
        properties.put("action", property("string", "create, list, pause, resume, cancel"));
        properties.put("text", property("string", "Original scheduling request for create action."));
        properties.put("reminderId", property("integer", "Reminder task id for pause, resume or cancel."));
        properties.put("sessionId", property("string", "Optional session id to resolve user context."));

        schema.put("properties", properties);
        schema.put("required", new JSONArray().fluentAdd("action"));
        return schema;
    }

    @Override
    public SkillResult execute(SkillRequest request) {
        JSONObject args = request.arguments();
        String action = trim(args.getString("action"));
        Map<String, Function<JSONObject, SkillResult>> actionHandlers = Map.of(
                "create", ignored -> createTask(args),
                "list", ignored -> listTasks(args),
                "pause", ignored -> pauseTask(args),
                "resume", ignored -> resumeTask(args),
                "cancel", ignored -> cancelTask(args)
        );
        Function<JSONObject, SkillResult> handler = actionHandlers.get(action);
        if (handler == null) {
            return SkillResult.failure(skillName(), "unknown action", "unknown action: " + action, 0L, false);
        }
        return handler.apply(args);
    }

    private SkillResult createTask(JSONObject args) {
        String text = trim(args.getString("text"));
        if (text == null || text.isBlank()) {
            return SkillResult.failure(skillName(), "text is required", "text is required", 0L, false);
        }

        UserSessionContext context = resolveContext(args);
        if (context == null || context.userId() == null) {
            return SkillResult.failure(skillName(), "session context is required", "session context is required", 0L, false);
        }

        ScheduledTaskSpecParser.ParsedScheduledTask parsed = scheduledTaskSpecParser.parse(text);
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
        eventPublisher.publishEvent(ScheduledTaskCommandEvent.schedule(entity));

        JSONObject data = new JSONObject();
        data.put("task", toTaskJson(entity));
        data.put("ruleText", parsed.schedule().ruleText());
        data.put("actionSummary", parsed.actionSummary());
        data.put("workflowSpecJson", parsed.workflowSpecJson());
        return SkillResult.success(skillName(), "scheduled task created", data, 0L);
    }

    private SkillResult listTasks(JSONObject args) {
        UserSessionContext context = resolveContext(args);
        if (context == null || context.userId() == null) {
            return SkillResult.failure(skillName(), "session context is required", "session context is required", 0L, false);
        }

        List<ReminderTaskEntity> tasks = reminderTaskService.list(context.userId());
        JSONArray items = new JSONArray();
        for (ReminderTaskEntity task : tasks) {
            items.add(toTaskJson(task));
        }

        JSONObject data = new JSONObject();
        data.put("tasks", items);
        data.put("count", tasks.size());
        return SkillResult.success(skillName(), "scheduled task list loaded", data, 0L);
    }

    private SkillResult pauseTask(JSONObject args) {
        Long reminderId = args.getLong("reminderId");
        if (reminderId == null) {
            return SkillResult.failure(skillName(), "reminderId is required", "reminderId is required", 0L, false);
        }
        UserSessionContext context = resolveContext(args);
        if (context == null || context.userId() == null) {
            return SkillResult.failure(skillName(), "session context is required", "session context is required", 0L, false);
        }

        ReminderTaskEntity entity = reminderTaskService.pause(context.userId(), reminderId);
        eventPublisher.publishEvent(ScheduledTaskCommandEvent.cancel(entity));
        JSONObject data = new JSONObject();
        data.put("task", toTaskJson(entity));
        return SkillResult.success(skillName(), "scheduled task paused", data, 0L);
    }

    private SkillResult resumeTask(JSONObject args) {
        Long reminderId = args.getLong("reminderId");
        if (reminderId == null) {
            return SkillResult.failure(skillName(), "reminderId is required", "reminderId is required", 0L, false);
        }
        UserSessionContext context = resolveContext(args);
        if (context == null || context.userId() == null) {
            return SkillResult.failure(skillName(), "session context is required", "session context is required", 0L, false);
        }

        ReminderTaskEntity entity = reminderTaskService.resume(context.userId(), reminderId);
        eventPublisher.publishEvent(ScheduledTaskCommandEvent.schedule(entity));
        JSONObject data = new JSONObject();
        data.put("task", toTaskJson(entity));
        return SkillResult.success(skillName(), "scheduled task resumed", data, 0L);
    }

    private SkillResult cancelTask(JSONObject args) {
        Long reminderId = args.getLong("reminderId");
        if (reminderId == null) {
            return SkillResult.failure(skillName(), "reminderId is required", "reminderId is required", 0L, false);
        }
        UserSessionContext context = resolveContext(args);
        if (context == null || context.userId() == null) {
            return SkillResult.failure(skillName(), "session context is required", "session context is required", 0L, false);
        }

        ReminderTaskEntity entity = reminderTaskService.cancel(context.userId(), reminderId);
        eventPublisher.publishEvent(ScheduledTaskCommandEvent.cancel(entity));
        JSONObject data = new JSONObject();
        data.put("task", toTaskJson(entity));
        return SkillResult.success(skillName(), "scheduled task cancelled", data, 0L);
    }

    private UserSessionContext resolveContext(JSONObject args) {
        UserSessionContext context = ToolExecutionContextHolder.get();
        if (context != null && context.userId() != null) {
            return context;
        }
        return UserSessionContext.fromSessionId(trim(args.getString("sessionId")));
    }

    private JSONObject toTaskJson(ReminderTaskEntity task) {
        JSONObject json = new JSONObject();
        json.put("id", task.getId());
        json.put("userId", task.getUserId());
        json.put("channelType", task.getChannelType());
        json.put("channelUserId", task.getChannelUserId());
        json.put("title", task.getTitle());
        json.put("message", task.getMessage());
        json.put("workflowSpecJson", task.getWorkflowSpecJson());
        json.put("taskType", task.getTaskType());
        json.put("status", task.getStatus());
        json.put("triggerAt", format(task.getTriggerAt()));
        json.put("cronExpr", task.getCronExpr());
        json.put("timezone", task.getTimezone());
        json.put("lastTriggeredAt", format(task.getLastTriggeredAt()));
        json.put("nextTriggerAt", format(task.getNextTriggerAt()));
        json.put("lastError", task.getLastError());
        json.put("createdAt", format(task.getCreatedAt()));
        json.put("updatedAt", format(task.getUpdatedAt()));
        return json;
    }

    private String format(java.time.LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private JSONObject property(String type, String description) {
        JSONObject property = new JSONObject();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}

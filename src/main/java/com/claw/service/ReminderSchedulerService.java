package com.claw.service;

import com.claw.entity.ReminderTaskEntity;
import com.claw.repository.ReminderTaskRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class ReminderSchedulerService {
    private static final Logger log = LoggerFactory.getLogger(ReminderSchedulerService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long SEND_WAIT_TIMEOUT_MS = 30_000L;
    private static final long ONE_TIME_RETRY_DELAY_MINUTES = 1L;

    private final TaskScheduler taskScheduler;
    private final ReminderTaskService reminderTaskService;
    private final ReminderTaskRepository reminderTaskRepository;
    private final ScheduledTaskWorkflowService scheduledTaskWorkflowService;
    private final ConversationMemoryService conversationMemoryService;
    private final WeChatManager weChatManager;
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public ReminderSchedulerService(TaskScheduler reminderTaskScheduler,
                                    ReminderTaskService reminderTaskService,
                                    ReminderTaskRepository reminderTaskRepository,
                                    ScheduledTaskWorkflowService scheduledTaskWorkflowService,
                                    ConversationMemoryService conversationMemoryService,
                                    WeChatManager weChatManager) {
        this.taskScheduler = reminderTaskScheduler;
        this.reminderTaskService = reminderTaskService;
        this.reminderTaskRepository = reminderTaskRepository;
        this.scheduledTaskWorkflowService = scheduledTaskWorkflowService;
        this.conversationMemoryService = conversationMemoryService;
        this.weChatManager = weChatManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreSchedules() {
        List<ReminderTaskEntity> tasks = reminderTaskRepository.findByStatusIn(List.of("active"));
        log.info("reminder restore start: activeTasks={}", tasks.size());
        for (ReminderTaskEntity task : tasks) {
            try {
                if (task.getNextTriggerAt() == null && task.getTriggerAt() == null) {
                    log.warn("skip reminder restore because trigger time missing: id={}, type={}, status={}",
                            task.getId(), task.getTaskType(), task.getStatus());
                    continue;
                }
                schedule(task);
            } catch (Exception e) {
                log.error("restore reminder schedule failed: id={}", task.getId(), e);
            }
        }
        log.info("reminder restore done: activeTasks={}", tasks.size());
    }

    public void schedule(ReminderTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        cancel(task.getId());

        ZoneId zone = resolveZone(task);
        ScheduledFuture<?> future = null;
        try {
            if ("recurring".equalsIgnoreCase(task.getTaskType())) {
                String cronExpr = task.getCronExpr();
                if (cronExpr == null || cronExpr.isBlank()) {
                    log.warn("skip recurring reminder scheduling because cron is blank: id={}", task.getId());
                    return;
                }
                CronExpression.parse(cronExpr);
                future = taskScheduler.schedule(() -> executeRecurring(task.getId()), new CronTrigger(cronExpr, zone));
            } else {
                LocalDateTime triggerAt = task.getNextTriggerAt() != null ? task.getNextTriggerAt() : task.getTriggerAt();
                if (triggerAt == null) {
                    log.warn("skip one-time reminder scheduling because triggerAt is blank: id={}", task.getId());
                    return;
                }
                future = taskScheduler.schedule(() -> executeOneTime(task.getId()), triggerAt.atZone(zone).toInstant());
            }
        } catch (Exception e) {
            log.error("reminder scheduling failed: id={}, type={}, cron={}, triggerAt={}, timezone={}",
                    task.getId(), task.getTaskType(), task.getCronExpr(),
                    task.getNextTriggerAt() != null ? task.getNextTriggerAt() : task.getTriggerAt(),
                    task.getTimezone(), e);
            reminderTaskService.markError(task, "schedule failed: " + safeMessage(e));
            return;
        }

        if (future != null) {
            scheduledTasks.put(task.getId(), future);
            log.info("reminder scheduled: id={}, type={}, status={}, timezone={}, nextTriggerAt={}, cron={}",
                    task.getId(), task.getTaskType(), task.getStatus(), zone, task.getNextTriggerAt(), task.getCronExpr());
        }
    }

    public void cancel(Long reminderId) {
        ScheduledFuture<?> future = scheduledTasks.remove(reminderId);
        if (future != null) {
            future.cancel(false);
            log.info("reminder schedule cancelled: id={}", reminderId);
        }
    }

    public void reschedule(ReminderTaskEntity task) {
        schedule(task);
    }

    private void executeOneTime(Long reminderId) {
        executeTask(reminderId, false);
    }

    private void executeRecurring(Long reminderId) {
        executeTask(reminderId, true);
    }

    private void executeTask(Long reminderId, boolean recurring) {
        ReminderTaskEntity task = reminderTaskRepository.findById(reminderId).orElse(null);
        if (task == null) {
            log.warn("reminder execution skipped because task missing: id={}, recurring={}", reminderId, recurring);
            return;
        }
        if (!"active".equalsIgnoreCase(task.getStatus())) {
            log.info("reminder execution skipped because task inactive: id={}, status={}, recurring={}",
                    reminderId, task.getStatus(), recurring);
            return;
        }

        ZoneId zone = resolveZone(task);
        boolean delivered = false;
        log.info("reminder execution hit: id={}, type={}, userId={}, channelUserId={}, timezone={}, nextTriggerAt={}, cron={}",
                task.getId(), task.getTaskType(), task.getUserId(), task.getChannelUserId(),
                zone, task.getNextTriggerAt(), task.getCronExpr());

        try {
            sendReminder(task, zone);
            if (recurring) {
                LocalDateTime next = CronExpression.parse(task.getCronExpr()).next(LocalDateTime.now(zone));
                reminderTaskService.markTriggered(task, next);
                log.info("reminder recurring delivered: id={}, nextTriggerAt={}", task.getId(), next);
            } else {
                reminderTaskService.markTriggered(task, null);
                log.info("reminder one-time delivered: id={}", task.getId());
            }
            delivered = true;
        } catch (Exception e) {
            String error = safeMessage(e);
            log.error("reminder execution failed: id={}, recurring={}, error={}", reminderId, recurring, error, e);
            if (recurring) {
                reminderTaskService.markError(task, error);
            } else {
                LocalDateTime retryAt = LocalDateTime.now(zone).plusMinutes(ONE_TIME_RETRY_DELAY_MINUTES);
                reminderTaskService.markRetry(task, retryAt, error);
                schedule(task);
                log.warn("reminder one-time retry scheduled: id={}, retryAt={}", task.getId(), retryAt);
            }
        } finally {
            if (!recurring && delivered) {
                cancel(reminderId);
            }
        }
    }

    private void sendReminder(ReminderTaskEntity task, ZoneId zone) throws Exception {
        String text = scheduledTaskWorkflowService.buildDeliveryText(task);
        log.info("reminder send start: id={}, toUserId={}, timezone={}, text={}",
                task.getId(), task.getChannelUserId(), zone, text);

        if ("desktop".equalsIgnoreCase(task.getChannelType())) {
            if (task.getChannelUserId() == null || task.getChannelUserId().isBlank()) {
                throw new IllegalStateException("desktop session is missing");
            }
            conversationMemoryService.addAssistantMessage(task.getChannelUserId(), text);
            log.info("reminder delivered to desktop session: id={}, sessionId={}", task.getId(), task.getChannelUserId());
            return;
        }

        boolean running = weChatManager.isRunning();
        if (!running) {
            log.warn("wechat bot not running before reminder send, waiting: id={}, toUserId={}",
                    task.getId(), task.getChannelUserId());
            running = weChatManager.waitUntilRunning(SEND_WAIT_TIMEOUT_MS);
        }
        if (!running) {
            throw new IllegalStateException("WeChat bot is not running");
        }

        weChatManager.sendMessage(task.getChannelUserId(), text);
        log.info("reminder send success: id={}, toUserId={}", task.getId(), task.getChannelUserId());
    }

    private ZoneId resolveZone(ReminderTaskEntity task) {
        try {
            if (task != null && task.getTimezone() != null && !task.getTimezone().isBlank()) {
                return ZoneId.of(task.getTimezone().trim());
            }
        } catch (Exception e) {
            log.warn("invalid reminder timezone, fallback to default: id={}, timezone={}",
                    task != null ? task.getId() : null,
                    task != null ? task.getTimezone() : null,
                    e);
        }
        return DEFAULT_ZONE;
    }

    private String safeMessage(Exception e) {
        return e == null || e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    @PreDestroy
    public void shutdown() {
        for (ScheduledFuture<?> future : scheduledTasks.values()) {
            future.cancel(false);
        }
        scheduledTasks.clear();
        log.info("reminder scheduler shutdown");
    }
}

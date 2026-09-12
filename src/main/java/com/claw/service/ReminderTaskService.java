package com.claw.service;

import com.claw.entity.ReminderTaskEntity;
import com.claw.repository.ReminderTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class ReminderTaskService {
    private static final Logger log = LoggerFactory.getLogger(ReminderTaskService.class);
    private final ReminderTaskRepository reminderTaskRepository;

    public ReminderTaskService(ReminderTaskRepository reminderTaskRepository) {
        this.reminderTaskRepository = reminderTaskRepository;
    }

    @Transactional
    public ReminderTaskEntity createOneTime(UserSessionContext context,
                                            String title,
                                            String message,
                                            LocalDateTime triggerAt,
                                            String timezone) {
        return createOneTime(context, title, message, triggerAt, timezone, null);
    }

    @Transactional
    public ReminderTaskEntity createOneTime(UserSessionContext context,
                                            String title,
                                            String message,
                                            LocalDateTime triggerAt,
                                            String timezone,
                                            String workflowSpecJson) {
        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setUserId(context.userId());
        entity.setChannelType(context.channelType());
        entity.setChannelUserId(context.channelUserId());
        entity.setTitle(title);
        entity.setMessage(message);
        entity.setWorkflowSpecJson(workflowSpecJson);
        entity.setTaskType("one_time");
        entity.setStatus("active");
        entity.setTriggerAt(triggerAt);
        entity.setTimezone(normalizeTimezone(timezone));
        entity.setNextTriggerAt(triggerAt);
        ReminderTaskEntity saved = reminderTaskRepository.save(entity);
        log.info("reminder task created: id={}, type=one_time, userId={}, nextTriggerAt={}, timezone={}", saved.getId(), saved.getUserId(), saved.getNextTriggerAt(), saved.getTimezone());
        return saved;
    }

    @Transactional
    public ReminderTaskEntity createRecurring(UserSessionContext context,
                                              String title,
                                              String message,
                                              String cronExpr,
                                              LocalDateTime nextTriggerAt,
                                              String timezone) {
        return createRecurring(context, title, message, cronExpr, nextTriggerAt, timezone, null);
    }

    @Transactional
    public ReminderTaskEntity createRecurring(UserSessionContext context,
                                              String title,
                                              String message,
                                              String cronExpr,
                                              LocalDateTime nextTriggerAt,
                                              String timezone,
                                              String workflowSpecJson) {
        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setUserId(context.userId());
        entity.setChannelType(context.channelType());
        entity.setChannelUserId(context.channelUserId());
        entity.setTitle(title);
        entity.setMessage(message);
        entity.setWorkflowSpecJson(workflowSpecJson);
        entity.setTaskType("recurring");
        entity.setStatus("active");
        entity.setCronExpr(cronExpr);
        entity.setTimezone(normalizeTimezone(timezone));
        entity.setNextTriggerAt(nextTriggerAt);
        ReminderTaskEntity saved = reminderTaskRepository.save(entity);
        log.info("reminder task created: id={}, type=recurring, userId={}, nextTriggerAt={}, timezone={}, cron={}", saved.getId(), saved.getUserId(), saved.getNextTriggerAt(), saved.getTimezone(), saved.getCronExpr());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ReminderTaskEntity> list(Long userId) {
        return reminderTaskRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public ReminderTaskEntity cancel(Long userId, Long reminderId) {
        ReminderTaskEntity entity = requireOwnedReminder(userId, reminderId);
        entity.setStatus("cancelled");
        entity.setNextTriggerAt(null);
        ReminderTaskEntity saved = reminderTaskRepository.save(entity);
        log.info("reminder task cancelled: id={}, userId={}", saved.getId(), saved.getUserId());
        return saved;
    }

    @Transactional
    public ReminderTaskEntity pause(Long userId, Long reminderId) {
        ReminderTaskEntity entity = requireOwnedReminder(userId, reminderId);
        entity.setStatus("paused");
        ReminderTaskEntity saved = reminderTaskRepository.save(entity);
        log.info("reminder task paused: id={}, userId={}", saved.getId(), saved.getUserId());
        return saved;
    }

    @Transactional
    public ReminderTaskEntity resume(Long userId, Long reminderId) {
        ReminderTaskEntity entity = requireOwnedReminder(userId, reminderId);
        entity.setStatus("active");
        ReminderTaskEntity saved = reminderTaskRepository.save(entity);
        log.info("reminder task resumed: id={}, userId={}", saved.getId(), saved.getUserId());
        return saved;
    }

    @Transactional
    public ReminderTaskEntity markTriggered(ReminderTaskEntity entity, LocalDateTime nextTriggerAt) {
        entity.setLastTriggeredAt(LocalDateTime.now(resolveZone(entity)));
        entity.setNextTriggerAt(nextTriggerAt);
        if (nextTriggerAt == null) {
            entity.setStatus("done");
        }
        ReminderTaskEntity saved = reminderTaskRepository.save(entity);
        log.info("reminder task triggered: id={}, userId={}, nextTriggerAt={}, status={}", saved.getId(), saved.getUserId(), saved.getNextTriggerAt(), saved.getStatus());
        return saved;
    }

    @Transactional
    public ReminderTaskEntity markError(ReminderTaskEntity entity, String error) {
        entity.setLastError(error);
        ReminderTaskEntity saved = reminderTaskRepository.save(entity);
        log.warn("reminder task error: id={}, userId={}, error={}", saved.getId(), saved.getUserId(), error);
        return saved;
    }

    @Transactional
    public ReminderTaskEntity markRetry(ReminderTaskEntity entity, LocalDateTime nextTriggerAt, String error) {
        entity.setLastError(error);
        entity.setNextTriggerAt(nextTriggerAt);
        entity.setStatus("active");
        ReminderTaskEntity saved = reminderTaskRepository.save(entity);
        log.warn("reminder task retry scheduled: id={}, userId={}, nextTriggerAt={}, error={}", saved.getId(), saved.getUserId(), nextTriggerAt, error);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ReminderTaskEntity> findRunnableTasks() {
        return reminderTaskRepository.findByStatusInAndNextTriggerAtAfter(List.of("active"), LocalDateTime.MIN);
    }

    @Transactional(readOnly = true)
    public ReminderTaskEntity requireOwnedReminder(Long userId, Long reminderId) {
        ReminderTaskEntity entity = reminderTaskRepository.findById(reminderId)
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在: " + reminderId));
        if (!entity.getUserId().equals(userId)) {
            throw new IllegalStateException("这个提醒不属于当前用户，不能操作。");
        }
        return entity;
    }

    private String normalizeTimezone(String timezone) {
        return timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.trim();
    }

    private ZoneId resolveZone(ReminderTaskEntity entity) {
        try {
            if (entity != null && entity.getTimezone() != null && !entity.getTimezone().isBlank()) {
                return ZoneId.of(entity.getTimezone().trim());
            }
        } catch (Exception e) {
            log.warn("invalid reminder timezone on task, fallback to system default: id={}, timezone={}",
                    entity != null ? entity.getId() : null,
                    entity != null ? entity.getTimezone() : null,
                    e);
        }
        return ZoneId.systemDefault();
    }
}

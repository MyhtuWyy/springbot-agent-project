package com.claw.service;

import com.claw.entity.ReminderTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class ScheduledTaskCommandListener {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskCommandListener.class);

    private final ReminderSchedulerService reminderSchedulerService;

    public ScheduledTaskCommandListener(ReminderSchedulerService reminderSchedulerService) {
        this.reminderSchedulerService = reminderSchedulerService;
    }

    @EventListener
    public void onScheduledTaskCommand(ScheduledTaskCommandEvent event) {
        if (event == null || event.task() == null) {
            return;
        }
        ReminderTaskEntity task = event.task();
        if ("cancel".equalsIgnoreCase(event.action())) {
            reminderSchedulerService.cancel(task.getId());
            log.info("scheduled task command handled: action=cancel, id={}", task.getId());
            return;
        }
        if ("schedule".equalsIgnoreCase(event.action())) {
            reminderSchedulerService.schedule(task);
            log.info("scheduled task command handled: action=schedule, id={}", task.getId());
        }
    }
}

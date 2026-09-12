package com.claw.service;

import com.claw.entity.ReminderTaskEntity;

public record ScheduledTaskCommandEvent(String action, ReminderTaskEntity task) {
    public static ScheduledTaskCommandEvent schedule(ReminderTaskEntity task) {
        return new ScheduledTaskCommandEvent("schedule", task);
    }

    public static ScheduledTaskCommandEvent cancel(ReminderTaskEntity task) {
        return new ScheduledTaskCommandEvent("cancel", task);
    }
}

package com.claw.service;

import com.claw.entity.ReminderTaskEntity;
import org.springframework.stereotype.Service;

@Service
public class ScheduledTaskWorkflowService {
    private final ScheduledTaskAgentService scheduledTaskAgentService;

    public ScheduledTaskWorkflowService(ScheduledTaskAgentService scheduledTaskAgentService) {
        this.scheduledTaskAgentService = scheduledTaskAgentService;
    }

    public String buildDeliveryText(ReminderTaskEntity task) {
        return scheduledTaskAgentService.execute(task);
    }
}

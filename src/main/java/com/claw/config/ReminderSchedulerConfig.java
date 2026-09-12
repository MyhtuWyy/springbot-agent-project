package com.claw.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ReminderSchedulerConfig {
    @Bean
    public TaskScheduler reminderTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("reminder-task-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(t -> org.slf4j.LoggerFactory.getLogger(ReminderSchedulerConfig.class)
                .error("unhandled reminder scheduler error", t));
        scheduler.initialize();
        return scheduler;
    }
}

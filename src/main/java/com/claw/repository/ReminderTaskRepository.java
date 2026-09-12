package com.claw.repository;

import com.claw.entity.ReminderTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReminderTaskRepository extends JpaRepository<ReminderTaskEntity, Long> {
    List<ReminderTaskEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<ReminderTaskEntity> findByStatusIn(List<String> statuses);

    List<ReminderTaskEntity> findByStatusInAndNextTriggerAtAfter(List<String> statuses, LocalDateTime nextTriggerAt);
}

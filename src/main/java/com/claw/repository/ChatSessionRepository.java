package com.claw.repository;

import com.claw.entity.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {
    List<ChatSessionEntity> findTop20ByUserIdOrderByLastActiveDesc(Long userId);
}

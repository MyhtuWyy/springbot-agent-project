package com.claw.repository;

import com.claw.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    List<ChatMessageEntity> findTop20BySessionIdOrderByCreatedAtDesc(String sessionId);

    ChatMessageEntity findTop1BySessionIdOrderByCreatedAtDesc(String sessionId);

    ChatMessageEntity findTop1BySessionIdAndRoleAndMessageTypeOrderByCreatedAtDesc(
            String sessionId, String role, String messageType);

    void deleteBySessionId(String sessionId);
}

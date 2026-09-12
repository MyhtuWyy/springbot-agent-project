package com.claw.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 对话消息持久化实体。
 * 对应 chat_message 表，存储每一轮对话的 user/assistant 消息。
 */
@Entity
@Table(name = "chat_message", indexes = {
        @Index(name = "idx_session_time", columnList = "session_id, created_at")
})
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "message_type", nullable = false, length = 16)
    private String messageType;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ChatMessageEntity() {
    }

    public ChatMessageEntity(String sessionId, String role, String content) {
        this(sessionId, role, content, "normal");
    }

    public ChatMessageEntity(String sessionId, String role, String content, String messageType) {
        this(sessionId, null, role, content, messageType);
    }

    public ChatMessageEntity(String sessionId, Long userId, String role, String content, String messageType) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.messageType = messageType == null || messageType.isBlank() ? "normal" : messageType;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    public void prePersist() {
        if (messageType == null || messageType.isBlank()) {
            messageType = "normal";
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

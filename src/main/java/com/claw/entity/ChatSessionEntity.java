package com.claw.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_session")
public class ChatSessionEntity {

    @Id
    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "channel_type", length = 32)
    private String channelType;

    @Column(name = "channel_user_id", length = 128)
    private String channelUserId;

    @Column(name = "last_active", nullable = false)
    private LocalDateTime lastActive;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "current_mode", nullable = false, length = 32)
    private String currentMode;

    @Column(name = "emotion_phase", nullable = false, length = 32)
    private String emotionPhase;

    @Column(name = "event_a", columnDefinition = "TEXT")
    private String eventA;

    @Column(name = "belief_b", columnDefinition = "TEXT")
    private String beliefB;

    @Column(name = "emotion_c", columnDefinition = "TEXT")
    private String emotionC;

    @Column(name = "dispute_d", columnDefinition = "TEXT")
    private String disputeD;

    @Column(name = "new_belief_e", columnDefinition = "TEXT")
    private String newBeliefE;

    @Column(name = "travel_context", columnDefinition = "LONGTEXT")
    private String travelContext;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ChatSessionEntity() {
    }

    public ChatSessionEntity(String sessionId) {
        this.sessionId = sessionId;
        this.lastActive = LocalDateTime.now();
        this.messageCount = 0;
        this.currentMode = "normal";
        this.emotionPhase = "LISTENING";
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (lastActive == null) {
            lastActive = now;
        }
        if (currentMode == null || currentMode.isBlank()) {
            currentMode = "normal";
        }
        if (emotionPhase == null || emotionPhase.isBlank()) {
            emotionPhase = "LISTENING";
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        if (lastActive == null) {
            lastActive = LocalDateTime.now();
        }
        if (currentMode == null || currentMode.isBlank()) {
            currentMode = "normal";
        }
        if (emotionPhase == null || emotionPhase.isBlank()) {
            emotionPhase = "LISTENING";
        }
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

    public String getChannelType() {
        return channelType;
    }

    public void setChannelType(String channelType) {
        this.channelType = channelType;
    }

    public String getChannelUserId() {
        return channelUserId;
    }

    public void setChannelUserId(String channelUserId) {
        this.channelUserId = channelUserId;
    }

    public LocalDateTime getLastActive() {
        return lastActive;
    }

    public void setLastActive(LocalDateTime lastActive) {
        this.lastActive = lastActive;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public String getCurrentMode() {
        return currentMode;
    }

    public void setCurrentMode(String currentMode) {
        this.currentMode = currentMode;
    }

    public String getEmotionPhase() {
        return emotionPhase;
    }

    public void setEmotionPhase(String emotionPhase) {
        this.emotionPhase = emotionPhase;
    }

    public String getEventA() {
        return eventA;
    }

    public void setEventA(String eventA) {
        this.eventA = eventA;
    }

    public String getBeliefB() {
        return beliefB;
    }

    public void setBeliefB(String beliefB) {
        this.beliefB = beliefB;
    }

    public String getEmotionC() {
        return emotionC;
    }

    public void setEmotionC(String emotionC) {
        this.emotionC = emotionC;
    }

    public String getDisputeD() {
        return disputeD;
    }

    public void setDisputeD(String disputeD) {
        this.disputeD = disputeD;
    }

    public String getNewBeliefE() {
        return newBeliefE;
    }

    public void setNewBeliefE(String newBeliefE) {
        this.newBeliefE = newBeliefE;
    }

    public String getTravelContext() {
        return travelContext;
    }

    public void setTravelContext(String travelContext) {
        this.travelContext = travelContext;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

package com.claw.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_skill_setting", uniqueConstraints = @UniqueConstraint(name = "uk_user_skill", columnNames = {"user_id", "skill_name"}))
public class UserSkillSettingEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "skill_name", nullable = false, length = 128) private String skillName;
    @Column(name = "enabled", nullable = false) private boolean enabled = true;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist @PreUpdate public void touch() { updatedAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public Long getUserId() { return userId; } public void setUserId(Long value) { userId = value; }
    public String getSkillName() { return skillName; } public void setSkillName(String value) { skillName = value; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

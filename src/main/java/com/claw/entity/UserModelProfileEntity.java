package com.claw.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_model_profile", indexes = @Index(name = "idx_model_profile_user", columnList = "user_id"))
public class UserModelProfileEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "provider", nullable = false, length = 64) private String provider = "openai-compatible";
    @Column(name = "base_url", nullable = false, length = 512) private String baseUrl;
    @Column(name = "model", nullable = false, length = 128) private String model;
    @Column(name = "encrypted_api_key", nullable = false, length = 2048) private String encryptedApiKey;
    @Column(name = "temperature") private Double temperature;
    @Column(name = "max_tokens") private Integer maxTokens;
    @Column(name = "is_default", nullable = false) private boolean defaultProfile;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist public void prePersist() { LocalDateTime now = LocalDateTime.now(); if (createdAt == null) createdAt = now; if (updatedAt == null) updatedAt = now; }
    @PreUpdate public void preUpdate() { updatedAt = LocalDateTime.now(); }
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
    public String getProvider(){return provider;} public void setProvider(String v){provider=v;}
    public String getBaseUrl(){return baseUrl;} public void setBaseUrl(String v){baseUrl=v;}
    public String getModel(){return model;} public void setModel(String v){model=v;}
    public String getEncryptedApiKey(){return encryptedApiKey;} public void setEncryptedApiKey(String v){encryptedApiKey=v;}
    public Double getTemperature(){return temperature;} public void setTemperature(Double v){temperature=v;}
    public Integer getMaxTokens(){return maxTokens;} public void setMaxTokens(Integer v){maxTokens=v;}
    public boolean isDefaultProfile(){return defaultProfile;} public void setDefaultProfile(boolean v){defaultProfile=v;}
}

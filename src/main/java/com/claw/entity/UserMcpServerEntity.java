package com.claw.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_mcp_server", uniqueConstraints = @UniqueConstraint(name = "uk_user_mcp_key", columnNames = {"user_id", "server_key"}))
public class UserMcpServerEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="server_key", nullable=false, length=80) private String serverKey;
    @Column(name="display_name", nullable=false, length=120) private String displayName;
    @Column(name="command_value", nullable=false, length=512) private String command;
    @Column(name="args_json", nullable=false, length=4096) private String argsJson = "[]";
    @Column(name="working_directory", nullable=false, length=1024) private String workingDirectory = "";
    @Column(name="encrypted_environment", nullable=false, length=8192) private String encryptedEnvironment;
    @Column(name="enabled", nullable=false) private boolean enabled;
    @Column(name="timeout_ms", nullable=false) private int timeoutMs = 10000;
    @Column(name="max_concurrent_requests", nullable=false) private int maxConcurrentRequests = 4;
    @Column(name="max_memory_mb", nullable=false) private int maxMemoryMb = 256;
    @Column(name="max_response_bytes", nullable=false) private int maxResponseBytes = 1048576;
    @Column(name="max_cpu_seconds", nullable=false) private int maxCpuSeconds = 300;
    @Column(name="max_cpu_percent", nullable=false) private int maxCpuPercent = 50;
    @Column(name="updated_at", nullable=false) private LocalDateTime updatedAt;
    @PrePersist @PreUpdate public void touch(){updatedAt=LocalDateTime.now();}
    public Long getId(){return id;} public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
    public String getServerKey(){return serverKey;} public void setServerKey(String v){serverKey=v;} public String getDisplayName(){return displayName;} public void setDisplayName(String v){displayName=v;}
    public String getCommand(){return command;} public void setCommand(String v){command=v;} public String getArgsJson(){return argsJson;} public void setArgsJson(String v){argsJson=v;}
    public String getWorkingDirectory(){return workingDirectory;} public void setWorkingDirectory(String v){workingDirectory=v;} public String getEncryptedEnvironment(){return encryptedEnvironment;} public void setEncryptedEnvironment(String v){encryptedEnvironment=v;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} public int getTimeoutMs(){return timeoutMs;} public void setTimeoutMs(int v){timeoutMs=v;} public LocalDateTime getUpdatedAt(){return updatedAt;}
    public int getMaxConcurrentRequests(){return maxConcurrentRequests;} public void setMaxConcurrentRequests(int v){maxConcurrentRequests=v;} public int getMaxMemoryMb(){return maxMemoryMb;} public void setMaxMemoryMb(int v){maxMemoryMb=v;} public int getMaxResponseBytes(){return maxResponseBytes;} public void setMaxResponseBytes(int v){maxResponseBytes=v;}
    public int getMaxCpuSeconds(){return maxCpuSeconds;} public void setMaxCpuSeconds(int v){maxCpuSeconds=v;}
    public int getMaxCpuPercent(){return maxCpuPercent;} public void setMaxCpuPercent(int v){maxCpuPercent=v;}
}

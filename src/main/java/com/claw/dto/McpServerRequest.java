package com.claw.dto;
import jakarta.validation.constraints.*; import java.util.List; import java.util.Map;
public record McpServerRequest(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,80}") String serverKey,
 @NotBlank @Size(max=120) String displayName,@NotBlank @Size(max=512) String command,
 @Size(max=64) List<@Size(max=512) String> args,@Size(max=1024) String workingDirectory,
 @Size(max=64) Map<@Pattern(regexp="[A-Za-z_][A-Za-z0-9_]*") String,@Size(max=2048) String> environment,
 boolean enabled,@Min(1000) @Max(120000) int timeoutMs,@Min(1) @Max(16) int maxConcurrentRequests,
 @Min(64) @Max(4096) int maxMemoryMb,@Min(65536) @Max(16777216) int maxResponseBytes,
 @Min(1) @Max(86400) int maxCpuSeconds,@Min(1) @Max(100) int maxCpuPercent){}

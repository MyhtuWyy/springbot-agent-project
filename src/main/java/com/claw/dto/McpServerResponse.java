package com.claw.dto;
import java.util.List;
public record McpServerResponse(Long id,String serverKey,String displayName,String command,List<String> args,
 String workingDirectory,List<String> environmentKeys,boolean enabled,int timeoutMs,int maxConcurrentRequests,int maxMemoryMb,int maxResponseBytes,int maxCpuSeconds,int maxCpuPercent){}

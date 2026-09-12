package com.claw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "mcp")
public class McpProperties {
    private final Map<String, Server> servers = new LinkedHashMap<>();

    public Map<String, Server> getServers() {
        return servers;
    }

    public static class Server {
        private boolean enabled;
        private String command = "";
        private List<String> args = new ArrayList<>();
        private String workingDirectory = "";
        private Map<String, String> environment = new LinkedHashMap<>();
        private int timeoutMs = 10000;
        private int initTimeoutMs = 120000;
        private String toolName = "";
        private int maxConcurrentRequests = 4;
        private int maxMemoryMb = 256;
        private int maxResponseBytes = 1048576;
        private int maxCpuSeconds = 300;
        private int maxCpuPercent = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public void setWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        public Map<String, String> getEnvironment() {
            return environment;
        }

        public void setEnvironment(Map<String, String> environment) {
            this.environment = environment;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getInitTimeoutMs() {
            return initTimeoutMs;
        }

        public void setInitTimeoutMs(int initTimeoutMs) {
            this.initTimeoutMs = initTimeoutMs;
        }

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }
        public int getMaxConcurrentRequests(){return maxConcurrentRequests;} public void setMaxConcurrentRequests(int v){maxConcurrentRequests=v;}
        public int getMaxMemoryMb(){return maxMemoryMb;} public void setMaxMemoryMb(int v){maxMemoryMb=v;}
        public int getMaxResponseBytes(){return maxResponseBytes;} public void setMaxResponseBytes(int v){maxResponseBytes=v;}
        public int getMaxCpuSeconds(){return maxCpuSeconds;} public void setMaxCpuSeconds(int v){maxCpuSeconds=v;}
        public int getMaxCpuPercent(){return maxCpuPercent;} public void setMaxCpuPercent(int v){maxCpuPercent=v;}

    }
}

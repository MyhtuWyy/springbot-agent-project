package com.claw.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.claw.config.McpProperties;
import com.claw.service.ToolExecutionContextHolder;
import com.claw.service.UserMcpServerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;

@Component
public class McpClient {
    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpProperties mcpProperties;
    private final UserMcpServerService userMcpServerService;
    private final Map<String, StdioSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, FailureSnapshot> failures = new ConcurrentHashMap<>();
    private final McpProcessResourceLimiter resourceLimiter;

    @Autowired
    public McpClient(McpProperties mcpProperties, UserMcpServerService userMcpServerService,McpProcessResourceLimiter resourceLimiter) {
        this.mcpProperties = mcpProperties;
        this.userMcpServerService = userMcpServerService;
        this.resourceLimiter=resourceLimiter;
    }
    public McpClient(McpProperties mcpProperties,UserMcpServerService userMcpServerService){this(mcpProperties,userMcpServerService,new McpProcessResourceLimiter());}

    public String callTool(String serverKey, String toolName, JSONObject arguments) {
        McpProperties.Server server = resolveServer(serverKey);
        if (server == null || !server.isEnabled()) {
            throw new IllegalStateException("MCP server is not enabled: " + serverKey);
        }
        return session(sessionKey(serverKey), server).callTool(toolName, arguments);
    }

    public List<RemoteTool> listTools(String serverKey) {
        McpProperties.Server server = resolveServer(serverKey);
        if (server == null || !server.isEnabled()) {
            throw new IllegalStateException("MCP server is not enabled: " + serverKey);
        }
        return session(sessionKey(serverKey), server).listTools();
    }

    public SessionStatus status(Long userId,String serverKey){String key=userId+":"+serverKey;StdioSession session=sessions.get(key);if(session!=null)return session.status();FailureSnapshot failure=failures.get(key);return failure==null?new SessionStatus("STOPPED",null,null,0,List.of(),null,null):new SessionStatus("FAILED",null,failure.at(),0,failure.stderr(),failure.message(),null);}
    public SessionStatus restart(Long userId,String serverKey){stop(userId,serverKey);McpProperties.Server server=resolveServer(serverKey);if(server==null||!server.isEnabled())throw new IllegalStateException("MCP server is not enabled: "+serverKey);return session(userId+":"+serverKey,server).status();}
    public void stop(Long userId,String serverKey){String key=userId+":"+serverKey;StdioSession session=sessions.remove(key);if(session!=null)session.close();failures.remove(key);}
    @PreDestroy public void shutdown(){sessions.values().forEach(StdioSession::close);sessions.clear();failures.clear();}

    private McpProperties.Server resolveServer(String serverKey) {
        var context = ToolExecutionContextHolder.get();
        if (context != null && context.userId() != null) {
            var configured = userMcpServerService.resolve(context.userId(), serverKey);
            if (configured.isPresent()) return configured.get();
        }
        return mcpProperties.getServers().get(serverKey);
    }

    private String sessionKey(String serverKey) {
        var context = ToolExecutionContextHolder.get();
        return (context == null || context.userId() == null ? "system" : context.userId()) + ":" + serverKey;
    }

    private StdioSession session(String serverKey, McpProperties.Server server) {
        String fingerprint = serverFingerprint(server);
        try{return sessions.compute(serverKey, (key, existing) -> {if(existing!=null&&existing.isAlive()&&existing.matches(fingerprint))return existing;if(existing!=null)existing.close();return new StdioSession(serverKey,server,fingerprint,resourceLimiter);});}
        catch(RuntimeException e){failures.put(serverKey,new FailureSnapshot(Instant.now(),e.getMessage(),List.of()));throw e;}
    }

    private String serverFingerprint(McpProperties.Server server) {
        return JSON.toJSONString(List.of(server.isEnabled(), server.getCommand(), server.getArgs(), server.getWorkingDirectory(), server.getEnvironment(), server.getTimeoutMs(), server.getInitTimeoutMs(),server.getMaxConcurrentRequests(),server.getMaxMemoryMb(),server.getMaxResponseBytes(),server.getMaxCpuSeconds(),server.getMaxCpuPercent()));
    }

    private static final class StdioSession implements AutoCloseable {
        private final String serverKey;
        private final McpProperties.Server server;
        private final String fingerprint;
        private final Process process;
        private final McpProcessResourceLimiter.ResourceHandle resourceHandle;
        private final String resourceLimitMode;
        private final BufferedInputStream stdout;
        private final OutputStream stdin;
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final Map<Integer, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
        private final Thread readerThread;
        private final Thread stderrThread;
        private final Object writeLock = new Object();
        private final List<String> stderrLines = Collections.synchronizedList(new ArrayList<>());
        private final Semaphore requestPermits;
        private final Instant startedAt=Instant.now();
        private volatile boolean initialized;
        private volatile boolean closed;
        private volatile String lastError;

        StdioSession(String serverKey, McpProperties.Server server, String fingerprint,McpProcessResourceLimiter resourceLimiter) {
            this.serverKey = serverKey;
            this.server = server;
            this.fingerprint = fingerprint;
            this.requestPermits=new Semaphore(Math.max(1,server.getMaxConcurrentRequests()),true);
            StartedProcess started=startProcess(server,resourceLimiter);this.process=started.process();this.resourceHandle=started.handle();this.resourceLimitMode=started.mode();
            this.stdout = new BufferedInputStream(process.getInputStream());
            this.stdin = process.getOutputStream();
            this.readerThread = new Thread(this::readLoop, "mcp-stdio-" + serverKey + "-stdout");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
            this.stderrThread = new Thread(this::logStderr, "mcp-stdio-" + serverKey + "-stderr");
            this.stderrThread.setDaemon(true);
            this.stderrThread.start();
            try{initialize();}catch(RuntimeException e){close();throw e;}
        }

        boolean isAlive() {
            return !closed && process.isAlive();
        }

        boolean matches(String value) { return fingerprint.equals(value); }

        SessionStatus status(){String state=lastError!=null?"FAILED":isAlive()?"RUNNING":"STOPPED";return new SessionStatus(state,process.pid(),startedAt,pending.size(),stderrSnapshot(),lastError,resourceLimitMode);}

        String callTool(String toolName, JSONObject arguments) {
            ensureInitialized();
            JSONObject params = new JSONObject();
            params.put("name", toolName);
            params.put("arguments", arguments == null ? new JSONObject() : arguments);
            JSONObject result = sendRequest("tools/call", params);
            return extractToolResult(result);
        }

        List<RemoteTool> listTools() {
            ensureInitialized();
            JSONObject result = sendRequest("tools/list", null);
            List<RemoteTool> tools = new ArrayList<>();
            if (result == null) {
                return tools;
            }
            JSONArray items = result.getJSONArray("tools");
            if (items == null) {
                items = result.getJSONArray("result");
            }
            if (items == null) {
                return tools;
            }
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                tools.add(new RemoteTool(
                        item.getString("name"),
                        item.getString("description"),
                        item.getJSONObject("inputSchema")
                ));
            }
            return tools;
        }

        private void initialize() {
            JSONObject params = new JSONObject();
            params.put("protocolVersion", PROTOCOL_VERSION);

            JSONObject clientInfo = new JSONObject();
            clientInfo.put("name", "wechat-bot");
            clientInfo.put("version", "1.0.0");
            params.put("clientInfo", clientInfo);

            JSONObject capabilities = new JSONObject();
            capabilities.put("tools", new JSONObject());
            params.put("capabilities", capabilities);

            JSONObject result = sendRequest("initialize", params, server.getInitTimeoutMs());
            if (result == null) {
                throw new IllegalStateException("MCP initialize returned no result: " + buildStartupHint());
            }
            sendNotification("notifications/initialized", null);
            initialized = true;
        }

        private void ensureInitialized() {
            if (!initialized) {
                throw new IllegalStateException("MCP session is not initialized: " + serverKey);
            }
        }

        private JSONObject sendRequest(String method, JSONObject params) {
            return sendRequest(method, params, server.getTimeoutMs());
        }

        private JSONObject sendRequest(String method, JSONObject params, long timeoutMs) {
            if (closed || !process.isAlive()) {
                throw new IllegalStateException("MCP process is not alive: " + serverKey);
            }

            boolean acquired=false;
            try{acquired=requestPermits.tryAcquire(Math.max(1L,timeoutMs),TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted while waiting for MCP capacity",e);}
            if(!acquired)throw new IllegalStateException("MCP concurrent request limit reached: "+serverKey);
            int id = nextId.getAndIncrement();
            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            if (params != null) {
                request.put("params", params);
            }

            CompletableFuture<JSONObject> future = new CompletableFuture<>();
            pending.put(id, future);
            writeMessage(request);

            try {
                JSONObject response = future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
                if (response != null && response.containsKey("error")) {
                    JSONObject error = response.getJSONObject("error");
                    String message = error != null ? error.getString("message") : response.getString("error");
                    throw new IllegalStateException("MCP error: " + method + ", " + message);
                }
                JSONObject result = response == null ? null : response.getJSONObject("result");
                return result != null ? result : response;
            } catch (Exception e) {
                pending.remove(id);
                throw new IllegalStateException("MCP request failed: " + method + ", " + e.getMessage()
                        + ". " + buildStartupHint(), e);
            } finally {requestPermits.release();}
        }

        private void sendNotification(String method, JSONObject params) {
            JSONObject message = new JSONObject();
            message.put("jsonrpc", "2.0");
            message.put("method", method);
            if (params != null) {
                message.put("params", params);
            }
            writeMessage(message);
        }

        private void writeMessage(JSONObject message) {
            byte[] body = message.toJSONString().getBytes(StandardCharsets.UTF_8);
            byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            synchronized (writeLock) {
                try {
                    stdin.write(header);
                    stdin.write(body);
                    stdin.flush();
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to write MCP request: " + serverKey, e);
                }
            }
        }

        private void readLoop() {
            try {
                while (!closed) {
                    Map<String, String> headers = readHeaders(stdout);
                    if (headers == null) {
                        return;
                    }
                    int contentLength = Integer.parseInt(headers.getOrDefault("Content-Length", "0").trim());
                    if (contentLength <= 0) {
                        continue;
                    }
                    if(contentLength>server.getMaxResponseBytes())throw new IOException("MCP response exceeds maxResponseBytes: "+contentLength);
                    byte[] body = stdout.readNBytes(contentLength);
                    if (body.length < contentLength) {
                        return;
                    }

                    JSONObject message = JSON.parseObject(new String(body, StandardCharsets.UTF_8));
                    if (message == null) {
                        continue;
                    }

                    Integer id = message.getInteger("id");
                    if (id != null) {
                        CompletableFuture<JSONObject> future = pending.remove(id);
                        if (future != null) {
                            future.complete(message);
                        }
                    } else {
                        log.debug("MCP notification from {}: {}", serverKey, message.toJSONString());
                    }
                }
            } catch (Exception e) {
                if (!closed) {
                    log.warn("MCP stdio read loop stopped: serverKey={}", serverKey, e);
                }
                lastError=e.getMessage(); process.destroy();
                failPending(e);
            }
        }

        private void logStderr() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = readLimitedLine(reader,4096)) != null) {
                    if (!line.isBlank()) {
                        rememberStderrLine(line);
                        log.debug("MCP stderr [{}]: {}", serverKey, line);
                    }
                }
            } catch (Exception e) {
                if (!closed) {
                    log.debug("MCP stderr reader stopped: serverKey={}", serverKey, e);
                }
            }
        }

        private Map<String, String> readHeaders(InputStream inputStream) throws IOException {
            Map<String, String> headers = new LinkedHashMap<>();
            while (true) {
                String line = readLine(inputStream);
                if (line == null) {
                    return null;
                }
                if (line.isBlank()) {
                    return headers;
                }
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
        }

        private String readLine(InputStream inputStream) throws IOException {
            StringBuilder builder = new StringBuilder();
            while (true) {
                int ch = inputStream.read();
                if (ch == -1) {
                    return builder.isEmpty() ? null : builder.toString();
                }
                if (ch == '\n') {
                    if (!builder.isEmpty() && builder.charAt(builder.length() - 1) == '\r') {
                        builder.setLength(builder.length() - 1);
                    }
                    return builder.toString();
                }
                builder.append((char) ch);
                if(builder.length()>8192)throw new IOException("MCP header line exceeds 8192 bytes");
            }
        }

        private String readLimitedLine(BufferedReader reader,int maxChars)throws IOException{StringBuilder value=new StringBuilder();while(true){int ch=reader.read();if(ch<0)return value.isEmpty()?null:value.toString();if(ch=='\n')return value.toString();if(ch!='\r'&&value.length()<maxChars)value.append((char)ch);}}

        private String extractToolResult(JSONObject result) {
            if (result == null) {
                return "";
            }
            Object content = result.get("content");
            if (content instanceof String text) {
                return text;
            }
            if (content instanceof List<?> list) {
                return stringifyContentList(list);
            }
            if (content instanceof JSONObject object) {
                return stringifySingleContent(object);
            }
            if (result.containsKey("text")) {
                return Objects.toString(result.getString("text"), "");
            }
            return result.toJSONString();
        }

        private String stringifyContentList(List<?> list) {
            StringBuilder builder = new StringBuilder();
            for (Object item : list) {
                if (item instanceof JSONObject object) {
                    String text = stringifySingleContent(object);
                    if (!text.isBlank()) {
                        if (!builder.isEmpty()) {
                            builder.append('\n');
                        }
                        builder.append(text);
                    }
                } else if (item != null) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(item);
                }
            }
            return builder.toString();
        }

        private String stringifySingleContent(JSONObject object) {
            if (object == null) {
                return "";
            }
            String type = object.getString("type");
            if (type != null && type.equalsIgnoreCase("text")) {
                return Objects.toString(object.getString("text"), "");
            }
            if (object.containsKey("text")) {
                return Objects.toString(object.getString("text"), "");
            }
            if (object.containsKey("content")) {
                return Objects.toString(object.getString("content"), "");
            }
            return object.toJSONString();
        }

        private StartedProcess startProcess(McpProperties.Server server,McpProcessResourceLimiter resourceLimiter) {
            List<String> command = new ArrayList<>();
            command.add(server.getCommand());
            if (server.getArgs() != null) {
                command.addAll(server.getArgs());
            }

            McpProcessResourceLimiter.Prepared prepared=resourceLimiter.prepare(command,server); ProcessBuilder builder = new ProcessBuilder(prepared.command());
            if (server.getWorkingDirectory() != null && !server.getWorkingDirectory().isBlank()) {
                builder.directory(new java.io.File(server.getWorkingDirectory()));
            }
            if (server.getEnvironment() != null && !server.getEnvironment().isEmpty()) {
                builder.environment().putAll(server.getEnvironment());
            }
            applyMemoryLimit(builder.environment(),server);

            builder.redirectErrorStream(false);
            try {
                log.info("Starting MCP stdio server: serverKey={}, command={}, args={}, workdir={}",
                        serverKey, server.getCommand(), server.getArgs(), server.getWorkingDirectory());
                Process process=builder.start();try{return new StartedProcess(process,prepared.attach(process),prepared.mode());}catch(RuntimeException e){process.destroyForcibly();throw e;}
            } catch (IOException e) {
                throw new IllegalStateException("Failed to start MCP stdio process: " + buildStartupHint(), e);
            }
        }

        private void rememberStderrLine(String line) {
            synchronized (stderrLines) {
                stderrLines.add(line.length()>500?line.substring(0,500):line);
                if (stderrLines.size() > 8) {
                    stderrLines.remove(0);
                }
            }
        }

        private List<String> stderrSnapshot(){synchronized(stderrLines){return List.copyOf(stderrLines);}}

        private void applyMemoryLimit(Map<String,String> environment,McpProperties.Server server){int mb=Math.max(64,server.getMaxMemoryMb());String command=new java.io.File(server.getCommand()).getName().toLowerCase();environment.put("MCP_MAX_MEMORY_MB",String.valueOf(mb));if(command.equals("node")||command.equals("node.exe")||command.equals("npx")||command.equals("npx.cmd"))environment.put("NODE_OPTIONS","--max-old-space-size="+mb);else if(command.equals("java")||command.equals("java.exe"))environment.put("JAVA_TOOL_OPTIONS","-Xmx"+mb+"m");}

        private String buildStartupHint() {
            String stderrSummary;
            synchronized (stderrLines) {
                stderrSummary = stderrLines.isEmpty() ? "none" : String.join(" | ", stderrLines);
            }
            return "serverKey=" + serverKey
                    + ", command=" + server.getCommand()
                    + ", args=" + server.getArgs()
                    + ", workdir=" + server.getWorkingDirectory()
                    + ", recentStderr=" + stderrSummary;
        }

        private void failPending(Exception e) {
            pending.forEach((id, future) -> future.completeExceptionally(e));
            pending.clear();
        }

        @Override
        public void close() {
            closed = true;
            failPending(new IllegalStateException("MCP session closed: " + serverKey));
            try {
                stdin.close();
            } catch (IOException ignored) {
            }
            process.destroy();
            try{if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}
            resourceHandle.close();
        }
    }

    public record RemoteTool(String name, String description, JSONObject inputSchema) {
    }
    private record StartedProcess(Process process,McpProcessResourceLimiter.ResourceHandle handle,String mode){}
    public record SessionStatus(String state,Long pid,Instant startedAt,int pendingRequests,List<String> stderr,String error,String resourceLimitMode){}
    private record FailureSnapshot(Instant at,String message,List<String> stderr){}
}

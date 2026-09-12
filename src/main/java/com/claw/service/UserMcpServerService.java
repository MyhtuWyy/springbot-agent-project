package com.claw.service;

import com.alibaba.fastjson2.JSON;
import com.claw.config.McpProperties;
import com.claw.dto.*;
import com.claw.entity.UserMcpServerEntity;
import com.claw.repository.UserMcpServerRepository;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Path;
import java.util.*;

@Service
public class UserMcpServerService {
    private final UserMcpServerRepository repository; private final SecretValueCodec codec; private final Set<String> allowedCommands; private final List<Path> allowedWorkingRoots; private final int maxArgs; private final int maxArgLength;
    public UserMcpServerService(UserMcpServerRepository repository,SecretValueCodec codec,Environment environment){
        this.repository=repository; this.codec=codec;
        this.allowedCommands=Arrays.stream(environment.getProperty("app.mcp.allowed-commands","").split(",")).map(String::trim).filter(v->!v.isBlank()).map(String::toLowerCase).collect(java.util.stream.Collectors.toUnmodifiableSet());
        String configuredRoots=Objects.requireNonNullElse(environment.getProperty("app.mcp.allowed-working-roots",""),""); List<Path> roots=Arrays.stream(configuredRoots.split(",")).map(String::trim).filter(v->!v.isBlank()).map(v->Path.of(v).toAbsolutePath().normalize()).toList(); this.allowedWorkingRoots=roots.isEmpty()?List.of(Path.of("").toAbsolutePath().normalize()):roots;
        this.maxArgs=Objects.requireNonNullElse(environment.getProperty("app.mcp.max-args",Integer.class,32),32); this.maxArgLength=Objects.requireNonNullElse(environment.getProperty("app.mcp.max-arg-length",Integer.class,512),512);
    }
    public List<McpServerResponse> list(Long userId){return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(this::response).toList();}
    @Transactional public McpServerResponse save(Long userId,Long id,McpServerRequest request){
        validateCommand(request.command(),request.enabled());
        validateArgs(request.args()); validateEnvironment(request.environment());
        UserMcpServerEntity entity=id==null?new UserMcpServerEntity():repository.findByIdAndUserId(id,userId).orElseThrow();
        repository.findByUserIdAndServerKey(userId,request.serverKey()).filter(other->!Objects.equals(other.getId(),entity.getId())).ifPresent(other->{throw new IllegalArgumentException("serverKey already exists");});
        entity.setUserId(userId); entity.setServerKey(request.serverKey().trim()); entity.setDisplayName(request.displayName().trim()); entity.setCommand(request.command().trim());
        entity.setArgsJson(JSON.toJSONString(request.args()==null?List.of():request.args())); entity.setWorkingDirectory(normalizeDirectory(request.workingDirectory()));
        if(id==null||(request.environment()!=null&&!request.environment().isEmpty()))entity.setEncryptedEnvironment(codec.encrypt(JSON.toJSONString(request.environment()==null?Map.of():request.environment()))); entity.setEnabled(request.enabled()); entity.setTimeoutMs(request.timeoutMs()); entity.setMaxConcurrentRequests(request.maxConcurrentRequests()); entity.setMaxMemoryMb(request.maxMemoryMb()); entity.setMaxResponseBytes(request.maxResponseBytes()); entity.setMaxCpuSeconds(request.maxCpuSeconds()); entity.setMaxCpuPercent(request.maxCpuPercent());
        return response(repository.save(entity));
    }
    @Transactional public void delete(Long userId,Long id){repository.findByIdAndUserId(id,userId).ifPresent(repository::delete);}
    @Transactional public McpServerResponse setEnabled(Long userId,Long id,boolean enabled){UserMcpServerEntity e=repository.findByIdAndUserId(id,userId).orElseThrow(); validateCommand(e.getCommand(),enabled); e.setEnabled(enabled); return response(repository.save(e));}
    public Optional<McpProperties.Server> resolve(Long userId,String serverKey){return repository.findByUserIdAndServerKey(userId,serverKey).map(this::runtime);}
    public List<EnabledServer> enabledServers(Long userId){return repository.findByUserIdAndEnabledTrueOrderByServerKey(userId).stream().map(e->new EnabledServer(e.getServerKey(),e.getUpdatedAt()==null?0L:e.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())).toList();}
    public String ownedServerKey(Long userId,Long id){return repository.findByIdAndUserId(id,userId).orElseThrow().getServerKey();}
    public List<ServerRef> serverRefs(Long userId){return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(e->new ServerRef(e.getId(),e.getServerKey())).toList();}
    public record EnabledServer(String serverKey,long version){}
    public record ServerRef(Long id,String serverKey){}
    private McpProperties.Server runtime(UserMcpServerEntity entity){
        McpProperties.Server server=new McpProperties.Server(); server.setEnabled(entity.isEnabled()); server.setCommand(entity.getCommand()); server.setArgs(JSON.parseArray(entity.getArgsJson(),String.class)); server.setWorkingDirectory(entity.getWorkingDirectory());
        server.setEnvironment(JSON.parseObject(codec.decrypt(entity.getEncryptedEnvironment()),new com.alibaba.fastjson2.TypeReference<Map<String,String>>(){})); server.setTimeoutMs(entity.getTimeoutMs()); server.setMaxConcurrentRequests(positive(entity.getMaxConcurrentRequests(),4)); server.setMaxMemoryMb(positive(entity.getMaxMemoryMb(),256)); server.setMaxResponseBytes(positive(entity.getMaxResponseBytes(),1048576)); server.setMaxCpuSeconds(positive(entity.getMaxCpuSeconds(),300)); server.setMaxCpuPercent(positive(entity.getMaxCpuPercent(),50)); return server;
    }
    private McpServerResponse response(UserMcpServerEntity e){Map<String,String> env=JSON.parseObject(codec.decrypt(e.getEncryptedEnvironment()),new com.alibaba.fastjson2.TypeReference<Map<String,String>>(){}); return new McpServerResponse(e.getId(),e.getServerKey(),e.getDisplayName(),e.getCommand(),JSON.parseArray(e.getArgsJson(),String.class),e.getWorkingDirectory(),env.keySet().stream().sorted().toList(),e.isEnabled(),e.getTimeoutMs(),positive(e.getMaxConcurrentRequests(),4),positive(e.getMaxMemoryMb(),256),positive(e.getMaxResponseBytes(),1048576),positive(e.getMaxCpuSeconds(),300),positive(e.getMaxCpuPercent(),50));}
    private int positive(int value,int fallback){return value>0?value:fallback;}
    private void validateCommand(String command,boolean enabled){if(!enabled)return; String name=Path.of(command.trim()).getFileName().toString().toLowerCase(); if(!allowedCommands.contains(name))throw new IllegalArgumentException("MCP command is not in app.mcp.allowed-commands");}
    private String normalizeDirectory(String value){Path path=value==null||value.isBlank()?allowedWorkingRoots.getFirst():Path.of(value.trim()).toAbsolutePath().normalize(); if(allowedWorkingRoots.stream().noneMatch(path::startsWith))throw new IllegalArgumentException("workingDirectory is outside app.mcp.allowed-working-roots"); return path.toString();}
    private void validateArgs(List<String> args){if(args==null)return;if(args.size()>maxArgs)throw new IllegalArgumentException("too many MCP arguments");for(String arg:args){if(arg==null||arg.length()>maxArgLength||arg.indexOf('\0')>=0||arg.contains("\r")||arg.contains("\n"))throw new IllegalArgumentException("invalid MCP argument");}}
    private void validateEnvironment(Map<String,String> env){if(env==null)return;Set<String> blocked=Set.of("PATH","PATHEXT","CLASSPATH","NODE_OPTIONS","JAVA_TOOL_OPTIONS");for(String key:env.keySet())if(blocked.contains(key.toUpperCase()))throw new IllegalArgumentException("environment key is reserved: "+key);}
}

package com.claw.service;

import com.alibaba.fastjson2.JSONObject;
import com.claw.mcp.McpClient;
import org.slf4j.Logger; import org.slf4j.LoggerFactory; import org.springframework.stereotype.Service;
import java.util.*; import java.util.concurrent.ConcurrentHashMap;

@Service
public class McpToolCatalogService {
    private static final Logger log=LoggerFactory.getLogger(McpToolCatalogService.class); private static final long CACHE_MS=30000L;
    private final McpClient client; private final UserMcpServerService servers; private final UserMcpToolSettingService toolSettings; private final Map<String,CacheEntry> cache=new ConcurrentHashMap<>();
    public McpToolCatalogService(McpClient client,UserMcpServerService servers,UserMcpToolSettingService toolSettings){this.client=client;this.servers=servers;this.toolSettings=toolSettings;}
    public List<DynamicTool> list(Long userId){
        if(userId==null)return List.of(); List<DynamicTool> all=new ArrayList<>(); long now=System.currentTimeMillis();
        for(var server:servers.enabledServers(userId)){
            String key=userId+":"+server.serverKey(); CacheEntry current=cache.get(key);
            if(current==null||current.version()!=server.version()||current.expiresAt()<now){current=discover(server.serverKey(),server.version(),now);cache.put(key,current);}
            Set<String> disabled=toolSettings.disabled(userId,server.serverKey()); all.addAll(current.tools().stream().filter(tool->!disabled.contains(tool.remoteName())).toList());
        }
        return List.copyOf(all);
    }
    public Optional<DynamicTool> resolve(Long userId,String exposedName){return list(userId).stream().filter(tool->tool.name().equals(exposedName)).findFirst();}
    public List<DynamicTool> testConnection(Long userId,String serverKey){List<DynamicTool> tools=mapTools(serverKey,client.listTools(serverKey));cache.remove(userId+":"+serverKey);return tools;}
    public List<ToolView> tools(Long userId,String serverKey){List<DynamicTool> tools=testConnection(userId,serverKey);Set<String> disabled=toolSettings.disabled(userId,serverKey);return tools.stream().map(tool->new ToolView(tool.name(),tool.remoteName(),tool.description(),!disabled.contains(tool.remoteName()))).toList();}
    public void setToolEnabled(Long userId,String serverKey,String remoteName,boolean enabled){toolSettings.setEnabled(userId,serverKey,remoteName,enabled);}
    public void invalidate(Long userId,String serverKey){cache.remove(userId+":"+serverKey);}
    public String call(DynamicTool tool,JSONObject arguments){return client.callTool(tool.serverKey(),tool.remoteName(),arguments);}
    private CacheEntry discover(String serverKey,long version,long now){
        try{List<DynamicTool> tools=mapTools(serverKey,client.listTools(serverKey)); return new CacheEntry(version,now+CACHE_MS,tools);}
        catch(Exception e){log.warn("MCP tool discovery failed, serverKey={}, error={}",serverKey,e.getMessage());return new CacheEntry(version,now+5000L,List.of());}
    }
    private List<DynamicTool> mapTools(String serverKey,List<McpClient.RemoteTool> remoteTools){return remoteTools.stream().filter(t->t.name()!=null&&!t.name().isBlank()&&t.name().length()<=256).map(t->new DynamicTool(exposedName(serverKey,t.name()),serverKey,t.name(),t.description()==null?"MCP tool "+t.name():t.description(),t.inputSchema()==null?JSONObject.of("type","object"):t.inputSchema())).toList();}
    private String exposedName(String serverKey,String remoteName){String raw=("mcp__"+serverKey+"__"+remoteName).replaceAll("[^A-Za-z0-9_-]","_"); if(raw.length()<=64)return raw; String suffix="_"+Integer.toUnsignedString(raw.hashCode(),36); return raw.substring(0,64-suffix.length())+suffix;}
    public record DynamicTool(String name,String serverKey,String remoteName,String description,JSONObject schema){}
    public record ToolView(String name,String remoteName,String description,boolean enabled){}
    private record CacheEntry(long version,long expiresAt,List<DynamicTool> tools){}
}

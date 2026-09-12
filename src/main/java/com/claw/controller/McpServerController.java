package com.claw.controller;

import com.claw.dto.*;
import com.claw.mcp.McpClient;
import com.claw.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/settings/mcp-servers")
public class McpServerController {
    private final UserMcpServerService service; private final McpToolCatalogService catalog; private final McpClient client; private final UserMcpToolSettingService toolSettings;
    public McpServerController(UserMcpServerService service,McpToolCatalogService catalog,McpClient client,UserMcpToolSettingService toolSettings){this.service=service;this.catalog=catalog;this.client=client;this.toolSettings=toolSettings;}
    @GetMapping public List<McpServerResponse> list(HttpServletRequest request){return service.list(user(request).userId());}
    @GetMapping("/statuses") public Map<Long,McpClient.SessionStatus> statuses(HttpServletRequest request){AuthenticatedUser user=user(request);Map<Long,McpClient.SessionStatus> result=new LinkedHashMap<>();for(var server:service.serverRefs(user.userId()))result.put(server.id(),client.status(user.userId(),server.serverKey()));return result;}
    @PostMapping public McpServerResponse create(@Valid @RequestBody McpServerRequest body,HttpServletRequest request){return service.save(user(request).userId(),null,body);}
    @PutMapping("/{id}") public McpServerResponse update(@PathVariable Long id,@Valid @RequestBody McpServerRequest body,HttpServletRequest request){AuthenticatedUser user=user(request);String oldKey=service.ownedServerKey(user.userId(),id);McpServerResponse result=service.save(user.userId(),id,body);client.stop(user.userId(),oldKey);catalog.invalidate(user.userId(),oldKey);if(!oldKey.equals(result.serverKey()))toolSettings.deleteServerSettings(user.userId(),oldKey);return result;}
    @PatchMapping("/{id}/enabled") public McpServerResponse enabled(@PathVariable Long id,@RequestBody Map<String,Boolean> body,HttpServletRequest request){AuthenticatedUser user=user(request);McpServerResponse result=service.setEnabled(user.userId(),id,Boolean.TRUE.equals(body.get("enabled")));if(!result.enabled())client.stop(user.userId(),result.serverKey());catalog.invalidate(user.userId(),result.serverKey());return result;}
    @GetMapping("/{id}/status") public McpClient.SessionStatus status(@PathVariable Long id,HttpServletRequest request){AuthenticatedUser user=user(request);return client.status(user.userId(),service.ownedServerKey(user.userId(),id));}
    @PostMapping("/{id}/restart") public McpClient.SessionStatus restart(@PathVariable Long id,HttpServletRequest request){AuthenticatedUser user=user(request);String key=service.ownedServerKey(user.userId(),id);return withContext(user,()->client.restart(user.userId(),key));}
    @PostMapping("/{id}/stop") public McpClient.SessionStatus stop(@PathVariable Long id,HttpServletRequest request){AuthenticatedUser user=user(request);String key=service.ownedServerKey(user.userId(),id);client.stop(user.userId(),key);catalog.invalidate(user.userId(),key);return client.status(user.userId(),key);}
    @PostMapping("/{id}/test") public Map<String,Object> test(@PathVariable Long id,HttpServletRequest request){AuthenticatedUser user=user(request);String key=service.ownedServerKey(user.userId(),id);return withContext(user,()->{var tools=catalog.testConnection(user.userId(),key);return Map.of("success",true,"toolCount",tools.size(),"tools",tools.stream().map(McpToolCatalogService.DynamicTool::name).toList());});}
    @GetMapping("/{id}/tools") public List<McpToolCatalogService.ToolView> tools(@PathVariable Long id,HttpServletRequest request){AuthenticatedUser user=user(request);String key=service.ownedServerKey(user.userId(),id);return withContext(user,()->catalog.tools(user.userId(),key));}
    @PatchMapping("/{id}/tools/enabled") public Map<String,Object> toolEnabled(@PathVariable Long id,@RequestBody Map<String,Object> body,HttpServletRequest request){AuthenticatedUser user=user(request);String key=service.ownedServerKey(user.userId(),id);String toolName=Objects.toString(body.get("toolName"),"").trim();if(toolName.isEmpty()||toolName.length()>256)throw new IllegalArgumentException("invalid MCP tool name");boolean enabled=Boolean.TRUE.equals(body.get("enabled"));catalog.setToolEnabled(user.userId(),key,toolName,enabled);return Map.of("name",toolName,"enabled",enabled);}
    @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable Long id,HttpServletRequest request){AuthenticatedUser user=user(request);String key=service.ownedServerKey(user.userId(),id);client.stop(user.userId(),key);catalog.invalidate(user.userId(),key);toolSettings.deleteServerSettings(user.userId(),key);service.delete(user.userId(),id);return Map.of("deleted",true);}
    private <T>T withContext(AuthenticatedUser user,Supplier<T> action){UserSessionContext previous=ToolExecutionContextHolder.get();ToolExecutionContextHolder.set(UserSessionContext.of(user.userId(),"desktop",user.username(),user.displayName()));try{return action.get();}finally{ToolExecutionContextHolder.set(previous);}}
    private AuthenticatedUser user(HttpServletRequest request){return (AuthenticatedUser)request.getAttribute(AuthController.USER_ATTRIBUTE);}
}

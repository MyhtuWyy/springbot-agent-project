package com.claw.mcp;

import com.alibaba.fastjson2.JSONObject;
import com.claw.config.McpProperties;
import com.claw.service.*;
import org.junit.jupiter.api.*;
import java.nio.file.*; import java.time.Duration; import java.util.*;
import static org.junit.jupiter.api.Assertions.*; import static org.mockito.ArgumentMatchers.*; import static org.mockito.Mockito.*;

class McpClientIntegrationTest {
    private McpClient client;
    @AfterEach void cleanup(){ToolExecutionContextHolder.clear();if(client!=null)client.shutdown();}

    @Test void realProcessesAreIsolatedByUserAndCanBeStopped()throws Exception{
        UserMcpServerService service=mock(UserMcpServerService.class);
        when(service.resolve(anyLong(),eq("demo"))).thenAnswer(call->Optional.of(server(call.getArgument(0))));
        client=new McpClient(new McpProperties(),service);

        ToolExecutionContextHolder.set(UserSessionContext.of(101L,"desktop","a","A"));
        assertEquals("user-101",client.callTool("demo","echo",new JSONObject())); long firstPid=Objects.requireNonNull(client.status(101L,"demo").pid());
        assertNotNull(client.status(101L,"demo").resourceLimitMode());
        ToolExecutionContextHolder.set(UserSessionContext.of(202L,"desktop","b","B"));
        assertEquals("user-202",client.callTool("demo","echo",new JSONObject())); long secondPid=Objects.requireNonNull(client.status(202L,"demo").pid());
        assertNotEquals(firstPid,secondPid); assertTrue(ProcessHandle.of(firstPid).orElseThrow().isAlive()); assertTrue(ProcessHandle.of(secondPid).orElseThrow().isAlive());
        awaitStderr(202L,"test-mcp-ready-user-202");
        long restartedPid=Objects.requireNonNull(client.restart(202L,"demo").pid()); assertNotEquals(secondPid,restartedPid); awaitStopped(secondPid);

        client.stop(101L,"demo"); assertEquals("STOPPED",client.status(101L,"demo").state()); assertTrue(client.status(202L,"demo").state().equals("RUNNING"));
        awaitStopped(firstPid); assertTrue(ProcessHandle.of(restartedPid).orElseThrow().isAlive());
        client.shutdown(); awaitStopped(restartedPid);
    }

    @Test void oversizedResponseIsRejected()throws Exception{
        UserMcpServerService service=mock(UserMcpServerService.class);when(service.resolve(anyLong(),eq("demo"))).thenAnswer(call->Optional.of(server(call.getArgument(0))));client=new McpClient(new McpProperties(),service);
        ToolExecutionContextHolder.set(UserSessionContext.of(303L,"desktop","c","C"));
        assertThrows(IllegalStateException.class,()->client.callTool("demo","echo",JSONObject.of("large",true)));
        assertEquals("FAILED",client.status(303L,"demo").state());
    }

    private McpProperties.Server server(Long userId)throws Exception{McpProperties.Server server=new McpProperties.Server();server.setEnabled(true);server.setCommand(Path.of(System.getProperty("java.home"),"bin",isWindows()?"java.exe":"java").toString());String classes=Path.of(TestMcpServerProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();server.setArgs(List.of("-cp",classes,TestMcpServerProcess.class.getName()));server.setWorkingDirectory(classes);server.setEnvironment(Map.of("TEST_USER","user-"+userId));server.setTimeoutMs(10000);server.setInitTimeoutMs(10000);server.setMaxConcurrentRequests(2);server.setMaxMemoryMb(2048);server.setMaxCpuSeconds(60);server.setMaxCpuPercent(50);server.setMaxResponseBytes(userId==303L?256:65536);return server;}
    private void awaitStopped(long pid)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(System.nanoTime()<deadline&&ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))Thread.sleep(25);assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));}
    private void awaitStderr(Long userId,String expected)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();while(System.nanoTime()<deadline){if(client.status(userId,"demo").stderr().stream().anyMatch(line->line.contains(expected)))return;Thread.sleep(20);}fail("stderr summary did not contain "+expected);}
    private boolean isWindows(){return System.getProperty("os.name","").toLowerCase().contains("win");}
}

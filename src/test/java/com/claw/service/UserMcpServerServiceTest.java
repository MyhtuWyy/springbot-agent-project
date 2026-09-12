package com.claw.service;

import com.claw.dto.McpServerRequest;
import com.claw.entity.UserMcpServerEntity;
import com.claw.repository.UserMcpServerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMcpServerServiceTest {
    @Test void rejectsEnabledCommandOutsideAdministratorAllowList() {
        Environment environment=mock(Environment.class); when(environment.getProperty("app.mcp.allowed-commands","")).thenReturn("node");
        UserMcpServerService service=new UserMcpServerService(mock(UserMcpServerRepository.class),mock(SecretValueCodec.class),environment);
        McpServerRequest request=new McpServerRequest("unsafe","Unsafe","powershell",List.of(),"",Map.of(),true,10000,4,256,1048576,300,50);
        assertThrows(IllegalArgumentException.class,()->service.save(1L,null,request));
    }

    @Test void responseExposesEnvironmentKeysButNeverSecretValues() {
        Environment environment=mock(Environment.class); when(environment.getProperty("app.mcp.allowed-commands","")).thenReturn("node");
        UserMcpServerRepository repository=mock(UserMcpServerRepository.class); SecretValueCodec codec=mock(SecretValueCodec.class);
        when(repository.findByUserIdAndServerKey(anyLong(),anyString())).thenReturn(Optional.empty()); when(codec.encrypt(anyString())).thenReturn("cipher"); when(codec.decrypt("cipher")).thenReturn("{\"TOKEN\":\"secret-value\"}"); when(repository.save(any())).thenAnswer(call->call.getArgument(0));
        UserMcpServerService service=new UserMcpServerService(repository,codec,environment);
        var response=service.save(1L,null,new McpServerRequest("demo","Demo","node",List.of("server.js"),"",Map.of("TOKEN","secret-value"),true,10000,4,256,1048576,300,50));
        assertEquals(List.of("TOKEN"),response.environmentKeys());
        assertFalse(response.toString().contains("secret-value"));
    }
}

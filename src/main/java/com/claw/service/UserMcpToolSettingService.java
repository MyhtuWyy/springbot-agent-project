package com.claw.service;
import com.claw.entity.UserMcpToolSettingEntity; import com.claw.repository.UserMcpToolSettingRepository; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import java.util.*; import java.util.stream.Collectors;
@Service public class UserMcpToolSettingService {
 private final UserMcpToolSettingRepository repository; public UserMcpToolSettingService(UserMcpToolSettingRepository repository){this.repository=repository;}
 public Set<String> disabled(Long userId,String serverKey){return repository.findByUserIdAndServerKeyAndEnabledFalse(userId,serverKey).stream().map(UserMcpToolSettingEntity::getToolName).collect(Collectors.toUnmodifiableSet());}
 @Transactional public void setEnabled(Long userId,String serverKey,String toolName,boolean enabled){UserMcpToolSettingEntity e=repository.findByUserIdAndServerKeyAndToolName(userId,serverKey,toolName).orElseGet(UserMcpToolSettingEntity::new);e.setUserId(userId);e.setServerKey(serverKey);e.setToolName(toolName);e.setEnabled(enabled);repository.save(e);}
 @Transactional public void deleteServerSettings(Long userId,String serverKey){repository.deleteByUserIdAndServerKey(userId,serverKey);}
}

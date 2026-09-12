package com.claw.repository;
import com.claw.entity.UserMcpToolSettingEntity; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface UserMcpToolSettingRepository extends JpaRepository<UserMcpToolSettingEntity,Long>{Optional<UserMcpToolSettingEntity> findByUserIdAndServerKeyAndToolName(Long userId,String serverKey,String toolName);List<UserMcpToolSettingEntity> findByUserIdAndServerKeyAndEnabledFalse(Long userId,String serverKey);void deleteByUserIdAndServerKey(Long userId,String serverKey);}

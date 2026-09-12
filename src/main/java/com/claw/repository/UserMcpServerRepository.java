package com.claw.repository;
import com.claw.entity.UserMcpServerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface UserMcpServerRepository extends JpaRepository<UserMcpServerEntity,Long>{
    List<UserMcpServerEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    Optional<UserMcpServerEntity> findByIdAndUserId(Long id,Long userId);
    Optional<UserMcpServerEntity> findByUserIdAndServerKey(Long userId,String serverKey);
    List<UserMcpServerEntity> findByUserIdAndEnabledTrueOrderByServerKey(Long userId);
}

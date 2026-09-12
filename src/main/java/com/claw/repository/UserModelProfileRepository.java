package com.claw.repository;

import com.claw.entity.UserModelProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserModelProfileRepository extends JpaRepository<UserModelProfileEntity, Long> {
    List<UserModelProfileEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    Optional<UserModelProfileEntity> findFirstByUserIdAndDefaultProfileTrue(Long userId);
    Optional<UserModelProfileEntity> findByIdAndUserId(Long id, Long userId);
    long countByUserId(Long userId);
}

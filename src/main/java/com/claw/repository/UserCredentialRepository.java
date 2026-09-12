package com.claw.repository;

import com.claw.entity.UserCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserCredentialRepository extends JpaRepository<UserCredentialEntity, Long> {
    Optional<UserCredentialEntity> findFirstByUserIdAndLoginTypeAndEnabledTrue(Long userId, String loginType);
}

package com.claw.repository;

import com.claw.entity.UserLoginSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserLoginSessionRepository extends JpaRepository<UserLoginSessionEntity, Long> {
    Optional<UserLoginSessionEntity> findByTokenId(String tokenId);
    void deleteByTokenId(String tokenId);
}

package com.claw.repository;

import com.claw.entity.UserChannelBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserChannelBindingRepository extends JpaRepository<UserChannelBindingEntity, Long> {
    Optional<UserChannelBindingEntity> findByChannelTypeAndChannelUserId(String channelType, String channelUserId);
}

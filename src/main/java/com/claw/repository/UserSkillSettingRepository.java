package com.claw.repository;

import com.claw.entity.UserSkillSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserSkillSettingRepository extends JpaRepository<UserSkillSettingEntity, Long> {
    Optional<UserSkillSettingEntity> findByUserIdAndSkillName(Long userId, String skillName);
    List<UserSkillSettingEntity> findByUserIdAndEnabledFalse(Long userId);
}

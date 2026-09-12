package com.claw.service;

import com.claw.entity.UserSkillSettingEntity;
import com.claw.repository.UserSkillSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserSkillSettingService {
    private final UserSkillSettingRepository repository;
    public UserSkillSettingService(UserSkillSettingRepository repository) { this.repository = repository; }

    public Set<String> disabledSkills(Long userId) {
        if (userId == null) return Set.of();
        return repository.findByUserIdAndEnabledFalse(userId).stream().map(UserSkillSettingEntity::getSkillName).collect(Collectors.toUnmodifiableSet());
    }

    @Transactional
    public void setEnabled(Long userId, String skillName, boolean enabled) {
        UserSkillSettingEntity setting = repository.findByUserIdAndSkillName(userId, skillName).orElseGet(UserSkillSettingEntity::new);
        setting.setUserId(userId); setting.setSkillName(skillName); setting.setEnabled(enabled);
        repository.save(setting);
    }
}

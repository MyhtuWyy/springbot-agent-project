package com.claw.service;

import com.claw.entity.UserChannelBindingEntity;
import com.claw.entity.UserEntity;
import com.claw.repository.UserChannelBindingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserIdentityService {
    private final UserService userService;
    private final UserChannelBindingRepository bindingRepository;

    public UserIdentityService(UserService userService,
                               UserChannelBindingRepository bindingRepository) {
        this.userService = userService;
        this.bindingRepository = bindingRepository;
    }

    @Transactional
    public UserSessionContext resolveOrCreate(String channelType, String channelUserId, String displayName) {
        String safeChannelType = channelType == null || channelType.isBlank() ? "unknown" : channelType.trim().toLowerCase();
        String safeChannelUserId = channelUserId == null ? "" : channelUserId.trim();
        UserChannelBindingEntity binding = bindingRepository.findByChannelTypeAndChannelUserId(safeChannelType, safeChannelUserId)
                .orElseGet(() -> createBinding(safeChannelType, safeChannelUserId, displayName));

        UserEntity user = userService.findById(binding.getUserId())
                .orElseThrow(() -> new IllegalStateException("绑定用户不存在: " + binding.getUserId()));
        return UserSessionContext.of(user.getId(), safeChannelType, safeChannelUserId, user.getDisplayName());
    }

    @Transactional
    public UserSessionContext resolveWechatUser(String wechatUserId, String displayName) {
        return resolveOrCreate("wechat", wechatUserId, displayName);
    }

    private UserChannelBindingEntity createBinding(String channelType, String channelUserId, String displayName) {
        String username = buildUsername(channelType, channelUserId);
        UserEntity user = userService.createUser(displayName, username);

        UserChannelBindingEntity binding = new UserChannelBindingEntity();
        binding.setUserId(user.getId());
        binding.setChannelType(channelType);
        binding.setChannelUserId(channelUserId);
        binding.setPrimaryBinding(true);
        return bindingRepository.save(binding);
    }

    private String buildUsername(String channelType, String channelUserId) {
        String normalizedType = channelType == null || channelType.isBlank() ? "unknown" : channelType.trim().toLowerCase();
        String normalizedId = channelUserId == null || channelUserId.isBlank() ? "anonymous" : channelUserId.trim();
        return normalizedType + ":" + normalizedId;
    }
}

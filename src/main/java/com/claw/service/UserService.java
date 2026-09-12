package com.claw.service;

import com.claw.entity.UserEntity;
import com.claw.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserEntity createUser(String displayName, String username) {
        UserEntity user = new UserEntity();
        user.setDisplayName(displayName);
        user.setUsername(username);
        return userRepository.save(user);
    }

    public Optional<UserEntity> findById(Long userId) {
        return userRepository.findById(userId);
    }

    public Optional<UserEntity> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Transactional
    public UserEntity save(UserEntity user) {
        return userRepository.save(user);
    }
}

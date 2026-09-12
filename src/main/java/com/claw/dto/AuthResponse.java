package com.claw.dto;

public record AuthResponse(String token, long expiresInSeconds, UserResponse user) {
    public record UserResponse(Long id, String username, String displayName) {}
}

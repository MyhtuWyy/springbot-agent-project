package com.claw.service;

public record AuthenticatedUser(Long userId, String username, String displayName, String tokenId) {}

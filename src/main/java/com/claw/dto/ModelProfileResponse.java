package com.claw.dto;

public record ModelProfileResponse(Long id, String provider, String baseUrl, String model,
                                   String maskedApiKey, Double temperature, Integer maxTokens,
                                   boolean defaultProfile) {}

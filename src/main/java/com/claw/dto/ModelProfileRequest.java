package com.claw.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModelProfileRequest(@NotBlank @Size(max=64) String provider,
                                  @NotBlank @Size(max=512) String baseUrl,
                                  @NotBlank @Size(max=128) String model,
                                  @NotBlank String apiKey,
                                  Double temperature,
                                  Integer maxTokens,
                                  Boolean defaultProfile) {}

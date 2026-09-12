package com.claw.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(@NotBlank @Size(min = 3, max = 128) String username,
                              @NotBlank @Size(min = 8, max = 128) String password,
                              @Size(max = 128) String displayName) {}

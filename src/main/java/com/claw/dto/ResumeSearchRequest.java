package com.claw.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ResumeSearchRequest(
        @NotNull Long userId,
        @NotBlank String query,
        Integer topK
) {
}

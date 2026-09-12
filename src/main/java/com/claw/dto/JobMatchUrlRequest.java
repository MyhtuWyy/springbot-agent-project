package com.claw.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record JobMatchUrlRequest(
        @NotNull Long userId,
        String platform,
        String jobTitle,
        String companyName,
        String city,
        @NotBlank String jobUrl,
        String jobDescription,
        Long resumeDocumentId
) {
}

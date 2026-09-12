package com.claw.controller;

import com.claw.dto.JobMatchUrlRequest;
import com.claw.entity.JobPostingEntity;
import com.claw.service.JobMatchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/job-match")
public class JobMatchController {
    private final JobMatchService jobMatchService;

    public JobMatchController(JobMatchService jobMatchService) {
        this.jobMatchService = jobMatchService;
    }

    @PostMapping("/url")
    public JobPostingEntity matchUrl(@Valid @RequestBody JobMatchUrlRequest request) {
        return jobMatchService.matchUrl(
                request.userId(),
                request.platform(),
                request.jobTitle(),
                request.companyName(),
                request.city(),
                request.jobUrl(),
                request.jobDescription(),
                request.resumeDocumentId()
        );
    }

    @GetMapping("/records")
    public List<JobPostingEntity> list(@RequestParam Long userId) {
        return jobMatchService.list(userId);
    }
}

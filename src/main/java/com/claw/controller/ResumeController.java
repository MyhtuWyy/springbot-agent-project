package com.claw.controller;

import com.claw.dto.ResumeSearchRequest;
import com.claw.dto.ResumeUploadResponse;
import com.claw.entity.ResumeDocumentEntity;
import com.claw.service.ResumeKnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    private final ResumeKnowledgeService resumeKnowledgeService;

    public ResumeController(ResumeKnowledgeService resumeKnowledgeService) {
        this.resumeKnowledgeService = resumeKnowledgeService;
    }

    @PostMapping("/upload")
    public ResumeUploadResponse upload(@RequestParam @NotNull Long userId,
                                       @RequestParam(required = false) String resumeName,
                                       @RequestPart MultipartFile file) throws Exception {
        ResumeDocumentEntity document = resumeKnowledgeService.ingestResume(
                userId,
                resumeName,
                file.getOriginalFilename(),
                file.getBytes()
        );
        return new ResumeUploadResponse(
                document.getId(),
                document.getUserId(),
                document.getResumeName(),
                document.getChunkCount(),
                "uploaded"
        );
    }

    @PostMapping("/search")
    public String search(@Valid @RequestBody ResumeSearchRequest request) {
        int topK = request.topK() == null || request.topK() <= 0 ? 5 : request.topK();
        return resumeKnowledgeService.buildRagContext(request.userId(), request.query(), topK);
    }

    @GetMapping("/{userId}")
    public List<ResumeDocumentEntity> list(@PathVariable Long userId) {
        return resumeKnowledgeService.listResumes(userId);
    }

    @DeleteMapping("/{userId}/{documentId}")
    public String delete(@PathVariable Long userId, @PathVariable Long documentId) {
        return resumeKnowledgeService.deleteResume(userId, documentId) ? "OK" : "NOT_FOUND";
    }
}

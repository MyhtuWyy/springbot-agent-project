package com.claw.service;

import com.claw.entity.JobPostingEntity;
import com.claw.entity.ResumeChunkEntity;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JobMatchingService {
    private final ResumeKnowledgeService resumeKnowledgeService;

    public JobMatchingService(ResumeKnowledgeService resumeKnowledgeService) {
        this.resumeKnowledgeService = resumeKnowledgeService;
    }

    public MatchResult evaluate(Long userId, JobPostingEntity posting) {
        String query = buildQuery(posting);
        List<ResumeKnowledgeService.ResumeSearchHit> hits = resumeKnowledgeService.search(
                userId,
                query,
                5,
                posting.getResumeDocumentId()
        );
        String ragContext = resumeKnowledgeService.buildRagContext(
                userId,
                query,
                5,
                posting.getResumeDocumentId()
        );

        double score = 0.0d;
        if (!hits.isEmpty()) {
            double total = 0.0d;
            for (ResumeKnowledgeService.ResumeSearchHit hit : hits) {
                total += hit.score();
            }
            score = Math.min(100.0d, Math.max(0.0d, (total / hits.size()) * 100.0d));
        }

        return new MatchResult(score, buildSummary(posting, hits, score), ragContext);
    }

    private String buildQuery(JobPostingEntity posting) {
        StringBuilder sb = new StringBuilder();
        append(sb, posting.getJobTitle());
        append(sb, posting.getCompanyName());
        append(sb, posting.getCity());
        append(sb, posting.getJobDescription());
        return sb.toString().trim();
    }

    private void append(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(text.trim());
    }

    private String buildSummary(JobPostingEntity posting,
                                List<ResumeKnowledgeService.ResumeSearchHit> hits,
                                double score) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u5c97\u4f4d\u300a")
                .append(posting.getJobTitle())
                .append("\u300b\u5339\u914d\u5206\u7ea6 ")
                .append(String.format("%.1f", score))
                .append(" \u5206\u3002");
        if (hits.isEmpty()) {
            sb.append("\u5f53\u524d\u7b80\u5386\u5e93\u6ca1\u6709\u53ec\u56de\u5230\u660e\u663e\u76f8\u5173\u5185\u5bb9\uff0c\u5efa\u8bae\u4eba\u5de5\u518d\u770b\u5c97\u4f4d\u63cf\u8ff0\u3002");
            return sb.toString();
        }
        sb.append(" \u53ec\u56de\u5230 ").append(hits.size()).append(" \u6bb5\u76f8\u5173\u7b80\u5386\u5185\u5bb9\u3002");
        ResumeChunkEntity bestChunk = hits.get(0).chunk();
        sb.append(" \u6700\u76f8\u5173\u7247\u6bb5\uff1a").append(trim(bestChunk.getContent(), 120));
        return sb.toString();
    }

    private String trim(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxLen ? normalized : normalized.substring(0, maxLen) + "...";
    }

    public record MatchResult(double score, String summary, String ragContext) {
    }
}

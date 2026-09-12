package com.claw.service;

import com.claw.entity.ResumeChunkEntity;
import com.claw.entity.ResumeDocumentEntity;
import com.claw.repository.ResumeChunkRepository;
import com.claw.repository.ResumeDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ResumeKnowledgeService {
    private final FileParseService fileParseService;
    private final ResumeChunkingService chunkingService;
    private final ResumeEmbeddingService embeddingService;
    private final QdrantResumeVectorService qdrantResumeVectorService;
    private final ResumeDocumentRepository documentRepository;
    private final ResumeChunkRepository chunkRepository;

    public ResumeKnowledgeService(FileParseService fileParseService,
                                  ResumeChunkingService chunkingService,
                                  ResumeEmbeddingService embeddingService,
                                  QdrantResumeVectorService qdrantResumeVectorService,
                                  ResumeDocumentRepository documentRepository,
                                  ResumeChunkRepository chunkRepository) {
        this.fileParseService = fileParseService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.qdrantResumeVectorService = qdrantResumeVectorService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    @Transactional
    public ResumeDocumentEntity ingestResume(Long userId, String resumeName, String fileName, byte[] fileBytes) {
        String text = fileParseService.parseFile(fileName, fileBytes);
        return ingestResumeText(userId, resumeName, fileName, "file", text);
    }

    @Transactional
    public ResumeDocumentEntity ingestResumeText(Long userId, String resumeName, String sourceFileName, String sourceType, String text) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isBlank()) {
            throw new IllegalArgumentException("resume content is empty");
        }

        String safeName = (resumeName == null || resumeName.isBlank()) ? "resume-" + userId : resumeName.trim();
        ResumeDocumentEntity document = new ResumeDocumentEntity();
        document.setUserId(userId);
        document.setResumeName(safeName);
        document.setSourceFileName(sourceFileName);
        document.setSourceType(sourceType);
        document.setRawText(normalizedText);
        document.setEmbeddingModel("local-hash-128");
        document.setChunkCount(0);
        document = documentRepository.save(document);

        List<String> chunks = chunkingService.chunk(normalizedText);
        int index = 0;
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            ResumeChunkEntity entity = new ResumeChunkEntity();
            entity.setDocumentId(document.getId());
            entity.setUserId(userId);
            entity.setChunkIndex(index++);
            entity.setChunkType("body");
            entity.setContent(chunk.trim());
            entity.setEmbeddingJson(embeddingService.serialize(embeddingService.embed(chunk)));
            entity.setKeywords(buildKeywords(chunk));
            entity = chunkRepository.save(entity);
            qdrantResumeVectorService.upsertChunk(
                    entity.getId(),
                    userId,
                    document.getId(),
                    entity.getChunkIndex(),
                    entity.getChunkType(),
                    entity.getKeywords(),
                    entity.getContent()
            );
        }

        document.setChunkCount(index);
        return documentRepository.save(document);
    }

    @Transactional(readOnly = true)
    public List<ResumeSearchHit> search(Long userId, String query, int topK) {
        return search(userId, query, topK, null);
    }

    @Transactional(readOnly = true)
    public List<ResumeSearchHit> search(Long userId, String query, int topK, Long resumeDocumentId) {
        if (userId == null || query == null || query.isBlank()) {
            return List.of();
        }
        List<QdrantResumeVectorService.SearchHit> vectorHits = qdrantResumeVectorService.search(userId, query, topK);

        List<ResumeSearchHit> hits = new ArrayList<>();
        for (QdrantResumeVectorService.SearchHit vectorHit : vectorHits) {
            if (vectorHit.pointId() <= 0) {
                continue;
            }
            ResumeChunkEntity chunk = chunkRepository.findById(vectorHit.pointId()).orElse(null);
            if (chunk == null || !chunk.getUserId().equals(userId)) {
                continue;
            }
            if (resumeDocumentId != null && !resumeDocumentId.equals(chunk.getDocumentId())) {
                continue;
            }
            double score = vectorHit.score() + keywordBoost(query.trim().toLowerCase(), chunk.getContent(), chunk.getKeywords());
            hits.add(new ResumeSearchHit(chunk, score));
        }

        if (hits.isEmpty() && resumeDocumentId != null) {
            for (ResumeChunkEntity chunk : chunkRepository.findByDocumentIdOrderByChunkIndexAsc(resumeDocumentId)) {
                if (!chunk.getUserId().equals(userId)) {
                    continue;
                }
                double score = keywordBoost(query.trim().toLowerCase(), chunk.getContent(), chunk.getKeywords());
                if (score > 0.0d) {
                    hits.add(new ResumeSearchHit(chunk, score));
                }
            }
        }

        hits.sort(Comparator.comparingDouble(ResumeSearchHit::score).reversed());
        int limit = Math.max(1, topK);
        if (hits.size() > limit) {
            return hits.subList(0, limit);
        }
        return hits;
    }

    @Transactional(readOnly = true)
    public String buildRagContext(Long userId, String query, int topK) {
        return buildRagContext(userId, query, topK, null);
    }

    @Transactional(readOnly = true)
    public String buildRagContext(Long userId, String query, int topK, Long resumeDocumentId) {
        List<ResumeSearchHit> hits = search(userId, query, topK, resumeDocumentId);
        if (hits.isEmpty()) {
            return "NO_RELEVANT_RESUME_CONTEXT";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Resume context for query: ").append(query == null ? "" : query.trim()).append("\n");
        for (int i = 0; i < hits.size(); i++) {
            ResumeSearchHit hit = hits.get(i);
            ResumeChunkEntity chunk = hit.chunk();
            sb.append(i + 1).append(". score=").append(String.format("%.4f", hit.score()));
            sb.append(" docId=").append(chunk.getDocumentId());
            sb.append(" chunkIndex=").append(chunk.getChunkIndex()).append("\n");
            sb.append(trimSnippet(chunk.getContent(), 420)).append("\n");
        }
        return sb.toString().trim();
    }

    @Transactional(readOnly = true)
    public List<ResumeDocumentEntity> listResumes(Long userId) {
        return documentRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public ResumeDocumentEntity getLatestResume(Long userId) {
        if (userId == null) {
            return null;
        }
        return documentRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public ResumeDocumentEntity findLatestBySourceFileName(Long userId, String sourceFileName) {
        if (userId == null || sourceFileName == null || sourceFileName.isBlank()) {
            return null;
        }
        return documentRepository.findFirstByUserIdAndSourceFileNameOrderByUpdatedAtDesc(userId, sourceFileName.trim());
    }

    @Transactional
    public boolean deleteResume(Long userId, Long documentId) {
        ResumeDocumentEntity document = documentRepository.findById(documentId).orElse(null);
        if (document == null || !document.getUserId().equals(userId)) {
            return false;
        }
        qdrantResumeVectorService.deleteByDocumentId(documentId);
        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.delete(document);
        return true;
    }

    private String buildKeywords(String text) {
        List<String> tokens = embeddingService.tokenize(text);
        return String.join(" ", tokens.stream()
                .distinct()
                .limit(30)
                .toList());
    }

    private double keywordBoost(String normalizedQuery, String content, String keywords) {
        String haystack = normalize(content) + " " + normalize(keywords);
        double boost = 0.0d;
        for (String token : normalizedQuery.split("\\s+")) {
            if (token.length() < 2) {
                continue;
            }
            if (haystack.contains(token)) {
                boost += 0.08d;
            }
        }
        if (haystack.contains(normalizedQuery)) {
            boost += 0.18d;
        }
        return Math.min(boost, 0.35d);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase();
    }

    private String trimSnippet(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxLen ? normalized : normalized.substring(0, maxLen) + "...";
    }

    public record ResumeSearchHit(ResumeChunkEntity chunk, double score) {
    }
}

package com.claw.repository;

import com.claw.entity.ResumeChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResumeChunkRepository extends JpaRepository<ResumeChunkEntity, Long> {
    List<ResumeChunkEntity> findByUserIdOrderByDocumentIdAscChunkIndexAsc(Long userId);

    List<ResumeChunkEntity> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    void deleteByDocumentId(Long documentId);
}

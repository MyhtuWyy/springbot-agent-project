package com.claw.repository;

import com.claw.entity.ResumeDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResumeDocumentRepository extends JpaRepository<ResumeDocumentEntity, Long> {
    List<ResumeDocumentEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    ResumeDocumentEntity findFirstByUserIdOrderByUpdatedAtDesc(Long userId);

    ResumeDocumentEntity findFirstByUserIdAndSourceFileNameOrderByUpdatedAtDesc(Long userId, String sourceFileName);
}

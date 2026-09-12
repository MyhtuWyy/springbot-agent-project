package com.claw.repository;

import com.claw.entity.JobPostingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobPostingRepository extends JpaRepository<JobPostingEntity, Long> {
    List<JobPostingEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<JobPostingEntity> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, String status);
}

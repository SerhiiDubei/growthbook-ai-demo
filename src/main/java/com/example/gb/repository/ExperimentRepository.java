package com.example.gb.repository;

import com.example.gb.model.Experiment;
import com.example.gb.model.enums.ExperimentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentRepository extends JpaRepository<Experiment, Long> {

    Page<Experiment> findByPageKeyOrderByUpdatedAtDesc(String pageKey, Pageable pageable);

    Optional<Experiment> findByPageKeyAndKey(String pageKey, String key);

    boolean existsByPageKeyAndKey(String pageKey, String key);

    long countByStatus(ExperimentStatus status);

    // Prefer ACTIVE experiment if multiple share the same featureKey
    Optional<Experiment> findFirstByFeatureKeyAndStatusOrderByUpdatedAtDesc(String featureKey, ExperimentStatus status);

    Optional<Experiment> findFirstByFeatureKeyOrderByUpdatedAtDesc(String featureKey);

    /** Find stale PENDING experiments older than a given time (for cleanup job). */
    List<Experiment> findByStatusAndUpdatedAtBefore(ExperimentStatus status, LocalDateTime before);

}
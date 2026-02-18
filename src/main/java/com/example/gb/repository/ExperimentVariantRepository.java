package com.example.gb.repository;

import com.example.gb.model.ExperimentVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExperimentVariantRepository extends JpaRepository<ExperimentVariant, Long> {

    List<ExperimentVariant> findByExperimentIdOrderBySortOrderAscIdAsc(Long experimentId);

    Optional<ExperimentVariant> findByExperimentIdAndKey(Long experimentId, String key);

    boolean existsByExperimentIdAndKey(Long experimentId, String key);

    void deleteAllByExperimentId(Long experimentId);
}

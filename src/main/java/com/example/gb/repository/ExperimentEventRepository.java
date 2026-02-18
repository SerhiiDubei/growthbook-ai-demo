package com.example.gb.repository;

import com.example.gb.model.ExperimentEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExperimentEventRepository
        extends JpaRepository<ExperimentEvent, Long> {

    long countByFeatureKeyAndAction(String featureKey, String action);

    long countByFeatureKeyAndActionAndVariation(
            String featureKey,
            String action,
            String variation
    );

    List<ExperimentEvent> findByFeatureKey(String featureKey);

    Page<ExperimentEvent> findByFeatureKeyOrderByCreatedAtDesc(String featureKey, Pageable pageable);

    // ---- Per-variant counts for A/B stats ----

    @Query("SELECT COUNT(e) FROM ExperimentEvent e " +
           "WHERE e.featureKey = :featureKey AND e.variantKey = :variantKey AND e.action = :action")
    long countByFeatureKeyAndVariantKeyAndAction(
            @Param("featureKey") String featureKey,
            @Param("variantKey") String variantKey,
            @Param("action") String action
    );

    /** Unique sessions (users) per variant + action */
    @Query("SELECT COUNT(DISTINCT e.sessionTag) FROM ExperimentEvent e " +
           "WHERE e.featureKey = :featureKey AND e.variantKey = :variantKey AND e.action = :action")
    long countDistinctSessionsByFeatureKeyAndVariantKeyAndAction(
            @Param("featureKey") String featureKey,
            @Param("variantKey") String variantKey,
            @Param("action") String action
    );

    /** All distinct variant keys that have at least one event for this featureKey */
    @Query("SELECT DISTINCT e.variantKey FROM ExperimentEvent e " +
           "WHERE e.featureKey = :featureKey AND e.variantKey IS NOT NULL")
    List<String> findDistinctVariantKeysByFeatureKey(@Param("featureKey") String featureKey);
}
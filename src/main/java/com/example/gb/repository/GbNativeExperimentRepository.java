package com.example.gb.repository;

import com.example.gb.model.GbNativeExperiment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GbNativeExperimentRepository extends JpaRepository<GbNativeExperiment, Long> {

    Optional<GbNativeExperiment> findByGbExperimentId(String gbExperimentId);

    Optional<GbNativeExperiment> findByTrackingKey(String trackingKey);

    Page<GbNativeExperiment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<GbNativeExperiment> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}

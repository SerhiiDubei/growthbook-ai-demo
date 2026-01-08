package com.example.gb.repository;

import com.example.gb.model.ExperimentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentEventRepository
        extends JpaRepository<ExperimentEvent, Long> {
}
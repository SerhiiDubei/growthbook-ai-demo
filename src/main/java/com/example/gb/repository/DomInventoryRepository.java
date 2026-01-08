package com.example.gb.repository;

import com.example.gb.model.DomInventoryLatest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomInventoryRepository  extends JpaRepository<DomInventoryLatest, Long> {

    Optional<DomInventoryLatest> findByPageKey(String pageKey);
    Optional<DomInventoryLatest> findTopByOriginOrderByLastSeenAtDesc(String origin);
}

package com.example.gb.repository;

import com.example.gb.model.DomInventoryLatest;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomInventoryLatestRepo extends JpaRepository<DomInventoryLatest, Long> {

    Optional<DomInventoryLatest> findByPageKey(String pageKey);

    Optional<DomInventoryLatest> findFirstByPageUrlOrderByUpdatedAtDesc(String pageUrl);

    List<DomInventoryLatest> findTop50ByOrderByUpdatedAtDesc();
}
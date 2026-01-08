package com.example.gb.repository;

import com.example.gb.model.DomEvent;
import com.example.gb.model.dto.DomEventStatsProjection;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DomEventRepository extends JpaRepository<DomEvent, Long> {
    @Query("""
        select e.featureKey as featureKey,
               e.variant as variant,
               sum(case when upper(e.eventType) = 'VIEW' then 1 else 0 end)  as views,
               sum(case when upper(e.eventType) = 'CLICK' then 1 else 0 end) as clicks
        from DomEvent e
        where e.featureKey = :featureKey
          and e.eventTs >= :from
        group by e.featureKey, e.variant
        """)
    List<DomEventStatsProjection> aggregateByFeatureSince(String featureKey, Instant from);
}


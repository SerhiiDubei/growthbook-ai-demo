package com.example.gb.model.dto;

import java.time.Instant;
import lombok.Data;

@Data
public class DomEventStatsDTO {
    private String featureKey;
    private String variant;
    private long views;
    private long clicks;
    private double ctr;      // 0..1
    private Instant fromTs;
    private Instant toTs;
}

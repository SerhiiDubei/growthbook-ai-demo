package com.example.gb.model.dto;


public interface DomEventStatsProjection {
    String getFeatureKey();
    String getVariant();
    long getViews();
    long getClicks();
}

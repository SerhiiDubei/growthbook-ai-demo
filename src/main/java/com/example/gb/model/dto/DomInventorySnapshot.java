package com.example.gb.model.dto;

public record DomInventorySnapshot(
        String pageKey,
        String pageUrl,
        String origin,
        String itemsJson,
        String itemsHash
) {}

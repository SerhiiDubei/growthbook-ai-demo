package com.example.gb.model;


import lombok.Data;
import java.util.Map;

@Data
public class UpsertFeatureRequest {
    private String id;                   // ID фічі (наприклад, "dom_patch_hero_v1")
    private Map<String, Object> json;    // сам рецепт (об’єкт з ops, vars і т.д.)
    private String tag;                  // опціонально: sessionTag (QA123 тощо)
}

package com.example.gb.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class UpsertForTagDto {
    @NotBlank
    private String id;         // feature id, напр. "dom_patch_hero_v1"
    @NotBlank
    private String tag;        // sessionTag, напр. "QA123"
    private String owner = "API";
    private Map<String, Object> json; // твій рецепт: { "ops": [ ... ], "vars": {...} }
}

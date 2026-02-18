package com.example.gb.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ExperimentFinishRequest {
    @Size(max = 2000)
    private String notes;
}

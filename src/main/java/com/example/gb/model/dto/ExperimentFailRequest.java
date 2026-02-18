package com.example.gb.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ExperimentFailRequest {
    @Size(max = 2000)
    private String error; // людське пояснення або exception message
}

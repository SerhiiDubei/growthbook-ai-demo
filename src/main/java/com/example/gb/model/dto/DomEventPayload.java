package com.example.gb.model.dto;

import java.util.List;
import lombok.Data;

@Data
public class DomEventPayload {
    private String sessionId;
    private String url;
    private String eventType; // VIEW, CLICK, CONVERSION
    private Long timestamp;
    private String selector;  // для CLICK/CONVERSION
    private String featureKey;
    private String variant;
    private List<DomEventItemDTO> items; // для VIEW по сторінці
}

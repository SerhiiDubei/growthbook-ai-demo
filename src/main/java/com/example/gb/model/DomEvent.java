package com.example.gb.model;

import com.example.gb.model.base.AbstractVersional;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "dom_event")
public class DomEvent extends AbstractVersional {

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "url", length = 1000)
    private String url;

    @Column(name = "event_type", length = 32)
    private String eventType; // VIEW, CLICK, CONVERSION

    @Column(name = "event_ts")
    private Instant eventTs;

    @Column(name = "selector", length = 500)
    private String selector;

    @Column(name = "feature_key", length = 500)
    private String featureKey;

    @Column(name = "variant", length = 100)
    private String variant;

}

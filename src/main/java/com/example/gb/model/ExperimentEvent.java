package com.example.gb.model;

import com.example.gb.model.base.AbstractVersional;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "experiment_event",
        indexes = {
                @Index(name = "ix_experiment_event_experiment_id", columnList = "experiment_id"),
                @Index(name = "ix_experiment_event_feature_key", columnList = "feature_key")
        }
)
public class ExperimentEvent extends AbstractVersional {

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "experiment_id", nullable = true)
    private Experiment experiment;

    @Column(name = "feature_key", nullable = false, length = 200)
    private String featureKey;      // localhost_8080__home__cta_...

    @Column(name = "variation", length = 50)
    private String variation;       // "on", "off", "A", "B"...

    @Column(name = "session_tag", length = 200)
    private String sessionTag;      // gbtag / gb_tag cookie

    @Column(name = "page", length = 200)
    private String page;            // "home", "signup"...

    @Column(name = "action", length = 50)
    private String action;          // "view", "click", "conversion"

    /**
     * Which A/B variant this user was assigned to.
     * Null for events logged before A/B variant support was added.
     */
    @Column(name = "variant_key", length = 100)
    private String variantKey;      // "control", "treatment", "A", "B"

    @Column(name = "meta_json", columnDefinition = "text")
    private String metaJson;        // JSON string
}

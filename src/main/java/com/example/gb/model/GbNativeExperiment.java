package com.example.gb.model;

import com.example.gb.model.base.AbstractVersional;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "gb_native_experiments")
public class GbNativeExperiment extends AbstractVersional {

    @Column(name = "gb_experiment_id", nullable = false, unique = true, length = 100)
    private String gbExperimentId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "tracking_key", nullable = false, length = 255)
    private String trackingKey;

    /** draft | running | stopped | archived */
    @Column(name = "status", nullable = false, length = 50)
    private String status = "draft";

    @Column(name = "feature_key", length = 255)
    private String featureKey;

    @Column(name = "hypothesis", columnDefinition = "TEXT")
    private String hypothesis;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** JSON condition string, e.g. {} or {"country":"UA"} */
    @Column(name = "targeting_condition", nullable = false, columnDefinition = "TEXT")
    private String targetingCondition = "{}";

    /** JSON array: [{key:"control",name:"Control"},{key:"treatment",name:"Treatment"}] */
    @Column(name = "variations_json", nullable = false, columnDefinition = "TEXT")
    private String variationsJson = "[]";

    /** JSON array of weights: [0.5, 0.5] */
    @Column(name = "weights_json", nullable = false, columnDefinition = "TEXT")
    private String weightsJson = "[]";

    /** Traffic coverage 0.0..1.0 */
    @Column(name = "coverage", nullable = false)
    private Double coverage = 1.0;

    /** Optional link to our local Experiment entity */
    @Column(name = "local_experiment_id")
    private Long localExperimentId;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy = "agent";
}

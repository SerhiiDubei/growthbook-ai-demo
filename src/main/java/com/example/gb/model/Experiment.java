package com.example.gb.model;

import com.example.gb.model.base.AbstractVersional;
import com.example.gb.model.enums.AutonomyLevel;
import com.example.gb.model.enums.ExperimentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(
        name = "experiments",
        uniqueConstraints = @UniqueConstraint(name = "uk_exp_page_key_key", columnNames = {"page_key", "key"})
)
public class Experiment extends AbstractVersional {

    @Column(name = "page_key", nullable = false, length = 200)
    private String pageKey;

    @Column(name = "page_url")
    private String pageUrl;

    @Column(name = "key", nullable = false, length = 200)
    private String key; // unique within pageKey

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "feature_key", nullable = false, length = 300)
    private String featureKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ExperimentStatus status = ExperimentStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "autonomy_level", nullable = false, length = 32)
    private AutonomyLevel autonomyLevel = AutonomyLevel.AGENT_FULL;

    @Column(name = "owner", length = 100)
    private String owner;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipe_json", nullable = false, columnDefinition = "jsonb")
    private String recipeJson;

    @Column(name = "primary_metric", length = 64)
    private String primaryMetric;

    @Column(name = "notes")
    private String notes;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "last_error")
    private String lastError;

    /**
     * A/B variants for this experiment.
     * Empty = single-arm (100% one recipe — legacy behaviour).
     * Populated = A/B mode: weights must sum to 1.0.
     */
    @OneToMany(
            mappedBy = "experiment",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("sortOrder ASC, id ASC")
    private List<ExperimentVariant> variants = new ArrayList<>();
}

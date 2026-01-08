package com.example.gb.model;

import com.example.gb.model.base.AbstractVersional;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "experiment_event")
public class ExperimentEvent extends AbstractVersional {

    @Column(name = "feature_key", nullable = false, length = 200)
    private String featureKey;      // localhost_8080__home__cta_...

    @Column(name = "variation", length = 50)
    private String variation;       // "on", "off", "A", "B"...

    @Column(name = "session_tag", length = 200)
    private String sessionTag;      // твій gbtag / gb_tag cookie

    @Column(name = "page", length = 200)
    private String page;            // "home", "signup" і т.п.

    @Column(name = "action", length = 50)
    private String action;          // "view", "click", "conversion"

    @Column(name = "meta_json", columnDefinition = "text")
    private String metaJson;        // будь-які додаткові дані (JSON рядок)
}

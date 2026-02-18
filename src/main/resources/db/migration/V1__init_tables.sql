-- ============================================================
-- V1: Initial schema — all tables from scratch
-- ============================================================

-- DOM snapshot: latest state of DOM elements per page
CREATE TABLE dom_inventory_latest (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    page_key    VARCHAR(255)  NOT NULL,
    origin      VARCHAR(255)  NOT NULL,
    page_url    TEXT          NOT NULL,
    items_json  JSONB,
    items_hash  VARCHAR(64)   NOT NULL,
    last_seen_at TIMESTAMP    NOT NULL,
    CONSTRAINT uk_dom_inventory_page_key UNIQUE (page_key)
);

-- DOM interaction events (click / view / conversion)
CREATE TABLE dom_event (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP,
    session_id  VARCHAR(128),
    url         VARCHAR(1000),
    event_type  VARCHAR(32),
    event_ts    TIMESTAMPTZ,
    selector    VARCHAR(500),
    feature_key VARCHAR(500),
    variant     VARCHAR(100)
);

-- A/B Experiments managed by the AI agent
CREATE TABLE experiments (
    id             BIGSERIAL PRIMARY KEY,
    created_at     TIMESTAMP,
    updated_at     TIMESTAMP,
    page_key       VARCHAR(200) NOT NULL,
    page_url       VARCHAR(255),
    key            VARCHAR(200) NOT NULL,
    title          VARCHAR(300) NOT NULL,
    description    VARCHAR(255),
    feature_key    VARCHAR(300) NOT NULL,
    status         VARCHAR(32)  NOT NULL
                       CHECK (status IN ('DRAFT','ACTIVE','PAUSED','FINISHED','FAILED')),
    autonomy_level VARCHAR(32)  NOT NULL
                       CHECK (autonomy_level IN ('AGENT_FULL','HUMAN_APPROVAL')),
    owner          VARCHAR(100),
    recipe_json    JSONB        NOT NULL,
    primary_metric VARCHAR(64),
    notes          VARCHAR(255),
    started_at     TIMESTAMP,
    finished_at    TIMESTAMP,
    last_error     VARCHAR(255),
    CONSTRAINT uk_exp_page_key_key UNIQUE (page_key, key)
);

-- Events tied to experiment impressions / clicks / conversions
CREATE TABLE experiment_event (
    id            BIGSERIAL PRIMARY KEY,
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP,
    experiment_id BIGINT       REFERENCES experiments (id) ON DELETE SET NULL,
    feature_key   VARCHAR(200) NOT NULL,
    variation     VARCHAR(50),
    session_tag   VARCHAR(200),
    page          VARCHAR(200),
    action        VARCHAR(50),
    variant_key   VARCHAR(100),
    meta_json     TEXT
);

CREATE INDEX ix_experiment_event_experiment_id ON experiment_event (experiment_id);
CREATE INDEX ix_experiment_event_feature_key   ON experiment_event (feature_key);

-- A/B variants for an experiment (control + treatment + ...)
CREATE TABLE experiment_variant (
    id            BIGSERIAL PRIMARY KEY,
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP,
    experiment_id BIGINT          NOT NULL REFERENCES experiments (id) ON DELETE CASCADE,
    key           VARCHAR(100)    NOT NULL,
    name          VARCHAR(200),
    weight        DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    recipe_json   TEXT            NOT NULL DEFAULT '{"ops":[]}',
    sort_order    INTEGER         NOT NULL DEFAULT 0,
    CONSTRAINT uk_exp_variant_exp_key UNIQUE (experiment_id, key)
);

CREATE INDEX ix_exp_variant_experiment_id ON experiment_variant (experiment_id);

-- ============================================================
-- V3: Native GrowthBook experiments table
-- Tracks experiments created directly via GrowthBook Admin API
-- ============================================================

CREATE TABLE IF NOT EXISTS gb_native_experiments (
    id                  BIGSERIAL PRIMARY KEY,
    gb_experiment_id    VARCHAR(100)  NOT NULL UNIQUE,  -- ID from GrowthBook (exp_...)
    name                VARCHAR(255)  NOT NULL,
    tracking_key        VARCHAR(255)  NOT NULL,
    status              VARCHAR(50)   NOT NULL DEFAULT 'draft', -- draft | running | stopped | archived
    feature_key         VARCHAR(255),                           -- linked GB feature key (optional)
    hypothesis          TEXT,
    description         TEXT,
    targeting_condition TEXT          NOT NULL DEFAULT '{}',    -- JSON condition for phase
    variations_json     TEXT          NOT NULL DEFAULT '[]',    -- JSON array of {key, name}
    weights_json        TEXT          NOT NULL DEFAULT '[]',    -- JSON array of weights [0.5, 0.5]
    coverage            DOUBLE PRECISION NOT NULL DEFAULT 1.0,  -- 0.0..1.0 traffic coverage
    local_experiment_id BIGINT        REFERENCES experiments(id) ON DELETE SET NULL,
    created_by          VARCHAR(100)  NOT NULL DEFAULT 'agent',
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_gb_native_exp_status      ON gb_native_experiments(status);
CREATE INDEX IF NOT EXISTS idx_gb_native_exp_feature_key ON gb_native_experiments(feature_key);
CREATE INDEX IF NOT EXISTS idx_gb_native_exp_created_at  ON gb_native_experiments(created_at DESC);

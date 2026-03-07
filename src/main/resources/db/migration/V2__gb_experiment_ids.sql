-- ============================================================
-- V2: Store native GrowthBook Experiment / Variation IDs
-- ============================================================

-- Native GB experiment ID (returned by POST /api/v1/experiments)
ALTER TABLE experiments
    ADD COLUMN IF NOT EXISTS gb_experiment_id VARCHAR(100);

-- Native GB variation ID per variant (returned inside experiment.variations[].id)
ALTER TABLE experiment_variant
    ADD COLUMN IF NOT EXISTS gb_variation_id VARCHAR(100);

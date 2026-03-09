-- Add PENDING to the experiments status check constraint.
-- PENDING is a transient state used by the Saga pattern during GrowthBook sync:
-- the experiment is saved as PENDING before GB sync completes, then transitions
-- to DRAFT (success) or FAILED (compensation). The cleanup job handles stale PENDING.

ALTER TABLE experiments
    DROP CONSTRAINT IF EXISTS experiments_status_check;

ALTER TABLE experiments
    ADD CONSTRAINT experiments_status_check
        CHECK (status IN ('PENDING', 'DRAFT', 'ACTIVE', 'PAUSED', 'FINISHED', 'FAILED'));

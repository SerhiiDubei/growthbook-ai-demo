package com.example.gb.model.enums;

public enum ExperimentStatus {
    /** Transient state: GB sync in progress. If app crashes here, cleanup job will compensate. */
    PENDING,
    DRAFT,
    ACTIVE,
    PAUSED,
    FINISHED,
    FAILED
}

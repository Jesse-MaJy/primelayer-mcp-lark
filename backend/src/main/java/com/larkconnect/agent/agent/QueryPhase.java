package com.larkconnect.agent.agent;

public enum QueryPhase {
    /** Backward-compatible value for checkpoints created before staged orchestration. */
    DECIDING,
    CONTEXT,
    PLANNING,
    DISCOVERING,
    MATCH_DISCOVERY,
    LIST_DISCOVERY,
    REPLANNING,
    COLLECTION_PLANNING,
    COLLECTING,
    FETCHING_PAGE,
    POLLING_ASYNC,
    ANALYZING_FORMS,
    FINALIZING,
    ROUTING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED
}

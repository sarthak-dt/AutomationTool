package com.stellar.platform.api.dto;

/** Async job lifecycle status for inventory and process jobs. */
public enum JobStatusDto {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

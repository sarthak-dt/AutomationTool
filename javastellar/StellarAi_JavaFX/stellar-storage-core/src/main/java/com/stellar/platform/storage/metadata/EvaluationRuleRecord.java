package com.stellar.platform.storage.metadata;

import java.time.Instant;

/** One stored rule version. The document is immutable for that version. */
public record EvaluationRuleRecord(
        String projectId, String ruleId, int version, String document, Instant createdAt
) {
}

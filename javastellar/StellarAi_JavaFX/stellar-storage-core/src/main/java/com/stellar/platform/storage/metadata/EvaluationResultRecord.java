package com.stellar.platform.storage.metadata;

import java.time.Instant;

/** Frozen evaluation. {@code included} only selects the report. It does not change the document. */
public record EvaluationResultRecord(
        String projectId,
        String resultId,
        String eventId,
        String ruleId,
        int ruleVersion,
        boolean included,
        String document,
        Instant createdAt
) {
}

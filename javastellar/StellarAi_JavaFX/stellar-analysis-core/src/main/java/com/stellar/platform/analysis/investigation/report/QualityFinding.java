package com.stellar.platform.analysis.investigation.report;

/**
 * One evidence-backed quality statement. An absent measurement is not stored as zero.
 * Status is availability, not an invented severity threshold.
 */
public record QualityFinding(
        String id,
        String type,
        QualityStatus status,
        String sourceId,
        String sourceFile,
        String channel,
        String signal,
        String dbc,
        String time,
        String clockBasis,
        String method,
        String measured,
        String evidenceReference,
        String provenance,
        String explanation,
        String limitations
) {
    public QualityFinding {
        id = text(id);
        type = text(type);
        status = status == null ? QualityStatus.UNAVAILABLE : status;
        sourceId = text(sourceId);
        sourceFile = text(sourceFile);
        channel = text(channel);
        signal = text(signal);
        dbc = text(dbc);
        time = text(time);
        clockBasis = text(clockBasis);
        method = text(method);
        measured = text(measured);
        evidenceReference = text(evidenceReference);
        provenance = text(provenance);
        explanation = text(explanation);
        limitations = text(limitations);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}

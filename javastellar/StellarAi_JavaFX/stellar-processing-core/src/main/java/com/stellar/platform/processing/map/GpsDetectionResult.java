package com.stellar.platform.processing.map;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of semantic GPS coordinate discovery for the current signal scope.
 */
public record GpsDetectionResult(
        GpsCoordinatePair bestPair,
        List<GpsCoordinatePair> alternatives,
        boolean ambiguous,
        String summary,
        GpsDetectionConfidence confidence,
        int scannedSignals,
        int latitudeCandidates,
        int longitudeCandidates
) {
    public GpsDetectionResult {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        summary = summary == null ? "" : summary;
        confidence = confidence == null ? GpsDetectionConfidence.NONE : confidence;
    }

    public static GpsDetectionResult none(String reason) {
        return none(reason, GpsDetectionConfidence.NONE, 0, 0, 0);
    }

    public static GpsDetectionResult none(
            String reason,
            GpsDetectionConfidence confidence,
            int scanned,
            int latCandidates,
            int lonCandidates) {
        return new GpsDetectionResult(
                null, List.of(), false, reason, confidence, scanned, latCandidates, lonCandidates);
    }

    public Optional<GpsCoordinatePair> pair() {
        return Optional.ofNullable(bestPair);
    }

    public boolean hasRoute() {
        return bestPair != null;
    }

    /** True when Map Route may auto-render without user confirmation. */
    public boolean hasReliableRoute() {
        return bestPair != null && confidence.isReliable();
    }
}

package com.stellar.platform.processing.drive.speed;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Authoritative speed source for one measurement and one time range.
 */
public record SpeedSourceResolution(
        String sourceId,
        String schemaFingerprint,
        SpeedSourceKind kind,
        SignalCatalogKey selectedKey,
        String displayName,
        String originalUnit,
        SpeedConfidence confidence,
        SpeedQualityAssessment quality,
        QualityAwarePeak peak,
        String reason,
        List<SpeedSourceCandidate> alternatives,
        SpeedSourceMapping mapping
) {
    public SpeedSourceResolution {
        sourceId = sourceId == null ? "" : sourceId;
        schemaFingerprint = schemaFingerprint == null ? "" : schemaFingerprint;
        kind = kind == null ? SpeedSourceKind.GPS_COORDINATE_DERIVED : kind;
        displayName = displayName == null || displayName.isBlank()
                ? (kind == null ? "Speed" : kind.displayLabel()) : displayName;
        originalUnit = originalUnit == null ? "" : originalUnit;
        confidence = confidence == null ? SpeedConfidence.NONE : confidence;
        quality = quality == null ? SpeedQualityAssessment.empty() : quality;
        peak = peak == null ? QualityAwarePeak.none(quality, reason) : peak;
        reason = reason == null ? "" : reason;
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }

    public static SpeedSourceResolution unavailable(String sourceId, String fingerprint, String reason) {
        String text = reason == null || reason.isBlank() ? "No trustworthy maximum speed." : reason;
        SpeedQualityAssessment quality = SpeedQualityAssessment.empty();
        return new SpeedSourceResolution(
                sourceId, fingerprint, SpeedSourceKind.GPS_COORDINATE_DERIVED, null, "Not available",
                "", SpeedConfidence.NONE, quality, QualityAwarePeak.none(quality, text),
                text, List.of(), null);
    }

    public OptionalDouble maximumKph() {
        return peak != null && peak.present()
                ? OptionalDouble.of(peak.valueKph())
                : OptionalDouble.empty();
    }

    public boolean available() {
        return maximumKph().isPresent();
    }

    public String provenanceText() {
        if (!available()) {
            return "Maximum Speed is not available. " + reason;
        }
        StringBuilder text = new StringBuilder();
        text.append("Source: ").append(kind.displayLabel());
        if (displayName != null && !displayName.isBlank() && kind != SpeedSourceKind.GPS_COORDINATE_DERIVED) {
            text.append("\nSignal: ").append(displayName);
        }
        if (sourceId != null && !sourceId.isBlank()) {
            text.append("\nMeasurement: ").append(sourceId);
        }
        if (peak != null && peak.timestampNanos() != null) {
            text.append("\nTimestamp: ").append(String.format("%.3f s", peak.timestampNanos() / 1e9));
        }
        text.append("\nConfidence: ").append(title(confidence));
        if (reason != null && !reason.isBlank()) {
            text.append("\n").append(reason);
        }
        return text.toString();
    }

    private static String title(SpeedConfidence confidence) {
        if (confidence == null) {
            return "None";
        }
        String name = confidence.name().toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}

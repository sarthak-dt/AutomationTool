package com.stellar.platform.processing.drive.speed;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Project-wide maximum is the highest per-measurement valid peak. Series are never concatenated.
 */
public final class ProjectSpeedAggregate {

    private ProjectSpeedAggregate() {
    }

    public record Winner(
            SpeedSourceResolution resolution,
            String sourceId,
            String measurementId,
            double valueKph
    ) {
    }

    public static Optional<Winner> maximum(List<SpeedSourceResolution> perMeasurement) {
        if (perMeasurement == null || perMeasurement.isEmpty()) {
            return Optional.empty();
        }
        return perMeasurement.stream()
                .filter(r -> r != null && r.available())
                .max(Comparator
                        .comparingDouble((SpeedSourceResolution r) -> r.maximumKph().orElse(Double.NEGATIVE_INFINITY))
                        .thenComparing(SpeedSourceResolution::sourceId, String.CASE_INSENSITIVE_ORDER))
                .map(r -> new Winner(r, r.sourceId(), r.sourceId(), r.maximumKph().getAsDouble()));
    }
}

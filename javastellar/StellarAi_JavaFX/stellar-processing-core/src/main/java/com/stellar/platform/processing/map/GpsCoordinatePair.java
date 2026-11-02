package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.Objects;
import java.util.Optional;

/**
 * A latitude/longitude pair resolved for Map Route, with optional enrichments.
 */
public record GpsCoordinatePair(
        SignalCatalogKey latitude,
        SignalCatalogKey longitude,
        SignalCatalogKey altitude,
        SignalCatalogKey speed,
        SignalCatalogKey heading,
        int confidence,
        GpsDetectionConfidence level,
        String evidence
) {
    public GpsCoordinatePair {
        Objects.requireNonNull(latitude, "latitude");
        Objects.requireNonNull(longitude, "longitude");
        level = level == null ? GpsDetectionConfidence.NONE : level;
        evidence = evidence == null ? "" : evidence;
    }

    public Optional<SignalCatalogKey> altitudeOptional() {
        return Optional.ofNullable(altitude);
    }

    public Optional<SignalCatalogKey> speedOptional() {
        return Optional.ofNullable(speed);
    }

    public Optional<SignalCatalogKey> headingOptional() {
        return Optional.ofNullable(heading);
    }
}

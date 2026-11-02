package com.stellar.platform.processing.drive.speed;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.drive.DriveSemanticResolver;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GpsLocationSignalIndex;
import com.stellar.platform.processing.map.GpsMappingScope;
import com.stellar.platform.processing.map.GpsSemanticResolver;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSignalRoleResolver;
import com.stellar.platform.processing.map.MapRouteModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Selects one authoritative vehicle-speed source per measurement, then a quality-aware peak
 * for the requested time range. Catalog presentation filters are never consulted.
 */
public final class VehicleSpeedSourceResolver {

    public static final int RESOLVER_VERSION = 1;

    public SpeedSourceResolution resolve(Request request) {
        Objects.requireNonNull(request, "request");
        List<SignalCatalogEntry> scoped = GpsMappingScope.catalogFor(request.sourceId(), request.catalog());
        String sourceId = resolveSourceId(request.sourceId(), scoped);
        String fingerprint = GpsSemanticResolver.schemaFingerprint(scoped);
        SpeedSourceMapping persisted = reusable(request.persisted(), sourceId, fingerprint, scoped);
        SpeedSourceCandidate selected;
        List<SpeedSourceCandidate> alternatives;
        if (persisted != null) {
            selected = fromPersisted(persisted, scoped);
            alternatives = List.of();
        } else {
            List<SpeedSourceCandidate> ranked = rank(scoped, request.query(), request.route());
            selected = ranked.isEmpty() ? null : ranked.getFirst();
            alternatives = selected == null || ranked.size() == 1
                    ? List.of()
                    : List.copyOf(ranked.subList(1, ranked.size()));
        }
        if (selected == null) {
            return SpeedSourceResolution.unavailable(sourceId, fingerprint, "No speed source in this measurement.");
        }
        QualityAwarePeak peak = peak(selected, request);
        SpeedConfidence confidence = confidence(selected, peak);
        String reason = reason(selected, peak, persisted != null);
        SpeedQualityAssessment quality = peak.quality();
        SpeedSourceMapping mapping = new SpeedSourceMapping(
                RESOLVER_VERSION, sourceId, fingerprint, selected.kind(), selected.key(),
                originalUnit(selected), confidence, selected.reason());
        return new SpeedSourceResolution(
                sourceId, fingerprint, selected.kind(), selected.key(), selected.displayName(),
                originalUnit(selected), confidence, quality, peak, reason, alternatives, mapping);
    }

    private QualityAwarePeak peak(SpeedSourceCandidate selected, Request request) {
        if (selected.kind() == SpeedSourceKind.GPS_COORDINATE_DERIVED) {
            return GpsDerivedSpeedPeak.scan(request.route(), request.indexA(), request.indexB());
        }
        if (request.query() == null || selected.key() == null || selected.unit() == null) {
            return QualityAwarePeak.none(SpeedQualityAssessment.empty(),
                    "Selected speed source has no queryable series.");
        }
        return VehicleSpeedPeakScanner.scan(
                request.query(), selected.key(), selected.unit(), request.startNanos(), request.endNanos());
    }

    private List<SpeedSourceCandidate> rank(List<SignalCatalogEntry> scoped,
                                            GraphSeriesQuery query,
                                            MapRouteModel route) {
        ArrayList<SpeedSourceCandidate> out = new ArrayList<>();
        GpsLocationSignalIndex locations = GpsLocationSignalIndex.build(scoped);
        for (SignalCatalogEntry entry : scoped) {
            SpeedSourceCandidate candidate = classify(entry, query, locations);
            if (candidate != null) {
                out.add(candidate);
            }
        }
        if (route != null && route.size() >= 2) {
            out.add(new SpeedSourceCandidate(
                    SpeedSourceKind.GPS_COORDINATE_DERIVED, null, SpeedUnit.KILOMETRES_PER_HOUR,
                    15, 40, 55, "GPS-coordinate-derived fallback from validated route geometry."));
        }
        out.sort(COMPARATOR);
        return out;
    }

    private SpeedSourceCandidate classify(SignalCatalogEntry entry,
                                          GraphSeriesQuery query,
                                          GpsLocationSignalIndex locations) {
        if (entry == null) {
            return null;
        }
        String text = DriveSemanticResolver.tokens(entry);
        if (excluded(text)) {
            return null;
        }
        SpeedUnit unit = SpeedUnit.parse(firstNonBlank(query == null ? "" : query.unit(entry.key()), entry.unit()))
                .orElse(null);
        int gpsScore = GpsSignalRoleResolver.scoreRole(GpsSignalRole.GPS_SPEED, entry);
        boolean gpsCohort = locations != null && locations.isLocationSignal(entry);
        SpeedSourceKind kind = null;
        int semantic = 0;
        String reason = "";
        if (gpsReceiver(text, gpsScore, gpsCohort)) {
            kind = SpeedSourceKind.GPS_RECEIVER_SPEED;
            semantic = 45 + Math.min(50, Math.max(gpsScore, gpsCohort ? 20 : 0));
            reason = "GPS/GNSS receiver ground-speed evidence from catalog metadata.";
        } else if (DriveSemanticResolver.vehicleSpeed(text) || vehicleLike(text)) {
            kind = SpeedSourceKind.AUTHORITATIVE_VEHICLE;
            semantic = 90 + (DriveSemanticResolver.vehicleSpeed(text) ? 8 : 0);
            reason = "Authoritative physical vehicle-speed evidence from name, message and unit metadata.";
        } else if (dynamicsSpeed(text)) {
            kind = SpeedSourceKind.ECU_DYNAMICS;
            semantic = wheelSpeed(text) ? 72 : 58;
            reason = "ECU/vehicle-dynamics speed evidence; not GPS-derived.";
        }
        if (kind == null) {
            return null;
        }
        if (unit == null) {
            return null;
        }
        int coverage = coverageScore(entry);
        int total = semantic * 10 + coverage + sampleBonus(entry) + originBonus(entry);
        return new SpeedSourceCandidate(kind, entry, unit, semantic, coverage, total, reason);
    }

    private static boolean gpsReceiver(String text, int gpsScore, boolean gpsCohort) {
        if (gpsScore > 0) {
            return true;
        }
        boolean motion = contains(text, "speed") || contains(text, "sog") || contains(text, "velocity");
        boolean gps = contains(text, "gps") || contains(text, "gnss") || contains(text, "sog")
                || contains(text, "ground");
        return motion && gps && (gpsCohort || contains(text, "gps") || contains(text, "gnss") || contains(text, "sog"));
    }

    private static boolean vehicleLike(String text) {
        if (contains(text, "gps") || contains(text, "gnss") || contains(text, "wheel")) {
            return false;
        }
        if (contains(text, "speed") && (contains(text, "veh") || contains(text, "vehicle")
                || contains(text, "vss") || text.contains("speedo") || contains(text, "cluster"))) {
            return true;
        }
        return (contains(text, "veh") || contains(text, "vss") || text.contains("speedo"))
                && !contains(text, "engine")
                && !contains(text, "rpm");
    }

    private static boolean dynamicsSpeed(String text) {
        if (contains(text, "gps") || contains(text, "gnss") || contains(text, "engine")
                || contains(text, "rpm") || contains(text, "wind") || contains(text, "fan")) {
            return false;
        }
        return wheelSpeed(text)
                || ((contains(text, "speed") || contains(text, "velocity"))
                && !contains(text, "air") && !contains(text, "flow"));
    }

    private static boolean wheelSpeed(String text) {
        return contains(text, "wheel") && (contains(text, "speed") || contains(text, "velocity"));
    }

    private static boolean excluded(String text) {
        return contains(text, "checksum")
                || contains(text, "crc")
                || contains(text, "counter")
                || contains(text, "alive")
                || (contains(text, "rpm") && !contains(text, "vehicle"))
                || (contains(text, "engine") && contains(text, "speed") && !contains(text, "vehicle"))
                || contains(text, "wind")
                || contains(text, "fan speed")
                || contains(text, "air speed");
    }

    private static int coverageScore(SignalCatalogEntry entry) {
        if (entry.sampleCount() <= 0) {
            return 0;
        }
        if (entry.firstTimestampNanos() == null || entry.lastTimestampNanos() == null) {
            return 20;
        }
        long span = entry.lastTimestampNanos() - entry.firstTimestampNanos();
        if (span <= 0) {
            return 10;
        }
        return 40;
    }

    private static int sampleBonus(SignalCatalogEntry entry) {
        long n = entry.sampleCount();
        if (n >= 1_000) {
            return 20;
        }
        if (n >= 100) {
            return 12;
        }
        if (n >= 10) {
            return 6;
        }
        return n > 0 ? 2 : 0;
    }

    private static int originBonus(SignalCatalogEntry entry) {
        return switch (entry.sourceType()) {
            case DBC_CAN, MF4_NATIVE -> 8;
            default -> 0;
        };
    }

    private static SpeedSourceMapping reusable(SpeedSourceMapping persisted,
                                               String sourceId,
                                               String fingerprint,
                                               List<SignalCatalogEntry> scoped) {
        if (persisted == null || persisted.resolverVersion() != RESOLVER_VERSION) {
            return null;
        }
        if (!sourceId.equalsIgnoreCase(persisted.sourceId()) || !fingerprint.equals(persisted.schemaFingerprint())) {
            return null;
        }
        if (persisted.coordinateDerived()) {
            return persisted;
        }
        return find(scoped, persisted.key()) == null ? null : persisted;
    }

    private SpeedSourceCandidate fromPersisted(SpeedSourceMapping persisted, List<SignalCatalogEntry> scoped) {
        if (persisted.coordinateDerived()) {
            return new SpeedSourceCandidate(
                    SpeedSourceKind.GPS_COORDINATE_DERIVED, null, SpeedUnit.KILOMETRES_PER_HOUR,
                    15, 40, 55, "Reused persisted GPS-derived resolution.");
        }
        SignalCatalogEntry entry = find(scoped, persisted.key());
        SpeedUnit unit = SpeedUnit.parse(persisted.originalUnit())
                .or(() -> entry == null ? java.util.Optional.empty() : SpeedUnit.parse(entry.unit()))
                .orElse(null);
        if (entry == null || unit == null) {
            return null;
        }
        return new SpeedSourceCandidate(persisted.kind(), entry, unit, 100, 40, 1_000,
                "Reused persisted speed source for unchanged catalog fingerprint.");
    }

    private static SignalCatalogEntry find(Collection<SignalCatalogEntry> catalog, SignalCatalogKey key) {
        if (catalog == null || key == null) {
            return null;
        }
        for (SignalCatalogEntry entry : catalog) {
            if (entry != null && key.equals(entry.key())) {
                return entry;
            }
        }
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null) {
                continue;
            }
            SignalCatalogKey other = entry.key();
            if (other.signalName().equals(key.signalName())
                    && other.sourceType() == key.sourceType()
                    && other.messageName().equals(key.messageName())
                    && other.channel() == key.channel()
                    && other.sourceFile().equalsIgnoreCase(key.sourceFile())) {
                return entry;
            }
        }
        return null;
    }

    private static String resolveSourceId(String requested, List<SignalCatalogEntry> scoped) {
        if (requested != null && !requested.isBlank() && !GpsMappingScope.isMerged(requested)) {
            return requested;
        }
        return GpsMappingScope.defaultSourceId(scoped);
    }

    private static SpeedConfidence confidence(SpeedSourceCandidate selected, QualityAwarePeak peak) {
        if (peak == null || !peak.present()) {
            return SpeedConfidence.NONE;
        }
        return switch (selected.kind()) {
            case AUTHORITATIVE_VEHICLE -> SpeedConfidence.HIGH;
            case ECU_DYNAMICS -> SpeedConfidence.HIGH;
            case GPS_RECEIVER_SPEED -> SpeedConfidence.MEDIUM;
            case GPS_COORDINATE_DERIVED -> SpeedConfidence.LOW;
        };
    }

    private static String reason(SpeedSourceCandidate selected, QualityAwarePeak peak, boolean reused) {
        String prefix = reused ? "Reused stored resolution. " : "";
        if (peak == null || !peak.present()) {
            return prefix + selected.reason() + " " + (peak == null ? "" : peak.reason());
        }
        if (selected.kind() == SpeedSourceKind.GPS_COORDINATE_DERIVED) {
            return prefix + selected.reason() + " Product Maximum Speed uses interval-validated GPS-derived speed.";
        }
        return prefix + selected.reason()
                + " GPS-coordinate-derived speed is not product Maximum Speed when a physical source exists.";
    }

    private static String originalUnit(SpeedSourceCandidate selected) {
        if (selected.unit() == null) {
            return "";
        }
        if (selected.entry() != null && selected.entry().unit() != null && !selected.entry().unit().isBlank()) {
            return selected.entry().unit();
        }
        return selected.unit().symbol();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }

    private static boolean contains(String text, String token) {
        return text != null && text.contains(token);
    }

    private static final Comparator<SpeedSourceCandidate> COMPARATOR = Comparator
            .comparingInt((SpeedSourceCandidate c) -> c.kind().tier())
            .thenComparing(Comparator.comparingInt(SpeedSourceCandidate::totalScore).reversed())
            .thenComparing(Comparator.comparingLong((SpeedSourceCandidate c) ->
                    c.entry() == null ? 0L : c.entry().sampleCount()).reversed())
            .thenComparing(c -> c.key() == null ? "" : c.key().toString(), String.CASE_INSENSITIVE_ORDER);

    public record Request(
            Collection<SignalCatalogEntry> catalog,
            String sourceId,
            GraphSeriesQuery query,
            MapRouteModel route,
            int indexA,
            int indexB,
            long startNanos,
            long endNanos,
            SpeedSourceMapping persisted
    ) {
        public Request {
            catalog = catalog == null ? List.of() : List.copyOf(catalog);
            sourceId = sourceId == null ? "" : sourceId;
            if (endNanos < startNanos) {
                long swap = startNanos;
                startNanos = endNanos;
                endNanos = swap;
            }
        }
    }
}

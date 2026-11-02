package com.stellar.platform.processing.drive;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.drive.speed.SpeedSourceMapping;
import com.stellar.platform.processing.drive.speed.SpeedSourceResolution;
import com.stellar.platform.processing.drive.speed.VehicleSpeedSourceResolver;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.MapRouteBuildOptions;
import com.stellar.platform.processing.map.MapRouteModel;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Drive-segment metrics from already-processed GPS and catalog series.
 * Never reparses MF4/DBC and never runs on a hover/move path.
 */
public final class DriveSegmentAnalysisService {

    private final DriveSemanticResolver semantics = new DriveSemanticResolver();
    private final DriveFuelAnalyzer fuelAnalyzer = new DriveFuelAnalyzer();
    private final DriveBrakingAnalyzer brakingAnalyzer = new DriveBrakingAnalyzer();
    private final DriveSignalHealthAnalyzer healthAnalyzer = new DriveSignalHealthAnalyzer();
    private final VehicleSpeedSourceResolver speedResolver = new VehicleSpeedSourceResolver();

    public DriveSegmentSummary analyze(DriveSegmentAnalysisRequest request) {
        Objects.requireNonNull(request, "request");
        MapRouteModel route = request.route();
        if (route == null || route.isEmpty()) {
            return DriveSegmentSummary.empty();
        }
        int last = route.size() - 1;
        long startTs = request.startTimestampNanos();
        long endTs = request.endTimestampNanos();
        int lo;
        int hi;
        if (startTs != Long.MIN_VALUE && endTs != Long.MIN_VALUE) {
            long start = Math.min(startTs, endTs);
            long end = Math.max(startTs, endTs);
            lo = route.nearestIndexByTime(start);
            hi = route.nearestIndexByTime(end);
            startTs = start;
            endTs = end;
        } else {
            int indexA = clamp(request.indexA(), 0, last);
            int indexB = clamp(request.indexB(), 0, last);
            lo = Math.min(indexA, indexB);
            hi = Math.max(indexA, indexB);
            startTs = route.timestampNanos(lo);
            endTs = route.timestampNanos(hi);
        }
        lo = clamp(lo, 0, last);
        hi = clamp(hi, 0, last);
        if (lo > hi) {
            int swap = lo;
            lo = hi;
            hi = swap;
        }
        boolean fullDrive = lo == 0 && hi == last && !request.userDefined();

        DriveMotionMetrics motion = DriveMotionMetrics.compute(
                route, lo, hi, request.thresholds(), request.options());
        Map<DriveSemanticRole, SignalCatalogEntry> roles = semantics.resolve(request.catalog());
        SignalCatalogEntry brake = semantics.firstBrakeEvidence(roles).orElse(null);
        DriveBrakingSummary braking;
        try {
            braking = brakingAnalyzer.analyze(
                    request.query(), brake, route, lo, hi, request.thresholds());
        } catch (RuntimeException ignored) {
            braking = DriveBrakingSummary.none();
        }
        DriveFuelSummary fuel;
        try {
            fuel = fuelAnalyzer.analyze(
                    request.query(),
                    roles.get(DriveSemanticRole.FUEL_RATE),
                    roles.get(DriveSemanticRole.FUEL_USED),
                    roles.get(DriveSemanticRole.FUEL_LEVEL),
                    startTs,
                    endTs,
                    motion.range().distanceMeters());
        } catch (RuntimeException ignored) {
            fuel = DriveFuelSummary.unavailable("Fuel analysis unavailable.");
        }
        DriveSignalHealthSummary health;
        try {
            health = healthAnalyzer.analyze(
                    request.query(),
                    request.catalog(),
                    request.selectedKeys(),
                    startTs,
                    endTs);
        } catch (RuntimeException ignored) {
            health = DriveSignalHealthSummary.none();
        }
        SpeedSourceResolution speed;
        try {
            speed = speedResolver.resolve(new VehicleSpeedSourceResolver.Request(
                    request.catalog(),
                    request.sourceId(),
                    request.query(),
                    route,
                    lo,
                    hi,
                    startTs,
                    endTs,
                    request.persistedSpeed()));
        } catch (RuntimeException ignored) {
            speed = SpeedSourceResolution.unavailable(request.sourceId(), "", "Speed resolution unavailable.");
        }
        return new DriveSegmentSummary(
                fullDrive,
                startTs,
                endTs,
                motion,
                braking,
                fuel,
                health,
                request.eventCount(),
                speed);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record DriveSegmentAnalysisRequest(
            MapRouteModel route,
            int indexA,
            int indexB,
            long startTimestampNanos,
            long endTimestampNanos,
            boolean userDefined,
            MapRouteBuildOptions options,
            GraphSeriesQuery query,
            Collection<SignalCatalogEntry> catalog,
            Collection<SignalCatalogKey> selectedKeys,
            DriveSegmentThresholds thresholds,
            int eventCount,
            String sourceId,
            SpeedSourceMapping persistedSpeed
    ) {
        public DriveSegmentAnalysisRequest {
            options = options == null ? MapRouteBuildOptions.defaults() : options;
            catalog = catalog == null ? List.of() : List.copyOf(catalog);
            selectedKeys = selectedKeys == null ? List.of() : List.copyOf(selectedKeys);
            thresholds = thresholds == null ? DriveSegmentThresholds.defaults() : thresholds;
            eventCount = Math.max(0, eventCount);
            sourceId = sourceId == null ? "" : sourceId;
        }

        public DriveSegmentAnalysisRequest(
                MapRouteModel route,
                int indexA,
                int indexB,
                long startTimestampNanos,
                long endTimestampNanos,
                boolean userDefined,
                MapRouteBuildOptions options,
                GraphSeriesQuery query,
                Collection<SignalCatalogEntry> catalog,
                Collection<SignalCatalogKey> selectedKeys,
                DriveSegmentThresholds thresholds,
                int eventCount
        ) {
            this(route, indexA, indexB, startTimestampNanos, endTimestampNanos, userDefined,
                    options, query, catalog, selectedKeys, thresholds, eventCount, "", null);
        }

        /** Index-based request; timestamps are resolved from the route samples. */
        public DriveSegmentAnalysisRequest(
                MapRouteModel route,
                int indexA,
                int indexB,
                boolean userDefined,
                MapRouteBuildOptions options,
                GraphSeriesQuery query,
                Collection<SignalCatalogEntry> catalog,
                Collection<SignalCatalogKey> selectedKeys,
                DriveSegmentThresholds thresholds,
                int eventCount
        ) {
            this(route, indexA, indexB, Long.MIN_VALUE, Long.MIN_VALUE, userDefined,
                    options, query, catalog, selectedKeys, thresholds, eventCount, "", null);
        }
    }
}

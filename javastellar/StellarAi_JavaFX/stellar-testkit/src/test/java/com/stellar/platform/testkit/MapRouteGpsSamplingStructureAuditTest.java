package com.stellar.platform.testkit;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.schema.MdfCgMetadata;
import com.stellar.platform.parser.mf4.schema.MdfCnMetadata;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.NativeMeasurementPolicy;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.drive.DriveSegmentAnalysisService;
import com.stellar.platform.processing.drive.DriveSegmentSummary;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GeoDistance;
import com.stellar.platform.processing.map.GpsDetectionResult;
import com.stellar.platform.processing.map.GpsSignalDiscoveryService;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSignalRoleResolver;
import com.stellar.platform.processing.map.MapRouteBuildOptions;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.MapRouteService;
import com.stellar.platform.processing.map.MapRouteCacheKey;
import com.stellar.platform.processing.map.RouteBuildDiagnostics;
import com.stellar.platform.processing.map.RouteRangeMetrics;
import com.stellar.platform.processing.map.ValidatedRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-file GPS sampling comparison for Lavasa vs Bengaluru. Diagnostic-only: does not change
 * production pairing. Skips when the workstation files are absent.
 */
class MapRouteGpsSamplingStructureAuditTest {

    private static final Path LAVASA = Path.of(
            "C:\\Users\\ADMIN\\Desktop\\DriveTech\\OBD2 Data_Lavasa_0002260_20240305_105231.mf4");
    private static final Path BENGALURU = Path.of(
            "C:\\Users\\ADMIN\\Desktop\\DriveTech\\Kalpesh2.0\\Files_Prasad_Sangpal\\Bengaluru_MF4\\"
                    + "MF4_KA04NC7760_Bengaluru_0002360_20230814_164651.mf4");
    private static final Path BENGALURU_DBC = Path.of(
            "C:\\Users\\ADMIN\\Desktop\\DriveTech\\Kalpesh2.0\\Files_Prasad_Sangpal\\Generic_DBC\\3.dbc");

    static boolean bothPresent() {
        return Files.isRegularFile(LAVASA) && Files.isRegularFile(BENGALURU)
                && Files.isRegularFile(BENGALURU_DBC);
    }

    @TempDir
    Path temp;

    @Test
    @EnabledIf("bothPresent")
    void compareLavasaAndBengaluruGpsSamplingStructures() throws Exception {
        DbcDatabase dummy = dummyDbc();
        DbcDatabase bengaluruDbc = new DbcParser().parse(BENGALURU_DBC);
        FileReport lavasa = audit("LAVASA", LAVASA, dummy, ProcessingSelection.none(),
                temp.resolve("lavasa-store"));
        FileReport bengaluru = audit("BENGALURU", BENGALURU, bengaluruDbc, null,
                temp.resolve("bengaluru-store"));
        printReport(lavasa);
        printReport(bengaluru);
        printDifference(lavasa, bengaluru);

        assertTrue(lavasa.latSamples() >= 2, "Lavasa must have latitude samples");
        assertTrue(bengaluru.latSamples() >= 2, "Bengaluru must have latitude samples");
        assertEquals(lavasa.lat.samples.size(), lavasa.lon.samples.size());
        assertEquals(bengaluru.lat.samples.size(), bengaluru.lon.samples.size());
        assertTrue(lavasa.pairing.exact > 0 && lavasa.pairing.rejected == 0);
        assertTrue(bengaluru.pairing.exact > 0 && bengaluru.pairing.rejected == 0);
        assertTrue(lavasa.validated.model().totalDistanceMeters() > 1_000,
                "Lavasa full-drive distance must remain a real city drive");
        assertTrue(lavasa.validated.model().totalDistanceMeters() < 50_000);
        assertTrue(bengaluru.validated.model().totalDistanceMeters() > 1_000,
                "Bengaluru telemetry supports a driven distance once lat/lon are synchronized");
        assertTrue(bengaluru.discoverySummary.toLowerCase(Locale.ROOT).contains("gps_lat_info"),
                "GPS_LAT_INFO must be auto-detected without filename special cases: "
                        + bengaluru.discoverySummary);
        assertTrue(bengaluru.analysis.motion().range().distanceMeters() > 0);
        assertEquals(lavasa.validated.model().totalDistanceMeters(),
                lavasa.prepared.totalDistanceMeters(), 1e-6);
        assertEquals(bengaluru.validated.model().totalDistanceMeters(),
                bengaluru.prepared.totalDistanceMeters(), 1e-6);
    }

    private FileReport audit(String label, Path mf4, DbcDatabase database, ProcessingSelection selection,
                             Path storeRoot) throws Exception {
        SchemaGps schema = extractGpsSchema(mf4);
        try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(
                storeRoot, mf4.getFileName().toString(), "gps-audit.dbc")) {
            MeasurementDecodePipeline.createDefault().process(
                    mf4,
                    database,
                    ReaderContext.ofBatchSize(256),
                    batch -> store.accept(keepLocationSignals(batch)),
                    ProcessingProgressListener.NOOP,
                    selection,
                    NativeMeasurementPolicy.INCLUDE);
            store.finishWriteSession();
            Thread.interrupted();

            SignalCatalog catalog = store.snapshot();
            System.out.println(label + " catalog signals=" + catalog.entries().size()
                    + " names=" + catalog.entries().stream().map(e -> e.signalName()
                    + "@ch" + e.channel() + "/" + e.messageName()).toList());
            GpsDetectionResult detected = new GpsSignalDiscoveryService().discover(catalog.entries());
            Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
            roles.putAll(GpsSignalDiscoveryService.toRoleMap(detected));
            if (!GpsSignalRoleResolver.hasRoute(roles)) {
                roles.putAll(new GpsSignalRoleResolver().resolve(catalog.entries()));
            }
            if (!GpsSignalRoleResolver.hasRoute(roles)) {
                roles.putAll(fallbackCoordinateRoles(catalog));
            }
            GraphSeriesQuery query = store.graphQuery();

            SeriesStats lat = series(query, roles.get(GpsSignalRole.LATITUDE));
            SeriesStats lon = series(query, roles.get(GpsSignalRole.LONGITUDE));
            SeriesStats alt = series(query, roles.get(GpsSignalRole.ALTITUDE));
            SignalCatalogEntry latEntry = entry(catalog, roles.get(GpsSignalRole.LATITUDE));
            SignalCatalogEntry lonEntry = entry(catalog, roles.get(GpsSignalRole.LONGITUDE));
            SignalCatalogEntry altEntry = entry(catalog, roles.get(GpsSignalRole.ALTITUDE));

            PairingStats pairing = pairing(lat, lon);
            ValidatedRoute validated = MapRouteBuilder.validateAndBuild(lat.samples, lon.samples, alt.samples);
            MapRouteService service = new MapRouteService(query);
            var prepared = service.prepare(
                    new MapRouteCacheKey(label, mf4.getFileName().toString(), 1L, roles),
                    Long.MIN_VALUE, Long.MAX_VALUE);
            DriveSegmentSummary analysis = DriveSegmentSummary.empty();
            if (!validated.model().isEmpty()) {
                Thread.interrupted();
                analysis = new DriveSegmentAnalysisService().analyze(
                        new DriveSegmentAnalysisService.DriveSegmentAnalysisRequest(
                                validated.model(), 0, validated.model().size() - 1, false, null, null,
                                List.of(), List.of(), null, 0));
            }
            String vehicleSpeed = findVehicleSpeed(catalog);
            return new FileReport(
                    label,
                    mf4.getFileName().toString(),
                    schema,
                    detected.summary(),
                    latEntry,
                    lonEntry,
                    altEntry,
                    lat,
                    lon,
                    alt,
                    pairing,
                    validated,
                    prepared.model(),
                    analysis,
                    vehicleSpeed,
                    GpsSignalRoleResolver.hasRoute(roles));
        }
    }

    private static SchemaGps extractGpsSchema(Path mf4) throws Exception {
        try (MdfFileAccess access = MdfFileAccess.open(mf4)) {
            var graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            MdfMetadataSummary metadata = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            Map<Long, MdfCgMetadata> cgByAddress = new LinkedHashMap<>();
            for (MdfCgMetadata cg : metadata.channelGroups()) {
                cgByAddress.put(cg.address(), cg);
            }
            List<ChannelSchema> channels = new ArrayList<>();
            for (MdfCnMetadata cn : metadata.channels()) {
                String name = cn.name() == null ? "" : cn.name();
                if (!looksGps(name) && cn.channelType() != 2 && cn.channelType() != 3) {
                    continue;
                }
                MdfCgMetadata cg = cgByAddress.get(cn.parentCgAddress());
                String master = masterName(metadata.channels(), cn.parentCgAddress());
                channels.add(new ChannelSchema(
                        name,
                        cn.unit(),
                        cn.channelType(),
                        cn.parentCgAddress(),
                        cg == null ? -1L : cg.recordId(),
                        cg == null ? "" : cg.sourceName(),
                        master,
                        cg == null ? 0L : cg.cycleCount(),
                        cg != null && cg.busEventChannelGroup()));
            }
            return new SchemaGps(metadata.channelGroups().size(), metadata.channels().size(), channels);
        }
    }

    private static String masterName(List<MdfCnMetadata> channels, long cgAddress) {
        for (MdfCnMetadata cn : channels) {
            if (cn.parentCgAddress() == cgAddress && (cn.channelType() == 2 || cn.channelType() == 3)) {
                return cn.name() == null ? "" : cn.name();
            }
        }
        return "";
    }

    private static boolean looksGps(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
        return n.contains("lat") || n.contains("lon") || n.contains("lng") || n.contains("alt")
                || n.contains("gps") || n.contains("gnss") || n.contains("speed")
                || n.contains("course") || n.contains("heading");
    }

    private static List<CanonicalSignal> keepLocationSignals(List<CanonicalSignal> batch) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }
        List<CanonicalSignal> kept = new ArrayList<>();
        for (CanonicalSignal signal : batch) {
            if (signal == null) {
                continue;
            }
            if (looksGps(signal.signalName()) || looksGps(signal.messageName())) {
                kept.add(signal);
            }
        }
        return kept;
    }

    private static Map<GpsSignalRole, SignalCatalogKey> fallbackCoordinateRoles(SignalCatalog catalog) {
        EnumMap<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
        for (SignalCatalogEntry entry : catalog.entries()) {
            String n = entry.signalName().toLowerCase(Locale.ROOT).replace("_", "");
            if (roles.get(GpsSignalRole.LATITUDE) == null
                    && n.contains("lat") && !n.contains("accuracy")) {
                roles.put(GpsSignalRole.LATITUDE, entry.key());
            } else if (roles.get(GpsSignalRole.LONGITUDE) == null
                    && (n.contains("long") || n.contains("lon")) && !n.contains("accuracy")) {
                roles.put(GpsSignalRole.LONGITUDE, entry.key());
            } else if (roles.get(GpsSignalRole.ALTITUDE) == null && n.contains("alt")) {
                roles.put(GpsSignalRole.ALTITUDE, entry.key());
            }
        }
        return roles;
    }

    private static SeriesStats series(GraphSeriesQuery query, SignalCatalogKey key) {
        if (key == null) {
            return SeriesStats.empty();
        }
        List<SignalSamplePoint> samples = query.queryRange(key, Long.MIN_VALUE, Long.MAX_VALUE);
        if (samples == null || samples.isEmpty()) {
            return new SeriesStats(key, List.of(), 0, 0, 0, 0, 0, 0, 0, Double.NaN, Double.NaN);
        }
        long first = samples.getFirst().timestampNanos();
        long last = samples.getLast().timestampNanos();
        long minDelta = Long.MAX_VALUE;
        long[] deltas = new long[Math.max(0, samples.size() - 1)];
        int duplicates = 0;
        int nonMono = 0;
        int nonFinite = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < samples.size(); i++) {
            SignalSamplePoint p = samples.get(i);
            double v = p.physicalValue();
            if (!Double.isFinite(v)) {
                nonFinite++;
            } else {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (i == 0) {
                continue;
            }
            long dt = p.timestampNanos() - samples.get(i - 1).timestampNanos();
            deltas[i - 1] = dt;
            if (dt == 0) {
                duplicates++;
            } else if (dt < 0) {
                nonMono++;
            } else {
                minDelta = Math.min(minDelta, dt);
            }
        }
        long medianDelta = 0;
        if (deltas.length > 0) {
            long[] copy = deltas.clone();
            Arrays.sort(copy);
            medianDelta = copy[copy.length / 2];
        }
        if (minDelta == Long.MAX_VALUE) {
            minDelta = 0;
        }
        if (!Double.isFinite(min)) {
            min = Double.NaN;
            max = Double.NaN;
        }
        return new SeriesStats(key, samples, first, last, minDelta, medianDelta, duplicates, nonMono, nonFinite,
                min, max);
    }

    private static PairingStats pairing(SeriesStats lat, SeriesStats lon) {
        if (lat.samples.isEmpty() || lon.samples.isEmpty()) {
            return PairingStats.empty();
        }
        Set<Long> latTs = new HashSet<>();
        Set<Long> lonTs = new HashSet<>();
        for (SignalSamplePoint p : lat.samples) {
            latTs.add(p.timestampNanos());
        }
        for (SignalSamplePoint p : lon.samples) {
            lonTs.add(p.timestampNanos());
        }
        int exact = 0;
        for (long t : latTs) {
            if (lonTs.contains(t)) {
                exact++;
            }
        }
        boolean sameCount = lat.samples.size() == lon.samples.size();
        boolean sameFirstLast = lat.first == lon.first && lat.last == lon.last;
        boolean sameMedian = lat.medianDelta == lon.medianDelta;
        String classification;
        if (exact == lat.samples.size() && exact == lon.samples.size()) {
            classification = "A exact same timestamps";
        } else if (sameCount && exact < lat.samples.size()) {
            classification = "B same sample count but different timestamps";
        } else if (!sameCount) {
            classification = "C different sample counts";
        } else {
            classification = "mixed";
        }
        long derivedTolerance = deriveTolerance(lat.samples);
        int nearest = 0;
        int rejected = 0;
        int lonCursor = 0;
        for (SignalSamplePoint latSample : lat.samples) {
            lonCursor = advance(lon.samples, lonCursor, latSample.timestampNanos());
            int idx = nearest(lon.samples, lonCursor, latSample.timestampNanos(), derivedTolerance);
            if (idx < 0) {
                rejected++;
            } else {
                nearest++;
            }
        }
        return new PairingStats(
                classification,
                sameCount,
                sameFirstLast,
                sameMedian,
                exact,
                nearest,
                rejected,
                derivedTolerance,
                lat.key == null || lon.key == null ? false : lat.key.channel() == lon.key.channel(),
                lat.key == null || lon.key == null ? false : lat.key.messageName().equals(lon.key.messageName()));
    }

    private static long deriveTolerance(List<SignalSamplePoint> samples) {
        int size = samples.size();
        if (size < 2) {
            return 1_000_000_000L;
        }
        long span = samples.get(size - 1).timestampNanos() - samples.get(0).timestampNanos();
        long average = span <= 0 ? 0 : span / (size - 1);
        return Math.max(1_000_000L, average);
    }

    private static int advance(List<SignalSamplePoint> samples, int cursor, long target) {
        int i = Math.max(0, cursor);
        int last = samples.size() - 1;
        while (i < last && samples.get(i + 1).timestampNanos() <= target) {
            i++;
        }
        return i;
    }

    private static int nearest(List<SignalSamplePoint> samples, int cursor, long target, long tolerance) {
        int best = -1;
        long bestDelta = Long.MAX_VALUE;
        for (int i = Math.max(0, cursor); i <= Math.min(samples.size() - 1, cursor + 1); i++) {
            long delta = Math.abs(samples.get(i).timestampNanos() - target);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return bestDelta <= tolerance ? best : -1;
    }

    private static SignalCatalogEntry entry(SignalCatalog catalog, SignalCatalogKey key) {
        if (key == null) {
            return null;
        }
        return catalog.entries().stream().filter(e -> e.key().equals(key)).findFirst().orElse(null);
    }

    private static String findVehicleSpeed(SignalCatalog catalog) {
        StringBuilder out = new StringBuilder();
        for (SignalCatalogEntry e : catalog.entries()) {
            String n = e.signalName().toLowerCase(Locale.ROOT);
            if (n.contains("speed") || n.contains("velocity") || n.contains("sog") || n.contains("vss")) {
                if (out.length() > 0) {
                    out.append("; ");
                }
                out.append(e.signalName())
                        .append(" type=").append(e.sourceType())
                        .append(" n=").append(e.sampleCount())
                        .append(" unit=").append(e.unit());
            }
        }
        return out.isEmpty() ? "(none in native catalog)" : out.toString();
    }

    private static void printReport(FileReport report) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("FILE: " + report.fileName);
        System.out.println("GPS discovery: " + report.discoverySummary);
        System.out.println("Has route: " + report.hasRoute);
        printRole("Latitude", report.latEntry, report.lat);
        printRole("Longitude", report.lonEntry, report.lon);
        printRole("Altitude", report.altEntry, report.alt);
        System.out.println("Vehicle speed candidates: " + report.vehicleSpeed);
        System.out.println("Schema GPS/time channels:");
        for (ChannelSchema ch : report.schema.gpsChannels()) {
            if (!looksGps(ch.name) && ch.channelType != 2 && ch.channelType != 3) {
                continue;
            }
            System.out.printf(Locale.ROOT,
                    "  %-28s type=%d unit=%-8s cg=%d recordId=%d src=%s master=%s cycles=%d busEvent=%s%n",
                    ch.name, ch.channelType, ch.unit, ch.cgAddress, ch.recordId, ch.sourceName,
                    ch.master, ch.cycleCount, ch.busEvent);
        }
        System.out.println("LAT samples: " + report.lat.samples.size());
        System.out.println("LON samples: " + report.lon.samples.size());
        System.out.println("LAT range: " + fmt(report.lat.min) + " .. " + fmt(report.lat.max));
        System.out.println("LON range: " + fmt(report.lon.min) + " .. " + fmt(report.lon.max));
        System.out.println("LAT time: " + timeRange(report.lat));
        System.out.println("LON time: " + timeRange(report.lon));
        System.out.println("Same timebase/channel: " + report.pairing.sameChannel
                + " sameMessage=" + report.pairing.sameMessage);
        System.out.println("Classification: " + report.pairing.classification);
        System.out.println("Exact timestamp pairs: " + report.pairing.exact);
        System.out.println("Nearest valid pairs (current builder): " + report.pairing.nearest);
        System.out.println("Rejected stale pairs (current builder): " + report.pairing.rejected);
        System.out.println("Derived pair tolerance ns: " + report.pairing.derivedTolerance
                + " (" + report.pairing.derivedTolerance / 1e6 + " ms)");
        System.out.println("Invalid/non-finite LAT: " + report.lat.nonFinite + " LON: " + report.lon.nonFinite);
        System.out.println("Duplicate timestamps LAT: " + report.lat.duplicates + " LON: " + report.lon.duplicates);
        System.out.println("Non-monotonic timestamps LAT: " + report.lat.nonMono + " LON: " + report.lon.nonMono);
        dumpPairs("LAT first 20", report.lat.samples, 20, true);
        dumpPairs("LAT last 20", report.lat.samples, 20, false);
        dumpPairs("LON first 20", report.lon.samples, 20, true);
        dumpPairs("LON last 20", report.lon.samples, 20, false);
        RouteBuildDiagnostics d = report.validated.diagnostics();
        MapRouteModel model = report.validated.model();
        System.out.println("Canonical route points: " + model.size());
        System.out.println("Builder diagnostics: " + d.summary());
        int gaps = 0;
        int validSeg = 0;
        int gapDt = 0;
        int gapJump = 0;
        int gapZeroDt = 0;
        double largest = 0;
        long largestDt = 0;
        Double largestSpeed = null;
        int largestIndex = -1;
        long maxGapNs = MapRouteBuildOptions.defaults().maxTimeGapNanos();
        double maxJump = MapRouteBuildOptions.defaults().maxJumpMeters();
        for (int i = 1; i < model.size(); i++) {
            double step = GeoDistance.haversineMeters(
                    model.latitude(i - 1), model.longitude(i - 1),
                    model.latitude(i), model.longitude(i));
            long dt = model.timestampNanos(i) - model.timestampNanos(i - 1);
            if (model.segmentBreakBefore(i)) {
                gaps++;
                if (dt < MapRouteBuilder.MIN_VALID_DT_NANOS) {
                    gapZeroDt++;
                } else if (dt > maxGapNs) {
                    gapDt++;
                } else if (!Double.isFinite(step) || step > maxJump) {
                    gapJump++;
                }
            } else {
                validSeg++;
                if (step > largest) {
                    largest = step;
                    largestDt = dt;
                    largestIndex = i;
                    if (dt > 0) {
                        largestSpeed = (step / (dt / 1e9)) * 3.6;
                    }
                }
            }
        }
        System.out.println("Valid segments: " + validSeg);
        System.out.println("Gap segments: " + gaps
                + " (dt>5s=" + gapDt
                + " jump>1000m=" + gapJump
                + " dt<1ms=" + gapZeroDt + ")");
        System.out.println("Total distance m: " + model.totalDistanceMeters()
                + " km=" + String.format(Locale.ROOT, "%.3f", model.totalDistanceMeters() / 1000.0));
        System.out.println("Largest accepted segment m: " + largest
                + " dtNs=" + largestDt
                + " dtS=" + (largestDt / 1e9)
                + " derivedSpeedKph=" + (largestSpeed == null ? "-" : String.format(Locale.ROOT, "%.3f", largestSpeed))
                + " atIndex=" + largestIndex);
        if (!model.isEmpty()) {
            RouteRangeMetrics metrics = RouteRangeMetrics.compute(model, 0, model.size() - 1, null);
            System.out.println("Full-drive metrics durationS=" + String.format(Locale.ROOT, "%.3f", metrics.durationSeconds())
                    + " distanceKm=" + String.format(Locale.ROOT, "%.3f", metrics.distanceKilometers())
                    + " avgKph=" + (metrics.averageSpeedKph() == null ? "-" : String.format(Locale.ROOT, "%.3f", metrics.averageSpeedKph()))
                    + " maxKph=" + (metrics.maxSpeedKph() == null ? "-" : String.format(Locale.ROOT, "%.3f", metrics.maxSpeedKph())));
            System.out.println("Segment analysis distanceMeters=" + report.analysis.motion().range().distanceMeters()
                    + " shownAsDash=" + (report.analysis.motion().range().distanceMeters() <= 0)
                    + " durationNanos=" + report.analysis.motion().range().durationNanos());
            System.out.println("Prepared MapRouteService size=" + report.prepared.size()
                    + " distance=" + report.prepared.totalDistanceMeters()
                    + " identicalToValidated=" + (report.prepared.size() == model.size()
                    && Math.abs(report.prepared.totalDistanceMeters() - model.totalDistanceMeters()) < 1e-9));
        } else {
            System.out.println("NO CANONICAL ROUTE — distance unavailable because route is empty");
        }
        System.out.println("UI Distance '—' condition: motion.range.distanceMeters() > 0 is "
                + (model.totalDistanceMeters() > 0)
                + " (MapRoutePane uses DriveSegmentSummary.motion.range.distanceMeters() > 0)");
    }

    private static void printRole(String role, SignalCatalogEntry entry, SeriesStats stats) {
        System.out.println(role + ":");
        if (entry == null) {
            System.out.println("  (unresolved)");
            return;
        }
        System.out.println("  canonical identity: " + entry.key());
        System.out.println("  signal name: " + entry.signalName());
        System.out.println("  source/channel group: type=" + entry.sourceType()
                + " message=" + entry.messageName()
                + " channel/recordId=" + entry.channel());
        System.out.println("  unit=" + entry.unit() + " sampleCount(catalog)=" + entry.sampleCount());
        System.out.println("  queried samples=" + stats.samples.size()
                + " firstTs=" + stats.first + " lastTs=" + stats.last);
        System.out.println("  minDtNs=" + stats.minDelta + " medianDtNs=" + stats.medianDelta
                + " minDtMs=" + stats.minDelta / 1e6 + " medianDtMs=" + stats.medianDelta / 1e6);
        System.out.println("  duplicates=" + stats.duplicates + " nonMono=" + stats.nonMono
                + " nonFinite=" + stats.nonFinite);
        System.out.println("  min=" + fmt(stats.min) + " max=" + fmt(stats.max));
    }

    private static void dumpPairs(String title, List<SignalSamplePoint> samples, int n, boolean first) {
        if (samples.isEmpty()) {
            System.out.println(title + ": (empty)");
            return;
        }
        int from;
        int to;
        if (first) {
            from = 0;
            to = Math.min(n, samples.size());
        } else {
            from = Math.max(0, samples.size() - n);
            to = samples.size();
        }
        System.out.println(title + ":");
        for (int i = from; i < to; i++) {
            SignalSamplePoint p = samples.get(i);
            System.out.printf(Locale.ROOT, "  [%d] t=%.6f s  v=%.9f%n",
                    i, p.timestampNanos() / 1e9, p.physicalValue());
        }
    }

    private static void printDifference(FileReport lavasa, FileReport bengaluru) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("STRUCTURAL DIFFERENCE");
        System.out.println("Lavasa LAT/LON counts: " + lavasa.lat.samples.size() + " / " + lavasa.lon.samples.size());
        System.out.println("Bengaluru LAT/LON counts: " + bengaluru.lat.samples.size() + " / " + bengaluru.lon.samples.size());
        System.out.println("Lavasa classification: " + lavasa.pairing.classification);
        System.out.println("Bengaluru classification: " + bengaluru.pairing.classification);
        System.out.println("Lavasa sameChannel=" + lavasa.pairing.sameChannel
                + " Bengaluru sameChannel=" + bengaluru.pairing.sameChannel);
        System.out.println("Lavasa exact pairs=" + lavasa.pairing.exact
                + " rejected=" + lavasa.pairing.rejected
                + " distanceM=" + lavasa.validated.model().totalDistanceMeters());
        System.out.println("Bengaluru exact pairs=" + bengaluru.pairing.exact
                + " rejected=" + bengaluru.pairing.rejected
                + " distanceM=" + bengaluru.validated.model().totalDistanceMeters());
        System.out.println("Bengaluru Distance unavailable because: "
                + bengaluruDistanceReason(bengaluru));
    }

    private static String bengaluruDistanceReason(FileReport report) {
        MapRouteModel model = report.validated.model();
        if (model.isEmpty()) {
            RouteBuildDiagnostics d = report.validated.diagnostics();
            return "ValidatedRoute empty. paired=" + d.pairedPoints()
                    + " rejectedTimestampPairs=" + d.rejectedTimestampPairs()
                    + " rejectedInvalid=" + d.rejectedInvalidCoordinates()
                    + " dupOrOutOfOrder=" + d.duplicateOrOutOfOrderTimestamps()
                    + " → MapRoutePane Distance '—' because distanceMeters()==0";
        }
        if (model.totalDistanceMeters() <= 0) {
            int gaps = 0;
            for (int i = 1; i < model.size(); i++) {
                if (model.segmentBreakBefore(i)) {
                    gaps++;
                }
            }
            return "Route has " + model.size() + " points so geometry can render, but ALL consecutive steps are gaps ("
                    + gaps + " gaps). cumulative distance=0. MapRoutePane shows '—' iff distanceMeters()<=0. "
                    + "Duration still comes from ActiveSegment timestamps (first→last). "
                    + "Builder: " + report.validated.diagnostics().summary();
        }
        return "distance is available (" + model.totalDistanceMeters() + " m) — UI dash may be a display mismatch";
    }

    private static String timeRange(SeriesStats stats) {
        if (stats.samples.isEmpty()) {
            return "(empty)";
        }
        return String.format(Locale.ROOT, "%.6f s .. %.6f s  span=%.3f s  medianDt=%.6f s",
                stats.first / 1e9, stats.last / 1e9, (stats.last - stats.first) / 1e9, stats.medianDelta / 1e9);
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.9f", v) : String.valueOf(v);
    }

    private DbcDatabase dummyDbc() throws Exception {
        Path dbc = temp.resolve("dummy.dbc");
        Files.writeString(dbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 256 EngineData: 8 ECU
                 SG_ EngineRPM : 0|16@1+ (0.25,0) [0|8000] "rpm" ECU
                """);
        return new DbcParser().parse(dbc);
    }

    private record ChannelSchema(
            String name,
            String unit,
            int channelType,
            long cgAddress,
            long recordId,
            String sourceName,
            String master,
            long cycleCount,
            boolean busEvent
    ) {
    }

    private record SchemaGps(int channelGroupCount, int channelCount, List<ChannelSchema> gpsChannels) {
        private SchemaGps {
            gpsChannels = gpsChannels == null ? List.of() : List.copyOf(gpsChannels);
        }
    }

    private static final class SeriesStats {
        final SignalCatalogKey key;
        final List<SignalSamplePoint> samples;
        final long first;
        final long last;
        final long minDelta;
        final long medianDelta;
        final int duplicates;
        final int nonMono;
        final int nonFinite;
        final double min;
        final double max;

        SeriesStats(SignalCatalogKey key, List<SignalSamplePoint> samples, long first, long last,
                    long minDelta, long medianDelta, int duplicates, int nonMono, int nonFinite,
                    double min, double max) {
            this.key = key;
            this.samples = samples == null ? List.of() : samples;
            this.first = first;
            this.last = last;
            this.minDelta = minDelta;
            this.medianDelta = medianDelta;
            this.duplicates = duplicates;
            this.nonMono = nonMono;
            this.nonFinite = nonFinite;
            this.min = min;
            this.max = max;
        }

        static SeriesStats empty() {
            return new SeriesStats(null, List.of(), 0, 0, 0, 0, 0, 0, 0, Double.NaN, Double.NaN);
        }
    }

    private record PairingStats(
            String classification,
            boolean sameCount,
            boolean sameFirstLast,
            boolean sameMedian,
            int exact,
            int nearest,
            int rejected,
            long derivedTolerance,
            boolean sameChannel,
            boolean sameMessage
    ) {
        static PairingStats empty() {
            return new PairingStats("no series", false, false, false, 0, 0, 0, 0, false, false);
        }
    }

    private record FileReport(
            String label,
            String fileName,
            SchemaGps schema,
            String discoverySummary,
            SignalCatalogEntry latEntry,
            SignalCatalogEntry lonEntry,
            SignalCatalogEntry altEntry,
            SeriesStats lat,
            SeriesStats lon,
            SeriesStats alt,
            PairingStats pairing,
            ValidatedRoute validated,
            MapRouteModel prepared,
            DriveSegmentSummary analysis,
            String vehicleSpeed,
            boolean hasRoute
    ) {
        int latSamples() {
            return lat.samples.size();
        }
    }
}

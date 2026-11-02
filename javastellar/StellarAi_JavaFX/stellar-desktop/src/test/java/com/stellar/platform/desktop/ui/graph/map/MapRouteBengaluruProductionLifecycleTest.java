package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.NativeMeasurementPolicy;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.map.GpsDetectionResult;
import com.stellar.platform.processing.map.GpsMappingScope;
import com.stellar.platform.processing.map.GpsSignalDiscoveryService;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSignalRoleResolver;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteCacheKey;
import com.stellar.platform.processing.map.MapRouteService;
import com.stellar.platform.processing.drive.DriveSegmentAnalysisService;
import com.stellar.platform.processing.drive.DriveSegmentSummary;
import com.stellar.platform.processing.drive.speed.SpeedSourceKind;
import com.stellar.platform.processing.drive.speed.SpeedSourceResolution;
import com.stellar.platform.processing.map.RouteRangeMetrics;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-lifecycle GPS discovery: process the real Bengaluru MF4, persist the catalog to
 * project-local SQLite, hydrate the same catalog Map Route uses, then auto-detect without
 * feeding known GPS keys into the resolver by hand.
 */
class MapRouteBengaluruProductionLifecycleTest {

    private static final Path BENGALURU = Path.of(
            "C:\\Users\\ADMIN\\Desktop\\DriveTech\\Kalpesh2.0\\Files_Prasad_Sangpal\\Bengaluru_MF4\\"
                    + "MF4_KA04NC7760_Bengaluru_0002360_20230814_164651.mf4");
    private static final Path BENGALURU_DBC = Path.of(
            "C:\\Users\\ADMIN\\Desktop\\DriveTech\\Kalpesh2.0\\Files_Prasad_Sangpal\\Generic_DBC\\3.dbc");
    private static final Path LAVASA = Path.of(
            "C:\\Users\\ADMIN\\Desktop\\DriveTech\\OBD2 Data_Lavasa_0002260_20240305_105231.mf4");

    static boolean bengaluruPresent() {
        return Files.isRegularFile(BENGALURU) && Files.isRegularFile(BENGALURU_DBC);
    }

    static boolean lavasaPresent() {
        return Files.isRegularFile(LAVASA);
    }

    @TempDir
    Path temp;

    @Test
    @Timeout(180)
    @EnabledIf("bengaluruPresent")
    void bengaluruProcessPersistHydrateDiscoversGpsWithoutManualMapping() throws Exception {
        Path artifactRoot = temp.resolve("bengaluru-artifact");
        Path sqlite = temp.resolve("bengaluru-project").resolve(".stellar").resolve("stellar.db");
        Files.createDirectories(sqlite.getParent());
        DbcDatabase dbc = new DbcParser().parse(BENGALURU_DBC);
        GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(
                artifactRoot, BENGALURU.getFileName().toString(), BENGALURU_DBC.getFileName().toString());
        SignalsScreenModel model = new SignalsScreenModel();
        try {
            MeasurementDecodePipeline.createDefault().process(
                    BENGALURU,
                    dbc,
                    ReaderContext.ofBatchSize(256),
                    store::accept,
                    ProcessingProgressListener.NOOP,
                    null,
                    NativeMeasurementPolicy.INCLUDE);
            store.finishWriteSession();

            model.replaceCatalog(store);
            List<SignalCatalogEntry> mapCatalog = model.mapRouteCatalog();
            assertTrue(mapCatalog.size() >= 129, "complete catalog, not the Hide Duplicates projection: "
                    + mapCatalog.size());
            SignalCatalogEntry latInfo = requireNamed(mapCatalog, "GPS_LAT_INFO");
            SignalCatalogEntry lonInfo = requireNamed(mapCatalog, "GPS_LONG_INFO");
            SignalCatalogEntry altInfo = requireNamed(mapCatalog, "GPS_ALTITUDE");
            System.out.println("BENGALURU hydrated catalog size=" + mapCatalog.size());
            printPresence("GPS_LAT_INFO", latInfo);
            printPresence("GPS_LONG_INFO", lonInfo);
            printPresence("GPS_ALTITUDE", altInfo);

            persistCatalog(sqlite, "bengaluru-project", mapCatalog);
            try (SqliteProjectMetadataStore metadata = new SqliteProjectMetadataStore(sqlite)) {
                List<SignalCatalogMetadata> gpsRows = metadata.searchSignalCatalog("bengaluru-project", "GPS");
                System.out.println("BENGALURU sqlite GPS rows=" + gpsRows.size());
                for (SignalCatalogMetadata row : gpsRows) {
                    if (gpsName(row.signalName()) || gpsName(row.messageName())) {
                        System.out.println("  sqlite name=" + row.signalName()
                                + " msg=" + row.messageName()
                                + " can=" + row.canId()
                                + " ch=" + row.signalChannel()
                                + " type=" + row.sourceType()
                                + " unit=" + row.unit()
                                + " samples=" + row.sampleCount()
                                + " min=" + row.minValue()
                                + " max=" + row.maxValue());
                    }
                }
                assertTrue(gpsRows.stream().anyMatch(r -> "GPS_LAT_INFO".equals(r.signalName())));
                assertTrue(gpsRows.stream().anyMatch(r -> "GPS_LONG_INFO".equals(r.signalName())));
                assertTrue(gpsRows.stream().anyMatch(r -> "GPS_ALTITUDE".equals(r.signalName())));
                GpsDetectionResult fromSqlite = new GpsSignalDiscoveryService().discover(
                        hydrate(gpsRows, BENGALURU.getFileName().toString(), BENGALURU_DBC.getFileName().toString()));
                assertTrue(fromSqlite.hasReliableRoute(), fromSqlite.summary());
                assertEquals("GPS_LAT_INFO", fromSqlite.pair().orElseThrow().latitude().signalName());
                assertEquals("GPS_LONG_INFO", fromSqlite.pair().orElseThrow().longitude().signalName());
            }

            String sourceId = GpsMappingScope.resolveRouteSource(
                    BENGALURU.getFileName().toString(), mapCatalog, List.of());
            List<SignalCatalogEntry> discoveryCatalog = GpsMappingScope.catalogFor(sourceId, mapCatalog);
            assertEquals(mapCatalog.size(), discoveryCatalog.size(),
                    "GPS discovery must use the complete catalog, not browser duplicates suppression");
            GpsDetectionResult detected = new GpsSignalDiscoveryService().discover(
                    discoveryCatalog, "bengaluru-lifecycle", Map.of());
            System.out.println("BENGALURU discovery " + detected.summary()
                    + " scanned=" + detected.scannedSignals()
                    + " latCandidates=" + detected.latitudeCandidates()
                    + " lonCandidates=" + detected.longitudeCandidates()
                    + " resolver=" + GpsSignalDiscoveryService.resolverLocation());
            assertTrue(detected.hasReliableRoute(), detected.summary());
            assertEquals("GPS_LAT_INFO", detected.pair().orElseThrow().latitude().signalName());
            assertEquals("GPS_LONG_INFO", detected.pair().orElseThrow().longitude().signalName());
            assertEquals("GPS_ALTITUDE", detected.pair().orElseThrow().altitudeOptional().orElseThrow().signalName());
            assertTrue(detected.latitudeCandidates() > 0);
            assertTrue(detected.longitudeCandidates() > 0);

            Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
            roles.putAll(GpsSignalDiscoveryService.toRoleMap(detected));
            MapRoute prepared = new MapRouteService(store.graphQuery()).prepare(
                    new MapRouteCacheKey("bengaluru-lifecycle", BENGALURU.getFileName().toString(), 1L, roles),
                    Long.MIN_VALUE, Long.MAX_VALUE);
            assertFalse(prepared.model().isEmpty());
            assertTrue(Math.abs(prepared.model().size() - 1631) <= 5, "points=" + prepared.model().size());
            assertEquals(25_187.0, prepared.model().totalDistanceMeters(), 50.0);
            assertEquals(0, countBreaks(prepared.model()));

            DriveSegmentSummary analysis = new DriveSegmentAnalysisService().analyze(
                    new DriveSegmentAnalysisService.DriveSegmentAnalysisRequest(
                            prepared.model(), 0, prepared.model().size() - 1,
                            prepared.model().firstTimestampNanos(), prepared.model().lastTimestampNanos(),
                            false, null, store.graphQuery(), mapCatalog, List.of(), null, 0, sourceId, null));
            SpeedSourceResolution speed = analysis.speed();
            RouteRangeMetrics gps = RouteRangeMetrics.compute(
                    prepared.model(), 0, prepared.model().size() - 1, null);
            System.out.println("BENGALURU SPEED kind=" + speed.kind()
                    + " signal=" + speed.displayName()
                    + " productMax=" + speed.maximumKph()
                    + " gpsRawMax=" + gps.maxSpeedKph()
                    + " ts=" + (speed.peak() == null ? "-" : speed.peak().timestampNanos())
                    + " confidence=" + speed.confidence()
                    + " reason=" + speed.reason());
            assertEquals(25_187.0, analysis.motion().range().distanceMeters(), 50.0);
            assertNotEquals(SpeedSourceKind.GPS_COORDINATE_DERIVED, speed.kind(),
                    "physical vehicle speed must outrank GPS-derived catch-up: " + speed.reason());
            assertTrue(gps.maxSpeedKph() != null && gps.maxSpeedKph() > 180.0,
                    "route-derived reference still sees the catch-up interval");
            assertTrue(speed.available());
            assertTrue(speed.maximumKph().getAsDouble() < 150.0,
                    "product Maximum Speed must not be the GPS catch-up spike, got "
                            + speed.maximumKph().getAsDouble());
            assertTrue(speed.reason().toLowerCase(Locale.ROOT).contains("gps-coordinate-derived")
                    || speed.reason().toLowerCase(Locale.ROOT).contains("physical"));
        } finally {
            model.shutdown();
            store.close();
        }

        try (GraphSignalSessionStore reopened = GraphSignalSessionStore.openExisting(artifactRoot)) {
            GpsDetectionResult reopenedDetection = new GpsSignalDiscoveryService().discover(reopened.snapshot().entries());
            assertTrue(reopenedDetection.hasReliableRoute(), reopenedDetection.summary());
            assertEquals("GPS_LAT_INFO", reopenedDetection.pair().orElseThrow().latitude().signalName());
            assertEquals("GPS_LONG_INFO", reopenedDetection.pair().orElseThrow().longitude().signalName());
        }
    }

    @Test
    @Timeout(180)
    @EnabledIf("lavasaPresent")
    void lavasaNativeGpsRemainsAutoDetected() throws Exception {
        Path artifactRoot = temp.resolve("lavasa-artifact");
        Path dummyDbc = temp.resolve("dummy.dbc");
        Files.writeString(dummyDbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 256 EngineData: 8 ECU
                 SG_ EngineRPM : 0|16@1+ (0.25,0) [0|8000] "rpm" ECU
                """);
        DbcDatabase dbc = new DbcParser().parse(dummyDbc);
        try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(
                artifactRoot, LAVASA.getFileName().toString(), dummyDbc.getFileName().toString())) {
            MeasurementDecodePipeline.createDefault().process(
                    LAVASA,
                    dbc,
                    ReaderContext.ofBatchSize(256),
                    store::accept,
                    ProcessingProgressListener.NOOP,
                    ProcessingSelection.none(),
                    NativeMeasurementPolicy.INCLUDE);
            store.finishWriteSession();
            SignalsScreenModel model = new SignalsScreenModel();
            try {
                model.replaceCatalog(store);
                GpsDetectionResult detected = new GpsSignalDiscoveryService().discover(model.mapRouteCatalog());
                assertTrue(detected.hasReliableRoute(), detected.summary());
                assertEquals("LATITUDE", detected.pair().orElseThrow().latitude().signalName());
                assertEquals("LONGITUDE", detected.pair().orElseThrow().longitude().signalName());
                Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
                roles.putAll(GpsSignalDiscoveryService.toRoleMap(detected));
                MapRoute prepared = new MapRouteService(store.graphQuery()).prepare(
                        new MapRouteCacheKey("lavasa-lifecycle", LAVASA.getFileName().toString(), 1L, roles),
                        Long.MIN_VALUE, Long.MAX_VALUE);
                assertTrue(Math.abs(prepared.model().size() - 6288) <= 5, "points=" + prepared.model().size());
                assertEquals(5_935.0, prepared.model().totalDistanceMeters(), 20.0);
                assertEquals(0, countBreaks(prepared.model()));
                DriveSegmentSummary analysis = new DriveSegmentAnalysisService().analyze(
                        new DriveSegmentAnalysisService.DriveSegmentAnalysisRequest(
                                prepared.model(), 0, prepared.model().size() - 1,
                                prepared.model().firstTimestampNanos(), prepared.model().lastTimestampNanos(),
                                false, null, store.graphQuery(), model.mapRouteCatalog(), List.of(), null, 0,
                                LAVASA.getFileName().toString(), null));
                assertEquals(5_935.0, analysis.motion().range().distanceMeters(), 20.0);
                assertTrue(analysis.speed() != null && (analysis.speed().available()
                        || analysis.speed().kind() == com.stellar.platform.processing.drive.speed.SpeedSourceKind.GPS_COORDINATE_DERIVED));
                System.out.println("LAVASA SPEED kind=" + analysis.speed().kind()
                        + " signal=" + analysis.speed().displayName()
                        + " productMax=" + analysis.speed().maximumKph()
                        + " reason=" + analysis.speed().reason());
            } finally {
                model.shutdown();
            }
        }
    }

    private static int countBreaks(com.stellar.platform.processing.map.MapRouteModel model) {
        int gaps = 0;
        for (int i = 1; i < model.size(); i++) {
            if (model.segmentBreakBefore(i)) {
                gaps++;
            }
        }
        return gaps;
    }

    private static SignalCatalogEntry requireNamed(List<SignalCatalogEntry> catalog, String name) {
        return catalog.stream()
                .filter(e -> name.equals(e.signalName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " missing from Map Route catalog"));
    }

    private static void printPresence(String name, SignalCatalogEntry entry) {
        System.out.println(name
                + " present=true"
                + " source=" + entry.sourceType()
                + " ch=" + entry.channel()
                + " msg=" + entry.messageName()
                + " can=" + entry.canId()
                + " unit=" + entry.unit()
                + " samples=" + entry.sampleCount()
                + " min=" + entry.minPhysical()
                + " max=" + entry.maxPhysical()
                + " latScore=" + GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, entry)
                + " lonScore=" + GpsSignalRoleResolver.scoreRole(GpsSignalRole.LONGITUDE, entry)
                + " altScore=" + GpsSignalRoleResolver.scoreRole(GpsSignalRole.ALTITUDE, entry));
    }

    private static boolean gpsName(String value) {
        String n = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return n.contains("gps") || n.contains("lat") || n.contains("lon") || n.contains("coord") || n.contains("alt");
    }

    private static void persistCatalog(Path sqlite, String projectId, List<SignalCatalogEntry> catalog) {
        Instant now = Instant.now();
        List<SignalCatalogMetadata> rows = new ArrayList<>();
        for (SignalCatalogEntry entry : catalog) {
            var key = entry.key();
            String signalKey = key.signalName() + "|" + key.sourceType() + "|" + key.messageName()
                    + "|" + key.canId() + "|" + key.channel();
            rows.add(new SignalCatalogMetadata(
                    UUID.randomUUID().toString(),
                    projectId,
                    key.sourceFile().isBlank() ? "source-1" : key.sourceFile(),
                    key.channel(),
                    "",
                    "art-1",
                    signalKey,
                    key.signalName(),
                    key.messageName(),
                    key.canId(),
                    key.channel(),
                    key.sourceType().name(),
                    entry.unit(),
                    entry.sampleCount(),
                    entry.firstTimestampNanos(),
                    entry.lastTimestampNanos(),
                    entry.minPhysical(),
                    entry.maxPhysical(),
                    now,
                    now));
        }
        try (SqliteProjectMetadataStore metadata = new SqliteProjectMetadataStore(sqlite)) {
            metadata.upsertProject(new ProjectMetadata(projectId, "Bengaluru GPS", now, now));
            metadata.replaceSignalCatalog(projectId, "art-1", rows);
        }
    }

    private static List<SignalCatalogEntry> hydrate(
            List<SignalCatalogMetadata> rows, String sourceFile, String sourceDatabase) {
        List<SignalCatalogEntry> out = new ArrayList<>();
        for (SignalCatalogMetadata row : rows) {
            SignalSourceType type;
            try {
                type = SignalSourceType.valueOf(row.sourceType());
            } catch (IllegalArgumentException ex) {
                type = SignalSourceType.DBC_CAN;
            }
            String message = row.messageName() == null || row.messageName().isBlank() ? "UNKNOWN" : row.messageName();
            SignalCatalogKey key = new SignalCatalogKey(
                    row.signalName(), type, message, row.canId(), row.signalChannel(), sourceFile, sourceDatabase);
            out.add(new SignalCatalogEntry(
                    key, row.unit(), row.sampleCount(),
                    row.startTimestampNanos(), row.endTimestampNanos(),
                    row.minValue(), row.maxValue(), sourceFile, sourceDatabase));
        }
        return out;
    }
}

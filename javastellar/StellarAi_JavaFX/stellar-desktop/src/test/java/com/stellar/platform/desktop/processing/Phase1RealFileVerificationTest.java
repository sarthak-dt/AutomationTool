package com.stellar.platform.desktop.processing;

import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProjectRouteReportLoader;
import com.stellar.platform.desktop.presentation.RouteMapReportContext;
import com.stellar.platform.desktop.project.FileKindDetector;
import com.stellar.platform.desktop.project.ProjectFilesAddResult;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.mf4.Mf4Inspector;
import com.stellar.platform.parser.mf4.report.MdfInspectionReport;
import com.stellar.platform.processing.DetectedMeasurementFormat;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.graph.CancellableLodGraphSeriesQuery;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.processing.graph.timeline.RecordingDescriptor;
import com.stellar.platform.processing.map.time.RecordingClockReader;
import com.stellar.platform.processing.graph.timeline.RecordingGroupIndex;
import com.stellar.platform.processing.graph.timeline.TimelineGroup;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.ProjectRouteAssembler;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 real-file inventory and processing. Original measurement bytes are only read.
 * Unfinalized MF4 containers are inspected and are not finalized or physically decoded.
 */
@Timeout(value = 45, unit = TimeUnit.MINUTES)
class Phase1RealFileVerificationTest {

    private static final Path MF4_A = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files");
    private static final Path MF4_B = Path.of("C:\\Users\\DT - User\\Downloads\\OneDrive_2_7-13-2026_MF4 files");
    private static final Path BLF = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\BLF");
    private static final Path ASC = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\ASC");
    private static final Path DAT = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\dat files 3.1\\.dat files 3.1");
    private static final Path DBC = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\dbcfiles");
    private static final String XENON =
            "Xenon new script C910P41 2020-02-07 16-22-19-999140 Partition 0.dat";
    private static final String XENON_SHA =
            "F66753745C79EC35D7AA87CE96B877D549640C15FD52286585887F2D6ED62CF5";
    private static final String KNOWN_MF4 = "4BE883CD_00000975_00000001-63C7A554.MF4";
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\phase1-real-file-verification.txt");
    private static final Path FORBIDDEN_E2E = Path.of("D:\\StellarAI-E2E");
    private static final Path FORBIDDEN_CONFIG = Path.of("D:\\config");

    private static final StringBuilder NOTES = new StringBuilder();

    @TempDir
    Path temp;

    private DesktopAppService service;

    @AfterAll
    static void release() {
        AnalysisStorageRoots.clear();
    }

    @Test
    void inventoryAndProcessSuppliedMeasurements() throws Exception {
        note("started=" + Instant.now());
        note("java=" + System.getProperty("java.version") + " " + System.getProperty("java.home"));
        note("product=" + com.stellar.platform.desktop.DesktopVersion.displayVersion()
                + " mavenGav=0.1.0-SNAPSHOT");
        Path home = temp.resolve("home").toAbsolutePath().normalize();
        Path db = temp.resolve("meta.db").toAbsolutePath().normalize();
        refuseUnsafe(home);
        refuseUnsafe(db);
        note("testHome=" + home);
        note("metadataDb=" + db);
        Map<String, List<Path>> byHash = new LinkedHashMap<>();
        List<Path> originals = new ArrayList<>();
        try {
            inventoryRoot("MF4Files", MF4_A, byHash, originals);
            inventoryRoot("MF4OneDrive", MF4_B, byHash, originals);
            inventoryRoot("BLF", BLF, byHash, originals);
            inventoryRoot("ASC", ASC, byHash, originals);
            inventoryRoot("DAT", DAT, byHash, originals);
            inventoryRoot("DBC", DBC, byHash, originals);
            noteDuplicates(byHash);
            inspectMf4(originals);
            processDatProject(home, db);
            processCanInventory();
            confirmOriginalsUnchanged(originals);
            note("result=PASS");
        } catch (Throwable failure) {
            note("result=FAIL " + failure);
            throw failure;
        } finally {
            if (service != null) {
                service.shutdown();
                service = null;
            }
            Files.createDirectories(EVIDENCE.getParent());
            Files.writeString(EVIDENCE, NOTES.toString());
            AnalysisStorageRoots.clear();
        }
    }

    private void processDatProject(Path home, Path db) throws Exception {
        List<Path> dats;
        try (Stream<Path> stream = Files.walk(DAT)) {
            dats = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dat"))
                    .sorted()
                    .toList();
        }
        assertFalse(dats.isEmpty(), "no DAT files");
        openService(home, db);
        service.newProject("Phase1 Real DAT");
        ProjectFilesAddResult added = service.addMeasurementPaths(dats, DesktopAppService.PlatformUi.NOOP);
        note("datAdded=" + added.addedCount() + " summary=" + added.summaryMessage());
        assertEquals(dats.size(), added.addedCount(), added.summaryMessage());
        assertFalse(service.directProcessingTargets().isEmpty());
        service.selectMeasurement(service.directProcessingTargets().getFirst().id());
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        service.startLocalProcessing(DesktopAppService.PlatformUi.NOOP, () -> {
            ProjectProcessingStatus status = service.workspace().processingStatus();
            if (!finished.get() && (status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                    || status == ProjectProcessingStatus.FAILED
                    || status == ProjectProcessingStatus.CANCELLED)) {
                finished.set(true);
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.MINUTES), "DAT processing did not finish");
        ProjectProcessingStatus status = service.workspace().processingStatus();
        String message = service.workspace().latestSummary().map(summary -> summary.userMessage()).orElse("");
        note("datStatus=" + status + " message=" + message);
        assertTrue(status == ProjectProcessingStatus.COMPLETED
                || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS, status + " " + message);

        ProjectMergedSignalStore merged = merged();
        long stored = 0;
        Map<String, Long> perFile = new LinkedHashMap<>();
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            long one = merged.physicalCatalog(file.id(), file.fileName()).stream()
                    .mapToLong(SignalCatalogEntry::sampleCount).sum();
            perFile.put(file.fileName(), one);
            stored += one;
            note("datFile " + file.fileName() + " storedSamples=" + one + " signals="
                    + merged.physicalCatalog(file.id(), file.fileName()).size());
        }
        note("datSources=" + perFile.size() + " logicalSignals=" + service.signalsModel().catalog().entries().size()
                + " storedSamples=" + stored);
        assertEquals(73, perFile.size());
        assertEquals(30, service.signalsModel().catalog().entries().size());
        assertEquals(18_700_329L, stored);
        assertEquals(268_069L, perFile.get(XENON));

        WorkspaceFile xenon = fileNamed(XENON);
        SignalCatalogEntry logicalSpeed = logical("IN_Vehicle_speed");
        SignalCatalogKey xenonSpeed = merged.physicalKeyFor(logicalSpeed.key(), xenon.id()).orElseThrow();
        SignalCatalogEntry speedPhysical = physical(xenonSpeed);
        assertEquals(9_638L, speedPhysical.sampleCount());
        assertEquals("km/h", speedPhysical.unit());
        AtomicReference<String> speedHit = new AtomicReference<>("missing");
        AtomicReference<String> coolantHit = new AtomicReference<>("missing");
        AtomicReference<String> fanHit = new AtomicReference<>("missing");
        merged.forEachSample(xenonSpeed, sample -> {
            if ("missing".equals(speedHit.get()) && sample.rawValue() != null && sample.rawValue() == 9340L) {
                speedHit.set(sample.rawValue() + "->" + sample.physicalValue() + " " + sample.validity());
                assertEquals(72.96875, sample.physicalValue(), 0.0);
                assertEquals(SignalValidity.VALID, sample.validity());
            }
        });
        SignalCatalogKey xenonCoolant = merged.physicalKeyFor(logical("IN_Coolant_temperature").key(), xenon.id()).orElseThrow();
        merged.forEachSample(xenonCoolant, sample -> {
            if ("missing".equals(coolantHit.get()) && sample.rawValue() != null && sample.rawValue() == 320L) {
                coolantHit.set(sample.rawValue() + "->" + sample.physicalValue());
                assertEquals(80.0, sample.physicalValue(), 0.0);
            }
        });
        SignalCatalogKey xenonFan = merged.physicalKeyFor(logical("ETC_Fan2_output").key(), xenon.id()).orElseThrow();
        merged.forEachSample(xenonFan, sample -> {
            if ("missing".equals(fanHit.get()) && sample.rawValue() != null && sample.rawValue() == 0L) {
                fanHit.set(sample.textualValue() + " " + sample.validity() + " numeric=" + sample.physicalValue());
                assertEquals("FALSE", sample.textualValue());
                assertEquals(SignalValidity.VALID, sample.validity());
                assertTrue(Double.isNaN(sample.physicalValue()));
            }
        });
        note("xenonSpeed=" + speedHit.get());
        note("xenonCoolant=" + coolantHit.get());
        note("xenonFan=" + fanHit.get());
        assertFalse(speedHit.get().equals("missing"));
        assertFalse(coolantHit.get().equals("missing"));
        assertFalse(fanHit.get().equals("missing"));

        GraphQueryProvider graphs = (GraphQueryProvider) merged;
        assertTrue(graphs.graphQuery().sampleCount(xenonSpeed) > 1_024);
        var page = graphs.graphQuery().queryRange(
                xenonSpeed, speedPhysical.firstTimestampNanos(), speedPhysical.lastTimestampNanos(), 8);
        assertEquals(8, page.size());
        assertEquals(speedPhysical.firstTimestampNanos().longValue(), page.getFirst().timestampNanos());
        note("graphLimited=" + page.size() + " firstTs=" + page.getFirst().timestampNanos()
                + " source=" + speedPhysical.sourceFile());
        assertTrue(graphs.graphQuery() instanceof CancellableLodGraphSeriesQuery);
        List<?> cancelled = ((CancellableLodGraphSeriesQuery) graphs.graphQuery()).queryRange(
                xenonSpeed, speedPhysical.firstTimestampNanos(), speedPhysical.lastTimestampNanos(), () -> false);
        assertTrue(cancelled.isEmpty(), "a cancelled graph query must stop without returning samples");
        assertEquals(9_638L, graphs.graphQuery().sampleCount(xenonSpeed));

        assertUnknownOrder();
        assertGps(xenon);
        AnalysisResult statistics = service.runEngineeringAnalysis(AnalysisRequest.builder(AnalysisType.STATISTICS)
                .projectId(service.currentProjectId())
                .addSignal(xenonSpeed)
                .build());
        assertEquals(AnalysisStatus.COMPLETED, statistics.status(), String.valueOf(statistics.status()));
        assertEquals(9_638L, statistics.statistics().getFirst().sampleCount());
        note("xenonStatistics=" + statistics.statistics().getFirst().sampleCount()
                + " min=" + statistics.statistics().getFirst().min()
                + " max=" + statistics.statistics().getFirst().max());
        var saved = service.saveProject();
        assertTrue(saved.saved(), saved.message());
        String projectId = service.currentProjectId();
        service.closeProject();
        service.shutdown();
        service = null;
        openService(home, db);
        var opened = service.openSavedProject(projectId);
        assertTrue(opened.opened(), opened.warnings().toString());
        long reopened = 0;
        ProjectMergedSignalStore again = merged();
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            reopened += again.physicalCatalog(file.id(), file.fileName()).stream()
                    .mapToLong(SignalCatalogEntry::sampleCount).sum();
        }
        note("reopenStored=" + reopened + " files=" + service.workspace().measurementFiles().size());
        assertEquals(18_700_329L, reopened);
        assertEquals(73, service.workspace().measurementFiles().size());
    }

    private void assertUnknownOrder() {
        ProjectMergedSignalStore merged = merged();
        List<RecordingDescriptor> recordings = new ArrayList<>();
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            long start = Long.MAX_VALUE;
            long end = Long.MIN_VALUE;
            boolean timed = false;
            for (SignalCatalogEntry entry : merged.physicalCatalog(file.id(), file.fileName())) {
                if (entry.firstTimestampNanos() != null && entry.lastTimestampNanos() != null) {
                    start = Math.min(start, entry.firstTimestampNanos());
                    end = Math.max(end, entry.lastTimestampNanos());
                    timed = true;
                }
            }
            if (!timed) {
                start = 0;
                end = 0;
            }
            recordings.add(new RecordingDescriptor(
                    file.id(), file.fileName(), RecordingClockReader.read(file.path(), file.id()), start, end));
        }
        RecordingGroupIndex index = RecordingGroupIndex.of(recordings);
        note("timeline utc=" + index.verifiedUtc().size() + " unknown=" + index.unknownOrder().size());
        assertEquals(73, index.unknownOrder().size());
        UnknownOrderLayout layout = index.unknownLayout();
        assertFalse(layout.connectsAcrossSlots());
        for (UnknownOrderLayout.Slot slot : layout.slots()) {
            assertEquals(UnknownOrderLayout.EQUAL_SLOT_SPAN, slot.layoutEnd() - slot.layoutStart());
            long[] stored = layout.storedQuery(slot, slot.layoutStart(), slot.layoutEnd());
            assertEquals(slot.storedStartNanos(), stored[0]);
            assertEquals(slot.storedEndNanos(), stored[1]);
            assertEquals(null, index.verifiedUtcNanos(TimelineGroup.UNKNOWN_ORDER, slot.layoutStart()));
        }
    }

    private void assertGps(WorkspaceFile xenon) {
        service.selectAnalysisScope(AnalysisScope.allMeasurements(73));
        RouteMapReportContext all = new ProjectRouteReportLoader().load(service);
        assertNotNull(all);
        ProjectRoute route = all.route();
        assertFalse(ProjectRouteAssembler.connectsAdjacentFiles(route));
        note("gpsRoutes=" + route.routeSegmentCount() + " gpsPoints=" + route.gpsSampleCount()
                + " capable=" + route.gpsCapableCount());
        assertEquals(73, route.gpsCapableCount());
        assertEquals(73, route.routeSegmentCount());
        assertEquals(634_781L, route.gpsSampleCount());
        Set<String> names = new LinkedHashSet<>();
        RouteSegment xenonSegment = null;
        for (RouteSegment segment : route.gpsSegments()) {
            assertTrue(names.add(segment.displayName()), segment.displayName());
            if (XENON.equals(segment.displayName())) {
                xenonSegment = segment;
            }
        }
        assertNotNull(xenonSegment);
        MapRouteModel model = xenonSegment.route().model();
        note("xenonGps points=" + model.size()
                + " start=" + model.latitude(0) + "," + model.longitude(0)
                + " end=" + model.latitude(model.size() - 1) + "," + model.longitude(model.size() - 1));
        RouteMapReportContext selected = new ProjectRouteReportLoader().load(
                false, xenon.id(), xenon.fileName(), 1, merged().physicalCatalogAll(),
                ((GraphQueryProvider) merged()).graphQuery(),
                service.loadAllGpsSignalMappings(), service.loadRouteConfigDefaults());
        MapRouteModel one = selected.route().gpsSegments().getFirst().route().model();
        assertEquals(model.latitude(0), one.latitude(0));
        assertEquals(model.longitude(0), one.longitude(0));
        assertEquals(model.latitude(model.size() - 1), one.latitude(one.size() - 1));
        assertEquals(model.longitude(model.size() - 1), one.longitude(one.size() - 1));
    }

    private void processCanInventory() throws Exception {
        service.newProject("Phase1 Real CAN");
        List<Path> measurements = new ArrayList<>();
        measurements.addAll(regularFiles(BLF));
        measurements.addAll(regularFiles(ASC));
        ProjectFilesAddResult added = service.addMeasurementPaths(measurements, DesktopAppService.PlatformUi.NOOP);
        note("canAdded=" + added.addedCount() + " summary=" + added.summaryMessage());
        List<Path> dbcs = regularFiles(DBC).stream()
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dbc"))
                .toList();
        ProjectFilesAddResult definitions = service.addDefinitionPaths(dbcs);
        note("dbcAdded=" + definitions.addedCount() + " summary=" + definitions.summaryMessage());
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            awaitInventory(file, 240);
            var inventory = service.sourceInventoryModel().inventoryFor(file.id());
            String detail = inventory.map(this::inventoryDetail).orElse("none");
            note("canInventory " + file.fileName() + " " + detail);
            if (file.fileName().contains("13-50-56-074765")) {
                CanSourceInventory ready = inventory.orElseThrow();
                assertEquals(204_239L, ready.totalCanFrames());
            }
            if (file.fileName().contains("145851")) {
                CanSourceInventory ready = inventory.orElseThrow();
                assertEquals(8_792L, ready.totalCanFrames());
                assertEquals(3L, ready.totalCanChannels());
                assertTrue(ready.diagnostics().stream().anyMatch(item -> item.message().contains("62000230")));
            }
        }
        note("canProcessing=NOT RUN as physical signal PASS. Inventory frames are recorded. "
                + "No independent physical oracle is available for these BLF and ASC files, "
                + "so decoded engineering values are not claimed.");
    }

    private void inspectMf4(List<Path> originals) {
        Mf4Inspector inspector = new Mf4Inspector();
        for (Path file : originals) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".mf4")) {
                continue;
            }
            try {
                MdfInspectionReport report = inspector.inspect(file);
                note("mf4Inspect file=" + file.getFileName()
                        + " version=" + report.mdfVersion()
                        + " finalization=" + report.finalizationStatus()
                        + " recoveryCandidate=" + report.recoveryCandidate()
                        + " physicalData=" + report.physicalDataDetected()
                        + " declaredCycles=" + report.declaredCycleCountTotal()
                        + " possibleRecords=" + report.possibleRecordCount()
                        + " reasons=" + report.finalizationReasons());
                if (KNOWN_MF4.equals(file.getFileName().toString())) {
                    note("knownMf4 physicalDecoding=NOT RUN. Container is " + report.finalizationStatus()
                            + " recoveryCandidate=" + report.recoveryCandidate()
                            + ". Earlier inventory was 807209 frames. No matching definition and no independent "
                            + "physical values are available. Product-owner decision is required before physical "
                            + "decoding can be accepted. The original file was not modified.");
                }
            } catch (Exception ex) {
                note("mf4Inspect file=" + file.getFileName() + " ERROR " + ex.getClass().getSimpleName()
                        + " " + ex.getMessage());
            }
        }
    }

    private void inventoryRoot(String label, Path root, Map<String, List<Path>> byHash, List<Path> originals)
            throws Exception {
        note("folder " + label + " path=" + root + " exists=" + Files.isDirectory(root));
        if (!Files.isDirectory(root)) {
            note("folder " + label + " UNAVAILABLE");
            return;
        }
        List<Path> empty = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.forEach(path -> {
                if (Files.isDirectory(path)) {
                    try (Stream<Path> children = Files.list(path)) {
                        if (children.findAny().isEmpty() && !path.equals(root)) {
                            empty.add(path);
                        }
                    } catch (Exception ex) {
                        note("folder " + label + " unreadable=" + path + " " + ex.getMessage());
                    }
                } else if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            });
        }
        files.sort(Comparator.comparing(Path::toString));
        note(label + " files=" + files.size() + " emptyDirectories=" + empty.size());
        for (Path emptyDir : empty) {
            note(label + " emptyDirectory=" + root.relativize(emptyDir));
        }
        for (Path file : files) {
            String hash = sha256(file);
            byHash.computeIfAbsent(hash, key -> new ArrayList<>()).add(file);
            originals.add(file);
            byte[] head = new byte[16];
            int read;
            try (var in = Files.newInputStream(file)) {
                read = in.read(head);
            }
            FileKindDetector.Inspection inspection = FileKindDetector.inspect(file);
            DetectedMeasurementFormat detected = inspection.detected();
            String extension = extension(file);
            String note = "";
            if ("log".equals(extension) && (detected == null || detected.content() == null)) {
                note = " application log is not a supported measurement LOG";
            }
            note(label
                    + " file=" + root.relativize(file)
                    + " name=" + file.getFileName()
                    + " extension=" + extension
                    + " signature=" + hex(head, read)
                    + " size=" + Files.size(file)
                    + " sha256=" + hash
                    + " kind=" + inspection.kind()
                    + " parser=" + inspection.parserSupported()
                    + " recognized=" + inspection.recognized()
                    + " content=" + (detected == null || detected.content() == null
                    ? "unsupported"
                    : detected.content().formatKey() + " " + detected.displayLabel())
                    + note);
        }
    }

    private void noteDuplicates(Map<String, List<Path>> byHash) {
        int groups = 0;
        for (var entry : byHash.entrySet()) {
            if (entry.getValue().size() > 1) {
                groups++;
                note("duplicate sha256=" + entry.getKey() + " paths=" + entry.getValue());
            }
        }
        note("duplicateGroups=" + groups);
    }

    private void confirmOriginalsUnchanged(List<Path> originals) throws Exception {
        Path xenon = DAT.resolve(XENON);
        Path known = findNamed(originals, KNOWN_MF4);
        assertEquals(XENON_SHA, sha256(xenon));
        note("xenonShaUnchanged=" + XENON_SHA);
        if (known != null) {
            note("knownMf4ShaUnchanged=" + sha256(known));
        }
        note("originalsReadOnly count=" + originals.size());
    }

    private static Path findNamed(List<Path> files, String name) {
        return files.stream().filter(path -> name.equals(path.getFileName().toString())).findFirst().orElse(null);
    }

    private void awaitInventory(WorkspaceFile file, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            var state = service.inventoryJobState(file.projectSourceId());
            if (state != null && state.terminal()) {
                return;
            }
            Thread.sleep(200);
        }
        note("inventoryTimeout " + file.fileName());
    }

    private String inventoryDetail(CanSourceInventory inventory) {
        return "status=" + inventory.status()
                + " frames=" + inventory.totalCanFrames()
                + " channels=" + inventory.totalCanChannels()
                + " diagnostics=" + inventory.diagnostics();
    }

    private void openService(Path home, Path db) {
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        refuseUnsafe(Path.of(System.getProperty("stellarai.home")));
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
    }

    private static void refuseUnsafe(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(FORBIDDEN_E2E) || normalized.startsWith(FORBIDDEN_CONFIG)) {
            throw new IllegalStateException("Refusing to write " + normalized);
        }
        for (Path source : List.of(MF4_A, MF4_B, BLF, ASC, DAT, DBC)) {
            if (normalized.startsWith(source.toAbsolutePath().normalize())) {
                throw new IllegalStateException("Refusing to write inside supplied measurements " + normalized);
            }
        }
    }

    private ProjectMergedSignalStore merged() {
        assertTrue(service.signalsModel().catalogStore() instanceof ProjectMergedSignalStore);
        return (ProjectMergedSignalStore) service.signalsModel().catalogStore();
    }

    private SignalCatalogEntry logical(String name) {
        return service.signalsModel().catalog().entries().stream()
                .filter(entry -> name.equals(entry.signalName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + name));
    }

    private SignalCatalogEntry physical(SignalCatalogKey key) {
        return merged().physicalCatalogAll().stream()
                .filter(entry -> key.equals(entry.key()))
                .findFirst()
                .orElseThrow();
    }

    private WorkspaceFile fileNamed(String name) {
        return service.workspace().measurementFiles().stream()
                .filter(file -> name.equals(file.fileName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name));
    }

    private static List<Path> regularFiles(Path folder) throws Exception {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(folder)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
    }

    private static String hex(byte[] bytes, int length) {
        int n = Math.max(0, Math.min(length, bytes.length));
        return HexFormat.of().formatHex(bytes, 0, n);
    }

    private static void note(String line) {
        NOTES.append(line).append(System.lineSeparator());
    }
}

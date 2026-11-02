package com.stellar.platform.desktop.processing;

import com.stellar.platform.analysis.advanced.AdvancedAnalyticsRequest;
import com.stellar.platform.analysis.advanced.AdvancedAnalyticsResult;
import com.stellar.platform.analysis.advanced.AdvancedAnalyticsType;
import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.presentation.ProjectRouteReportLoader;
import com.stellar.platform.desktop.presentation.RouteMapReportContext;
import com.stellar.platform.desktop.project.ProjectFilesAddResult;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.graph.GraphMeasurementSeries;
import com.stellar.platform.processing.graph.GraphQueryProvider;
import com.stellar.platform.processing.graph.timeline.RecordingDescriptor;
import com.stellar.platform.processing.map.time.RecordingClockReader;
import com.stellar.platform.processing.graph.timeline.RecordingGroupIndex;
import com.stellar.platform.processing.graph.timeline.TimelineGroup;
import com.stellar.platform.processing.graph.timeline.TimelineViewportPlanner;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.ProjectRouteAssembler;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1_SRC_LIFECYCLE_001 and P1_SRC_LIFECYCLE_002.
 * Add one measurement after processing, then remove one, using the supported
 * project-level Direct Processing path. Original measurement files are only read.
 */
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class SourceAddRemoveAfterProcessingTest {

    private static final Path DAT = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\dat files 3.1\\.dat files 3.1");
    private static final String FILE_A =
            "Xenon new script C910P41 2020-02-03 12-39-52-680142 Partition 0.dat";
    private static final String FILE_B =
            "Xenon new script C910P41 2020-02-08 09-30-16-094953 Partition 0.dat";
    private static final String FILE_C =
            "Xenon new script C910P41 2020-02-04 13-42-03-688072 Partition 0.dat";
    private static final String SPEED = "IN_Vehicle_speed";
    private static final String LATITUDE = "Latitude";
    private static final Path EVIDENCE = Path.of(
            "D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\source-lifecycle-execution.txt");

    private static final StringBuilder NOTES = new StringBuilder();

    @TempDir
    Path temp;

    private DesktopAppService service;
    private Path home;
    private Path db;
    private String speedSignal = SPEED;
    private String gpsSignal = LATITUDE;
    private final Map<String, Long> speedByName = new LinkedHashMap<>();
    private final Map<String, Long> storedByName = new LinkedHashMap<>();
    private final Map<String, Long> gpsByName = new LinkedHashMap<>();
    private final Map<String, Long> routePointsByName = new LinkedHashMap<>();
    private final Map<String, String> shaByName = new LinkedHashMap<>();

    @AfterAll
    static void writeEvidence() throws Exception {
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, NOTES.toString());
        AnalysisStorageRoots.clear();
    }

    @Test
    void addThenRemoveMeasurementAfterProcessing() throws Exception {
        Path pathA = DAT.resolve(FILE_A);
        Path pathB = DAT.resolve(FILE_B);
        Path pathC = DAT.resolve(FILE_C);
        assertTrue(Files.isRegularFile(pathA), pathA.toString());
        assertTrue(Files.isRegularFile(pathB), pathB.toString());
        assertTrue(Files.isRegularFile(pathC), pathC.toString());
        rememberFile(FILE_A, pathA);
        rememberFile(FILE_B, pathB);
        rememberFile(FILE_C, pathC);
        note("started=" + Instant.now());
        note("contract=project-level Direct Processing runs every active DAT/LOG source");
        try {
            runLifecycle(pathA, pathB, pathC);
        } catch (Throwable failure) {
            note("result=FAIL " + failure);
            throw failure;
        } finally {
            if (service != null) {
                service.shutdown();
                service = null;
            }
        }
    }

    private void runLifecycle(Path pathA, Path pathB, Path pathC) throws Exception {
        openService();
        service.newProject("Source Lifecycle");
        ProjectFilesAddResult first = service.addMeasurementPaths(List.of(pathA, pathB), DesktopAppService.PlatformUi.NOOP);
        assertEquals(2, first.addedCount(), first.summaryMessage());
        process("initial-ab");
        captureIdentity("after-ab");
        assertStage("after-ab", Set.of(FILE_A, FILE_B));
        exerciseModules("after-ab", Set.of(FILE_A, FILE_B));
        reopen("after-ab");
        assertStage("reopen-ab", Set.of(FILE_A, FILE_B));

        ProjectFilesAddResult added = service.addMeasurementPaths(List.of(pathC), DesktopAppService.PlatformUi.NOOP);
        assertEquals(1, added.addedCount(), added.summaryMessage());
        assertEquals(Set.of(FILE_A, FILE_B, FILE_C), names());
        process("add-c");
        captureIdentity("after-abc");
        assertStage("after-abc", Set.of(FILE_A, FILE_B, FILE_C));
        exerciseModules("after-abc", Set.of(FILE_A, FILE_B, FILE_C));
        String abcReport = report("Lifecycle ABC", Set.of(FILE_A, FILE_B, FILE_C));
        reopen("after-abc");
        assertStage("reopen-abc", Set.of(FILE_A, FILE_B, FILE_C));

        WorkspaceFile fileB = fileNamed(FILE_B);
        String removedId = fileB.id();
        service.selectAnalysisScope(AnalysisScope.single(removedId, fileB.fileName()));
        SignalCatalogKey focus = physicalKey(speedSignal, removedId);
        service.graphInvestigation().setFocus(focus);
        assertTrue(service.removeFile(removedId));
        assertEquals(shaByName.get(FILE_B), sha256(pathB), "original File B changed on disk");
        assertFalse(names().contains(FILE_B));
        assertTrue(service.analysisScope().isAll(), "File Focus on the removed source must recover");
        SignalCatalogKey cleared = service.graphInvestigation().focusKey();
        assertTrue(cleared == null || !removedId.equals(cleared.sourceFile()));
        process("remove-b");
        assertStage("after-remove-b", Set.of(FILE_A, FILE_C));
        assertAbsent(removedId, FILE_B);
        exerciseModules("after-remove-b", Set.of(FILE_A, FILE_C));
        report("Lifecycle AC", Set.of(FILE_A, FILE_C));
        reopen("after-remove-b");
        assertStage("reopen-ac", Set.of(FILE_A, FILE_C));
        assertAbsent(removedId, FILE_B);

        ProjectFilesAddResult again = service.addMeasurementPaths(List.of(pathB), DesktopAppService.PlatformUi.NOOP);
        assertEquals(1, again.addedCount(), again.summaryMessage());
        process("readd-b");
        assertStage("after-readd-b", Set.of(FILE_A, FILE_B, FILE_C));
        exerciseModules("after-readd-b", Set.of(FILE_A, FILE_B, FILE_C));
        WorkspaceFile fileBAgain = fileNamed(FILE_B);
        assertTrue(service.removeFile(fileBAgain.id()));
        process("remove-b-again");
        assertStage("after-remove-b-again", Set.of(FILE_A, FILE_C));
        assertEquals(shaByName.get(FILE_A), sha256(pathA));
        assertEquals(shaByName.get(FILE_B), sha256(pathB));
        assertEquals(shaByName.get(FILE_C), sha256(pathC));
        note("abcReport=" + abcReport);
        note("finished=" + Instant.now());
        note("result=PASS");
    }

    private void rememberFile(String name, Path path) throws Exception {
        shaByName.put(name, sha256(path));
        note("file " + name + " size=" + Files.size(path) + " sha256=" + shaByName.get(name));
    }

    private void process(String stage) throws Exception {
        assertFalse(service.directProcessingTargets().isEmpty(), stage);
        service.selectMeasurement(service.directProcessingTargets().getFirst().id());
        Map<String, Instant> before = artifactTimes();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
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
        assertTrue(done.await(20, TimeUnit.MINUTES), stage + " processing did not finish");
        ProjectProcessingStatus status = service.workspace().processingStatus();
        String message = service.workspace().latestSummary().map(summary -> summary.userMessage()).orElse("");
        note(stage + " status=" + status + " message=" + message);
        assertTrue(status == ProjectProcessingStatus.COMPLETED
                        || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS,
                stage + " " + status + " " + message);
        Map<String, Instant> after = artifactTimes();
        for (String id : after.keySet()) {
            Instant previous = before.get(id);
            note(stage + " artifact " + nameOf(id)
                    + (previous == null ? " created" : previous.equals(after.get(id)) ? " reused" : " redecoded"));
        }
    }

    private void captureIdentity(String stage) {
        ProjectMergedSignalStore merged = merged();
        if (speedByName.isEmpty()) {
            Set<String> shared = null;
            for (WorkspaceFile file : service.workspace().measurementFiles()) {
                Set<String> names = new LinkedHashSet<>();
                for (SignalCatalogEntry entry : merged.physicalCatalog(file.id(), file.fileName())) {
                    if (entry.sampleCount() > 0) {
                        names.add(entry.signalName());
                    }
                }
                shared = shared == null ? names : intersect(shared, names);
            }
            assertNotNull(shared);
            assertTrue(shared.contains(SPEED), stage + " shared signals " + shared);
            assertTrue(shared.contains(LATITUDE), stage + " GPS signal missing from " + shared);
            speedSignal = SPEED;
            gpsSignal = LATITUDE;
        }
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            long stored = 0;
            long speed = 0;
            long gps = 0;
            List<String> signals = new ArrayList<>();
            for (SignalCatalogEntry entry : merged.physicalCatalog(file.id(), file.fileName())) {
                stored += entry.sampleCount();
                signals.add(entry.signalName());
                if (speedSignal.equals(entry.signalName())) {
                    speed = entry.sampleCount();
                }
                if (gpsSignal.equals(entry.signalName())) {
                    gps = entry.sampleCount();
                }
            }
            note(stage + " source " + file.fileName()
                    + " id=" + file.id()
                    + " signals=" + signals.size()
                    + " storedSamples=" + stored
                    + " " + speedSignal + "=" + speed
                    + " " + gpsSignal + "=" + gps);
            if (!storedByName.containsKey(file.fileName())) {
                storedByName.put(file.fileName(), stored);
                speedByName.put(file.fileName(), speed);
                gpsByName.put(file.fileName(), gps);
            }
        }
    }

    private void assertStage(String stage, Set<String> expectedNames) {
        captureIdentity(stage + "-observed");
        assertEquals(expectedNames, names(), stage);
        ProjectMergedSignalStore merged = merged();
        assertEquals(expectedNames.size(), merged.memberStoreCount(), stage + " member stores");
        assertEquals(expectedNames.size(), service.processingResultRegistry().all().size(), stage + " registry");
        long stored = 0;
        long speed = 0;
        long latitudeSamples = 0;
        Set<String> logical = new LinkedHashSet<>();
        for (SignalCatalogEntry entry : service.signalsModel().catalog().entries()) {
            logical.add(entry.signalName());
        }
        for (String name : expectedNames) {
            WorkspaceFile file = fileNamed(name);
            long fileStored = 0;
            long fileSpeed = 0;
            Long seenSpeed = null;
            for (SignalCatalogEntry entry : merged.physicalCatalog(file.id(), file.fileName())) {
                fileStored += entry.sampleCount();
                assertEquals(file.fileName(), entry.sourceFile(), stage);
                assertEquals(file.id(), entry.key().sourceFile(), stage);
                if (speedSignal.equals(entry.signalName())) {
                    assertTrue(seenSpeed == null, stage + " duplicate " + speedSignal + " in " + name);
                    seenSpeed = entry.sampleCount();
                    fileSpeed = entry.sampleCount();
                    assertEquals(speedByName.get(name), fileSpeed, stage + " " + name + " speed inflated");
                }
            }
            assertEquals(storedByName.get(name), fileStored, stage + " " + name + " stored samples inflated");
            assertNotNull(seenSpeed, stage + " " + name + " missing " + speedSignal);
            stored += fileStored;
            speed += fileSpeed;
            latitudeSamples += gpsByName.get(name);
        }
        SignalCatalogEntry logicalSpeed = logical(speedSignal);
        assertEquals(speed, logicalSpeed.sampleCount(), stage + " logical speed is not the sum of source samples");
        assertEquals(expectedNames.size(), merged.physicalRouteCount(logicalSpeed.key()), stage);
        assertEquals(speed, merged.retainedSampleCount(logicalSpeed.key()), stage);
        long artifactStored = 0;
        Set<String> artifactSources = new LinkedHashSet<>();
        SavedProjectSnapshot snapshot = service.metadataStore().loadProject(service.currentProjectId()).orElseThrow();
        for (ProcessingArtifactMetadata artifact : snapshot.processingArtifacts()) {
            if (!ProcessingArtifactMetadata.STATUS_VALID.equals(artifact.status())) {
                continue;
            }
            assertTrue(artifactSources.add(artifact.sourceId()), stage + " duplicate artifact " + artifact.sourceId());
            artifactStored += artifact.sampleCount();
        }
        assertEquals(ids(), artifactSources, stage + " artifact ownership");
        assertEquals(stored, artifactStored, stage + " artifact sample total");
        Set<String> catalogSources = new LinkedHashSet<>();
        for (SignalCatalogMetadata row : snapshot.signalCatalog()) {
            if (row.sourceId() != null && !row.sourceId().isBlank()) {
                catalogSources.add(row.sourceId());
            }
        }
        assertEquals(ids(), catalogSources, stage + " persisted catalog sources");
        note(stage + " sources=" + expectedNames.size()
                + " logicalSignals=" + logical.size()
                + " measurements=" + service.workspace().measurementFiles().size()
                + " storedSamples=" + stored
                + " speedSamples=" + speed
                + " latitudeSamples=" + latitudeSamples);
        assertGps(stage, expectedNames);
        assertAnalytics(stage, expectedNames, speed);
    }

    private void assertGps(String stage, Set<String> expectedNames) {
        service.selectAnalysisScope(AnalysisScope.allMeasurements(expectedNames.size()));
        RouteMapReportContext all = new ProjectRouteReportLoader().load(service);
        assertNotNull(all, stage + " GPS route was not built");
        ProjectRoute route = all.route();
        assertFalse(ProjectRouteAssembler.connectsAdjacentFiles(route), stage);
        assertEquals(expectedNames.size(), route.gpsCapableCount(), stage + " GPS-capable routes");
        long points = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (RouteSegment segment : route.gpsSegments()) {
            assertTrue(seen.add(segment.displayName()), stage + " duplicate route " + segment.displayName());
            assertTrue(ids().contains(segment.measurementId()), stage + " route for inactive source");
            MapRouteModel model = segment.route().model();
            assertTrue(model.size() > 0, stage + " " + segment.displayName());
            note(stage + " route " + segment.displayName()
                    + " points=" + model.size()
                    + " start=" + model.latitude(0) + "," + model.longitude(0)
                    + " end=" + model.latitude(model.size() - 1) + "," + model.longitude(model.size() - 1));
            routePointsByName.putIfAbsent(segment.displayName(), (long) model.size());
            assertEquals(routePointsByName.get(segment.displayName()).intValue(), model.size(),
                    stage + " GPS points changed for " + segment.displayName());
            points += model.size();
            RouteMapReportContext selected = selectedRoute(segment.measurementId(), segment.displayName());
            assertNotNull(selected, stage + " selected route");
            assertEquals(1, selected.route().routeSegmentCount(), stage);
            MapRouteModel one = selected.route().gpsSegments().getFirst().route().model();
            assertEquals(model.latitude(0), one.latitude(0), stage + " START latitude");
            assertEquals(model.longitude(0), one.longitude(0), stage + " START longitude");
            assertEquals(model.latitude(model.size() - 1), one.latitude(one.size() - 1), stage + " END latitude");
            assertEquals(model.longitude(model.size() - 1), one.longitude(one.size() - 1), stage + " END longitude");
        }
        assertEquals(expectedNames, seen, stage + " route membership");
        assertEquals(points, route.gpsSampleCount(), stage + " GPS point total");
        note(stage + " gpsRoutes=" + route.routeSegmentCount() + " gpsPoints=" + points);
    }

    private RouteMapReportContext selectedRoute(String id, String name) {
        ProjectMergedSignalStore merged = merged();
        return new ProjectRouteReportLoader().load(
                false,
                id,
                name,
                1,
                merged.physicalCatalogAll(),
                ((GraphQueryProvider) merged).graphQuery(),
                service.loadAllGpsSignalMappings(),
                service.loadRouteConfigDefaults());
    }

    private void assertAnalytics(String stage, Set<String> expectedNames, long speedSamples) {
        service.selectAnalysisScope(AnalysisScope.allMeasurements(expectedNames.size()));
        SignalCatalogKey logical = logical(speedSignal).key();
        AnalysisResult project = service.runEngineeringAnalysis(AnalysisRequest.builder(AnalysisType.STATISTICS)
                .projectId(service.currentProjectId())
                .addSignal(logical)
                .build());
        assertEquals(AnalysisStatus.COMPLETED, project.status(), stage + " " + project.message());
        assertEquals(1, project.statistics().size(), stage);
        assertEquals(speedSamples, project.statistics().getFirst().sampleCount(),
                stage + " engineering statistics mixed or dropped a source");
        SignalCatalogEntry speed = logical(speedSignal);
        long start = speed.firstTimestampNanos() == null ? 0L : speed.firstTimestampNanos();
        long end = speed.lastTimestampNanos() == null ? start : speed.lastTimestampNanos();
        if (end <= start) {
            end = start + 1;
        }
        AdvancedAnalyticsResult moving = service.runAdvancedAnalytics(
                AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.MOVING_AVERAGE)
                        .projectId(service.currentProjectId())
                        .signals(List.of(logical))
                        .window(TimeWindow.of(start, end))
                        .build());
        assertEquals(AnalysisStatus.COMPLETED, moving.status(), stage + " " + moving.message());
        assertNotNull(moving.derived(), stage + " moving average");
        assertNotNull(moving.sampling(), stage + " moving average samples");
        assertEquals(speedSamples, moving.sampling().sampleCount(),
                stage + " moving average did not use every living speed sample");
        note(stage + " statisticsSamples=" + project.statistics().getFirst().sampleCount()
                + " min=" + project.statistics().getFirst().min()
                + " max=" + project.statistics().getFirst().max()
                + " movingAveragePoints=" + moving.derived().timestamps().length
                + " movingAverageInput=" + moving.sampling().sampleCount()
                + " interrupted=" + Thread.currentThread().isInterrupted());
        for (String name : expectedNames) {
            WorkspaceFile file = fileNamed(name);
            SignalCatalogKey physical = physicalKey(speedSignal, file.id());
            AnalysisResult one = service.runEngineeringAnalysis(AnalysisRequest.builder(AnalysisType.STATISTICS)
                    .projectId(service.currentProjectId())
                    .addSignal(physical)
                    .build());
            assertEquals(AnalysisStatus.COMPLETED, one.status(), name);
            assertEquals(speedByName.get(name), one.statistics().getFirst().sampleCount(),
                    stage + " file statistics " + name);
        }
    }

    private void exerciseModules(String stage, Set<String> expectedNames) {
        ProjectMergedSignalStore merged = merged();
        SignalCatalogEntry speed = logical(speedSignal);
        List<GraphMeasurementSeries.PlotGroup> groups = GraphMeasurementSeries.groupsForPlot(
                List.of(speed.key()),
                List.of(speed),
                merged.physicalCatalogAll(),
                true);
        assertEquals(1, groups.size(), stage);
        GraphMeasurementSeries.PlotGroup group = groups.getFirst();
        assertEquals(expectedNames, new LinkedHashSet<>(group.measurementLabels()), stage + " graph ownership");
        assertEquals(expectedNames.size(), new LinkedHashSet<>(group.physicalKeys()).size(), stage + " duplicate graph series");
        GraphQueryProvider graphs = (GraphQueryProvider) merged;
        for (SignalCatalogKey key : group.physicalKeys()) {
            SignalCatalogEntry physical = merged.physicalCatalogAll().stream()
                    .filter(entry -> key.equals(entry.key()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(physical.sampleCount(), merged.retainedSampleCount(key), stage);
            assertEquals(physical.sampleCount(), graphs.graphQuery().sampleCount(key), stage);
            long start = physical.firstTimestampNanos() == null ? 0L : physical.firstTimestampNanos();
            long end = physical.lastTimestampNanos() == null ? start : physical.lastTimestampNanos();
            if (Thread.interrupted()) {
                note(stage + " cleared interrupt before graph read " + physical.sourceFile());
            }
            assertFalse(graphs.graphQuery().queryRange(key, start, end, 8).isEmpty(),
                    stage + " graph has no points for " + physical.sourceFile());
        }
        assertGraphTimeline(stage, speed.signalName(), speed.unit());
        service.signalsModel().addToWorkspace(List.of(speed.key()));
    }

    private void assertGraphTimeline(String stage, String signal, String unit) {
        ProjectMergedSignalStore merged = merged();
        List<RecordingDescriptor> recordings = new ArrayList<>();
        Map<String, String> units = new LinkedHashMap<>();
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            long start = Long.MAX_VALUE;
            long end = Long.MIN_VALUE;
            boolean timed = false;
            for (SignalCatalogEntry entry : merged.physicalCatalog(file.id(), file.fileName())) {
                if (entry.firstTimestampNanos() != null && entry.lastTimestampNanos() != null
                        && entry.lastTimestampNanos() >= entry.firstTimestampNanos()) {
                    start = Math.min(start, entry.firstTimestampNanos());
                    end = Math.max(end, entry.lastTimestampNanos());
                    timed = true;
                }
                if (signal.equals(entry.signalName())) {
                    units.put(file.id(), entry.unit() == null ? "" : entry.unit());
                }
            }
            if (!timed) {
                start = 0L;
                end = 0L;
            }
            recordings.add(new RecordingDescriptor(
                    file.id(), file.fileName(), RecordingClockReader.read(file.path(), file.id()), start, end));
        }
        RecordingGroupIndex index = RecordingGroupIndex.of(recordings);
        note(stage + " timeline utc=" + index.verifiedUtc().size()
                + " unknown=" + index.unknownOrder().size());
        if (index.unknownOrder().isEmpty()) {
            note(stage + " timeline uses verified UTC; no unknown-order slot is on screen");
            return;
        }
        UnknownOrderLayout layout = index.unknownLayout();
        assertEquals(index.unknownOrder().size(), layout.size(), stage);
        assertFalse(layout.connectsAcrossSlots(), stage);
        assertEquals(null, index.verifiedUtcNanos(TimelineGroup.UNKNOWN_ORDER, layout.layoutStart()), stage);
        TimelineViewportPlanner.LogicalSignalSelection selection =
                new TimelineViewportPlanner.LogicalSignalSelection(signal, unit == null ? "" : unit);
        for (UnknownOrderLayout.Slot slot : layout.slots()) {
            assertEquals(UnknownOrderLayout.EQUAL_SLOT_SPAN, slot.layoutEnd() - slot.layoutStart(),
                    stage + " unknown-order slot presented as recording duration");
            long[] stored = layout.storedQuery(slot, slot.layoutStart(), slot.layoutEnd());
            assertNotNull(stored, stage);
            assertEquals(slot.storedStartNanos(), stored[0], stage);
            assertEquals(slot.storedEndNanos(), stored[1], stage);
        }
        UnknownOrderLayout.Slot first = layout.slot(0);
        TimelineViewportPlanner.Plan zoom = TimelineViewportPlanner.plan(
                index, TimelineGroup.UNKNOWN_ORDER, first.layoutStart(), first.layoutEnd(), selection, units);
        assertEquals(List.of(first.measurementId()), measurementIds(zoom.visible()), stage + " zoom");
        assertEquals(first.storedStartNanos(), zoom.visible().getFirst().storedStartNanos(), stage);
        assertEquals(first.storedEndNanos(), zoom.visible().getFirst().storedEndNanos(), stage);
        if (first.storedSpanNanos() != UnknownOrderLayout.EQUAL_SLOT_SPAN) {
            long queried = zoom.visible().getFirst().storedEndNanos() - zoom.visible().getFirst().storedStartNanos();
            assertFalse(queried == UnknownOrderLayout.EQUAL_SLOT_SPAN && first.storedSpanNanos() != queried,
                    stage + " zoom queried the layout slot as elapsed time");
        }
        if (layout.size() > 1) {
            UnknownOrderLayout.Slot second = layout.slot(1);
            TimelineViewportPlanner.Plan pan = TimelineViewportPlanner.plan(
                    index, TimelineGroup.UNKNOWN_ORDER, second.layoutStart(), second.layoutEnd(), selection, units);
            assertEquals(List.of(second.measurementId()), measurementIds(pan.visible()), stage + " pan");
        }
        TimelineViewportPlanner.Plan fit = TimelineViewportPlanner.plan(
                index, TimelineGroup.UNKNOWN_ORDER, layout.layoutStart(), layout.layoutEnd(), selection, units);
        assertEquals(layout.size(), fit.visible().size(), stage + " fit");
        service.graphInvestigation().setLayoutMode(GraphInvestigationState.OVERLAY);
        assertEquals(GraphInvestigationState.OVERLAY, service.graphInvestigation().layoutMode());
        service.graphInvestigation().setLayoutMode(GraphInvestigationState.STACKED);
        service.graphInvestigation().setViewport(layout.layoutStart(), layout.layoutEnd());
        assertEquals(GraphInvestigationState.STACKED, service.graphInvestigation().layoutMode());
    }

    private String report(String name, Set<String> expectedNames) throws Exception {
        service.selectAnalysisScope(AnalysisScope.allMeasurements(expectedNames.size()));
        var investigation = service.createInvestigation(name, "source lifecycle", "FULL");
        Path html = service.engineeringInvestigations().generateHtmlReport(
                service.metadataStore(), investigation, "qa", null);
        String page = Files.readString(html);
        assertTrue(page.contains("<th>Measurement files</th><td>" + expectedNames.size() + "</td>"),
                name + " measurement count");
        for (String fileName : expectedNames) {
            assertTrue(page.contains(fileName), name + " missing " + fileName);
        }
        for (String fileName : List.of(FILE_A, FILE_B, FILE_C)) {
            if (!expectedNames.contains(fileName)) {
                assertFalse(page.contains(fileName), name + " still names " + fileName);
            }
        }
        note(name + " html=" + html + " bytes=" + Files.size(html));
        return html.toString();
    }

    private void assertAbsent(String removedId, String removedName) {
        assertFalse(names().contains(removedName));
        assertFalse(ids().contains(removedId));
        ProjectMergedSignalStore merged = merged();
        assertFalse(merged.memberIdentities().contains(removedId));
        assertTrue(service.signalsModel().catalog().entries().stream()
                .noneMatch(entry -> removedId.equals(entry.key().sourceFile())));
        assertTrue(merged.physicalCatalogAll().stream()
                .noneMatch(entry -> removedId.equals(entry.key().sourceFile())
                        || removedName.equals(entry.sourceFile())));
        SavedProjectSnapshot snapshot = service.metadataStore().loadProject(service.currentProjectId()).orElseThrow();
        assertTrue(snapshot.processingArtifacts().stream().noneMatch(artifact -> removedId.equals(artifact.sourceId())));
        assertTrue(snapshot.signalCatalog().stream().noneMatch(row -> removedId.equals(row.sourceId())));
        assertTrue(snapshot.files().stream().noneMatch(file -> removedId.equals(file.fileId())));
        Path orphan = ArtifactStorageRefs.jobRoot(service.currentProjectId(), removedId, 0, "");
        note("removed " + removedName + " diskArtifactExists=" + Files.isDirectory(orphan));
    }

    private void reopen(String stage) {
        String projectId = service.currentProjectId();
        long viewportStart = service.graphInvestigation().viewportStart();
        long viewportEnd = service.graphInvestigation().viewportEnd();
        String layout = service.graphInvestigation().layoutMode();
        var saved = service.saveProject();
        assertTrue(saved.saved(), saved.message() + " " + saved.warnings());
        service.closeProject();
        service.shutdown();
        service = null;
        openService();
        var opened = service.openSavedProject(projectId);
        assertTrue(opened.opened(), opened.warnings().toString());
        note(stage + " reopen warnings=" + opened.warnings());
        assertEquals(layout, service.graphInvestigation().layoutMode(), stage);
        assertEquals(viewportStart, service.graphInvestigation().viewportStart(), stage);
        assertEquals(viewportEnd, service.graphInvestigation().viewportEnd(), stage);
    }

    private void openService() {
        if (home == null) {
            home = temp.resolve("home");
            db = temp.resolve("meta.db");
        }
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
    }

    private ProjectMergedSignalStore merged() {
        assertTrue(service.signalsModel().catalogStore() instanceof ProjectMergedSignalStore,
                "catalog is not a merged project store");
        return (ProjectMergedSignalStore) service.signalsModel().catalogStore();
    }

    private SignalCatalogEntry logical(String signal) {
        return service.signalsModel().catalog().entries().stream()
                .filter(entry -> signal.equals(entry.signalName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing logical signal " + signal));
    }

    private SignalCatalogKey physicalKey(String signal, String sourceId) {
        return merged().physicalKeyFor(logical(signal).key(), sourceId)
                .orElseThrow(() -> new AssertionError(signal + " missing from " + sourceId));
    }

    private WorkspaceFile fileNamed(String name) {
        return service.workspace().measurementFiles().stream()
                .filter(file -> name.equals(file.fileName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name));
    }

    private Set<String> names() {
        Set<String> out = new LinkedHashSet<>();
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            out.add(file.fileName());
        }
        return out;
    }

    private Set<String> ids() {
        Set<String> out = new LinkedHashSet<>();
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            out.add(file.id());
        }
        return out;
    }

    private String nameOf(String id) {
        return service.workspace().findFile(id).map(WorkspaceFile::fileName).orElse(id);
    }

    private Map<String, Instant> artifactTimes() {
        Map<String, Instant> times = new LinkedHashMap<>();
        service.metadataStore().loadProject(service.currentProjectId()).ifPresent(snapshot -> {
            for (ProcessingArtifactMetadata artifact : snapshot.processingArtifacts()) {
                if (ProcessingArtifactMetadata.STATUS_VALID.equals(artifact.status())) {
                    times.put(artifact.sourceId(), artifact.createdAt());
                }
            }
        });
        return times;
    }

    private static List<String> measurementIds(List<TimelineViewportPlanner.SourceQueryWindow> windows) {
        List<String> ids = new ArrayList<>();
        for (TimelineViewportPlanner.SourceQueryWindow window : windows) {
            ids.add(window.measurementId());
        }
        return ids;
    }

    private static Set<String> intersect(Set<String> left, Set<String> right) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : left) {
            if (right.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
    }

    private static void note(String line) {
        NOTES.append(line).append(System.lineSeparator());
    }
}

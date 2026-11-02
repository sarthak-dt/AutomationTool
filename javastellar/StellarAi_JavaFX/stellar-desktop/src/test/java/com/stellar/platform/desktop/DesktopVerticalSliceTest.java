package com.stellar.platform.desktop;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.desktop.support.DesktopFixtures;
import com.stellar.platform.desktop.validation.InputValidationResult;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.processing.ExecutionMode;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingRequest;
import com.stellar.platform.processing.ProcessingStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Presentation/service boundary + real pipeline integration for the desktop vertical slice.
 */
class DesktopVerticalSliceTest {

    @TempDir
    Path tempDir;

    private ParserRegistry registry;
    private Path dbc;
    private Path asc;
    private Path mf4Finalized;
    private Path mf4Unfinalized;

    private String previousHome;
    private final List<DesktopAppService> services = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        registry = ParserRegistry.load();
        dbc = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", tempDir.resolve("desktop_engine.dbc"));
        asc = DesktopFixtures.copyResource("asc/desktop_engine.asc", tempDir.resolve("desktop_engine.asc"));
        mf4Finalized = DesktopFixtures.writeFinalizedMf4(tempDir.resolve("finalized.mf4"));
        mf4Unfinalized = DesktopFixtures.writeUnfinalizedMf4(tempDir.resolve("unfinalized.mf4"));
    }

    @AfterEach
    void restoreHome() {
        for (DesktopAppService service : services) {
            service.shutdown();
        }
        services.clear();
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
    }

    @Test
    void newProjectCreation() {
        DesktopAppService service = newApp();
        service.newProject("Bench Run A");
        assertEquals("Bench Run A", service.workspace().projectName());
        assertTrue(service.workspace().measurementFiles().isEmpty());
        assertEquals(ProjectProcessingStatus.IDLE, service.workspace().processingStatus());
    }

    @Test
    void validAscAndDbcSelection() {
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(asc);
        WorkspaceFile d = service.addPath(dbc);
        service.selectMeasurement(m.id());
        service.selectDefinition(d.id());
        InputValidationResult result = service.validateCurrentSelection();
        assertTrue(result.valid(), result.errorMessages().toString());
        assertEquals(FileValidationStatus.READY, m.status());
        assertEquals(FileValidationStatus.READY, d.status());
    }

    @Test
    void validMf4AndDbcSelection() {
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(mf4Finalized);
        WorkspaceFile d = service.addPath(dbc);
        service.selectMeasurement(m.id());
        service.selectDefinition(d.id());
        InputValidationResult result = service.validateCurrentSelection();
        assertTrue(result.valid(), result.errorMessages().toString());
        assertEquals(FileKind.MEASUREMENT_MF4, m.kind());
    }

    @Test
    void unsupportedFileRejection() throws Exception {
        Path unknown = tempDir.resolve("future.xyz");
        Files.writeString(unknown, "not-implemented");
        DesktopAppService service = newApp();
        WorkspaceFile file = service.addPath(unknown);
        assertEquals(FileKind.UNKNOWN, file.kind());
        assertEquals(FileValidationStatus.UNSUPPORTED, file.status());
        assertTrue(file.validationMessage().toLowerCase().contains("could not determine"),
                file.validationMessage());
        assertTrue(service.workspace().measurementFiles().isEmpty());
    }

    @Test
    void blfIsAcceptedAsImplementedMeasurement() throws Exception {
        Path blf = com.stellar.platform.desktop.support.TestMeasurementFixtures.validMinimalBlf(
                tempDir.resolve("run.blf"));
        DesktopAppService service = newApp();
        WorkspaceFile file = service.addPath(blf);
        assertEquals(FileKind.MEASUREMENT_BLF, file.kind());
        assertEquals(FileValidationStatus.READY, file.status());
        assertEquals(1, service.workspace().measurementFiles().size());
    }

    @Test
    void truncatedLoggPrefixIsNotABlfMeasurement() throws Exception {
        Path blf = com.stellar.platform.desktop.support.TestMeasurementFixtures.truncatedLoggPrefix(
                tempDir.resolve("trunc.blf"));
        DesktopAppService service = newApp();
        WorkspaceFile file = service.addPath(blf);
        assertEquals(FileKind.UNKNOWN, file.kind());
        assertEquals(FileValidationStatus.UNSUPPORTED, file.status());
        assertTrue(service.workspace().measurementFiles().isEmpty());
    }

    @Test
    void missingDbcValidation() {
        DesktopAppService service = newApp();
        WorkspaceFile m = service.addPath(asc);
        service.selectMeasurement(m.id());
        InputValidationResult result = service.validateCurrentSelection();
        assertFalse(result.valid());
        assertTrue(result.hasCode(com.stellar.platform.desktop.validation.ValidationIssueCode.MISSING_DBC));
        assertTrue(result.errorMessages().stream().anyMatch(s -> s.toLowerCase().contains("dbc")));
        assertFalse(result.hasCode(com.stellar.platform.desktop.validation.ValidationIssueCode.NO_MEASUREMENT_FILE_SELECTED));
    }

    @Test
    void localProcessingGatewayUsesProcessingCore() {
        LocalProcessingGateway gateway = new LocalProcessingGateway(new MeasurementDecodePipeline(registry));
        WorkspaceFile measurement = new WorkspaceFile(asc, FileKind.MEASUREMENT_ASC, 1);
        WorkspaceFile definition = new WorkspaceFile(dbc, FileKind.DEFINITION_DBC, 1);
        LocalProcessingGateway.GatewayResult result = gateway.process(
                measurement, definition, 8, 100, null, null);
        assertEquals(ExecutionMode.LOCAL, ProcessingRequest.local(asc, dbc).executionMode());
        assertTrue(result.summary().statistics().sourceFramesRead() > 0);
        assertTrue(result.summary().statistics().signalsEmitted() > 0);
        assertNotNull(result.processingResult());
        assertTrue(result.catalog().size() >= 1);
    }

    @Test
    void ascDbcProcessingUpdatesResult() throws Exception {
        DesktopAppService service = newApp();
        runToCompletion(service, asc, dbc);
        assertEquals(ProjectProcessingStatus.COMPLETED, service.workspace().processingStatus());
        assertTrue(service.workspace().latestSummary().isPresent());
        assertEquals(1, service.workspace().latestSummary().orElseThrow().statistics().sourceFramesRead());
        assertEquals(3, service.workspace().latestSummary().orElseThrow().statistics().signalsEmitted());
        CanonicalSignal speed = service.signalsModel().buffer().snapshot().stream()
                .filter(s -> "EngineSpeed".equals(s.signalName()))
                .findFirst()
                .orElseThrow();
        assertEquals(2500.0, speed.physicalValue(), 1e-9);
        assertEquals("rpm", speed.unit());
        SignalCatalog catalog = service.signalsModel().catalog();
        assertTrue(catalog.size() >= 1);
        assertTrue(catalog.search("EngineSpeed").stream().anyMatch(e -> e.sampleCount() > 0));
    }

    @Test
    void mf4DbcProcessingUpdatesResult() throws Exception {
        DesktopAppService service = newApp();
        runToCompletion(service, mf4Finalized, dbc);
        assertEquals(ProjectProcessingStatus.COMPLETED, service.workspace().processingStatus());
        assertEquals(1, service.workspace().latestSummary().orElseThrow().statistics().validFrames());
        assertEquals(3, service.signalsModel().buffer().size());
    }

    @Test
    void recoveryStatisticsPropagated() throws Exception {
        DesktopAppService service = newApp();
        runToCompletion(service, mf4Unfinalized, dbc);
        var summary = service.workspace().latestSummary().orElseThrow();
        assertTrue(summary.statistics().recoveredFrames() > 0);
        assertTrue(summary.recoveryUsed());
        assertEquals("RECOVERED", summary.recoveryState());
        assertFalse(summary.mf4FileStatus().isBlank());
        assertTrue(service.processingModel().recoveryUsed());
        assertTrue(service.processingModel().statistics().recoveredFrames() > 0);
    }

    @Test
    void cancellationPropagation() throws Exception {
        Path largeAsc = tempDir.resolve("large.asc");
        StringBuilder sb = new StringBuilder();
        sb.append("date Thu Jan 01 12:00:00.000 am 2026\n");
        sb.append("base hex timestamps absolute\n");
        sb.append("Begin Triggerblock Thu Jan 01 12:00:00.000 am 2026\n");
        for (int i = 0; i < 20_000; i++) {
            sb.append("  ").append(i).append(".000000 1 100 Rx d 8 10 27 5A 01 00 00 00 00\n");
        }
        sb.append("End TriggerBlock\n");
        Files.writeString(largeAsc, sb.toString());

        LocalProcessingGateway gateway = new LocalProcessingGateway(new MeasurementDecodePipeline(registry));
        WorkspaceFile measurement = new WorkspaceFile(largeAsc, FileKind.MEASUREMENT_ASC, Files.size(largeAsc));
        WorkspaceFile definition = new WorkspaceFile(dbc, FileKind.DEFINITION_DBC, Files.size(dbc));
        CountDownLatch started = new CountDownLatch(1);

        LocalProcessingGateway.GatewayResult result = gateway.process(
                measurement,
                definition,
                1,
                100,
                new com.stellar.platform.processing.ProcessingProgressListener() {
                    @Override
                    public void onStage(ProcessingStage stage) {
                        if (stage == ProcessingStage.DBC_DECODING || stage == ProcessingStage.READING) {
                            started.countDown();
                            gateway.cancel();
                        }
                    }
                },
                batch -> {
                    started.countDown();
                    gateway.cancel();
                }
        );

        assertEquals(ProjectProcessingStatus.CANCELLED, result.summary().completionStatus());
        assertTrue(result.summary().userMessage().toLowerCase().contains("cancel"));
        assertTrue(started.getCount() == 0 || result.processingResult() == null
                || result.processingResult().cancelled());
    }

    @Test
    void processingErrorMapsToUiState() throws Exception {
        Path badDbc = tempDir.resolve("bad.dbc");
        Files.writeString(badDbc, "this is not a valid dbc {{{");
        DesktopAppService service = newApp();
        runToCompletion(service, asc, badDbc);
        assertEquals(ProjectProcessingStatus.FAILED, service.workspace().processingStatus());
        assertEquals(ProcessingStage.FAILED, service.processingModel().stage());
        assertFalse(service.processingModel().userMessage().isBlank());
        assertFalse(service.workspace().latestSummary().orElseThrow().technicalDetail().isBlank());
    }

    @Test
    void signalPreviewIsBounded() {
        SignalPreviewBuffer buffer = new SignalPreviewBuffer(3);
        List<CanonicalSignal> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            batch.add(signal("S" + i, "M", i));
        }
        buffer.accept(batch);
        assertEquals(3, buffer.size());
        assertEquals(5, buffer.totalAccepted());
        assertTrue(buffer.truncated());
        assertEquals("S2", buffer.snapshot().get(0).signalName());
        assertEquals("S4", buffer.snapshot().get(2).signalName());
    }

    @Test
    void searchFilterWorks() {
        SignalPreviewBuffer buffer = new SignalPreviewBuffer(50);
        buffer.accept(List.of(
                signal("EngineSpeed", "EngineData", 0x100),
                signal("WheelSpeedFL", "ChassisStatus", 0x100),
                signal("Switch", "SwitchStates", 0x500)
        ));
        List<CanonicalSignal> bySignal = buffer.filter("engine", "", "");
        assertEquals(1, bySignal.size());
        assertEquals("EngineSpeed", bySignal.get(0).signalName());

        List<CanonicalSignal> byMessage = buffer.filter("", "chassis", "");
        assertEquals(1, byMessage.size());

        List<CanonicalSignal> byCan = buffer.filter("", "", "0x500");
        assertEquals(1, byCan.size());
        assertEquals("Switch", byCan.get(0).signalName());
    }

    @Test
    void gatewayCancelApiIsWired() {
        LocalProcessingGateway gateway = new LocalProcessingGateway(new MeasurementDecodePipeline(registry));
        ProcessingRequest request = ProcessingRequest.local(asc, dbc, 8, new SimpleCancellationToken());
        assertEquals(ExecutionMode.LOCAL, request.executionMode());
        gateway.cancel(); // no active job — must be safe
        WorkspaceFile measurement = new WorkspaceFile(asc, FileKind.MEASUREMENT_ASC, 1);
        WorkspaceFile definition = new WorkspaceFile(dbc, FileKind.DEFINITION_DBC, 1);
        LocalProcessingGateway.GatewayResult result = gateway.process(
                measurement, definition, 8, 10, null, null);
        assertNotNull(result.summary());
        assertEquals(ProjectProcessingStatus.COMPLETED, result.summary().completionStatus());
    }

    private DesktopAppService newApp() {
        DesktopAppService service = new DesktopAppService(new DesktopSession(registry));
        services.add(service);
        return service;
    }

    private static void runToCompletion(DesktopAppService service, Path measurement, Path dbcPath)
            throws InterruptedException {
        WorkspaceFile m = service.addPath(measurement);
        WorkspaceFile d = service.addPath(dbcPath);
        service.selectMeasurement(m.id());
        service.selectDefinition(d.id());

        // Wait for source inventory, then select all discovered channels (required for LOCAL processing).
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            var inv = service.sourceInventoryModel().focusedInventory().orElse(null);
            if (inv != null && inv.channelDiscoveryComplete() && !inv.channels().isEmpty()) {
                service.syncChannelSelection();
                service.channelSelectionModel().selectAll(inv);
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(service.channelSelectionModel().hasSelection(), "expected channel selection before processing");

        CountDownLatch done = new CountDownLatch(1);
        service.startLocalProcessing(Runnable::run, () -> {
            ProjectProcessingStatus status = service.workspace().processingStatus();
            if (status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                    || status == ProjectProcessingStatus.NO_CAN_DATA
                    || status == ProjectProcessingStatus.RECOVERY_FAILED
                    || status == ProjectProcessingStatus.UNSUPPORTED_STRUCTURE
                    || status == ProjectProcessingStatus.FAILED
                    || status == ProjectProcessingStatus.CANCELLED) {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "processing did not complete");
    }

    private static CanonicalSignal signal(String name, String message, long canId) {
        return new CanonicalSignal(
                name,
                message,
                1L,
                canId,
                0,
                1.0,
                1L,
                "u",
                null,
                new com.stellar.platform.domain.SourceReference(1, 0)
        );
    }
}

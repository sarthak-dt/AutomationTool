package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.analysis.investigation.EngineerEventInclusion;
import com.stellar.platform.analysis.investigation.EngineerEventValidation;
import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.EngineerEventDocuments;
import com.stellar.platform.desktop.presentation.GnssEventCorrelation;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.SignalReportRenderer;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the Report tab event editor, include/remove buttons, and HTML/PDF export
 * on the Tata Sierra thermocouple recording.
 */
class EngineerEventLiveUiTest {

    private static final Path ROOT = Path.of("C:/Users/DT - User/Downloads/Mahima_Time_Issue");
    private static final Path MF4 = ROOT.resolve("tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = ROOT.resolve("TEMP DBC 1 (1).dbc");
    private static final String CH01 = "CH01_FRONT_BUMPER_GRILL_AMBIENT";

    @TempDir Path temp;
    private DesktopAppService service;
    private InvestigationController controller;
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.setLevel(java.util.logging.Level.INFO);
        java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler();
        handler.setLevel(java.util.logging.Level.INFO);
        root.addHandler(handler);
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose live event builder", () -> {
            if (controller != null) {
                controller.dispose();
                controller = null;
            }
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        if (service != null) {
            service.shutdown();
            service = null;
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    @Timeout(180)
    void reportTabCreatesEditsIncludesExportsAndReopensSierraEvents() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC),
                "Tata Sierra MF4 and TEMP DBC are not on this machine");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        LocalProcessingGateway.GatewayResult processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        try {
            SignalSeriesStore store = processed.catalogStore();
            SignalCatalogEntry ch01 = entry(store, CH01);
            SignalCatalogEntry second = secondThermocouple(store, ch01);
            SignalCatalogEntry latitude = entry(store, "LATITUDE");
            SignalCatalogEntry longitude = entry(store, "LONGITUDE");
            assertNotNull(ch01);
            assertNotNull(second);
            assertNotNull(latitude);
            assertNotNull(longitude);
            List<SignalSamplePoint> primary = readAll(store, ch01);
            List<SignalSamplePoint> other = readAll(store, second);
            double kept = primary.get(primary.size() / 2).physicalValue();
            int rise = -1;
            int settle = -1;
            double windowMin = Double.POSITIVE_INFINITY;
            double windowMax = Double.NEGATIVE_INFINITY;
            int below = 0;
            for (int i = 0; i < primary.size(); i++) {
                SignalSamplePoint sample = primary.get(i);
                if (!sample.usableMeasurement() || sample.physicalValue() < 40.0d) {
                    continue;
                }
                if (rise < 0) {
                    rise = i;
                }
                settle = i;
            }
            assertTrue(rise > 0 && settle > rise);
            long startNanos = primary.get(rise).timestampNanos();
            long endNanos = primary.get(settle).timestampNanos();
            for (int i = rise; i <= settle; i++) {
                SignalSamplePoint sample = primary.get(i);
                if (!sample.usableMeasurement()) {
                    continue;
                }
                windowMin = Math.min(windowMin, sample.physicalValue());
                windowMax = Math.max(windowMax, sample.physicalValue());
                if (sample.physicalValue() < 40.0d) {
                    below++;
                }
            }
            var route = MapRouteBuilder.validateAndBuild(
                    readAll(store, latitude), readAll(store, longitude), List.of());
            assertFalse(route.model().isEmpty(), route.diagnostics().summary());
            GnssEventCorrelation.Result located = GnssEventCorrelation.locate(
                    route.model(), startNanos, endNanos, "Thermal window");
            assertTrue(located.located(), located.statement());
            long gapStart = -1L;
            long gapEnd = -1L;
            for (int i = 0; i + 1 < route.model().size(); i++) {
                long left = route.model().timestampNanos(i);
                long right = route.model().timestampNanos(i + 1);
                if (right - left > 2L) {
                    gapStart = left + 1L;
                    gapEnd = right - 1L;
                    break;
                }
            }
            assertTrue(gapStart > 0L && gapEnd >= gapStart);
            GnssEventCorrelation.Result missing = GnssEventCorrelation.locate(
                    route.model(), gapStart, gapEnd, "GNSS gap");
            assertFalse(missing.located(), missing.statement());
            SignalSamplePoint pointSample = primary.get(Math.min(100, primary.size() - 1));
            long rangeStart = Math.min(primary.getFirst().timestampNanos(), route.model().timestampNanos(0));
            long rangeEnd = Math.max(primary.getLast().timestampNanos(),
                    route.model().timestampNanos(route.model().size() - 1));
            String measured = "Measured CH01 minimum " + decimal(windowMin) + " °C and maximum "
                    + decimal(windowMax) + " °C. " + below + " usable samples in the window are below 40 °C.";
            BufferedImage chart = SignalReportRenderer.paintAligned(List.of(
                    track(CH01, ch01.unit(), primary),
                    track(second.signalName(), second.unit(), other)), List.of(
                    new SignalReportRenderer.EventSpan(
                            "Thermal window", startNanos / 1_000_000_000d, endNanos / 1_000_000_000d),
                    new SignalReportRenderer.EventSpan(
                            "Sensor check", pointSample.timestampNanos() / 1_000_000_000d, Double.NaN)));
            byte[] snapshotBytes = toPng(chart);

            service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
            service.newProject("Sierra live events");
            service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
            EngineeringInvestigation created = service.createInvestigation(
                    "Sierra events", "Live event builder", "FULL");
            EngineeringInvestigation saved = service.saveInvestigation(created.toBuilder()
                    .reportConfig(InvestigationReportConfig.engineeringV2())
                    .signalKeyBlobs(List.of(
                            GraphInvestigationCodec.encodeKey(ch01.key()),
                            GraphInvestigationCodec.encodeKey(second.key())))
                    .range(rangeStart, rangeEnd)
                    .build());
            EngineeringInvestigation withSnapshot = service.attachGraphSnapshot(snapshotBytes, "Sierra CH01 and CH02");
            String snapshotId = withSnapshot.evidence().stream()
                    .filter(item -> item.kind() == EvidenceKind.GRAPH_SNAPSHOT)
                    .findFirst().orElseThrow().referenceId();
            Path snapshotFile = InvestigationStoragePaths.snapshot(
                    AnalysisStorageRoots.projectArtifactsRoot(saved.projectId()),
                    saved.investigationId(), snapshotId);
            assertArrayEquals(snapshotBytes, Files.readAllBytes(snapshotFile));

            JavaFxTestSupport.runOnFx("load the investigation screen", () -> {
                FXMLLoader loader = new FXMLLoader(InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
                Parent root = loader.load();
                controller = loader.getController();
                stage = new Stage();
                stage.setScene(new Scene(root, 1366, 768));
                stage.show();
                controller.setAppService(service, null);
                controller.setOpenGeneratedReportForTest(false);
                controller.refresh();
            });
            JavaFxTestSupport.drainFxPulse();
            JavaFxTestSupport.runOnFx("open the report tab", () -> {
                TabPane tabs = (TabPane) stage.getScene().getRoot().lookup(".tab-pane");
                tabs.getTabs().stream().filter(tab -> "Report".equals(tab.getText())).findFirst()
                        .ifPresent(tab -> tabs.getSelectionModel().select(tab));
                assertTrue(!button("Define Event").isDisabled());
                assertTrue(!button("Generate HTML Report").isDisabled());
                assertTrue(!button("Generate PDF Report").isDisabled());
            });

            String thermalId = saveNew("Thermal observation", "Thermal window", "Time window",
                    Long.toString(startNanos), Long.toString(endNanos),
                    CH01 + ", " + second.signalName(), true, true,
                    "Record the window. Do not treat it as a continuous threshold result.",
                    measured, "Engineer-selected window on the measurement clock.", "Confirmed");
            JavaFxTestSupport.runOnFx("edit the saved thermal event", () -> {
                var dialog = controller.eventEditorForTest(EngineerEventDocuments.decode(
                        service.findEngineeringBookmark(thermalId).orElseThrow()));
                assertEquals("Edit Event", dialog.getTitle());
                VBox form = form(dialog);
                ((TextArea) form.getChildren().get(18)).setText(
                        "Expected behaviour was updated. This is not a detected event and not a pass/fail result.");
                ((TextArea) form.getChildren().get(22)).setText(
                        "Edited after save. The window remains an engineer selection.");
                String savedId = controller.saveEventEditorForTest(dialog);
                assertEquals(thermalId, savedId);
            });
            String pointId = saveNew("Sensor anomaly", "Sensor check", "Point",
                    Long.toString(pointSample.timestampNanos()), "",
                    CH01, false, false,
                    "", "CH01 at this sample is " + decimal(pointSample.physicalValue()) + " °C.",
                    "Point event at one original sample. The category is not a sensor-defect finding.", "Draft");
            String gapId = saveNew("Custom event", "GNSS gap", "Time window",
                    Long.toString(gapStart), Long.toString(gapEnd),
                    CH01, false, true,
                    "", "No thermocouple value was interpolated into this gap.",
                    "Short window between two GNSS samples. The missing sample stays missing evidence.", "Draft");

            include(thermalId, "Thermal window");
            include(pointId, "Sensor check");
            include(gapId, "GNSS gap");
            include(thermalId, "Thermal window");
            assertEquals(1, service.engineeringInvestigations()
                    .find(service.metadataStore(), saved.investigationId()).orElseThrow()
                    .evidence().stream().filter(item -> thermalId.equals(item.referenceId())).count());

            Path reports = temp.resolve("reports");
            Files.createDirectories(reports);
            export("m11-live-html", reports, false);
            export("m11-live-pdf", reports, true);
            Path html = Files.list(reports).filter(path -> path.getFileName().toString().endsWith(".html"))
                    .findFirst().orElseThrow();
            Path pdf = Files.list(reports).filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .findFirst().orElseThrow();
            Files.copy(html, repoTarget().resolve("engineering-report-v2-m11-live.html"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(pdf, repoTarget().resolve("engineering-report-v2-m11-live.pdf"), StandardCopyOption.REPLACE_EXISTING);
            String text = Files.readString(html);
            assertTrue(text.contains("href=\"#sec-events\""));
            assertTrue(text.contains("Engineering Events"));
            assertTrue(text.contains("Edited after save. The window remains an engineer selection."));
            assertTrue(text.contains("Expected behaviour was updated."));
            assertTrue(text.contains(EngineeringFormats.interval(startNanos, endNanos)));
            assertTrue(text.contains(EngineeringFormats.seconds(pointSample.timestampNanos())));
            assertTrue(text.contains(EngineeringFormats.interval(gapStart, gapEnd)));
            assertTrue(text.contains("Confirmed"));
            assertTrue(text.contains("Draft"));
            assertTrue(text.contains("Sensor anomaly"));
            assertTrue(text.contains("GPS evidence was requested"));
            assertTrue(text.contains("Snapshot, GPS requested"));
            assertTrue(text.contains("GPS requested"));
            assertFalse(text.contains("No GNSS sample"));
            assertTrue(text.contains("No coordinate is interpolated."));
            assertTrue(text.contains("GPS evidence was not attached"));
            assertTrue(text.contains(MF4.getFileName().toString()));
            assertTrue(text.contains("Sierra CH01 and CH02"));
            assertFalse(text.contains(decimal(located.latitude())));
            assertFalse(text.contains("sensor defect"));
            assertFalse(text.contains("logger fault"));
            assertFalse(text.contains("not regenerated"));
            assertFalse(text.contains(" s s"));
            assertFalse(text.contains("PASS"));
            assertFalse(text.contains("http://"));
            int images = text.split("<img", -1).length - 1;
            assertEquals(1, images, text.substring(Math.max(0, text.indexOf("Graph")), Math.min(text.length(), text.indexOf("Graph") + 400)));
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                String packed = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
                assertTrue(packed.contains("Thermalwindow"));
                assertTrue(packed.contains("Sensorcheck"));
                assertTrue(packed.contains("GNSSgap"));
                assertTrue(packed.contains("Confirmed"));
                assertTrue(packed.contains("Draft"));
                assertFalse(packed.contains("PASS"));
                Path pages = repoTarget().resolve("engineering-report-v2-m11-live-pages");
                Files.createDirectories(pages);
                PDFRenderer renderer = new PDFRenderer(document);
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    ImageIO.write(renderer.renderImageWithDPI(i, 110), "png",
                            pages.resolve("page-" + (i + 1) + ".png").toFile());
                }
                assertTrue(document.getNumberOfPages() >= 2);
            }
            JavaFxTestSupport.runOnFx("remove the thermal event from the report", () -> {
                selectEvent("Thermal window");
                button("Remove from Report").fire();
            });
            JavaFxTestSupport.drainFxPulse();
            EngineerEventDefinition removed = EngineerEventDocuments.decode(
                    service.findEngineeringBookmark(thermalId).orElseThrow());
            assertEquals(startNanos, removed.startNanos());
            assertEquals(endNanos, removed.endNanos());
            assertFalse(EngineerEventInclusion.linked(
                    service.engineeringInvestigations().find(service.metadataStore(), saved.investigationId())
                            .orElseThrow().evidence(), thermalId));
            include(thermalId, "Thermal window");

            String projectId = saved.projectId();
            service.closeProject();
            assertTrue(service.openSavedProject(projectId).opened());
            JavaFxTestSupport.runOnFx("refresh the reopened project", () -> controller.refresh());
            EngineerEventDefinition reopened = EngineerEventDocuments.decode(
                    service.findEngineeringBookmark(thermalId).orElseThrow());
            assertEquals(startNanos, reopened.startNanos());
            assertEquals(endNanos, reopened.endNanos());
            assertEquals(List.of(CH01, second.signalName()), reopened.signals());
            assertEquals(List.of(snapshotId), reopened.snapshotIds());
            assertEquals("Confirmed", reopened.confirmation());
            assertTrue(reopened.gpsAttached());
            assertTrue(reopened.gnssStatement().contains("GPS evidence was requested"));
            assertFalse(reopened.gnssStatement().contains(decimal(located.latitude())));
            assertEquals(MF4.getFileName().toString(), reopened.sourceFile());
            EngineerEventDefinition reopenedPoint = EngineerEventDocuments.decode(
                    service.findEngineeringBookmark(pointId).orElseThrow());
            assertEquals(pointSample.timestampNanos(), reopenedPoint.startNanos());
            assertEquals(null, reopenedPoint.endNanos());
            assertEquals("Draft", reopenedPoint.confirmation());
            assertFalse(reopenedPoint.gpsAttached());
            EngineerEventDefinition reopenedGap = EngineerEventDocuments.decode(
                    service.findEngineeringBookmark(gapId).orElseThrow());
            assertEquals(gapStart, reopenedGap.startNanos());
            assertEquals(gapEnd, reopenedGap.endNanos());
            assertTrue(reopenedGap.window());
            assertTrue(EngineerEventInclusion.linked(
                    service.engineeringInvestigations().find(service.metadataStore(), saved.investigationId())
                            .orElseThrow().evidence(), thermalId));
            assertArrayEquals(snapshotBytes, Files.readAllBytes(snapshotFile));
            assertEquals(kept, primary.get(primary.size() / 2).physicalValue(), 0.0);
            assertTrue(EngineerEventValidation.warnings(reopened, Map.of(), false).stream()
                    .anyMatch(warning -> warning.contains("Chronological order was not assumed.")));
            assertFalse(reopened.limitations().contains("Chronological order was not assumed."));
            System.out.println("LIVE thermal " + EngineeringFormats.interval(startNanos, endNanos)
                    + " gnssSample " + EngineeringFormats.seconds(located.sampleTimestampNanos())
                    + " lat " + decimal(located.latitude())
                    + " lon " + decimal(located.longitude())
                    + " gap " + EngineeringFormats.interval(gapStart, gapEnd)
                    + " point " + EngineeringFormats.seconds(pointSample.timestampNanos())
                    + " ch01 " + decimal(pointSample.physicalValue())
                    + " uiGpsRequested");
        } finally {
            if (processed.catalogStore() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private String saveNew(
            String category, String name, String shape, String start, String end, String signals,
            boolean snapshot, boolean gps, String expected, String measured, String comment, String confirmation
    ) throws Exception {
        String[] id = new String[1];
        JavaFxTestSupport.runOnFx("save " + name, () -> {
            assertTrue(!button("Define Event").isDisabled());
            var dialog = controller.eventEditorForTest(null);
            assertEquals("Define Event", dialog.getTitle());
            fill(dialog, category, name, shape, start, end, signals, snapshot, gps, expected, measured, comment, confirmation);
            id[0] = controller.saveEventEditorForTest(dialog);
        });
        assertNotNull(id[0]);
        assertFalse(id[0].isBlank(), id[0]);
        return id[0];
    }

    private void include(String eventId, String title) throws Exception {
        JavaFxTestSupport.runOnFx("include " + title, () -> {
            selectEvent(title);
            button("Include in Report").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        assertTrue(EngineerEventInclusion.linked(
                service.engineeringInvestigations().find(
                        service.metadataStore(), service.engineeringInvestigations().activeInvestigationId())
                        .orElseThrow().evidence(), eventId));
    }

    private void export(String name, Path directory, boolean pdf) throws Exception {
        String extension = pdf ? ".pdf" : ".html";
        JavaFxTestSupport.runOnFx("export " + name, () -> {
            controller.setReportDestinationForTest(name, directory);
            button(pdf ? "Generate PDF Report" : "Generate HTML Report").fire();
            assertEquals("Generating report…", controller.reportStatusForTest());
        });
        long deadline = System.nanoTime() + 90_000_000_000L;
        Path written = null;
        String status = "";
        while (System.nanoTime() < deadline) {
            String[] holder = new String[1];
            JavaFxTestSupport.runOnFx("read report status", () -> holder[0] = controller.reportStatusForTest());
            status = holder[0];
            try (var listing = Files.list(directory)) {
                for (Path path : listing.toList()) {
                    if (path.getFileName().toString().toLowerCase().endsWith(extension) && Files.size(path) > 0) {
                        written = path;
                    }
                }
            }
            if (written != null && ("Report generated.".equals(status) || status.isBlank()
                    || "Report written".equals(status))) {
                break;
            }
            if (status.startsWith("Report could not") || "Report generation cancelled.".equals(status)) {
                break;
            }
            Thread.sleep(200);
        }
        assertNotNull(written, "No " + extension + " was written. Status: " + status);
        assertFalse(status.startsWith("Report could not"), status);
        assertFalse("Report generation cancelled.".equals(status), status);
    }

    private void selectEvent(String title) {
        ListView<?> found = null;
        for (Node node : stage.getScene().getRoot().lookupAll(".list-view")) {
            if (node instanceof ListView<?> list) {
                for (Object item : list.getItems()) {
                    if (item instanceof String text && text.startsWith(title + " ·")) {
                        found = list;
                    }
                }
            }
        }
        assertNotNull(found, title);
        for (int i = 0; i < found.getItems().size(); i++) {
            Object item = found.getItems().get(i);
            if (item instanceof String text && text.startsWith(title + " ·")) {
                found.getSelectionModel().select(i);
            }
        }
    }

    private void fill(
            Dialog<?> dialog, String category, String name, String shape, String start, String end, String signals,
            boolean snapshot, boolean gps, String expected, String measured, String comment, String confirmation
    ) {
        VBox form = form(dialog);
        combo(form, 1).getSelectionModel().select(category);
        ((TextField) form.getChildren().get(3)).setText(name);
        boolean sourceSelected = false;
        for (Object item : combo(form, 5).getItems()) {
            if (item instanceof String text && text.contains("tata_sierra")) {
                combo(form, 5).getSelectionModel().select(text);
                sourceSelected = true;
            }
        }
        assertTrue(sourceSelected);
        combo(form, 7).getSelectionModel().select(shape);
        ((TextField) form.getChildren().get(9)).setText(start);
        ((TextField) form.getChildren().get(11)).setText(end);
        ((TextField) form.getChildren().get(13)).setText(signals);
        if (snapshot) {
            boolean selected = false;
            for (Object item : combo(form, 15).getItems()) {
                if (item instanceof String text && text.contains("\t")) {
                    combo(form, 15).getSelectionModel().select(text);
                    selected = true;
                }
            }
            assertTrue(selected);
        }
        ((CheckBox) form.getChildren().get(16)).setSelected(gps);
        ((TextArea) form.getChildren().get(18)).setText(expected);
        ((TextArea) form.getChildren().get(20)).setText(measured);
        ((TextArea) form.getChildren().get(22)).setText(comment);
        combo(form, 24).getSelectionModel().select(confirmation);
    }

    private static VBox form(Dialog<?> dialog) {
        return (VBox) ((ScrollPane) dialog.getDialogPane().getContent()).getContent();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> combo(VBox form, int index) {
        return (ComboBox<String>) form.getChildren().get(index);
    }

    private Button button(String text) {
        for (Node node : stage.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError(text);
    }

    private static Path repoTarget() {
        Path sampleDir = Path.of("").toAbsolutePath();
        Path repo = "stellar-desktop".equals(sampleDir.getFileName().toString()) ? sampleDir.getParent() : sampleDir;
        Path out = repo.resolve("target");
        try {
            Files.createDirectories(out);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return out;
    }

    private static SignalReportRenderer.Track track(String name, String unit, List<SignalSamplePoint> samples) {
        return new SignalReportRenderer.Track(name, unit,
                samples.stream().mapToDouble(sample -> sample.timestampNanos() / 1_000_000_000d).toArray(),
                samples.stream().mapToDouble(SignalSamplePoint::physicalValue).toArray());
    }

    private static SignalCatalogEntry entry(SignalSeriesStore store, String name) {
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (name.equals(candidate.signalName())) {
                return candidate;
            }
        }
        return null;
    }

    private static SignalCatalogEntry secondThermocouple(SignalSeriesStore store, SignalCatalogEntry ch01) {
        SignalCatalogEntry found = null;
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (candidate == ch01 || CH01.equals(candidate.signalName())) {
                continue;
            }
            if (candidate.unit() == null || !candidate.unit().contains("C") || candidate.sampleCount() < 1000) {
                continue;
            }
            if (candidate.minPhysical() == null || candidate.maxPhysical() == null
                    || candidate.minPhysical().equals(candidate.maxPhysical())) {
                continue;
            }
            if (found == null || candidate.signalName().compareTo(found.signalName()) < 0) {
                found = candidate;
            }
        }
        return found;
    }

    private static List<SignalSamplePoint> readAll(SignalSeriesStore store, SignalCatalogEntry entry) {
        int retained = store.retainedSampleCount(entry.key());
        List<SignalSamplePoint> all = new ArrayList<>(Math.max(16, retained));
        int offset = 0;
        while (offset < retained) {
            List<SignalSamplePoint> page = store.querySamples(entry.key(), offset, 5_000);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            offset += page.size();
        }
        return all;
    }

    private static String decimal(double value) {
        return new BigDecimal(Double.toString(value)).setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static byte[] toPng(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}

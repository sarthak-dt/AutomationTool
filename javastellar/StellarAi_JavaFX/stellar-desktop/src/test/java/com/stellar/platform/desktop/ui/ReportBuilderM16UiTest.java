package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphEventGps;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Report Builder on the investigation screen. Selection reuses saved evidence.
 */
class ReportBuilderM16UiTest {

    private static final Path MF4 = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/TEMP DBC 1 (1).dbc");
    private static final String CH01 = "CH01_FRONT_BUMPER_GRILL_AMBIENT";

    @TempDir Path temp;
    private DesktopAppService service;
    private InvestigationController controller;
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose report builder", () -> {
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
    @Timeout(120)
    void builderSelectsSavedEvidenceAndKeepsHtmlAndPdfTogether() throws Exception {
        assertEquals(12, EngineeringReportSection.values().length);
        assertEquals(EngineeringReportSection.allMask(), 4095);
        InvestigationReportConfig classic = InvestigationReportConfig.decode("1111111111");
        assertEquals(com.stellar.platform.analysis.investigation.EngineeringReportTemplate.CLASSIC, classic.template());

        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Report builder");
        String ch01 = GraphInvestigationCodec.encodeKey(new SignalCatalogKey(
                CH01, SignalSourceType.DBC_CAN, "TEMP", 256, 2, "mf4", "dbc"));
        String ch02 = GraphInvestigationCodec.encodeKey(new SignalCatalogKey(
                "CH02_HOOD_ENGINE_SIDE", SignalSourceType.DBC_CAN, "TEMP", 256, 2, "mf4", "dbc"));
        EngineeringInvestigation created = service.createInvestigation("Evidence", "Selected signals", "CURRENT");
        EngineeringInvestigation saved = service.saveInvestigation(created.toBuilder()
                .reportConfig(InvestigationReportConfig.engineeringV2())
                .signalKeyBlobs(List.of(ch01, ch02, "not-a-real-key"))
                .build());
        service.graphInvestigation().setViewport(0L, 20_000_000_000L);
        String eventId = service.saveGraphEvent(null, "Brake point", "Braking", "Saved from the graph",
                5_000_000_000L, null, "meas-1", "route.mf4",
                List.of(CH01, "CH02_HOOD_ENGINE_SIDE"), List.of(ch01, ch02));
        assertFalse(eventId.isBlank());
        MapRouteModel route = route(new long[] {4_000_000_000L, 5_020_000_000L, 8_000_000_000L},
                new double[] {19.030000, 19.030868, 19.031200},
                new double[] {74.726000, 74.726919, 74.727200});
        var bookmark = service.findEngineeringBookmark(eventId).orElseThrow();
        GraphEventGps.Evidence matched = GraphEventGps.review(bookmark,
                new GraphEventGps.RouteView(route, "meas-1", "route.mf4", "", "LATITUDE", "LONGITUDE"));
        assertEquals(GraphEventGps.MATCHED, matched.status());
        assertEquals(5_020_000_000L, matched.sampleTimestampNanos());
        assertEquals("", service.saveGraphEventGps(eventId, matched));
        byte[] png = png();
        EngineeringInvestigation withSnapshot = service.attachGraphSnapshot(png, "Brake view");
        String snapshotId = withSnapshot.evidence().stream()
                .filter(item -> item.kind() == com.stellar.platform.analysis.investigation.EvidenceKind.GRAPH_SNAPSHOT)
                .findFirst().orElseThrow().referenceId();
        assertEquals("", service.linkGraphEventSnapshot(eventId, snapshotId));
        Path snapshotFile = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(saved.projectId()),
                saved.investigationId(), snapshotId);
        byte[] hash = sha256(Files.readAllBytes(snapshotFile));

        long opened = System.nanoTime();
        openScreen();
        long openMs = (System.nanoTime() - opened) / 1_000_000L;
        JavaFxTestSupport.runOnFx("open the report tab", () -> {
            TabPane tabs = (TabPane) stage.getScene().getRoot().lookup(".tab-pane");
            tabs.getTabs().stream().filter(tab -> "Report".equals(tab.getText())).findFirst()
                    .ifPresent(tab -> tabs.getSelectionModel().select(tab));
        });
        JavaFxTestSupport.drainFxPulse();
        JavaFxTestSupport.runOnFx("read the builder", () -> {
            assertTrue(pane("Report details"));
            assertTrue(pane("Selected signals"));
            assertTrue(pane("Graph events"));
            assertTrue(pane("Snapshots"));
            assertTrue(pane("GPS evidence"));
            assertTrue(pane("KPIs and findings"));
            assertTrue(pane("Section visibility"));
            assertTrue(pane("Engineer observations"));
            String row = eventRow("Brake point");
            assertTrue(row.contains("GPS sample matched"), row);
            assertTrue(row.contains("saved"), row);
            assertTrue(summary().contains("signal mapping stale"), summary());
            assertTrue(gpsLabel().contains("No GPS route is attached"), gpsLabel());
            assertTrue(gpsLabel().contains("1 matched"), gpsLabel());
            selectEvent("Brake point");
        });
        long selectedAt = System.nanoTime();
        JavaFxTestSupport.runOnFx("remove the stale signal and include the event", () -> {
            ListView<?> signals = signalList();
            int stale = -1;
            for (int i = 0; i < signals.getItems().size(); i++) {
                if (String.valueOf(signals.getItems().get(i)).contains("signal mapping stale")) {
                    stale = i;
                }
            }
            assertTrue(stale >= 0);
            signals.getSelectionModel().select(stale);
            buttonBeside("Add Signal", "Remove").fire();
            selectEvent("Brake point");
            button("Include in Report").fire();
            ((TextField) stage.getScene().getRoot().lookup("#observationTitle")).setText("Grill observation");
            ((TextArea) stage.getScene().getRoot().lookup("#observationNote")).setText(
                    "Engineer observation for the saved brake point. No new event was created.");
            button("Add Observation").fire();
            ((CheckBox) stage.getScene().getRoot().lookup("#v2Conclusions")).setSelected(false);
        });
        JavaFxTestSupport.drainFxPulse();
        long selectionMs = (System.nanoTime() - selectedAt) / 1_000_000L;
        shoot("m16-report-builder.png");
        long previewAt = System.nanoTime();
        JavaFxTestSupport.runOnFx("preview", () -> button("Preview HTML").fire());
        waitStatus("Preview opened.");
        long previewMs = (System.nanoTime() - previewAt) / 1_000_000L;

        Path htmlDir = temp.resolve("html");
        Path pdfDir = temp.resolve("pdf");
        Files.createDirectories(htmlDir);
        Files.createDirectories(pdfDir);
        long htmlAt = System.nanoTime();
        export("builder-matched", htmlDir, false);
        long htmlMs = (System.nanoTime() - htmlAt) / 1_000_000L;
        long pdfAt = System.nanoTime();
        export("builder-matched", pdfDir, true);
        long pdfMs = (System.nanoTime() - pdfAt) / 1_000_000L;
        String html = Files.readString(htmlFile(htmlDir));
        String pdf = pdfText(pdfFile(pdfDir));
        assertTrue(html.contains("Brake point"), html);
        assertTrue(html.contains("19.030868"));
        assertTrue(html.contains("74.726919"));
        assertTrue(html.contains(CH01));
        assertTrue(html.contains("CH02_HOOD_ENGINE_SIDE"));
        assertTrue(html.contains("Grill observation"));
        assertTrue(html.contains("not a picture of that event signal"));
        assertTrue(html.contains("assets/"));
        assertFalse(html.contains("http://"));
        assertEquals(1, html.split("<img", -1).length - 1);
        assertTrue(packed(pdf).contains("19.030868"));
        assertTrue(packed(pdf).contains("74.726919"));
        assertTrue(packed(pdf).contains("Grillobservation"));
        assertTrue(packed(pdf).contains("notapictureofthateventsignal"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(hash, sha256(Files.readAllBytes(snapshotFile)));

        Path excludedDir = temp.resolve("excluded");
        Files.createDirectories(excludedDir);
        JavaFxTestSupport.runOnFx("exclude the event", () -> {
            selectEvent("Brake point");
            button("Remove from Report").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        export("builder-excluded", excludedDir, false);
        String excluded = Files.readString(htmlFile(excludedDir));
        assertFalse(excluded.contains("Brake point"), excluded);
        assertFalse(excluded.contains("19.030868"));
        assertTrue(service.findEngineeringBookmark(eventId).isPresent());
        assertEquals(5_000_000_000L, service.findEngineeringBookmark(eventId).orElseThrow().timestampNanos());
        org.junit.jupiter.api.Assertions.assertArrayEquals(hash, sha256(Files.readAllBytes(snapshotFile)));

        JavaFxTestSupport.runOnFx("include the event again", () -> {
            selectEvent("Brake point");
            button("Include in Report").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        GraphEventGps.Evidence staleGps = GraphEventGps.revalidate(
                service.findEngineeringBookmark(eventId).orElseThrow(), GraphEventGps.RouteView.none(), true);
        assertEquals(GraphEventGps.STALE, staleGps.status());
        assertEquals("", service.saveGraphEventGps(eventId, staleGps));
        JavaFxTestSupport.runOnFx("refresh stale gps", () -> controller.refresh());
        JavaFxTestSupport.drainFxPulse();
        JavaFxTestSupport.runOnFx("read stale status", () -> {
            assertTrue(eventRow("Brake point").contains("GPS evidence stale or missing"));
            assertTrue(gpsLabel().contains("1 stale"), gpsLabel());
        });
        Path staleDir = temp.resolve("stale");
        Files.createDirectories(staleDir);
        export("builder-stale", staleDir, false);
        String staleHtml = Files.readString(htmlFile(staleDir));
        assertTrue(staleHtml.contains("not treated as verified"), staleHtml);
        assertFalse(staleHtml.contains("Latitude "));

        assertEquals("", service.clearGraphEventGps(eventId));
        JavaFxTestSupport.runOnFx("refresh cleared gps", () -> controller.refresh());
        JavaFxTestSupport.drainFxPulse();
        Path noGpsDir = temp.resolve("nogps");
        Files.createDirectories(noGpsDir);
        export("builder-nogps", noGpsDir, false);
        String noGps = Files.readString(htmlFile(noGpsDir));
        assertTrue(noGps.contains("No GPS route is attached"), noGps);
        assertFalse(noGps.contains("19.030868"));
        assertFalse(noGps.contains("Latitude "));

        String projectId = saved.projectId();
        String investigationId = saved.investigationId();
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        JavaFxTestSupport.runOnFx("reopen the builder", () -> controller.refresh());
        JavaFxTestSupport.drainFxPulse();
        EngineeringInvestigation reopened = service.engineeringInvestigations()
                .find(service.metadataStore(), investigationId).orElseThrow();
        assertEquals(List.of(ch01, ch02), reopened.signalKeyBlobs());
        assertFalse(reopened.reportConfig().shows(EngineeringReportSection.CONCLUSIONS));
        assertTrue(reopened.reportConfig().shows(EngineeringReportSection.COVER));
        assertTrue(reopened.reportConfig().encode().contains("V"));
        assertEquals(3, maskDigits(reopened.reportConfig().encode()));
        assertEquals(1, reopened.observations().size());
        assertEquals("Grill observation", reopened.observations().getFirst().title());
        assertEquals(5_000_000_000L, service.findEngineeringBookmark(eventId).orElseThrow().timestampNanos());
        assertEquals(GraphEventGps.NOT_REQUESTED,
                GraphEventGps.read(service.findEngineeringBookmark(eventId).orElseThrow()).status());
        org.junit.jupiter.api.Assertions.assertArrayEquals(hash, sha256(Files.readAllBytes(snapshotFile)));

        long manyAt = System.nanoTime();
        for (int i = 0; i < 80; i++) {
            assertFalse(service.saveGraphEvent(null, "Extra " + i, "Observation", "",
                    i * 1_000_000L, null, "meas-1", "route.mf4", List.of(CH01), List.of(ch01)).isBlank());
        }
        JavaFxTestSupport.runOnFx("refresh many events", () -> controller.refresh());
        JavaFxTestSupport.drainFxPulse();
        long manyMs = (System.nanoTime() - manyAt) / 1_000_000L;
        JavaFxTestSupport.runOnFx("count events", () -> assertTrue(eventRow("Extra 79").contains("GPS not requested")));
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.println("M16 openMs=" + openMs + " selectionMs=" + selectionMs
                + " previewMs=" + previewMs + " htmlMs=" + htmlMs + " pdfMs=" + pdfMs
                + " manyMs=" + manyMs + " heapBytes=" + heap);
        assertTrue(openMs < 5_000, "openMs=" + openMs);
        assertTrue(selectionMs < 5_000, "selectionMs=" + selectionMs);
        assertTrue(previewMs < 30_000, "previewMs=" + previewMs);
        assertTrue(htmlMs < 30_000, "htmlMs=" + htmlMs);
        assertTrue(pdfMs < 30_000, "pdfMs=" + pdfMs);
        assertTrue(manyMs < 8_000, "manyMs=" + manyMs);
        assertTrue(heap < 1_536L * 1024 * 1024, "heapBytes=" + heap);
    }

    @Test
    @Timeout(180)
    void sierraReportUsesTheSavedEventSnapshotAndGpsSample() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC),
                "Tata Sierra MF4 and TEMP DBC are not on this machine");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("sierra.db").toString());
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        LocalProcessingGateway.GatewayResult processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        SignalSeriesStore store = processed.catalogStore();
        SignalCatalogEntry ch01 = entry(store, CH01);
        SignalCatalogEntry ch02 = entryPrefix(store, "CH02");
        SignalCatalogEntry latitude = entry(store, "LATITUDE");
        SignalCatalogEntry longitude = entry(store, "LONGITUDE");
        assertNotNull(ch01);
        assertNotNull(ch02);
        assertNotNull(latitude);
        assertNotNull(longitude);
        var built = MapRouteBuilder.validateAndBuild(
                readAll(store, latitude), readAll(store, longitude), List.of());
        assertFalse(built.model().isEmpty(), built.diagnostics().summary());
        MapRouteModel model = built.model();
        int sampleIndex = model.size() / 2;
        long sampleNanos = model.timestampNanos(sampleIndex);
        double sampleLat = model.latitude(sampleIndex);
        double sampleLon = model.longitude(sampleIndex);
        List<SignalSamplePoint> thermal = readAll(store, ch01);
        SignalSamplePoint nearest = thermal.getFirst();
        long best = Long.MAX_VALUE;
        for (SignalSamplePoint sample : thermal) {
            long gap = Math.abs(sample.timestampNanos() - sampleNanos);
            if (gap < best) {
                best = gap;
                nearest = sample;
            }
        }
        double kept = nearest.physicalValue();
        long keptNanos = nearest.timestampNanos();

        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("sierra.db")));
        service.newProject("Sierra report");
        service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
        String ch01Key = GraphInvestigationCodec.encodeKey(ch01.key());
        String ch02Key = GraphInvestigationCodec.encodeKey(ch02.key());
        EngineeringInvestigation created = service.createInvestigation(
                "Sierra evidence", "CH01 and CH02 with the saved graph event", "FULL");
        EngineeringInvestigation saved = service.saveInvestigation(created.toBuilder()
                .reportConfig(InvestigationReportConfig.engineeringV2())
                .signalKeyBlobs(List.of(ch01Key, ch02Key))
                .build());
        service.graphInvestigation().setViewport(0L, Math.max(sampleNanos, 1L) + 20_000_000_000L);
        String eventId = service.saveGraphEvent(null, "Sierra point", "Thermal observation",
                "Existing graph event", sampleNanos, null, "sierra", MF4.getFileName().toString(),
                List.of(CH01, ch02.signalName()), List.of(ch01Key, ch02Key));
        var bookmark = service.findEngineeringBookmark(eventId).orElseThrow();
        GraphEventGps.Evidence matched = GraphEventGps.review(bookmark, new GraphEventGps.RouteView(
                model, "sierra", MF4.getFileName().toString(), "", "LATITUDE", "LONGITUDE"));
        assertEquals(GraphEventGps.MATCHED, matched.status(), matched.statement());
        assertEquals(sampleNanos, matched.sampleTimestampNanos());
        assertEquals("", service.saveGraphEventGps(eventId, matched));
        byte[] png = png();
        EngineeringInvestigation withSnapshot = service.attachGraphSnapshot(png, "Sierra view");
        String snapshotId = withSnapshot.evidence().stream()
                .filter(item -> item.kind() == com.stellar.platform.analysis.investigation.EvidenceKind.GRAPH_SNAPSHOT)
                .findFirst().orElseThrow().referenceId();
        assertEquals("", service.linkGraphEventSnapshot(eventId, snapshotId));
        Path snapshotFile = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(saved.projectId()),
                saved.investigationId(), snapshotId);
        byte[] hash = sha256(Files.readAllBytes(snapshotFile));

        openScreen();
        JavaFxTestSupport.runOnFx("open the sierra report tab", () -> {
            TabPane tabs = (TabPane) stage.getScene().getRoot().lookup(".tab-pane");
            tabs.getTabs().stream().filter(tab -> "Report".equals(tab.getText())).findFirst()
                    .ifPresent(tab -> tabs.getSelectionModel().select(tab));
            selectEvent("Sierra point");
            button("Include in Report").fire();
            ((TextField) stage.getScene().getRoot().lookup("#observationTitle")).setText("Sierra observation");
            ((TextArea) stage.getScene().getRoot().lookup("#observationNote")).setText(
                    "CH01 at " + keptNanos + " ns remains " + kept + ". The snapshot is not a picture of CH01.");
            button("Add Observation").fire();
            ((CheckBox) stage.getScene().getRoot().lookup("#v2Conclusions")).setSelected(false);
        });
        JavaFxTestSupport.drainFxPulse();
        shoot("m16-sierra-report-builder.png");
        JavaFxTestSupport.runOnFx("sierra builder status", () -> {
            String row = eventRow("Sierra point");
            assertTrue(row.contains("included"), row);
            assertTrue(row.contains("GPS sample matched"), row);
            assertTrue(signalText().contains(CH01), signalText());
            assertTrue(signalText().contains(ch02.signalName()), signalText());
        });
        Path htmlDir = temp.resolve("sierra-html");
        Path pdfDir = temp.resolve("sierra-pdf");
        Files.createDirectories(htmlDir);
        Files.createDirectories(pdfDir);
        long htmlAt = System.nanoTime();
        export("sierra-report", htmlDir, false);
        long htmlMs = (System.nanoTime() - htmlAt) / 1_000_000L;
        long pdfAt = System.nanoTime();
        export("sierra-report", pdfDir, true);
        long pdfMs = (System.nanoTime() - pdfAt) / 1_000_000L;
        Path htmlPath = htmlFile(htmlDir);
        Path pdfPath = pdfFile(pdfDir);
        String html = Files.readString(htmlPath);
        String pdf = packed(pdfText(pdfPath));
        String lat = String.format(Locale.US, "%.8f", sampleLat);
        String lon = String.format(Locale.US, "%.8f", sampleLon);
        assertTrue(html.contains("Sierra point"));
        assertTrue(html.contains(lat), lat);
        assertTrue(html.contains(lon), lon);
        assertTrue(html.contains(CH01));
        assertTrue(html.contains(ch02.signalName()));
        assertTrue(html.contains("Sierra observation"));
        assertTrue(html.contains("not a picture of that event signal"));
        assertFalse(html.contains("Engineering Conclusions"));
        assertTrue(html.contains("assets/"));
        assertFalse(html.contains("http://"));
        assertEquals(1, html.split("<img", -1).length - 1);
        assertTrue(pdf.contains(packed(lat)));
        assertTrue(pdf.contains("Sierrapoint"));
        assertTrue(pdf.contains("Sierraobservation"));
        assertTrue(pdf.contains("notapictureofthateventsignal"));
        Files.copy(htmlPath, Path.of("target", "m16-sierra-report.html"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pdfPath, Path.of("target", "m16-sierra-report.pdf"), StandardCopyOption.REPLACE_EXISTING);

        JavaFxTestSupport.runOnFx("exclude the sierra event", () -> {
            selectEvent("Sierra point");
            button("Remove from Report").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        Path excludedDir = temp.resolve("sierra-excluded");
        Files.createDirectories(excludedDir);
        export("sierra-excluded", excludedDir, false);
        String excluded = Files.readString(htmlFile(excludedDir));
        assertFalse(excluded.contains("Sierra point"));
        assertFalse(excluded.contains(lat));
        assertTrue(service.findEngineeringBookmark(eventId).isPresent());
        assertEquals(sampleNanos, service.findEngineeringBookmark(eventId).orElseThrow().timestampNanos());
        org.junit.jupiter.api.Assertions.assertArrayEquals(hash, sha256(Files.readAllBytes(snapshotFile)));

        String projectId = saved.projectId();
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        JavaFxTestSupport.runOnFx("reopen sierra", () -> controller.refresh());
        JavaFxTestSupport.drainFxPulse();
        var reopened = service.findEngineeringBookmark(eventId).orElseThrow();
        assertEquals(sampleNanos, reopened.timestampNanos());
        assertEquals(GraphEventGps.MATCHED, GraphEventGps.read(reopened).status());
        assertEquals(sampleNanos, GraphEventGps.read(reopened).sampleTimestampNanos());
        EngineeringInvestigation investigation = service.engineeringInvestigations()
                .find(service.metadataStore(), saved.investigationId()).orElseThrow();
        assertEquals(List.of(ch01Key, ch02Key), investigation.signalKeyBlobs());
        assertFalse(investigation.reportConfig().shows(EngineeringReportSection.CONCLUSIONS));
        assertTrue(investigation.reportConfig().shows(EngineeringReportSection.GPS_ROUTE));
        assertEquals("Sierra observation", investigation.observations().getFirst().title());
        assertFalse(com.stellar.platform.analysis.investigation.EngineerEventInclusion.linked(
                investigation.evidence(), eventId));
        assertEquals(kept, nearest.physicalValue(), 0.0);
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.println("M16 sierraSampleNanos=" + sampleNanos + " lat=" + sampleLat + " lon=" + sampleLon
                + " ch01Nanos=" + keptNanos + " ch01=" + kept
                + " htmlMs=" + htmlMs + " pdfMs=" + pdfMs + " heapBytes=" + heap);
        assertTrue(htmlMs < 60_000);
        assertTrue(pdfMs < 60_000);
        assertTrue(heap < 1_536L * 1024 * 1024);
        assertEquals(EngineeringFormats.seconds(sampleNanos), EngineeringFormats.seconds(reopened.timestampNanos()));
    }

    private void openScreen() throws Exception {
        long[] phase = new long[4];
        long fxStart = System.nanoTime();
        JavaFxTestSupport.runOnFx("load the investigation screen", () -> {
            long mark = System.nanoTime();
            FXMLLoader loader = new FXMLLoader(InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
            Parent root = loader.load();
            phase[0] = (System.nanoTime() - mark) / 1_000_000L;
            mark = System.nanoTime();
            controller = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
            phase[1] = (System.nanoTime() - mark) / 1_000_000L;
            mark = System.nanoTime();
            controller.setAppService(service, null);
            controller.setOpenGeneratedReportForTest(false);
            phase[2] = (System.nanoTime() - mark) / 1_000_000L;
            mark = System.nanoTime();
            controller.refresh();
            phase[3] = (System.nanoTime() - mark) / 1_000_000L;
        });
        long fxMs = (System.nanoTime() - fxStart) / 1_000_000L;
        long drainStart = System.nanoTime();
        JavaFxTestSupport.drainFxPulse();
        long drainMs = (System.nanoTime() - drainStart) / 1_000_000L;
        System.out.println("M16 OPEN PHASES fxmlMs=" + phase[0]
                + " showMs=" + phase[1]
                + " bindMs=" + phase[2]
                + " refreshMs=" + phase[3]
                + " fxQueueMs=" + fxMs
                + " drainMs=" + drainMs);
    }

    private void export(String name, Path directory, boolean pdf) throws Exception {
        String extension = pdf ? ".pdf" : ".html";
        JavaFxTestSupport.runOnFx("export " + name, () -> {
            controller.setReportDestinationForTest(name, directory);
            button(pdf ? "Generate PDF Report" : "Generate HTML Report").fire();
        });
        long deadline = System.nanoTime() + 90_000_000_000L;
        Path written = null;
        String status = "";
        while (System.nanoTime() < deadline) {
            String[] holder = new String[1];
            JavaFxTestSupport.runOnFx("read report status", () -> holder[0] = controller.reportStatusForTest());
            status = holder[0] == null ? "" : holder[0];
            if (Files.isDirectory(directory)) {
                try (var listing = Files.list(directory)) {
                    for (Path path : listing.toList()) {
                        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (filename.endsWith(extension) && !filename.endsWith(".writing") && Files.size(path) > 0) {
                            written = path;
                        }
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
    }

    private void waitStatus(String expected) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        String status = "";
        while (System.nanoTime() < deadline) {
            String[] holder = new String[1];
            JavaFxTestSupport.runOnFx("read status", () -> holder[0] = controller.reportStatusForTest());
            status = holder[0] == null ? "" : holder[0];
            if (expected.equals(status) || status.startsWith("Report could not")) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(expected, status);
    }

    private void shoot(String name) throws Exception {
        JavaFxTestSupport.runOnFx("screenshot " + name, () -> {
            WritableImage image = stage.getScene().snapshot(null);
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            PixelReader reader = image.getPixelReader();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    buffered.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            Path out = Path.of("target", name);
            try {
                Files.createDirectories(out.getParent());
                ImageIO.write(buffered, "png", out.toFile());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private boolean pane(String title) {
        for (Node node : stage.getScene().getRoot().lookupAll(".titled-pane")) {
            if (node instanceof TitledPane pane && title.equals(pane.getText())) {
                return true;
            }
        }
        return false;
    }

    private String eventRow(String title) {
        for (Node node : stage.getScene().getRoot().lookupAll(".list-view")) {
            if (node instanceof ListView<?> list) {
                for (Object item : list.getItems()) {
                    if (item instanceof String text && text.startsWith(title + " ·")) {
                        return text;
                    }
                }
            }
        }
        throw new AssertionError(title);
    }

    private void selectEvent(String title) {
        ListView<?> found = null;
        int index = -1;
        for (Node node : stage.getScene().getRoot().lookupAll(".list-view")) {
            if (node instanceof ListView<?> list) {
                for (int i = 0; i < list.getItems().size(); i++) {
                    Object item = list.getItems().get(i);
                    if (item instanceof String text && text.startsWith(title + " ·")) {
                        found = list;
                        index = i;
                    }
                }
            }
        }
        assertNotNull(found, title);
        found.getSelectionModel().select(index);
    }

    private ListView<?> signalList() {
        for (Node node : stage.getScene().getRoot().lookupAll(".list-view")) {
            if (node instanceof ListView<?> list) {
                for (Object item : list.getItems()) {
                    if (item instanceof String text && (text.contains(CH01) || text.contains("signal mapping stale"))) {
                        return list;
                    }
                }
            }
        }
        throw new AssertionError("Signal list was not shown");
    }

    private String signalText() {
        StringBuilder out = new StringBuilder();
        for (Object item : signalList().getItems()) {
            out.append(item).append('\n');
        }
        return out.toString();
    }

    private String summary() {
        var labeled = (javafx.scene.control.Labeled) stage.getScene().getRoot().lookup("#reportSelectionSummary");
        return labeled.getText() == null ? "" : labeled.getText();
    }

    private String gpsLabel() {
        var labeled = (javafx.scene.control.Labeled) stage.getScene().getRoot().lookup("#gpsEvidenceLabel");
        return labeled.getText() == null ? "" : labeled.getText();
    }

    private Button button(String text) {
        for (Node node : stage.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError(text);
    }

    private Button buttonBeside(String anchor, String text) {
        Button found = button(anchor);
        if (found.getParent() instanceof javafx.scene.layout.Pane row) {
            for (Node child : row.getChildren()) {
                if (child instanceof Button button && text.equals(button.getText())) {
                    return button;
                }
            }
        }
        throw new AssertionError(text);
    }

    private static Path htmlFile(Path directory) throws Exception {
        try (var listing = Files.list(directory)) {
            return listing.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html"))
                    .findFirst().orElseThrow();
        }
    }

    private static Path pdfFile(Path directory) throws Exception {
        try (var listing = Files.list(directory)) {
            return listing.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .findFirst().orElseThrow();
        }
    }

    private static String pdfText(Path pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String packed(String text) {
        return text.replace(" ", "").replace("\n", "").replace("\r", "");
    }

    private static int maskDigits(String encoded) {
        int mark = encoded.indexOf('V');
        if (mark < 0 || mark + 4 > encoded.length()) {
            return -1;
        }
        String digits = encoded.substring(mark + 1, mark + 4);
        Integer.parseInt(digits, 16);
        return digits.length();
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                image.setRGB(x, y, 0x0E7490);
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static MapRouteModel route(long[] times, double[] latitude, double[] longitude) {
        var built = MapRouteBuilder.validateAndBuild(samples(times, latitude), samples(times, longitude), List.of());
        assertFalse(built.model().isEmpty(), built.diagnostics().summary());
        return built.model();
    }

    private static List<SignalSamplePoint> samples(long[] times, double[] values) {
        List<SignalSamplePoint> out = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            out.add(new SignalSamplePoint(times[i], values[i], null, null));
        }
        return out;
    }

    private static SignalCatalogEntry entry(SignalSeriesStore store, String name) {
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (name.equals(candidate.signalName())) {
                return candidate;
            }
        }
        return null;
    }

    private static SignalCatalogEntry entryPrefix(SignalSeriesStore store, String prefix) {
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (candidate.signalName() != null && candidate.signalName().startsWith(prefix)) {
                return candidate;
            }
        }
        return null;
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
}

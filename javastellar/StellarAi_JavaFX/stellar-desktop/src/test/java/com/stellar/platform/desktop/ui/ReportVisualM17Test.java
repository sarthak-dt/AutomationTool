package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphEventGps;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.SignalReportRenderer;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Report chart readability and the Sierra HTML/PDF presentation. */
class ReportVisualM17Test {

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
        JavaFxTestSupport.runOnFx("dispose visual report", () -> {
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
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    void alignedChartKeepsLabelsOffTheTraceAndOnTheStoredTime() {
        double[] time = {0d, 5d, 10d};
        double[] first = {100_000d, 100_100d, 100_200d};
        double[] second = {100_010d, 100_110d, 100_210d};
        var tracks = List.of(
                new SignalReportRenderer.Track("CH01_FRONT_BUMPER_GRILL_AMBIENT", "C", time, first),
                new SignalReportRenderer.Track("CH02_HOOD_ENGINE_SIDE", "C", time, second));
        SignalReportRenderer.ChartLayout layout = SignalReportRenderer.alignedLayout(tracks);
        SignalReportRenderer.TrackFrame frame = layout.tracks().getFirst();
        for (SignalReportRenderer.PlacedLabel label : frame.labels()) {
            assertTrue(label.x() >= 0, label.text());
            assertTrue(label.x() + label.width() <= layout.width(), label.text());
        }
        assertTrue(frame.unit().contains("C"));
        BufferedImage image = SignalReportRenderer.paintAligned(tracks, List.of(
                new SignalReportRenderer.EventSpan("Thermal window", 1d, 8d),
                new SignalReportRenderer.EventSpan("Sierra point", 5d, Double.NaN)));
        assertEquals(100_100d, first[1], 0d);
        int x = frame.plotLeft() + (int) Math.round((5d - frame.xMin()) / (frame.xMax() - frame.xMin())
                * (frame.plotWidth() - 1));
        assertTrue(columnHas(image, x, frame, 0x6B4EC2) || columnHas(image, x, frame, 0xE6A817));
        assertTrue(legendAbovePlot(image, frame));
    }

    @Test
    @Timeout(180)
    void sierraHtmlAndPdfShareTheSelectedFacts() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        var processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        SignalSeriesStore store = processed.catalogStore();
        SignalCatalogEntry ch01 = entry(store, CH01);
        SignalCatalogEntry ch02 = entryPrefix(store, "CH02");
        SignalCatalogEntry latitude = entry(store, "LATITUDE");
        SignalCatalogEntry longitude = entry(store, "LONGITUDE");
        assertNotNull(ch01);
        assertNotNull(ch02);
        List<SignalSamplePoint> thermal = readAll(store, ch01);
        List<SignalSamplePoint> other = readAll(store, ch02);
        int rise = -1;
        int settle = -1;
        for (int i = 0; i < thermal.size(); i++) {
            SignalSamplePoint sample = thermal.get(i);
            if (!sample.usableMeasurement() || sample.physicalValue() < 40.0d) {
                continue;
            }
            if (rise < 0) {
                rise = i;
            }
            settle = i;
        }
        assertTrue(rise > 0 && settle > rise);
        long startNanos = thermal.get(rise).timestampNanos();
        long endNanos = thermal.get(settle).timestampNanos();
        double windowMin = Double.POSITIVE_INFINITY;
        int below = 0;
        for (int i = rise; i <= settle; i++) {
            SignalSamplePoint sample = thermal.get(i);
            if (!sample.usableMeasurement()) {
                continue;
            }
            windowMin = Math.min(windowMin, sample.physicalValue());
            if (sample.physicalValue() < 40.0d) {
                below++;
            }
        }
        assertEquals(39.631952d, windowMin, 0.000001d);
        assertEquals(93, below);
        double shownMin = windowMin;
        int shownBelow = below;
        var built = MapRouteBuilder.validateAndBuild(readAll(store, latitude), readAll(store, longitude), List.of());
        MapRouteModel route = built.model();
        long sampleNanos = route.timestampNanos(route.size() / 2);
        double kept = thermal.get(thermal.size() / 2).physicalValue();

        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Sierra visual");
        service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
        String ch01Key = GraphInvestigationCodec.encodeKey(ch01.key());
        String ch02Key = GraphInvestigationCodec.encodeKey(ch02.key());
        EngineeringInvestigation created = service.createInvestigation(
                "Sierra visual", "CH01 and CH02 evidence selected for the report.", "FULL");
        EngineeringInvestigation saved = service.saveInvestigation(created.toBuilder()
                .reportConfig(InvestigationReportConfig.engineeringV2())
                .signalKeyBlobs(List.of(ch01Key, ch02Key))
                .range(startNanos, endNanos)
                .build());
        service.graphInvestigation().setViewport(0L, Math.max(endNanos, sampleNanos) + 1_000_000_000L);
        String windowId = service.saveGraphEvent(null, "Thermal window", "Thermal observation",
                "Engineer-selected window. Not an automatic detection.",
                startNanos, endNanos, "sierra", MF4.getFileName().toString(),
                List.of(CH01, ch02.signalName()), List.of(ch01Key, ch02Key));
        String pointId = service.saveGraphEvent(null, "Sierra point", "Thermal observation",
                "Existing graph point.", sampleNanos, null, "sierra", MF4.getFileName().toString(),
                List.of(CH01, ch02.signalName()), List.of(ch01Key, ch02Key));
        GraphEventGps.Evidence matched = GraphEventGps.review(
                service.findEngineeringBookmark(pointId).orElseThrow(),
                new GraphEventGps.RouteView(route, "sierra", MF4.getFileName().toString(), "", "LATITUDE", "LONGITUDE"));
        assertEquals(GraphEventGps.MATCHED, matched.status(), matched.statement());
        assertEquals("", service.saveGraphEventGps(pointId, matched));
        BufferedImage chart = SignalReportRenderer.paintAligned(List.of(
                track(CH01, ch01.unit(), thermal),
                track(ch02.signalName(), ch02.unit(), other)), List.of(
                new SignalReportRenderer.EventSpan("Thermal window", startNanos / 1_000_000_000d, endNanos / 1_000_000_000d),
                new SignalReportRenderer.EventSpan("Sierra point", sampleNanos / 1_000_000_000d, Double.NaN)));
        var withSnapshot = service.attachGraphSnapshot(png(chart), "CH01 and CH02");
        String snapshotId = withSnapshot.evidence().stream()
                .filter(item -> item.kind() == com.stellar.platform.analysis.investigation.EvidenceKind.GRAPH_SNAPSHOT)
                .findFirst().orElseThrow().referenceId();
        assertEquals("", service.linkGraphEventSnapshot(windowId, snapshotId));
        assertEquals("", service.linkGraphEventSnapshot(pointId, snapshotId));
        Path snapshotFile = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(saved.projectId()), saved.investigationId(), snapshotId);
        open(1366, 768);
        JavaFxTestSupport.runOnFx("wide report tab", () -> {
            selectReport();
            assertTrue(button("Preview HTML").getLayoutBounds().getWidth() > 20);
            assertTrue(button("Generate PDF Report").getLayoutBounds().getWidth() > 20);
            selectEvent("Thermal window");
            button("Include in Report").fire();
            selectEvent("Sierra point");
            button("Include in Report").fire();
            ((TextField) stage.getScene().getRoot().lookup("#observationTitle")).setText("CH01 window");
            ((TextArea) stage.getScene().getRoot().lookup("#observationNote")).setText(
                    "Measured CH01 minimum " + String.format(Locale.US, "%.6f", shownMin)
                            + " C. " + shownBelow + " usable samples in the window are below 40 C. "
                            + "This is an engineer observation, not a compliance result.");
            button("Add Observation").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        shoot("m17-report-wide.png");
        stage.setWidth(1100);
        stage.setHeight(720);
        JavaFxTestSupport.drainFxPulse();
        JavaFxTestSupport.runOnFx("narrow report tab", () -> {
            Button pdf = button("Generate PDF Report");
            assertTrue(pdf.localToScene(pdf.getBoundsInLocal()).getMaxX() <= stage.getScene().getWidth() + 2);
        });
        shoot("m17-report-narrow.png");

        Path htmlDir = temp.resolve("html");
        Path pdfDir = temp.resolve("pdf");
        Files.createDirectories(htmlDir);
        Files.createDirectories(pdfDir);
        long htmlAt = System.nanoTime();
        export("sierra-visual", htmlDir, false);
        long htmlMs = (System.nanoTime() - htmlAt) / 1_000_000L;
        long pdfAt = System.nanoTime();
        export("sierra-visual", pdfDir, true);
        long pdfMs = (System.nanoTime() - pdfAt) / 1_000_000L;
        String html = Files.readString(htmlFile(htmlDir));
        String pdf = packed(pdfText(pdfFile(pdfDir)));
        String lat = String.format(Locale.US, "%.8f", matched.latitude());
        String lon = String.format(Locale.US, "%.8f", matched.longitude());
        assertTrue(html.contains("Thermal window"));
        assertTrue(html.contains("Sierra point"));
        assertTrue(html.contains("39.631952"));
        assertTrue(html.contains("93 usable samples"));
        assertTrue(html.contains(lat));
        assertTrue(html.contains(lon));
        assertTrue(html.contains("No GPS route is attached"));
        assertTrue(html.contains("Engineer-selected window"));
        assertTrue(html.contains("engineer-authored"));
        assertFalse(html.contains("http://"));
        assertEquals(1, html.split("<img", -1).length - 1);
        assertTrue(pdf.contains("39.631952"));
        assertTrue(pdf.contains("93usablesamples"));
        assertTrue(pdf.contains(packed(lat)));
        assertTrue(pdf.contains("Thermalwindow"));
        assertTrue(pdf.contains("Sierrapoint"));
        assertFalse(html.contains("PASS"));
        Path pdfPath = pdfFile(pdfDir);
        int pages = renderPages(pdfPath, "m17-page");
        assertTrue(pages >= 1 && pages <= 8, "pages=" + pages);
        Files.copy(htmlFile(htmlDir), Path.of("target", "m17-sierra-report.html"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pdfPath, Path.of("target", "m17-sierra-report.pdf"), StandardCopyOption.REPLACE_EXISTING);

        Files.delete(snapshotFile);
        Path missingDir = temp.resolve("missing");
        Files.createDirectories(missingDir);
        export("sierra-missing", missingDir, false);
        assertTrue(Files.readString(htmlFile(missingDir)).contains("Snapshot file is missing"));

        assertEquals("", service.clearGraphEventGps(pointId));
        JavaFxTestSupport.runOnFx("refresh cleared gps", () -> controller.refresh());
        JavaFxTestSupport.drainFxPulse();
        Path noGpsDir = temp.resolve("nogps");
        Files.createDirectories(noGpsDir);
        export("sierra-nogps", noGpsDir, false);
        String noGps = Files.readString(htmlFile(noGpsDir));
        assertTrue(noGps.contains("No GPS route is attached"));
        assertFalse(noGps.contains(lat));

        JavaFxTestSupport.runOnFx("exclude the point", () -> {
            selectEvent("Sierra point");
            button("Remove from Report").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        Path excludedDir = temp.resolve("excluded");
        Files.createDirectories(excludedDir);
        export("sierra-excluded", excludedDir, false);
        String excluded = Files.readString(htmlFile(excludedDir));
        assertFalse(excluded.contains("Sierra point"));
        assertTrue(excluded.contains("Thermal window"));
        assertEquals(sampleNanos, service.findEngineeringBookmark(pointId).orElseThrow().timestampNanos());
        assertEquals(kept, thermal.get(thermal.size() / 2).physicalValue(), 0d);

        service.closeProject();
        assertTrue(service.openSavedProject(saved.projectId()).opened());
        EngineeringInvestigation reopened = service.engineeringInvestigations()
                .find(service.metadataStore(), saved.investigationId()).orElseThrow();
        assertEquals(List.of(ch01Key, ch02Key), reopened.signalKeyBlobs());
        assertEquals("CH01 window", reopened.observations().getFirst().title());
        assertTrue(reopened.reportConfig().shows(EngineeringReportSection.GRAPH_EVIDENCE));
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.println("M17 htmlMs=" + htmlMs + " pdfMs=" + pdfMs + " pages=" + pages
                + " htmlBytes=" + Files.size(Path.of("target", "m17-sierra-report.html"))
                + " pdfBytes=" + Files.size(Path.of("target", "m17-sierra-report.pdf"))
                + " images=1 heapBytes=" + heap);
        assertTrue(htmlMs < 60_000 && pdfMs < 60_000);
        assertTrue(heap < 1_536L * 1024 * 1024);
    }

    private static boolean columnHas(BufferedImage image, int x, SignalReportRenderer.TrackFrame frame, int rgb) {
        int target = rgb & 0xFFFFFF;
        for (int y = frame.plotTop(); y < frame.plotTop() + frame.plotHeight(); y++) {
            if ((image.getRGB(x, y) & 0xFFFFFF) == target) {
                return true;
            }
        }
        return false;
    }

    private static boolean legendAbovePlot(BufferedImage image, SignalReportRenderer.TrackFrame frame) {
        int blue = 0x1677FF;
        for (int y = Math.max(0, frame.plotTop() - 40); y < frame.plotTop(); y++) {
            for (int x = frame.plotLeft(); x < frame.plotLeft() + 24; x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == blue) {
                    return true;
                }
            }
        }
        return false;
    }

    private void open(double width, double height) throws Exception {
        JavaFxTestSupport.runOnFx("open investigation", () -> {
            FXMLLoader loader = new FXMLLoader(InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
            Parent root = loader.load();
            controller = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, width, height));
            stage.show();
            controller.setAppService(service, null);
            controller.setOpenGeneratedReportForTest(false);
            controller.refresh();
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private void selectReport() {
        TabPane tabs = (TabPane) stage.getScene().getRoot().lookup(".tab-pane");
        tabs.getTabs().stream().filter(tab -> "Report".equals(tab.getText())).findFirst()
                .ifPresent(tab -> tabs.getSelectionModel().select(tab));
    }

    private void selectEvent(String title) {
        javafx.scene.control.ListView<?> found = null;
        int index = -1;
        for (javafx.scene.Node node : stage.getScene().getRoot().lookupAll(".list-view")) {
            if (node instanceof javafx.scene.control.ListView<?> list) {
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

    private Button button(String text) {
        for (javafx.scene.Node node : stage.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError(text);
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
            JavaFxTestSupport.runOnFx("status", () -> holder[0] = controller.reportStatusForTest());
            status = holder[0] == null ? "" : holder[0];
            try (var listing = Files.list(directory)) {
                for (Path path : listing.toList()) {
                    String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (filename.contains(name) && filename.endsWith(extension) && Files.size(path) > 0) {
                        written = path;
                    }
                }
            }
            if (written != null && ("Report generated.".equals(status) || status.isBlank())) {
                break;
            }
            if (status.startsWith("Report could not")) {
                break;
            }
            Thread.sleep(200);
        }
        assertNotNull(written, status);
        assertFalse(status.startsWith("Report could not"), status);
    }

    private void shoot(String name) throws Exception {
        JavaFxTestSupport.runOnFx("shot " + name, () -> {
            var image = stage.getScene().snapshot(null);
            BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            var reader = image.getPixelReader();
            for (int y = 0; y < buffered.getHeight(); y++) {
                for (int x = 0; x < buffered.getWidth(); x++) {
                    buffered.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            try {
                ImageIO.write(buffered, "png", Path.of("target", name).toFile());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private static int renderPages(Path pdf, String prefix) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                BufferedImage page = renderer.renderImageWithDPI(i, 100);
                ImageIO.write(page, "png", Path.of("target", prefix + "-" + (i + 1) + ".png").toFile());
            }
            return document.getNumberOfPages();
        }
    }

    private static Path htmlFile(Path directory) throws Exception {
        try (var listing = Files.list(directory)) {
            return listing.filter(path -> path.getFileName().toString().endsWith(".html")).findFirst().orElseThrow();
        }
    }

    private static Path pdfFile(Path directory) throws Exception {
        try (var listing = Files.list(directory)) {
            return listing.filter(path -> path.getFileName().toString().endsWith(".pdf"))
                    .max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElseThrow();
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

    private static byte[] png(BufferedImage image) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static SignalReportRenderer.Track track(String name, String unit, List<SignalSamplePoint> samples) {
        double[] time = new double[samples.size()];
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            time[i] = samples.get(i).timestampNanos() / 1_000_000_000d;
            values[i] = samples.get(i).physicalValue();
        }
        return new SignalReportRenderer.Track(name, unit == null ? "" : unit, time, values);
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

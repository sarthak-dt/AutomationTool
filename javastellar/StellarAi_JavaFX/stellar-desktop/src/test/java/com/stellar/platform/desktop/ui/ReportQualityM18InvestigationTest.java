package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Generates Engineering Report V2 from the Investigation screen for the read-only Tata Sierra sources.
 */
class ReportQualityM18InvestigationTest {

    private static final Path MF4 = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/TEMP DBC 1 (1).dbc");
    private static final Pattern FINDING_ID = Pattern.compile("samples-[A-Za-z0-9-]+|interval-[A-Za-z0-9-]+|unit-[A-Za-z0-9-]+|scan-scope|dbc-coverage-[a-z-]+");

    @TempDir
    Path temp;
    private DesktopAppService service;
    private InvestigationController controller;
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose m18 investigation", () -> {
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
    @Timeout(240)
    void investigationGenerateWritesTheSameSierraFactsToHtmlAndPdf() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        long started = System.nanoTime();
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        var processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        SignalSeriesStore store = processed.catalogStore();
        SignalCatalogEntry ch01 = entry(store, "CH01_FRONT_BUMPER_GRILL_AMBIENT");
        SignalCatalogEntry ch02 = entryPrefix(store, "CH02");
        SignalCatalogEntry latitude = entry(store, "LATITUDE");
        assertNotNull(ch01);
        assertNotNull(ch02);
        assertNotNull(latitude);
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("project.db")));
        service.newProject("M18 Sierra");
        service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
        service.addDefinitionPaths(List.of(DBC));
        service.signalsModel().replaceCatalog(store);
        EngineeringInvestigation created = service.createInvestigation(
                "M18 Sierra", "Investigation report from the original MF4.", "FULL");
        service.saveInvestigation(created.toBuilder()
                .reportConfig(InvestigationReportConfig.engineeringV2())
                .signalKeyBlobs(List.of(
                        GraphInvestigationCodec.encodeKey(latitude.key()),
                        GraphInvestigationCodec.encodeKey(ch01.key()),
                        GraphInvestigationCodec.encodeKey(ch02.key())))
                .build());
        open();
        Path htmlDir = temp.resolve("html");
        Path pdfDir = temp.resolve("pdf");
        Files.createDirectories(htmlDir);
        Files.createDirectories(pdfDir);
        long reportStart = System.nanoTime();
        export("m18-investigation", htmlDir, false);
        export("m18-investigation", pdfDir, true);
        long reportMs = (System.nanoTime() - reportStart) / 1_000_000L;
        Path output = Path.of("target");
        if (Path.of("").toAbsolutePath().getFileName().toString().equals("stellar-desktop")) {
            output = Path.of("").toAbsolutePath().getParent().resolve("target");
        }
        Path htmlPath = output.resolve("m18-investigation-sierra-report.html");
        Path pdfPath = output.resolve("m18-investigation-sierra-report.pdf");
        Files.createDirectories(htmlPath.getParent());
        Files.copy(htmlFile(htmlDir), htmlPath, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pdfFile(pdfDir), pdfPath, StandardCopyOption.REPLACE_EXISTING);
        String html = Files.readString(htmlPath);
        String pdf;
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            pdf = new PDFTextStripper().getText(document);
        }
        String flatHtml = html.replaceAll("\\s+", "");
        String flatPdf = pdf.replaceAll("\\s+", "");
        assertTrue(flatHtml.contains("84940000"), html);
        assertTrue(flatPdf.contains("84940000"));
        assertTrue(html.contains("Scan evidence is complete"));
        assertTrue(flatPdf.contains("Scanevidenceiscomplete"));
        assertTrue(html.contains("observed=45"));
        assertTrue(html.contains("matched=4"));
        assertTrue(html.contains("unmatchedIdentities=41"));
        assertTrue(html.contains("not reconstructed"));
        assertTrue(flatPdf.contains("observed=45"));
        assertTrue(flatPdf.contains("matched=4"));
        assertTrue(flatPdf.contains("unmatchedIdentities=41"));
        assertTrue(flatPdf.contains("notreconstructed"));
        assertTrue(html.contains("\u00B0C") || html.contains("unit=\u00B0C") || html.contains("unit=°C"));
        assertFalse(html.contains("1000 times"));
        assertFalse(html.contains("clocks-unverified"));
        assertFalse(flatPdf.contains("clocks-unverified"));
        List<String> ids = findingIds(html);
        assertFalse(ids.isEmpty());
        for (String id : ids) {
            assertTrue(flatPdf.contains(id), id);
        }
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("M18 investigation elapsedMs=" + elapsed
                + " reportMs=" + reportMs
                + " findings=" + ids.size()
                + " heapBytes=" + heap
                + " htmlBytes=" + Files.size(htmlPath)
                + " pdfBytes=" + Files.size(pdfPath));
        assertTrue(heap < 1_536L * 1024 * 1024);
    }

    private void open() throws Exception {
        JavaFxTestSupport.runOnFx("open investigation", () -> {
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

    private Button button(String text) {
        for (javafx.scene.Node node : stage.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError(text);
    }

    private static List<String> findingIds(String html) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = FINDING_ID.matcher(html);
        while (matcher.find()) {
            if (!ids.contains(matcher.group())) {
                ids.add(matcher.group());
            }
        }
        return ids;
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

    private static Path htmlFile(Path directory) throws Exception {
        try (var listing = Files.list(directory)) {
            return listing.filter(path -> path.getFileName().toString().endsWith(".html")).findFirst().orElseThrow();
        }
    }

    private static Path pdfFile(Path directory) throws Exception {
        try (var listing = Files.list(directory)) {
            return listing.filter(path -> path.getFileName().toString().endsWith(".pdf")).findFirst().orElseThrow();
        }
    }
}

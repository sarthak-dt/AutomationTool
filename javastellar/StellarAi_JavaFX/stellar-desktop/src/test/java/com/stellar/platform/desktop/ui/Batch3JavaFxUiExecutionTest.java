package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.advanced.AdvancedAnalyticsType;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AdvancedAnalyticsPane;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 3 QA execution on the real JavaFX shell.
 * Uses a metadata copy of the isolated 73-file Xenon project and the original artifacts.
 * Does not reprocess measurements.
 */
@Timeout(value = 25, unit = TimeUnit.MINUTES)
class Batch3JavaFxUiExecutionTest {

    private static final String XENON_SHA =
            "F66753745C79EC35D7AA87CE96B877D549640C15FD52286585887F2D6ED62CF5";
    private static final double TOLERANCE = 0.05;
    private static final Path EVIDENCE = Path.of(
            "D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\batch3-ui-execution.txt");
    private static final List<String> LINES = new ArrayList<>();
    private static final List<String> FAILURES = new ArrayList<>();

    private static DesktopAppService service;
    private static Stage stage;
    private static Scene scene;
    private static Path home;
    private static String xenonId = "";
    private static String focusedId = "";
    private static String xenonName = "";
    private static double fileSpeedMin = Double.NaN;
    private static double fileSpeedMax = Double.NaN;
    private static double fileCoolantMin = Double.NaN;
    private static double fileCoolantMax = Double.NaN;
    private static Double fileFanMin;
    private static Double fileFanMax;
    private static String statisticsReport = "";
    private static String htmlSpeedRow = "";
    private static Path htmlPath;

    @BeforeAll
    static void toolkit() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void shutdown() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    void analyticsEngineeringReportAndPersistence() throws Exception {
        try {
            recordEnvironment();
            openXenonCopy();
            verifyCatalogOracle();
            narrowWorkspace();
            launchShell();
            driveAnalyticsAndEngineering();
            generateHtmlReport();
            saveAndReopen();
        } catch (Throwable thrown) {
            note("ABORTED " + thrown);
            thrown.printStackTrace(System.out);
            FAILURES.add("aborted: " + thrown.getMessage());
        } finally {
            writeEvidence();
            closeStage();
        }
        assertTrue(FAILURES.isEmpty(), String.join("\n", FAILURES));
    }

    private static void recordEnvironment() throws Exception {
        home = prepareHome();
        String userHome = System.getProperty("user.home");
        note("started=" + Instant.now());
        note("java.version=" + System.getProperty("java.version"));
        note("java.home=" + System.getProperty("java.home"));
        note("product=" + DesktopVersion.displayVersion());
        note("launch=mvn -pl stellar-desktop -am test -Dtest=Batch3JavaFxUiExecutionTest -Dsurefire.failIfNoSpecifiedTests=false");
        note("stellarHome=" + home);
        note("sourceHome=" + Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home"));
        note("fixture=M2-73 Xenon metadata copy; artifacts stay in the original isolated home");
        boolean profileDot = home.startsWith(Path.of(userHome, ".stellarai"));
        boolean customer = home.startsWith(Path.of("D:\\config")) || home.startsWith(Path.of("D:\\StellarAI-E2E"));
        check("ENV-HOME", !profileDot && !customer && Files.isRegularFile(home.resolve("meta.db")),
                "home=" + home);
    }

    private static Path prepareHome() throws Exception {
        Path source = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
        Path dest = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "batch3-home");
        Files.createDirectories(dest);
        Files.copy(source.resolve("meta.db"), dest.resolve("meta.db"), StandardCopyOption.REPLACE_EXISTING);
        Path config = source.resolve("config");
        if (Files.isDirectory(config)) {
            try (var walk = Files.walk(config)) {
                for (Path path : walk.toList()) {
                    Path target = dest.resolve("config").resolve(config.relativize(path));
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Path link = dest.resolve("analysis-storage");
        if (!Files.exists(link)) {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), source.resolve("analysis-storage").toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException("analysis-storage link failed: " + output);
            }
        }
        return dest;
    }

    private static void openXenonCopy() throws Exception {
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(home.resolve("meta.db")));
        var project = service.metadataStore().listProjects().stream()
                .filter(row -> "M2-73".equals(row.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(service.openSavedProject(project.projectId()).opened());
        note("project=" + project.name() + " id=" + project.projectId());
        note("measurements=" + service.workspace().measurementFiles().size());
        note("processingStatus=" + service.workspace().processingStatus());
        check("PROJECT-73", service.workspace().measurementFiles().size() == 73,
                "files=" + service.workspace().measurementFiles().size());
    }

    private static void verifyCatalogOracle() throws Exception {
        WorkspaceFile xenon = null;
        for (WorkspaceFile file : service.workspace().measurementFiles()) {
            if (file.fileName().contains("2020-02-07 16-22-19")) {
                String hash = sha256(file.path());
                note("hashCandidate=" + file.fileName() + " " + hash);
                if (XENON_SHA.equalsIgnoreCase(hash)) {
                    xenon = file;
                    break;
                }
            }
        }
        check("XENON-HASH", xenon != null, xenon == null ? "reference file not in the copy" : xenon.fileName());
        if (xenon != null) {
            xenonId = xenon.id();
            xenonName = xenon.fileName();
            note("xenonId=" + xenonId);
        }
        List<SignalCatalogEntry> catalog = service.signalsModel().catalog().entries();
        long aggregate = catalog.stream().mapToLong(SignalCatalogEntry::sampleCount).sum();
        note("catalogSignals=" + catalog.size());
        note("mergedAggregateSamples=" + aggregate);
        check("XENON-COUNT", catalog.size() == 30 && aggregate == 18_700_329L,
                "signals=" + catalog.size() + " mergedSamples=" + aggregate);
        SignalCatalogEntry speed = named(catalog, "IN_Vehicle_speed");
        SignalCatalogEntry coolant = named(catalog, "IN_Coolant_temperature");
        SignalCatalogEntry fan = named(catalog, "ETC_Fan2_output");
        AtomicReference<String> speedHit = new AtomicReference<>("missing");
        AtomicReference<String> coolantHit = new AtomicReference<>("missing");
        AtomicReference<String> fanHit = new AtomicReference<>("missing");
        service.signalsModel().catalogStore().forEachSample(speed.key(), sample -> {
            if ("missing".equals(speedHit.get()) && Long.valueOf(9340L).equals(sample.rawValue())
                    && sample.physicalValue() > 72.9 && sample.physicalValue() < 73.0) {
                speedHit.set(describe(sample));
            }
        });
        service.signalsModel().catalogStore().forEachSample(coolant.key(), sample -> {
            if ("missing".equals(coolantHit.get()) && Long.valueOf(320L).equals(sample.rawValue())
                    && sample.physicalValue() == 80.0) {
                coolantHit.set(describe(sample));
            }
        });
        service.signalsModel().catalogStore().forEachSample(fan.key(), sample -> {
            if ("missing".equals(fanHit.get()) && "FALSE".equals(sample.textualValue())) {
                fanHit.set(describe(sample));
            }
        });
        check("XENON-SPEED", speedHit.get().contains("72.96875"), speedHit.get());
        check("XENON-COOLANT", coolantHit.get().contains("80.0"), coolantHit.get());
        check("XENON-FAN", fanHit.get().contains("FALSE") && fanHit.get().contains("VALID"), fanHit.get());
        List<SignalCatalogMetadata> rows = service.metadataStore().listSignalCatalog(service.currentProjectId());
        noteCatalog("speed", rows, "IN_Vehicle_speed");
        noteCatalog("coolant", rows, "IN_Coolant_temperature");
        noteCatalog("fan", rows, "ETC_Fan2_output");
        fileSpeedMin = sourceExtremum(rows, "IN_Vehicle_speed", true);
        fileSpeedMax = sourceExtremum(rows, "IN_Vehicle_speed", false);
        fileCoolantMin = sourceExtremum(rows, "IN_Coolant_temperature", true);
        fileCoolantMax = sourceExtremum(rows, "IN_Coolant_temperature", false);
        fileFanMin = sourceValue(rows, "ETC_Fan2_output", true);
        fileFanMax = sourceValue(rows, "ETC_Fan2_output", false);
        note("fileSpeedRange=" + fileSpeedMin + ".." + fileSpeedMax
                + " expectedBand=23.09..85.02 tolerance=" + TOLERANCE);
        note("fileCoolantRange=" + fileCoolantMin + ".." + fileCoolantMax + " expectedBand=80..93");
        note("fileFanStoredMin=" + fileFanMin + " fileFanStoredMax=" + fileFanMax);
    }

    private static void narrowWorkspace() {
        var model = service.signalsModel();
        model.clearWorkspace();
        for (String name : List.of("IN_Vehicle_speed", "IN_Coolant_temperature", "ETC_Fan2_output")) {
            model.catalog().entries().stream()
                    .filter(entry -> name.equals(entry.signalName()))
                    .findFirst()
                    .ifPresent(entry -> model.addToWorkspace(entry.key()));
        }
        note("workspaceBeforeGraph=" + model.workspaceKeys().size());
    }

    private static void driveAnalyticsAndEngineering() throws Exception {
        fx("graphs", () -> button("Graphs & Analysis").fire());
        await("graph ready", () -> graph().selectedSignalCountForTest() > 0
                && !String.valueOf(graph().graphStatusForTest()).contains("Loading"), 180);
        note("graphStatus=" + text(() -> graph().graphStatusForTest()));
        note("timeline=" + text(() -> String.valueOf(graph().timelineGroupForTest())));
        note("range=" + text(() -> graph().rangeTextForTest()));
        if (!"UNKNOWN_ORDER".equals(text(() -> String.valueOf(graph().timelineGroupForTest())))) {
            Button unknown = buttonContaining("Unknown Order");
            if (unknown != null) {
                fx("unknown order", unknown::fire);
            }
            await("unknown order", () -> "UNKNOWN_ORDER".equals(String.valueOf(graph().timelineGroupForTest()))
                    && !String.valueOf(graph().graphStatusForTest()).contains("Loading"), 120);
        }
        note("timelineAfterUnknown=" + text(() -> String.valueOf(graph().timelineGroupForTest()))
                + " range=" + text(() -> graph().rangeTextForTest()));
        fx("engineering tab", () -> graph().selectAnalysisTabForTest(2));
        fx("run unknown", () -> button("Run Analysis").fire());
        await("unknown refusal", () -> areas().contains("Recording order unknown"), 20);
        String refusal = areasText();
        check("ENG-UNKNOWN-ORDER", refusal.contains("Layout slots are not sample timestamps"),
                clip(refusal, 400));

        fx("file focus", () -> graph().enableFileFocusForTest());
        await("file focus ready", () -> graph().fileFocusActiveForTest()
                && !String.valueOf(graph().graphStatusForTest()).contains("Loading")
                && !graph().fileFocusLabelForTest().isBlank(), 180);
        boolean focused = text(() -> String.valueOf(graph().focusRecordingNamedForTest(xenonName)
                || graph().focusRecordingNamedForTest("2020-02-07 16-22-19")
                || graph().focusRecordingNamedForTest(xenonId))).contains("true");
        if (focused) {
            await("focused file", () -> !String.valueOf(graph().graphStatusForTest()).contains("Loading"), 180);
        }
        note("fileFocus=" + text(() -> graph().fileFocusLabelForTest())
                + " id=" + text(() -> graph().focusedMeasurementIdForTest())
                + " timeline=" + text(() -> String.valueOf(graph().timelineGroupForTest()))
                + " range=" + text(() -> graph().rangeTextForTest()));
        focusedId = text(() -> graph().focusedMeasurementIdForTest());
        check("FILE-FOCUS", focused && "true".equals(text(() -> String.valueOf(graph().fileFocusActiveForTest()))),
                text(() -> graph().fileFocusLabelForTest()) + " id=" + focusedId);
        fx("three signals", () -> {
            var model = service.signalsModel();
            var keys = model.catalog().entries().stream()
                    .filter(entry -> "IN_Vehicle_speed".equals(entry.signalName())
                            || "IN_Coolant_temperature".equals(entry.signalName())
                            || "ETC_Fan2_output".equals(entry.signalName()))
                    .map(com.stellar.platform.domain.catalog.SignalCatalogEntry::key)
                    .toList();
            model.clearWorkspace();
            model.addToWorkspace(keys);
        });
        note("analysisSignals=" + text(() -> String.valueOf(service.signalsModel().workspaceKeys().size())));

        fx("engineering again", () -> graph().selectAnalysisTabForTest(2));
        String beforeStatistics = service.lastEngineeringAnalysisReport();
        fx("run statistics", () -> button("Run Analysis").fire());
        statisticsReport = waitForReport("statistics", beforeStatistics,
                report -> report.contains("IN_Vehicle_speed") && report.contains("Min"), 180);
        note("statistics=" + clip(statisticsReport, 1200));
        Double speedMin = metric(statisticsReport, "IN_Vehicle_speed", "Min");
        Double speedMax = metric(statisticsReport, "IN_Vehicle_speed", "Max");
        Double speedMean = metric(statisticsReport, "IN_Vehicle_speed", "Mean");
        Double coolantMin = metric(statisticsReport, "IN_Coolant_temperature", "Min");
        Double coolantMax = metric(statisticsReport, "IN_Coolant_temperature", "Max");
        note("observedSpeed min=" + speedMin + " max=" + speedMax + " mean=" + speedMean
                + " catalogFile=" + fileSpeedMin + ".." + fileSpeedMax + " tolerance=" + TOLERANCE);
        note("observedCoolant min=" + coolantMin + " max=" + coolantMax
                + " catalogFile=" + fileCoolantMin + ".." + fileCoolantMax);
        boolean speedInside = speedMin != null && speedMax != null
                && Double.isFinite(fileSpeedMin) && Double.isFinite(fileSpeedMax)
                && speedMin >= fileSpeedMin - TOLERANCE
                && speedMax <= fileSpeedMax + TOLERANCE
                && speedMax > speedMin
                && sampleCount(statisticsReport, "IN_Vehicle_speed") < 20_000;
        check("ENG-SPEED", speedInside, "min=" + speedMin + " max=" + speedMax
                + " file=" + fileSpeedMin + ".." + fileSpeedMax);
        boolean coolantInside = coolantMin != null && coolantMax != null
                && Double.isFinite(fileCoolantMin) && Double.isFinite(fileCoolantMax)
                && coolantMin >= fileCoolantMin - TOLERANCE
                && coolantMax <= fileCoolantMax + TOLERANCE;
        check("ENG-COOLANT", coolantInside, "min=" + coolantMin + " max=" + coolantMax
                + " file=" + fileCoolantMin + ".." + fileCoolantMax);
        String fanSection = section(statisticsReport, "ETC_Fan2_output");
        note("fanStatistics=" + clip(fanSection, 300));
        Double fanMin = metric(statisticsReport, "ETC_Fan2_output", "Min");
        check("ENG-FAN", fanSection.contains("valid 0") || (fanMin == null && fanSection.contains("—")),
                clip(fanSection, 240));

        fx("analytics tab", () -> graph().selectAnalysisTabForTest(1));
        await("analytics model", () -> graph().analyticsModelForTest().contains("IN_Vehicle_speed"), 30);
        String model = text(() -> graph().analyticsModelForTest());
        note("analyticsModel=" + clip(model, 700));
        check("ANALYTICS-CHART", model.contains("IN_Vehicle_speed") && model.contains("km/h"),
                clip(model, 300));
        note("analyticsContainsFalse=" + model.contains("FALSE"));
        check("ANALYTICS-FAN", model.contains("FALSE"),
                model.contains("FALSE") ? "categorical FALSE is in the chart model" : clip(model, 300));
        fx("create investigation", () -> button("Create Investigation").fire());
        await("investigation created", () -> String.valueOf(graph().graphStatusForTest()).contains("Created investigation"), 20);
        note("investigationStatus=" + text(() -> graph().graphStatusForTest()));

        fx("numeric signals", () -> {
            var workspace = service.signalsModel();
            var drop = workspace.workspaceKeys().stream()
                    .filter(key -> "ETC_Fan2_output".equals(key.signalName()))
                    .toList();
            drop.forEach(workspace::removeFromWorkspace);
        });
        note("numericWorkspace=" + text(() -> service.signalsModel().workspaceKeys().stream()
                .map(com.stellar.platform.domain.catalog.SignalCatalogKey::signalName)
                .reduce((a, b) -> a + "," + b).orElse("")));
        runAdvanced("FFT", AdvancedAnalyticsPane.Category.FREQUENCY, null);
        runAdvanced("MOVING-AVERAGE", AdvancedAnalyticsPane.Category.TIME, AdvancedAnalyticsType.MOVING_AVERAGE);
        runAdvanced("LAG", AdvancedAnalyticsPane.Category.COMPARISON, null);
    }

    private static void runAdvanced(String id, AdvancedAnalyticsPane.Category category, AdvancedAnalyticsType operation)
            throws Exception {
        String before = service.lastEngineeringAnalysisReport();
        fx(id, () -> {
            graph().selectAnalysisTabForTest(2);
            graph().engineeringPaneForTest().advanced().selectCategory(category);
            if (operation != null) {
                for (Node node : scene.getRoot().lookupAll(".combo-box")) {
                    if (node instanceof ComboBox<?> box && box.isVisible()
                            && box.getItems().contains(operation)) {
                        @SuppressWarnings("unchecked")
                        ComboBox<Object> values = (ComboBox<Object>) box;
                        values.setValue(operation);
                    }
                }
            }
            button("Run").fire();
        });
        String report = waitForReport(id, before, text -> text != null && !text.isBlank() && !text.equals(before), 180);
        note(id + "=" + clip(report, 800));
        boolean refused = report.contains("Recording order unknown");
        boolean empty = report.equals(before) || report.isBlank();
        boolean numeric = report.contains("COMPLETED") && !report.contains("No samples in the selected window");
        boolean lagBoundary = "LAG".equals(id)
                && (report.contains("COMPLETED") || report.contains("Resample") || report.contains("gap"));
        check(id, !refused && !empty && (numeric || lagBoundary), clip(report, 280));
    }

    private static void generateHtmlReport() throws Exception {
        Path reports = home.resolve("batch3-reports");
        Files.createDirectories(reports);
        try (var existing = Files.list(reports)) {
            for (Path old : existing.filter(Files::isRegularFile).toList()) {
                Files.deleteIfExists(old);
            }
        }
        fx("investigations", () -> shell().openRoute(
                com.stellar.platform.desktop.presentation.WorkspaceRoute.INVESTIGATIONS));
        await("report button", () -> {
            Button generate = buttonOrNull("Generate HTML Report");
            return generate != null && !generate.isDisabled();
        }, 30);
        fx("generate", () -> {
            investigations().setReportDestinationForTest("Batch3 Xenon", reports);
            button("Generate HTML Report").fire();
        });
        await("html", () -> {
            try (var listed = Files.list(reports)) {
                return listed.anyMatch(path -> {
                    try {
                        return path.getFileName().toString().endsWith(".html") && Files.size(path) > 1000;
                    } catch (java.io.IOException sizeError) {
                        return false;
                    }
                });
            } catch (java.io.IOException ex) {
                return false;
            }
        }, 120);
        note("reportStatus=" + text(() -> investigations().reportStatusForTest()));
        htmlPath = Files.list(reports)
                .filter(path -> path.getFileName().toString().endsWith(".html"))
                .max(java.util.Comparator.comparing(path -> {
                    try {
                        return Files.getLastModifiedTime(path);
                    } catch (java.io.IOException ex) {
                        return java.nio.file.attribute.FileTime.fromMillis(0);
                    }
                }))
                .orElse(null);
        check("HTML-FILE", htmlPath != null && Files.size(htmlPath) > 0,
                htmlPath == null ? "no html" : htmlPath.toString() + " bytes=" + Files.size(htmlPath));
        if (htmlPath == null) {
            return;
        }
        String html = Files.readString(htmlPath);
        note("htmlPath=" + htmlPath);
        note("htmlGenerated=" + Files.getLastModifiedTime(htmlPath));
        htmlSpeedRow = row(html, "IN_Vehicle_speed");
        String coolantRow = row(html, "IN_Coolant_temperature");
        String fanRow = row(html, "ETC_Fan2_output");
        note("htmlSpeed=" + clip(htmlSpeedRow, 400));
        note("htmlCoolant=" + clip(coolantRow, 400));
        note("htmlFan=" + clip(fanRow, 400));
        check("HTML-PROJECT", html.contains("M2-73") && html.contains(xenonName),
                "containsProject=" + html.contains("M2-73") + " containsFile=" + html.contains(xenonName));
        check("HTML-SPEED", htmlSpeedRow.contains("km/h")
                        && containsNumberNear(htmlSpeedRow, fileSpeedMin)
                        && containsNumberNear(htmlSpeedRow, fileSpeedMax),
                clip(htmlSpeedRow, 300));
        check("HTML-COOLANT", (coolantRow.contains("degC") || coolantRow.contains("°C"))
                        && containsNumberNear(coolantRow, fileCoolantMin)
                        && containsNumberNear(coolantRow, fileCoolantMax),
                clip(coolantRow, 300));
        check("HTML-FAN", fanRow.contains("FALSE") && fanRow.contains("n/a")
                        && !fanRow.contains("620330") && !fanRow.contains("620,330"),
                clip(fanRow, 300));
    }

    private static void saveAndReopen() throws Exception {
        int beforeArtifacts = service.metadataStore().loadProject(service.currentProjectId())
                .orElseThrow().processingArtifacts().size();
        String projectId = service.currentProjectId();
        String focusBefore = focusedId;
        fx("save", () -> {
            shell().openRoute(com.stellar.platform.desktop.presentation.WorkspaceRoute.HOME);
            button("Save").fire();
        });
        await("saved", () -> collect(scene.getRoot()).contains("Saved"), 30);
        closeStage();
        service.shutdown();
        service = null;
        service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(home.resolve("meta.db")));
        assertTrue(service.openSavedProject(projectId).opened());
        int afterArtifacts = service.metadataStore().loadProject(projectId).orElseThrow().processingArtifacts().size();
        int reopened = service.signalsModel().catalog().entries().size();
        note("reopenStatus=" + service.workspace().processingStatus());
        note("reopenCatalog=" + reopened);
        note("artifacts=" + beforeArtifacts + " -> " + afterArtifacts);
        check("PERSIST-CATALOG", reopened == 30 && afterArtifacts == beforeArtifacts
                        && "COMPLETED".equals(String.valueOf(service.workspace().processingStatus())),
                "catalog=" + reopened + " artifacts " + beforeArtifacts + " -> " + afterArtifacts
                        + " status=" + service.workspace().processingStatus());
        launchShell();
        fx("reopen graphs", () -> button("Graphs & Analysis").fire());
        await("reopen graph", () -> !String.valueOf(graph().graphStatusForTest()).contains("Loading")
                && graph().selectedSignalCountForTest() > 0, 180);
        note("reopenTimeline=" + text(() -> String.valueOf(graph().timelineGroupForTest()))
                + " focus=" + text(() -> graph().focusedMeasurementIdForTest())
                + " range=" + text(() -> graph().rangeTextForTest())
                + " savedFocus=" + focusBefore);
        check("PERSIST-FOCUS", focusBefore.equals(text(() -> graph().focusedMeasurementIdForTest()))
                        && "FILE_FOCUS".equals(String.valueOf(graph().timelineGroupForTest())),
                "saved=" + focusBefore + " restored=" + text(() -> graph().focusedMeasurementIdForTest())
                        + " timeline=" + text(() -> String.valueOf(graph().timelineGroupForTest())));
        if (htmlPath != null) {
            String again = row(Files.readString(htmlPath), "IN_Vehicle_speed");
            check("HTML-REOPEN", htmlSpeedRow.equals(again), clip(again, 240));
        }
        fx("reopen investigations", () -> shell().openRoute(
                com.stellar.platform.desktop.presentation.WorkspaceRoute.INVESTIGATIONS));
        await("history", () -> collect(scene.getRoot()).contains("Batch3 Xenon"), 30);
        check("PERSIST-REPORT", collect(scene.getRoot()).contains("Batch3 Xenon"),
                clip(collect(scene.getRoot()), 240));
    }

    private static void noteCatalog(String label, List<SignalCatalogMetadata> rows, String name) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (SignalCatalogMetadata row : rows) {
            if (!name.equals(row.signalName()) || row.minValue() == null || row.maxValue() == null) {
                continue;
            }
            if (!Double.isFinite(row.minValue()) || !Double.isFinite(row.maxValue())) {
                continue;
            }
            count++;
            min = Math.min(min, row.minValue());
            max = Math.max(max, row.maxValue());
        }
        note(label + "CatalogRows=" + count + " projectMin=" + min + " projectMax=" + max);
    }

    private static double sourceExtremum(List<SignalCatalogMetadata> rows, String name, boolean minimum) {
        Double value = sourceValue(rows, name, minimum);
        return value == null ? Double.NaN : value;
    }

    private static Double sourceValue(List<SignalCatalogMetadata> rows, String name, boolean minimum) {
        for (SignalCatalogMetadata row : rows) {
            if (!name.equals(row.signalName()) || !xenonId.equals(row.sourceId())) {
                continue;
            }
            return minimum ? row.minValue() : row.maxValue();
        }
        return null;
    }

    private static SignalCatalogEntry named(List<SignalCatalogEntry> catalog, String name) {
        return catalog.stream().filter(entry -> name.equals(entry.signalName())).findFirst().orElseThrow();
    }

    private static String section(String report, String signal) {
        int at = report.indexOf(signal);
        if (at < 0) {
            return "";
        }
        int end = report.indexOf("\n\n", at);
        return end < 0 ? report.substring(at) : report.substring(at, end);
    }

    private static int sampleCount(String report, String signal) {
        String block = section(report, signal);
        int at = block.indexOf("Samples");
        if (at < 0) {
            return -1;
        }
        String tail = block.substring(at + "Samples".length()).trim();
        int end = 0;
        while (end < tail.length() && (Character.isDigit(tail.charAt(end)) || tail.charAt(end) == ',')) {
            end++;
        }
        try {
            return NumberFormat.getIntegerInstance(Locale.US).parse(tail.substring(0, end)).intValue();
        } catch (Exception ex) {
            return -1;
        }
    }

    private static Double metric(String report, String signal, String label) {
        String block = section(report, signal);
        for (String line : block.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(label)) {
                continue;
            }
            String number = trimmed.substring(label.length()).trim();
            if (number.startsWith("—") || number.isBlank()) {
                return null;
            }
            try {
                return NumberFormat.getNumberInstance(Locale.US).parse(number).doubleValue();
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    private static boolean containsNumberNear(String text, double expected) {
        if (!Double.isFinite(expected)) {
            return false;
        }
        String plain = String.format(Locale.ROOT, "%.4g", expected);
        return text.contains(plain) || text.contains(String.format(Locale.ROOT, "%.2f", expected));
    }

    private static String row(String html, String signal) {
        int at = html.indexOf(">" + signal + "<");
        if (at < 0) {
            at = html.indexOf(signal);
        }
        if (at < 0) {
            return "";
        }
        int start = html.lastIndexOf("<tr", at);
        int end = html.indexOf("</tr>", at);
        if (start < 0 || end < 0) {
            return html.substring(at, Math.min(html.length(), at + 200));
        }
        return html.substring(start, end + 5).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String waitForReport(
            String label, String previous, java.util.function.Predicate<String> ready, int seconds
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        String latest = previous == null ? "" : previous;
        while (System.nanoTime() < deadline) {
            String report = service.lastEngineeringAnalysisReport();
            if (report != null) {
                latest = report;
            }
            if (ready.test(latest)) {
                return latest;
            }
            Thread.sleep(250);
        }
        note("TIMEOUT " + label);
        return latest;
    }

    private static String areasText() throws Exception {
        return text(Batch3JavaFxUiExecutionTest::areas);
    }

    private static String areas() {
        StringBuilder text = new StringBuilder();
        for (Node node : scene.getRoot().lookupAll(".text-area")) {
            if (node instanceof TextArea area && area.getText() != null && !area.getText().isBlank()) {
                text.append(area.getText()).append('\n');
            }
        }
        return text.toString();
    }

    private static GraphController graph() {
        return (GraphController) shell().controllerForTest(NavigationPage.GRAPHS);
    }

    private static InvestigationController investigations() {
        return (InvestigationController) shell().controllerForTest(NavigationPage.INVESTIGATIONS);
    }

    private static void launchShell() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                MainShellController shell = loader.getController();
                shell.init(service);
                shell.setUnsavedLeaveDecisionForTest(
                        intent -> com.stellar.platform.desktop.presentation.UnsavedLeaveChoice.DISCARD);
                scene = new Scene(root, 1440, 900);
                scene.getProperties().put("shell", shell);
                scene.getStylesheets().add(
                        MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                stage = new Stage();
                stage.setTitle(DesktopVersion.windowTitle());
                stage.setScene(scene);
                stage.show();
            } catch (Exception ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(30, TimeUnit.SECONDS), "shell did not start");
        if (error.get() != null) {
            throw new IllegalStateException("shell failed", error.get());
        }
    }

    private static MainShellController shell() {
        return (MainShellController) scene.getProperties().get("shell");
    }

    private static Button button(String text) {
        Button found = buttonOrNull(text);
        if (found == null) {
            throw new IllegalStateException("button not found: " + text);
        }
        return found;
    }

    private static Button buttonOrNull(String text) {
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText()) && button.isVisible()) {
                return button;
            }
        }
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static Button buttonContaining(String text) {
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button button && button.getText() != null && button.getText().contains(text)) {
                return button;
            }
        }
        return null;
    }

    private static String collect(Parent parent) {
        StringBuilder text = new StringBuilder();
        collect(parent, text);
        return text.toString();
    }

    private static void collect(Node node, StringBuilder text) {
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            text.append(labeled.getText()).append('\n');
        }
        if (node instanceof TextField field && field.getText() != null) {
            text.append(field.getText()).append('\n');
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, text);
            }
        }
    }

    private static String describe(SignalSamplePoint sample) {
        return "raw=" + sample.rawValue()
                + " physical=" + sample.physicalValue()
                + " textual=" + sample.textualValue()
                + " validity=" + sample.validity();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder text = new StringBuilder();
        for (byte value : hash) {
            text.append(String.format("%02X", value));
        }
        return text.toString();
    }

    private static void fx(String label, JavaFxTestSupport.FxWork work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(300, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX action timed out (" + label + ")");
        }
        if (error.get() != null) {
            throw new IllegalStateException(label, error.get());
        }
    }

    private static void await(String label, java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            AtomicBoolean ok = new AtomicBoolean();
            AtomicReference<Throwable> error = new AtomicReference<>();
            fx(label, () -> {
                try {
                    ok.set(condition.getAsBoolean());
                } catch (Throwable thrown) {
                    error.set(thrown);
                }
            });
            if (error.get() != null) {
                throw new IllegalStateException(label, error.get());
            }
            if (ok.get()) {
                return;
            }
            Thread.sleep(250);
        }
        note("TIMEOUT " + label);
    }

    private static String text(java.util.function.Supplier<String> supplier) throws Exception {
        AtomicReference<String> value = new AtomicReference<>("");
        fx("text", () -> value.set(supplier.get()));
        return value.get();
    }

    private static void check(String id, boolean pass, String actual) {
        String line = id + (pass ? " PASS " : " FAIL ") + (actual == null ? "" : actual.replace('\n', ' '));
        note(line);
        if (!pass) {
            FAILURES.add(line);
        }
    }

    private static void note(String line) {
        LINES.add(line);
        System.out.println("BATCH3 " + line);
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    private static void closeStage() throws Exception {
        if (stage == null) {
            return;
        }
        fx("close", () -> stage.close());
        stage = null;
    }

    private static void writeEvidence() throws Exception {
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, String.join(System.lineSeparator(), LINES));
    }
}

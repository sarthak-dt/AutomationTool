package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.DesktopFixtures;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Runs on the installed StellarAI runtime (bundled java.exe), not the developer JDK.
 * First invocation processes a project. Second invocation reopens it.
 */
public final class InstalledRuntimeCheck {
    private static final Path HOME = Path.of("D:/StellarAI-E2E/InstalledHome");
    private static final Path SELECTED = Path.of("D:/StellarAI-E2E/InstalledStorage");
    private static final Path WORK = Path.of("D:/StellarAI-E2E/InstalledWork");
    private static final Path STATE = Path.of(
            "D:/Reports/StellarAI_1.0.0_E2E/03_TestResults/LiveE2E/installed-runtime.properties");
    private static final Path EVIDENCE = Path.of(
            "D:/Reports/StellarAI_1.0.0_E2E/03_TestResults/LiveE2E/installed-runtime.txt");

    private InstalledRuntimeCheck() {
    }

    public static void main(String[] args) throws Exception {
        String phase = args.length == 0 ? "process" : args[0];
        List<String> failures = new ArrayList<>();
        System.setProperty("stellarai.home", HOME.toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        System.setProperty("stellarai.test.isolate", "false");
        AnalysisStorageRoots.clear();
        Files.createDirectories(WORK);
        Files.createDirectories(EVIDENCE.getParent());
        String javaHome = System.getProperty("java.home", "");
        if (!javaHome.toLowerCase(Locale.ROOT).contains("stellarai")) {
            failures.add("java.home is not the bundled runtime: " + javaHome);
        }
        String envHome = System.getenv("JAVA_HOME");
        if (envHome != null && !envHome.isBlank()) {
            failures.add("JAVA_HOME is set: " + envHome);
        }
        DesktopAppService service = null;
        try {
            service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            if ("process".equals(phase)) {
                process(service, failures, javaHome);
            } else if ("reopen".equals(phase)) {
                reopen(service, failures, javaHome);
            } else {
                failures.add("unknown phase " + phase);
            }
        } catch (Throwable ex) {
            failures.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (service != null) {
                try {
                    service.shutdown();
                } catch (RuntimeException ignored) {
                }
            }
            DesktopLogging.closeForTests();
            AnalysisStorageRoots.clear();
        }
        String header = failures.isEmpty() ? "PASS " + phase : "FAIL " + phase + " " + String.join(" | ", failures);
        Files.writeString(EVIDENCE, header + System.lineSeparator(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        System.out.println(header);
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void process(DesktopAppService service, List<String> failures, String javaHome)
            throws Exception {
        var saved = service.saveAnalysisStorage(SELECTED);
        if (!saved.valid()) {
            failures.add("configuration save: " + saved.message());
            return;
        }
        Path configured = service.analysisStorage().userSelectedRoot().orElse(null);
        if (configured == null || !configured.equals(SELECTED.toAbsolutePath().normalize())) {
            failures.add("selected storage not used: " + configured);
            return;
        }
        service.newProject("Installed Runtime " + System.currentTimeMillis());
        prepareSource(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        if (!waitIdle(service, 180)) {
            failures.add("processing did not finish");
            return;
        }
        int catalog = catalogSize(service);
        if (catalog <= 0) {
            failures.add("catalog empty after processing");
        }
        if (service.processingSelectionModel().mode() != ProcessingPageMode.EXECUTION_RESULT) {
            failures.add("mode " + service.processingSelectionModel().mode());
        }
        Path db = service.activeDatabasePath().orElse(null);
        Path controlled = SELECTED.resolve("StellarAIAnalysis").toAbsolutePath().normalize();
        if (db == null || !db.toAbsolutePath().normalize().startsWith(controlled)) {
            failures.add("stellar.db not under selected storage: " + db);
        }
        String projectId = service.currentProjectId();
        EngineeringInvestigation investigation = service.saveInvestigation(
                EngineeringInvestigation.builder(projectId).name("Installed Report").build());
        Path report = service.generateInvestigationReport(investigation, "release");
        if (report == null || !Files.isRegularFile(report)) {
            failures.add("report missing: " + report);
        } else if (!report.toAbsolutePath().normalize().startsWith(controlled)) {
            failures.add("report outside storage: " + report);
        } else {
            String html = Files.readString(report);
            String lower = html.toLowerCase(Locale.ROOT);
            if (lower.contains("apikey") || lower.contains("api_key") || lower.contains("aiza")) {
                failures.add("report exposes an API key");
            }
        }
        if (!service.saveProject().saved()) {
            failures.add("project save failed");
        }
        audit(failures);
        Properties state = new Properties();
        state.setProperty("projectId", projectId == null ? "" : projectId);
        state.setProperty("catalog", Integer.toString(catalog));
        state.setProperty("report", report == null ? "" : report.toAbsolutePath().toString());
        state.setProperty("javaHome", javaHome);
        state.setProperty("db", db == null ? "" : db.toAbsolutePath().toString());
        try (var out = Files.newOutputStream(STATE)) {
            state.store(out, "installed runtime");
        }
    }

    private static void reopen(DesktopAppService service, List<String> failures, String javaHome)
            throws Exception {
        Properties state = new Properties();
        try (var in = Files.newInputStream(STATE)) {
            state.load(in);
        }
        if (service.analysisStorage().userSelectedRoot().isEmpty()) {
            failures.add("bootstrap did not restore selected storage");
            return;
        }
        Path configured = service.analysisStorage().userSelectedRoot().orElseThrow();
        if (!configured.equals(SELECTED.toAbsolutePath().normalize())) {
            failures.add("reopen storage " + configured);
        }
        String projectId = state.getProperty("projectId");
        var opened = service.openSavedProject(projectId);
        if (!opened.opened()) {
            failures.add("reopen failed: " + String.join("; ", opened.warnings()));
            return;
        }
        int catalog = catalogSize(service);
        int expected = Integer.parseInt(state.getProperty("catalog", "0"));
        if (catalog != expected || catalog <= 0) {
            failures.add("catalog after restart " + catalog + " expected " + expected);
        }
        ProcessingWorkspacePhase phase = ProcessingWorkspacePhase.of(
                service.processingSelectionModel().mode(), service.isPlanExecutionActive());
        if (phase != ProcessingWorkspacePhase.COMPLETED) {
            failures.add("reprocess unavailable, phase " + phase);
        }
        Path report = Path.of(state.getProperty("report", ""));
        if (!Files.isRegularFile(report)) {
            failures.add("report missing after close: " + report);
        }
        if (phase == ProcessingWorkspacePhase.COMPLETED) {
            service.forceReprocessReviewedPlan(Runnable::run, () -> { });
            if (!waitIdle(service, 180)) {
                failures.add("reprocess did not finish");
            } else if (catalogSize(service) <= 0) {
                failures.add("catalog empty after reprocess");
            }
        }
        if (!javaHome.equals(state.getProperty("javaHome"))) {
            failures.add("runtime changed on restart");
        }
        audit(failures);
    }

    private static void prepareSource(DesktopAppService service) throws Exception {
        Path dbcPath = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", WORK.resolve("installed.dbc"));
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath));
        service.workspace().addFile(dbc);
        Path mf4 = DesktopFixtures.writeFinalizedMf4(WORK.resolve("installed.mf4"));
        WorkspaceFile source = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, Files.size(mf4));
        service.workspace().addFile(source);
        CanSourceInventory inventory = new CanSourceInventory(
                mf4, "installed.mf4", MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, 1, 1, true,
                List.of(new CanChannelInventory(
                        0, "Channel 0", 1, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(0, 0x100L, false), 1, 8, 8, false)))),
                List.of());
        service.sourceInventoryModel().put(source.id(), inventory);
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(
                service.activeDatabasePath().orElseThrow())) {
            store.saveInventory(service.currentProjectId(), source.id(), inventory);
        }
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 0),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), dbc.fileName(),
                dbc.definitionSourceId(), dbc.fileName(), 1, 1, 1.0));
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
    }

    private static void audit(List<String> failures) throws Exception {
        Path controlled = SELECTED.resolve("StellarAIAnalysis").toAbsolutePath().normalize();
        Path db = controlled.resolve("database").resolve("stellar.db");
        if (!Files.isRegularFile(db)) {
            failures.add("database missing at " + db);
        }
        Path pointer = HOME.resolve("config").resolve("analysis-storage.properties");
        if (!Files.isRegularFile(pointer)) {
            failures.add("bootstrap pointer missing");
        } else {
            String text = Files.readString(pointer).replace("\\\\", "\\");
            if (!text.toLowerCase(Locale.ROOT).contains("installedstorage")) {
                failures.add("pointer does not name selected storage: " + text);
            }
        }
        forbidAuthoritative(HOME, pointer, failures, 0L);
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            Path install = Path.of(local, "StellarAI");
            forbidAuthoritative(install, null, failures, 0L);
        }
        long started = Files.getLastModifiedTime(pointer).toMillis() - 60_000L;
        forbidAuthoritative(Path.of(System.getProperty("user.home"), ".stellarai"), null, failures, started);
        forbidAuthoritative(Path.of("C:/Users/DT - User/StellerAI"), null, failures, started);
        forbidAuthoritative(Path.of(System.getProperty("java.io.tmpdir"), "StellarAI"), null, failures, started);
    }

    private static void forbidAuthoritative(Path root, Path allowed, List<String> failures, long modifiedAfter)
            throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        Path normalized = root.toAbsolutePath().normalize();
        Path installApp = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "StellarAI", "app");
        Path installRuntime = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "StellarAI", "runtime");
        try (Stream<Path> walk = Files.walk(normalized)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                Path absolute = path.toAbsolutePath().normalize();
                if (allowed != null && absolute.equals(allowed.toAbsolutePath().normalize())) {
                    continue;
                }
                if (absolute.startsWith(SELECTED.resolve("StellarAIAnalysis").toAbsolutePath().normalize())) {
                    continue;
                }
                if (absolute.startsWith(installApp) || absolute.startsWith(installRuntime)) {
                    continue;
                }
                if (modifiedAfter > 0 && Files.getLastModifiedTime(absolute).toMillis() < modifiedAfter) {
                    continue;
                }
                String name = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean signalPayload = modifiedAfter == 0 && name.endsWith(".bin");
                if (name.equals("stellar.db") || name.startsWith("series-index")
                        || name.equals("stellar-desktop.log") || signalPayload) {
                    failures.add("outside configured root: " + absolute);
                }
            }
        }
    }

    private static int catalogSize(DesktopAppService service) {
        return service.signalsModel().catalog() == null ? 0 : service.signalsModel().catalog().entries().size();
    }

    private static boolean waitIdle(DesktopAppService service, int seconds) throws InterruptedException {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
        boolean sawActive = service.isPlanExecutionActive();
        while (System.nanoTime() < end) {
            if (service.isPlanExecutionActive()) {
                sawActive = true;
            } else if (sawActive) {
                return true;
            }
            Thread.sleep(50);
        }
        return !service.isPlanExecutionActive() && catalogSize(service) > 0;
    }
}

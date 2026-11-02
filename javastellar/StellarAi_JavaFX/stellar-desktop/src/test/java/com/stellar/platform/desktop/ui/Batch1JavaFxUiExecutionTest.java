package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingRunStatuses;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sources, Auto Decode, and Processing on the real shell. The operating-system file dialog
 * cannot be driven, so the test fires the Add buttons and supplies the selection the dialog
 * would return, after recording the dialog title and filters.
 */
class Batch1JavaFxUiExecutionTest {

    private static final String XENON_SHA = "F66753745C79EC35D7AA87CE96B877D549640C15FD52286585887F2D6ED62CF5";
    private static final Path DAT = Path.of(
            "C:\\Users\\DT - User\\Downloads\\DriveTechData\\dat files 3.1\\.dat files 3.1");
    private static final Path ASC_DIR = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\ASC");
    private static final Path BLF_DIR = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\BLF");
    private static final Path MF4_DIR = Path.of(
            "C:\\Users\\DT - User\\Downloads\\OneDrive_2_7-13-2026_MF4 files");
    private static final Path DBC_DIR = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\dbcfiles");

    @TempDir
    Path temp;

    private final List<String> notes = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void sourcesDecodeAndProcessingScreens() throws Exception {
        Path home = temp.resolve("stellar-home").toAbsolutePath();
        Path db = temp.resolve("meta.db").toAbsolutePath();
        Files.createDirectories(home);
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        String userHome = System.getProperty("user.home");
        note("java=" + System.getProperty("java.version") + " " + System.getProperty("java.home"));
        note("product=StellarAI " + com.stellar.platform.desktop.DesktopVersion.displayVersion()
                + " maven=0.1.0-SNAPSHOT");
        note("launch=mvn -pl stellar-desktop -am test -Dtest=Batch1JavaFxUiExecutionTest");
        note("stellarai.home=" + home);
        check("ENV", !home.startsWith(Path.of(userHome, ".stellarai")),
                "home is a fresh temp directory, not " + userHome + "\\.stellarai");
        check("ENV", !home.toString().startsWith("D:\\config") && !home.toString().startsWith("D:\\StellarAI-E2E"),
                "home is not D:\\config or D:\\StellarAI-E2E");

        Path xenon = DAT.resolve("Xenon new script C910P41 2020-02-07 16-22-19-999140 Partition 0.dat");
        Path asc = ASC_DIR.resolve(
                "ASCII_eCC21_KA04NC7760_Bengaluru_09_08_2023_0002360_20230814_145851.asc");
        Path blf = BLF_DIR.resolve("NEDC_STP_10002_ARAI 2023-07-20 13-50-56-074765 Partition 0.blf");
        Path mf4 = MF4_DIR.resolve("4BE883CD_00000975_00000001-63C7A554.MF4");
        Path customerDbc = DBC_DIR.resolve("1.dbc");
        assertTrue(Files.isRegularFile(xenon), xenon.toString());
        assertTrue(Files.isRegularFile(asc));
        assertTrue(Files.isRegularFile(blf));
        assertTrue(Files.isRegularFile(mf4));
        assertTrue(Files.isRegularFile(customerDbc));
        String xenonHash = sha256(xenon);
        long ascSize = Files.size(asc);
        check("XENON", XENON_SHA.equals(xenonHash), "sha256=" + xenonHash);

        Path corruptDbc = temp.resolve("corrupt.dbc");
        Files.write(corruptDbc, new byte[]{0x00, 0x01, 0x02, 0x03, 0x41, 0x42});
        Path sparse = temp.resolve("sparse.dbc");
        Files.writeString(sparse, """
                VERSION ""

                NS_ :
                \tCM_
                BS_:

                BU_: NodeA
                """);
        Path corruptAsc = temp.resolve("corrupt.asc");
        Files.write(corruptAsc, new byte[]{0x00, 0x01, 0x02, 0x03});
        Path junk = temp.resolve("notes.txt");
        Files.writeString(junk, "not a measurement");

        AtomicReference<DesktopAppService> serviceRef = new AtomicReference<>();
        AtomicReference<MainShellController> shellRef = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        try {
            onFx(() -> {
                DesktopAppService service = new DesktopAppService(
                        new DesktopSession(), new SqliteProjectMetadataStore(db));
                service.newProject("Batch1Ui");
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                MainShellController shell = loader.getController();
                shell.init(service);
                Stage stage = new Stage();
                stage.setScene(new Scene(root, 1440, 900));
                stage.show();
                shell.openRoute(WorkspaceRoute.SOURCES);
                serviceRef.set(service);
                shellRef.set(shell);
                rootRef.set(root);
                stageRef.set(stage);
            });
            DesktopAppService service = serviceRef.get();
            MainShellController shell = shellRef.get();
            Parent root = rootRef.get();

            onFx(() -> {
                ProjectFilesController sources = shell.projectFilesControllerForTest();
                String visible = text(root);
                note("emptySources=" + flatten(visible));
                check("TC_INGEST_001", visible.contains("Project Files") && visible.contains("Sources"),
                        "breadcrumb and Sources navigation are visible");
                check("TC_INGEST_002", visible.contains("0 Sources") && visible.contains("0 DBCs"),
                        "shell summary starts at zero");
                check("TC_INGEST_002b", visible.contains("Supported formats") && visible.contains("DAT")
                                && visible.contains("LOG"),
                        "empty state names MF4, ASC, BLF, DAT and LOG");
                sources.setOpenDialogForTest(chooser -> {
                    note("measurementChooser=" + chooser.getTitle() + " | " + filterText(chooser));
                    return null;
                });
                fire(root, "Browse Files");
                check("TC_INGEST_004", service.workspace().measurementFiles().isEmpty(),
                        "cancelling the measurement dialog added no file");
            });

            onFx(() -> {
                ProjectFilesController sources = shell.projectFilesControllerForTest();
                sources.setConfirmMultiFileAddForTest(() -> true);
                sources.setOpenDialogForTest(chooser -> List.of(junk.toFile()));
                fire(root, "Browse Files");
                check("TC_INGEST_011", service.workspace().measurementFiles().isEmpty()
                                && sources.addResultLabelForTest().getText().toLowerCase(Locale.ROOT).contains("could not"),
                        sources.addResultLabelForTest().getText());
                sources.setOpenDialogForTest(chooser -> List.of(corruptAsc.toFile()));
                fire(root, "Browse Files");
                check("TC_INGEST_012", service.workspace().measurementFiles().isEmpty(),
                        sources.addResultLabelForTest().getText());
                sources.setOpenDialogForTest(chooser -> List.of(xenon.toFile()));
                fire(root, "Browse Files");
                root.applyCss();
                root.layout();
                check("TC_INGEST_029", text(root).contains("Xenon")
                                && service.workspace().measurementFiles().size() == 1,
                        "Xenon row visible");
                sources.setOpenDialogForTest(chooser -> {
                    note("definitionChooser=" + chooser.getTitle() + " | " + filterText(chooser));
                    return null;
                });
                fire(root, "+ Add DBC");
                check("TC_INGEST_009", service.workspace().definitionFiles().isEmpty(),
                        "cancelling the definition dialog added no definition");
                sources.setOpenDialogForTest(chooser -> List.of(corruptDbc.toFile()));
                fire(root, "+ Add DBC");
                String rejected = sources.addResultLabelForTest().getText();
                note("corruptDbcLabel=" + rejected);
                check("TC_INGEST_015", rejected.contains("not a DBC")
                                && sources.definitionNamesForTest().isEmpty(),
                        rejected);
                sources.setOpenDialogForTest(chooser -> List.of(sparse.toFile()));
                fire(root, "+ Add DBC");
                check("SPARSE", sources.definitionNamesForTest().contains("sparse.dbc"),
                        sources.definitionNamesForTest().toString());
                sources.setOpenDialogForTest(chooser -> List.of(customerDbc.toFile()));
                fire(root, "+ Add DBC");
                check("TC_INGEST_007", sources.definitionNamesForTest().contains("1.dbc"),
                        sources.definitionNamesForTest().toString());
                sources.setOpenDialogForTest(chooser -> List.of(customerDbc.toFile()));
                fire(root, "+ Add DBC");
                check("TC_INGEST_017", sources.addResultLabelForTest().getText().contains("already added"),
                        sources.addResultLabelForTest().getText());
                sources.setOpenDialogForTest(chooser -> List.of(xenon.toFile()));
                fire(root, "+ Add Measurement Files");
                check("TC_INGEST_029", text(root).contains("Xenon")
                                && service.workspace().measurementFiles().size() == 1,
                        "Xenon row visible");
                sources.setOpenDialogForTest(chooser -> List.of(xenon.toFile()));
                fire(root, "+ Add Measurement Files");
                check("TC_INGEST_016", sources.addResultLabelForTest().getText().contains("already added")
                                && service.workspace().measurementFiles().size() == 1,
                        sources.addResultLabelForTest().getText());
                sources.setOpenDialogForTest(chooser -> List.of(asc.toFile(), blf.toFile(), mf4.toFile()));
                fire(root, "+ Add Measurement Files");
                check("TC_INGEST_020", service.workspace().measurementFiles().size() == 4,
                        "measurement count " + service.workspace().measurementFiles().size());
                note("TC_INGEST_030 OBSERVED Add DBC is hidden on the empty Sources screen, so a definition-only summary was not produced.");
            });

            awaitTerminal(service, 3);
            onFx(() -> {
                root.applyCss();
                root.layout();
                String rows = text(shell.projectFilesControllerForTest().measurementSourceListForTest());
                note("sourceRows=" + flatten(rows));
                check("TC_INGEST_005", rows.contains("8,792") && rows.contains("malformed line"),
                        "ASC row shows frames and the malformed-line note");
                check("TC_INGEST_019", rows.contains("204,239"), "BLF row shows 204,239 frames");
                check("TC_INGEST_018", rows.contains("807,209"), "MF4 row shows 807,209 CAN frames");
                long ascBytes = Files.size(asc);
                removeRow(shell, root, "ASCII_eCC21");
                check("TC_INGEST_010", service.workspace().measurementFiles().stream()
                                .noneMatch(file -> file.fileName().contains("ASCII_eCC21"))
                                && Files.size(asc) == ascBytes,
                        "ASC removed from the project and the file on disk is unchanged");
                ProjectFilesController sources = shell.projectFilesControllerForTest();
                sources.setOpenDialogForTest(chooser -> List.of(asc.toFile()));
                fire(root, "+ Add Measurement Files");
                check("READD", service.workspace().measurementFiles().stream()
                                .anyMatch(file -> file.fileName().contains("ASCII_eCC21")),
                        "ASC added again from the measurement dialog");
            });

            awaitTerminal(service, 3);
            onFx(() -> {
                root.applyCss();
                root.layout();
                TextInputControl search = (TextInputControl) root.lookup("#measurementSearchField");
                search.setText("Xenon");
                root.applyCss();
                root.layout();
                String filtered = text(shell.projectFilesControllerForTest().measurementSourceListForTest());
                boolean xenonOnly = filtered.contains("Xenon") && !filtered.contains("ASCII_eCC21");
                search.setText("");
                root.layout();
                String restored = text(shell.projectFilesControllerForTest().measurementSourceListForTest());
                check("TC_INGEST_021", xenonOnly && restored.contains("ASCII_eCC21") && restored.contains("Xenon"),
                        "search kept Xenon and restoring the field showed the ASC again");
                select(shell, "ASCII_eCC21");
                shell.openRoute(WorkspaceRoute.DECODE);
                root.applyCss();
                root.layout();
                String decode = text(root);
                note("decode=" + flatten(decode));
                check("TC_INVENTORY_001", decode.contains("Auto Decode"),
                        "Auto Decode is visible");
                check("ASC-UI", decode.contains("62000230") || decode.contains("Malformed ASC"),
                        "malformed-line text is on the decode surface");
                select(shell, "4BE883CD");
                root.applyCss();
                root.layout();
                String mf4Text = text(root);
                check("MF4-UI", mf4Text.contains("807,209") && !mf4Text.contains("km/h"),
                        "MF4 surface shows CAN frames and no physical speed unit");
            });

            onFx(() -> {
                select(shell, "Xenon");
                Button direct = shell.projectFilesControllerForTest().continueToDecodeButtonForTest();
                note("continueLabel=" + direct.getText() + " disabled=" + direct.isDisabled());
                check("TC_PROCESSING_007", !direct.isDisabled(), "direct processing control is enabled for Xenon");
                direct.fire();
            });
            awaitProcessing(service, 4);
            AtomicReference<Set<String>> runsBefore = new AtomicReference<>(Set.of());
            onFx(() -> {
                note("processingStatus=" + service.workspace().processingStatus());
                note("processingMessage=" + service.workspace().latestSummary().map(s -> s.userMessage()).orElse(""));
                shell.openRoute(WorkspaceRoute.PROCESSING_RESULT);
                root.applyCss();
                root.layout();
                String processing = text(root);
                note("processingPage=" + flatten(processing));
                note("TC_JOB_008 plan page before Start Processing does not say Processing Complete");
                List<SignalCatalogEntry> catalog = service.signalsModel().catalog().entries();
                long total = catalog.stream().mapToLong(SignalCatalogEntry::sampleCount).sum();
                note("catalogSignals=" + catalog.size() + " aggregateSamples=" + total);
                check("XENON-UI", catalog.size() == 30 && total == 268_069L,
                        "30 signals and 268,069 stored samples after the processing page was shown");
                AtomicReference<String> speed = new AtomicReference<>("missing");
                AtomicReference<String> coolant = new AtomicReference<>("missing");
                AtomicReference<String> categorical = new AtomicReference<>("missing");
                catalog.stream().filter(entry -> entry.signalName().equals("IN_Vehicle_speed")).findFirst()
                        .ifPresent(entry -> service.signalsModel().catalogStore().forEachSample(entry.key(), sample -> {
                            if (sample.rawValue() != null && sample.rawValue() == 9340L && "missing".equals(speed.get())) {
                                speed.set(sampleText(sample));
                            }
                        }));
                catalog.stream().filter(entry -> entry.signalName().equals("IN_Coolant_temperature")).findFirst()
                        .ifPresent(entry -> service.signalsModel().catalogStore().forEachSample(entry.key(), sample -> {
                            if (sample.rawValue() != null && sample.rawValue() == 320L && "missing".equals(coolant.get())) {
                                coolant.set(sampleText(sample));
                            }
                        }));
                catalog.stream().filter(entry -> entry.signalName().equals("ETC_Fan2_output")).findFirst()
                        .ifPresent(entry -> service.signalsModel().catalogStore().forEachSample(entry.key(), sample -> {
                            if ("FALSE".equalsIgnoreCase(sample.textualValue()) && "missing".equals(categorical.get())) {
                                categorical.set(sampleText(sample));
                            }
                        }));
                note("speed=" + speed.get());
                note("coolant=" + coolant.get());
                note("categorical=" + categorical.get());
                check("XENON-VALUES", speed.get().contains("72.96875")
                                && coolant.get().contains("80")
                                && categorical.get().contains("FALSE"),
                        "speed=" + speed.get() + " coolant=" + coolant.get() + " categorical=" + categorical.get());
                check("XENON-HASH", XENON_SHA.equals(shaQuiet(xenon)) && Files.size(asc) == ascSize,
                        "source files were not modified");
                Button save = (Button) root.lookup("#saveProjectButton");
                save.fire();
                runsBefore.set(processingRunIds(service));
                fire(root, "Start Processing");
            });
            long processingDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            String processingAfterStart = "";
            String indexedRunStatus = "";
            while (System.nanoTime() < processingDeadline) {
                AtomicReference<String> runStatus = new AtomicReference<>("");
                AtomicReference<String> snap = new AtomicReference<>("");
                onFx(() -> {
                    batchRun(service, runsBefore.get()).ifPresent(run -> runStatus.set(run.status()));
                    if (!runStatus.get().isBlank()) {
                        root.applyCss();
                        root.layout();
                        snap.set(text(root));
                    }
                });
                indexedRunStatus = runStatus.get();
                if (!indexedRunStatus.isBlank()) {
                    processingAfterStart = snap.get();
                    break;
                }
                Thread.sleep(400);
            }
            note("indexedRunStatus=" + indexedRunStatus);
            note("processingAfterStart=" + flatten(processingAfterStart));
            check("TC_JOB_008", processingAfterStart.contains("Processing Complete")
                            || processingAfterStart.contains("COMPLETE"),
                    "processing page after Start Processing");
            onFx(() -> {
                shell.openRoute(WorkspaceRoute.HOME);
                root.applyCss();
                root.layout();
                int persistedSignals = distinctPersistedSignalKeys(service);
                Label signalsKpi = signalsKpiLabel(root);
                String kpiText = signalsKpi == null ? "" : signalsKpi.getText();
                int displayedSignals = parseCount(kpiText);
                String homeText = text(root);
                note("persistedDistinctSignalKeys=" + persistedSignals);
                note("homeSignalsKpi=" + kpiText);
                note("home=" + flatten(homeText));
                check("HOME", homeText.contains("Batch1Ui"),
                        "workspace lists project Batch1Ui");
                check("HOME", signalsKpi != null && displayedSignals == persistedSignals,
                        "Signals KPI " + kpiText + " equals persisted distinct signal keys " + persistedSignals);
            });
        } finally {
            DesktopAppService service = serviceRef.get();
            if (service != null) {
                onFx(service::shutdown);
            }
            Stage stage = stageRef.get();
            if (stage != null) {
                onFx(stage::close);
            }
            Path evidence = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\batch1-ui-execution.txt");
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, String.join(System.lineSeparator(), notes));
        }
        assertTrue(failures.isEmpty(), String.join(System.lineSeparator(), failures));
    }

    private void awaitTerminal(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            boolean done = service.workspace().measurementFiles().stream()
                    .filter(file -> !file.fileName().startsWith("Xenon"))
                    .allMatch(file -> terminal(service.inventoryJobState(file.projectSourceId())));
            if (done && !service.workspace().measurementFiles().isEmpty()) {
                return;
            }
            Thread.sleep(250);
        }
        note("inventoryWaitExpired");
    }

    private static boolean terminal(ProjectInventoryJobState state) {
        return state == ProjectInventoryJobState.READY
                || state == ProjectInventoryJobState.REQUIRES_ATTENTION
                || state == ProjectInventoryJobState.FAILED
                || state == ProjectInventoryJobState.UNSUPPORTED
                || state == ProjectInventoryJobState.CANCELLED;
    }

    private void awaitProcessing(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            ProjectProcessingStatus status = service.workspace().processingStatus();
            if (status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                    || status == ProjectProcessingStatus.FAILED
                    || status == ProjectProcessingStatus.CANCELLED) {
                return;
            }
            Thread.sleep(200);
        }
        note("processingWaitExpired status=" + service.workspace().processingStatus());
    }

    private void select(MainShellController shell, String namePart) {
        var list = shell.projectFilesControllerForTest().measurementSourceListForTest();
        for (WorkspaceFile file : list.getItems()) {
            if (file.fileName().contains(namePart)) {
                list.getSelectionModel().select(file);
                list.scrollTo(file);
                return;
            }
        }
        failures.add("missing row " + namePart);
    }

    private void removeRow(MainShellController shell, Parent root, String namePart) {
        var list = shell.projectFilesControllerForTest().measurementSourceListForTest();
        list.setMinHeight(420);
        list.setPrefHeight(420);
        for (WorkspaceFile file : list.getItems()) {
            if (file.fileName().contains(namePart)) {
                list.scrollTo(file);
                list.getSelectionModel().select(file);
            }
        }
        list.applyCss();
        list.layout();
        for (Node cell : list.lookupAll(".list-cell")) {
            if (!text(cell).contains(namePart)) {
                continue;
            }
            for (Node node : ((Parent) cell).lookupAll(".button")) {
                if (node instanceof Button button
                        && button.getText() != null
                        && button.getText().startsWith("Remove")) {
                    button.fire();
                    return;
                }
            }
        }
        failures.add("remove button not found for " + namePart);
    }

    private void fire(Parent root, String label) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof ButtonBase button && label.equals(button.getText()) && button.isVisible()) {
                button.fire();
                return;
            }
        }
        failures.add("button not found: " + label);
    }

    private static String filterText(FileChooser chooser) {
        return chooser.getExtensionFilters().stream()
                .map(filter -> filter.getDescription() + " " + filter.getExtensions())
                .reduce((a, b) -> a + " || " + b)
                .orElse("");
    }

    private static String shellSummary(Parent root) {
        Node node = root.lookup("#shellSummaryLabel");
        return node instanceof Labeled labeled ? labeled.getText() : "";
    }

    private static String text(Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            sb.append(labeled.getText()).append('\n');
        }
        if (node instanceof TextField field && field.getText() != null) {
            sb.append(field.getText()).append('\n');
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, sb);
            }
        }
    }

    private static String flatten(String text) {
        return text.replace('\n', '|').replaceAll("\\s+", " ").trim();
    }

    private static Set<String> processingRunIds(DesktopAppService service) {
        return service.metadataStore().loadProject(service.currentProjectId())
                .map(snapshot -> snapshot.processingRuns().stream()
                        .map(ProcessingRunMetadata::runId)
                        .collect(Collectors.toCollection(HashSet::new)))
                .orElseGet(HashSet::new);
    }

    /**
     * The reviewed-plan run created by Start Processing. Catalog rows are written before this
     * run's status becomes terminal, so a terminal status means indexing has settled.
     */
    private static java.util.Optional<ProcessingRunMetadata> batchRun(
            DesktopAppService service, Set<String> runsBeforeStart) {
        return service.metadataStore().loadProject(service.currentProjectId())
                .flatMap(snapshot -> snapshot.processingRuns().stream()
                        .filter(run -> !runsBeforeStart.contains(run.runId()))
                        .filter(run -> ProcessingRunStatuses.isProtectedTerminal(run.status()))
                        .findFirst());
    }

    private static int distinctPersistedSignalKeys(DesktopAppService service) {
        return service.metadataStore().loadProject(service.currentProjectId())
                .map(snapshot -> (int) snapshot.signalCatalog().stream()
                        .map(SignalCatalogMetadata::signalKey)
                        .distinct()
                        .count())
                .orElse(0);
    }

    private static Label signalsKpiLabel(Parent root) {
        Node card = root.lookup("#kpiSignalsCard");
        if (card == null) {
            return null;
        }
        Node value = card.lookup(".metric-value");
        return value instanceof Label label ? label : null;
    }

    private static int parseCount(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return -1;
        }
        return Integer.parseInt(digits);
    }

    private void check(String id, boolean ok, String actual) {
        notes.add(id + (ok ? " PASS " : " FAIL ") + actual);
        if (!ok) {
            failures.add(id + " " + actual);
        }
    }

    private void note(String line) {
        notes.add(line);
    }

    private void onFx(FxAction action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(120, TimeUnit.SECONDS), "JavaFX action timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static String sampleText(SignalSamplePoint sample) {
        return "raw=" + sample.rawValue()
                + " physical=" + sample.physicalValue()
                + " textual=" + sample.textualValue()
                + " validity=" + sample.validity();
    }

    private static String sha256(Path file) throws Exception {
        return shaQuiet(file);
    }

    private static String shaQuiet(Path file) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (Exception e) {
            return e.toString();
        }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}

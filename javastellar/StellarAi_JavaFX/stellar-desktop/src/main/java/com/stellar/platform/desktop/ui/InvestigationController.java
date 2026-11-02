package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.InvestigationHealth;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.InvestigationStatus;
import com.stellar.platform.analysis.investigation.InvestigationValidity;
import com.stellar.platform.analysis.investigation.ReportAvailability;
import com.stellar.platform.analysis.investigation.report.ReportNameAllocator;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.DesktopInvestigationService;
import com.stellar.platform.desktop.presentation.ReportDestinationClaim;
import com.stellar.platform.desktop.presentation.ReportPackageSize;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.storage.metadata.InvestigationReportRow;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Project-level engineering investigation workspace. Graph rendering stays on Graphs.
 */
public final class InvestigationController implements AppServiceAware, Refreshable, DesktopPageLifecycle {

    private static final Logger LOG = Logger.getLogger(InvestigationController.class.getName());

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stellar-investigation-report");
        t.setDaemon(true);
        return t;
    });

    @FXML private SplitPane mainSplit;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> statusFilter;
    @FXML private ComboBox<String> sortBox;
    @FXML private Label listSummary;
    @FXML private ListView<EngineeringInvestigation> investigationList;
    @FXML private VBox emptyListBox;
    @FXML private Label healthLabel;
    @FXML private ListView<EngineeringEvidenceItem> evidenceList;
    @FXML private VBox emptyEvidenceBox;
    @FXML private TextField observationTitle;
    @FXML private TextArea observationNote;
    @FXML private ListView<EngineeringObservation> observationList;
    @FXML private CheckBox secProject;
    @FXML private CheckBox secSources;
    @FXML private CheckBox secSignals;
    @FXML private CheckBox secAnalysis;
    @FXML private CheckBox secDiagnostics;
    @FXML private CheckBox secFrequency;
    @FXML private CheckBox secGraphs;
    @FXML private CheckBox secWarnings;
    @FXML private CheckBox secAppendix;
    @FXML private CheckBox secPaths;
    @FXML private ComboBox<EngineeringReportTemplate> templateBox;
    @FXML private FlowPane classicSections;
    @FXML private FlowPane v2Sections;
    @FXML private CheckBox v2Cover;
    @FXML private CheckBox v2Summary;
    @FXML private CheckBox v2Overview;
    @FXML private CheckBox v2Signals;
    @FXML private CheckBox v2Graphs;
    @FXML private CheckBox v2Route;
    @FXML private CheckBox v2Observations;
    @FXML private CheckBox v2Conclusions;
    @FXML private CheckBox v2Assumptions;
    @FXML private CheckBox v2Sources;
    @FXML private CheckBox v2Appendix;
    @FXML private CheckBox v2Kpis;
    @FXML private Label kpiConfigLabel;
    @FXML private TextArea kpiConfigField;
    @FXML private TextField authorField;
    @FXML private Label reportStatus;
    @FXML private Label reportSelectionSummary;
    @FXML private Label gpsEvidenceLabel;
    @FXML private ListView<String> reportSignalList;
    @FXML private ComboBox<String> annotationPicker;
    @FXML private ListView<String> engineerEventList;
    @FXML private ListView<InvestigationReportRow> reportHistory;
    @FXML private TextField nameField;
    @FXML private TextArea descriptionField;
    @FXML private ComboBox<InvestigationStatus> statusBox;
    @FXML private Label rangeLabel;
    @FXML private TextArea conclusionField;
    @FXML private Label statusLabel;
    @FXML private Button previewReportButton;
    @FXML private Button generateReportButton;
    @FXML private Button generatePdfButton;
    @FXML private Button exportPackageButton;

    private DesktopAppService appService;
    private boolean reportBusy;
    private String reportNameOverride;
    private Path reportDirectoryOverride;
    private boolean openGeneratedReport = true;
    private MainShellController shell;
    private boolean suppress;

    @Override
    public void setAppService(DesktopAppService appService, MainShellController shell) {
        this.appService = appService;
        this.shell = shell;
    }

    @FXML
    private void initialize() {
        statusFilter.setItems(FXCollections.observableArrayList("ALL", "DRAFT", "IN_REVIEW", "COMPLETED"));
        statusFilter.getSelectionModel().select("ALL");
        sortBox.setItems(FXCollections.observableArrayList("updated", "created", "name"));
        sortBox.getSelectionModel().select("updated");
        statusBox.setItems(FXCollections.observableArrayList(InvestigationStatus.values()));
        statusFilter.setOnAction(e -> refresh());
        sortBox.setOnAction(e -> refresh());
        investigationList.setCellFactory(list -> {
            javafx.scene.control.ListCell<EngineeringInvestigation> cell = new javafx.scene.control.ListCell<>() {
                @Override
                protected void updateItem(EngineeringInvestigation item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.name() + "  ·  " + item.status());
                }
            };
            cell.setOnContextMenuRequested(event -> {
                EngineeringInvestigation item = cell.getItem();
                if (item == null || cell.isEmpty()) {
                    return;
                }
                investigationList.getSelectionModel().select(item);
                ContextMenu menu = investigationContextMenu(item);
                menu.show(cell, event.getScreenX(), event.getScreenY());
                event.consume();
            });
            return cell;
        });
        evidenceList.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(EngineeringEvidenceItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : evidenceLabel(item));
            }
        });
        observationList.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(EngineeringObservation item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : (item.title().isBlank() ? "Engineer Observation" : item.title()) + " — " + item.note());
            }
        });
        reportHistory.setCellFactory(list -> {
            javafx.scene.control.ListCell<InvestigationReportRow> cell = new javafx.scene.control.ListCell<>() {
                @Override
                protected void updateItem(InvestigationReportRow item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        return;
                    }
                    String state = reportFileMissing(item) ? "File missing" : item.status();
                    String size = "";
                    if (!reportFileMissing(item)) {
                        long bytes = ReportPackageSize.bytes(Path.of(item.destination()));
                        if (bytes >= 0) {
                            size = "  ·  " + UiFormat.bytes(bytes);
                        }
                    }
                    setText(item.name() + "  ·  " + item.format() + "  ·  " + state + size);
                }
            };
            cell.setOnContextMenuRequested(event -> {
                if (cell.getItem() != null) {
                    reportHistory.getSelectionModel().select(cell.getItem());
                }
            });
            return cell;
        });
        ContextMenu reportMenu = new ContextMenu(
                menu("Open", this::onOpenReport),
                menu("Rename", this::onRenameReport),
                menu("Delete", this::onDeleteReport));
        reportHistory.setContextMenu(reportMenu);
        investigationList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> show(b));
        for (CheckBox box : List.of(secProject, secSources, secSignals, secAnalysis, secDiagnostics,
                secFrequency, secGraphs, secWarnings, secAppendix, secPaths)) {
            box.selectedProperty().addListener((o, a, b) -> persistConfig());
        }
        templateBox.setItems(FXCollections.observableArrayList(EngineeringReportTemplate.values()));
        templateBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(EngineeringReportTemplate template) {
                return template == null ? "" : template.displayName();
            }

            @Override
            public EngineeringReportTemplate fromString(String value) {
                return EngineeringReportTemplate.CLASSIC;
            }
        });
        templateBox.getSelectionModel().select(EngineeringReportTemplate.CLASSIC);
        templateBox.valueProperty().addListener((o, a, b) -> {
            showTemplate(b);
            persistConfig();
        });
        for (CheckBox box : v2Boxes()) {
            box.selectedProperty().addListener((o, a, b) -> persistConfig());
        }
        kpiConfigField.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) {
                persistConfig();
            }
        });
        showTemplate(EngineeringReportTemplate.CLASSIC);
    }

    @Override
    public void refresh() {
        if (appService == null) {
            return;
        }
        DesktopInvestigationService svc = appService.engineeringInvestigations();
        String query = searchField == null || searchField.getText() == null ? "" : searchField.getText();
        String status = statusFilter.getValue() == null ? "ALL" : statusFilter.getValue();
        String sort = sortBox.getValue() == null ? "updated" : sortBox.getValue();
        List<EngineeringInvestigation> items = svc.search(
                appService.metadataStore(), appService.currentProjectId(), query, status, sort);
        String selectedId = Optional.ofNullable(investigationList.getSelectionModel().getSelectedItem())
                .map(EngineeringInvestigation::investigationId)
                .orElse(svc.activeInvestigationId());
        investigationList.setItems(FXCollections.observableArrayList(items));
        boolean empty = items.isEmpty();
        emptyListBox.setVisible(empty);
        emptyListBox.setManaged(empty);
        investigationList.setVisible(!empty);
        investigationList.setManaged(!empty);
        long drafts = items.stream().filter(i -> i.status() == InvestigationStatus.DRAFT).count();
        listSummary.setText(items.size() + " investigations · " + drafts + " draft");
        if (!selectedId.isBlank()) {
            items.stream().filter(i -> selectedId.equals(i.investigationId())).findFirst()
                    .ifPresent(found -> investigationList.getSelectionModel().select(found));
        } else if (!items.isEmpty()) {
            investigationList.getSelectionModel().selectFirst();
        }
        show(investigationList.getSelectionModel().getSelectedItem());
    }

    @Override
    public void onShow() {
        refresh();
        if (investigationList != null && investigationList.getScene() != null) {
            investigationList.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                    this::onNew);
        }
    }

    @Override
    public void onHide() {
        if (investigationList != null && investigationList.getScene() != null) {
            investigationList.getScene().getAccelerators().remove(
                    new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        }
    }

    @Override
    public void dispose() {
        worker.shutdownNow();
    }

    @FXML
    private void onSearchChanged() {
        refresh();
    }

    @FXML
    private void onNew() {
        if (appService == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog("New investigation");
        dialog.setTitle("New Investigation");
        dialog.setHeaderText("Name this investigation");
        dialog.setContentText("Name");
        dialog.showAndWait().ifPresent(name -> {
            EngineeringInvestigation created = appService.createInvestigation(name, "", "FULL");
            status("Created " + created.name());
            refresh();
            select(created.investigationId());
        });
    }

    @FXML
    private void onFromGraph() {
        if (appService == null) {
            return;
        }
        EngineeringInvestigation created = appService.createInvestigationFromCurrentGraph();
        status("Captured current graph into " + created.name());
        refresh();
        select(created.investigationId());
    }

    @FXML
    private void onDuplicate() {
        EngineeringInvestigation current = selected();
        if (current == null) {
            return;
        }
        EngineeringInvestigation copy = appService.duplicateInvestigation(current);
        refresh();
        select(copy.investigationId());
    }

    @FXML
    private void onDelete() {
        EngineeringInvestigation current = selected();
        if (current == null) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete investigation \"" + current.name() + "\"? Project sources and shared analysis stay intact.",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("Delete investigation");
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        appService.deleteInvestigation(current);
        refresh();
    }

    @FXML
    private void onOpenGraph() {
        EngineeringInvestigation current = selected();
        if (current == null || shell == null) {
            return;
        }
        appService.applyInvestigationGraph(current);
        shell.openRoute(WorkspaceRoute.GRAPHS);
    }

    @FXML
    private void onSetRangeFromGraph() {
        EngineeringInvestigation current = selected();
        if (current == null) {
            return;
        }
        appService.setInvestigationRangeFromGraph(current);
        refresh();
        select(current.investigationId());
    }

    @FXML
    private void onSaveDetails() {
        EngineeringInvestigation current = selected();
        if (current == null) {
            return;
        }
        EngineeringInvestigation saved = appService.saveInvestigation(current.toBuilder()
                .name(nameField.getText())
                .description(descriptionField.getText())
                .status(statusBox.getValue())
                .conclusion(conclusionField.getText())
                .reportConfig(configFromUi())
                .build());
        status("Investigation saved.");
        refresh();
        select(saved.investigationId());
    }

    @FXML
    private void onMoveUp() {
        moveEvidence(-1);
    }

    @FXML
    private void onMoveDown() {
        moveEvidence(1);
    }

    @FXML
    private void onRemoveEvidence() {
        EngineeringInvestigation current = selected();
        EngineeringEvidenceItem item = evidenceList.getSelectionModel().getSelectedItem();
        if (current == null || item == null) {
            return;
        }
        List<EngineeringEvidenceItem> next = new ArrayList<>();
        int order = 0;
        for (EngineeringEvidenceItem e : current.evidence()) {
            if (!e.evidenceId().equals(item.evidenceId())) {
                next.add(e.withOrder(order++));
            }
        }
        appService.saveInvestigation(current.toBuilder().evidence(next).build());
        refresh();
        select(current.investigationId());
    }

    @FXML
    private void onAddObservation() {
        EngineeringInvestigation current = selected();
        if (current == null) {
            return;
        }
        String note = observationNote.getText() == null ? "" : observationNote.getText().trim();
        if (note.isBlank()) {
            status("Enter an engineer observation first.");
            return;
        }
        List<EngineeringObservation> notes = new ArrayList<>(current.observations());
        notes.add(new EngineeringObservation(null, current.investigationId(),
                current.rangeStartNanos(), current.rangeEndNanos(),
                observationTitle.getText(), note, Instant.now(), Instant.now()));
        appService.saveInvestigation(current.toBuilder().observations(notes).build());
        observationNote.clear();
        refresh();
        select(current.investigationId());
    }

    @FXML
    private void onPreviewHtml() {
        EngineeringInvestigation current = selected();
        if (!ReportAvailability.canGenerate(current)) {
            reportStatus.setText("Create and save an investigation before generating a report.");
            return;
        }
        EngineeringInvestigation withConfig = appService.saveInvestigation(
                current.toBuilder().reportConfig(configFromUi()).build());
        setReportBusy(true);
        reportStatus.setText("Preparing preview…");
        String author = authorField.getText();
        worker.submit(() -> {
            try {
                Path directory = Files.createTempDirectory("stellar-report-preview");
                Path html = appService.previewInvestigationHtml(
                        withConfig, author, directory.resolve("preview.html"));
                Platform.runLater(() -> {
                    setReportBusy(false);
                    reportStatus.setText("Preview opened.");
                    if (openGeneratedReport) {
                        openPath(html);
                    }
                });
            } catch (AnalysisCancelledException cancelled) {
                Platform.runLater(() -> {
                    setReportBusy(false);
                    reportStatus.setText("Report generation cancelled.");
                });
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, "Report preview could not be opened", ex);
                Platform.runLater(() -> {
                    setReportBusy(false);
                    reportStatus.setText("Report could not be generated.");
                    showError("Report could not be generated",
                            "Report could not be generated. " + human(ex), null);
                });
            }
        });
    }

    @FXML
    private void onGenerateReport() {
        generateReport(false);
    }

    @FXML
    private void onGeneratePdfReport() {
        generateReport(true);
    }

    private void generateReport(boolean pdf) {
        EngineeringInvestigation current = selected();
        if (!ReportAvailability.canGenerate(current)) {
            reportStatus.setText("Create and save an investigation before generating a report.");
            return;
        }
        String requested = reportNameOverride != null
                ? ReportNameAllocator.requireDisplayName(reportNameOverride)
                : promptReportName(pdf ? "Generate PDF Report" : "Generate HTML Report",
                "Report name", current.name(), null);
        if (requested == null) {
            return;
        }
        Path chosenDirectory = reportDirectoryOverride;
        if (chosenDirectory == null) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose report folder");
            java.io.File folder = chooser.showDialog(
                    investigationList.getScene() == null ? null : investigationList.getScene().getWindow());
            if (folder == null) {
                return;
            }
            chosenDirectory = folder.toPath();
        }
        Path directory = chosenDirectory;
        EngineeringInvestigation withConfig = appService.saveInvestigation(
                current.toBuilder().reportConfig(configFromUi()).build());
        setReportBusy(true);
        reportStatus.setText("Generating report…");
        String author = authorField.getText();
        String projectId = current.projectId();
        worker.submit(() -> {
            Path destination = null;
            try {
                destination = pdf
                        ? ReportDestinationClaim.claimPdf(directory, requested, reportNamesIn(projectId, directory))
                        : ReportDestinationClaim.claim(directory, requested, reportNamesIn(projectId, directory));
                Path html = pdf
                        ? appService.generateInvestigationPdfReport(withConfig, author, destination)
                        : appService.generateInvestigationReport(withConfig, author, destination);
                String savedAs = pdf
                        ? html.getFileName().toString().replaceFirst("(?i)\\.pdf$", "")
                        : ReportNameAllocator.stemOf(html.getFileName().toString());
                Platform.runLater(() -> {
                    setReportBusy(false);
                    reportStatus.setText("Report generated.");
                    refresh();
                    select(withConfig.investigationId());
                    status("Report written: " + savedAs);
                    if (openGeneratedReport) {
                        openPath(html);
                    }
                });
            } catch (AnalysisCancelledException cancelled) {
                releaseEmptyClaim(destination);
                Platform.runLater(() -> {
                    setReportBusy(false);
                    reportStatus.setText("Report generation cancelled.");
                    refresh();
                });
            } catch (Exception ex) {
                releaseEmptyClaim(destination);
                LOG.log(Level.SEVERE, "Report could not be generated", ex);
                Platform.runLater(() -> {
                    setReportBusy(false);
                    reportStatus.setText("Report could not be generated.");
                    showError("Report could not be generated",
                            "Report could not be generated. " + human(ex), null);
                });
            }
        });
    }

    @FXML
    private void onExportPackage() {
        EngineeringInvestigation current = selected();
        if (current == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Investigation Package");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP archive", "*.zip"));
        chooser.setInitialFileName(safeName(current.name()) + ".zip");
        java.io.File file = chooser.showSaveDialog(
                investigationList.getScene() == null ? null : investigationList.getScene().getWindow());
        if (file == null) {
            return;
        }
        String author = authorField.getText();
        worker.submit(() -> {
            try {
                Path zip = appService.exportInvestigationPackage(current, file.toPath(), author);
                Platform.runLater(() -> status("Package exported: " + zip.getFileName()));
            } catch (Exception ex) {
                Platform.runLater(() -> showError("Export destination unavailable", human(ex), ex));
            }
        });
    }

    @FXML
    private void onOpenReport() {
        InvestigationReportRow row = reportHistory.getSelectionModel().getSelectedItem();
        if (row == null || row.destination().isBlank()) {
            return;
        }
        if (reportFileMissing(row)) {
            reportStatus.setText("File missing");
            return;
        }
        openPath(Path.of(row.destination()));
    }

    @FXML
    private void onRenameReport() {
        InvestigationReportRow row = reportHistory.getSelectionModel().getSelectedItem();
        EngineeringInvestigation current = selected();
        if (row == null || current == null) {
            return;
        }
        if (reportFileMissing(row)) {
            reportStatus.setText("File missing");
            return;
        }
        String requested = promptReportName("Rename Report", "Report name", row.name(), row);
        if (requested == null) {
            return;
        }
        try {
            Path directory = Path.of(row.destination()).getParent();
            appService.renameInvestigationReport(row, requested, reportNamesIn(current.projectId(), directory));
            reportStatus.setText("Report renamed.");
            refresh();
            select(current.investigationId());
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Report could not be renamed", ex);
            reportStatus.setText("File missing".equals(ex.getMessage()) ? "File missing" : "Report could not be renamed.");
            showError("Report could not be renamed", human(ex), ex);
        }
    }

    @FXML
    private void onRevealReport() {
        InvestigationReportRow row = reportHistory.getSelectionModel().getSelectedItem();
        if (row == null || row.destination().isBlank()) {
            return;
        }
        try {
            Path path = Path.of(row.destination());
            Path reveal = Files.isDirectory(path) ? path : path.getParent();
            if (reveal != null && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(reveal.toFile());
            }
        } catch (Exception ex) {
            showError("Cannot reveal report", human(ex), ex);
        }
    }

    @FXML
    private void onDeleteReport() {
        InvestigationReportRow row = reportHistory.getSelectionModel().getSelectedItem();
        EngineeringInvestigation current = selected();
        if (row == null || current == null) {
            return;
        }
        String label = row.name() == null || row.name().isBlank() ? "this report" : row.name();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + label + "\"?\n\nThis will permanently delete the generated report.",
                new ButtonType("Delete", javafx.scene.control.ButtonBar.ButtonData.OK_DONE),
                ButtonType.CANCEL);
        alert.setTitle("Delete Report");
        alert.setHeaderText("Delete Report");
        if (alert.showAndWait().orElse(ButtonType.CANCEL).getButtonData()
                != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
            return;
        }
        try {
            appService.deleteInvestigationReport(row);
            reportHistory.getSelectionModel().clearSelection();
            reportStatus.setText("Report deleted.");
            refresh();
            select(current.investigationId());
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Report could not be deleted", ex);
            reportStatus.setText("The report could not be deleted. It may be open in another program.");
            showError("Report could not be deleted",
                    "The report could not be deleted. It may be open in another program.", ex);
        }
    }

    private void moveEvidence(int delta) {
        EngineeringInvestigation current = selected();
        EngineeringEvidenceItem item = evidenceList.getSelectionModel().getSelectedItem();
        if (current == null || item == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (EngineeringEvidenceItem e : current.evidence()) {
            ids.add(e.evidenceId());
        }
        int idx = ids.indexOf(item.evidenceId());
        int target = idx + delta;
        if (idx < 0 || target < 0 || target >= ids.size()) {
            return;
        }
        java.util.Collections.swap(ids, idx, target);
        appService.reorderInvestigationEvidence(current, ids);
        refresh();
        select(current.investigationId());
    }

    private void show(EngineeringInvestigation inv) {
        if (suppress) {
            return;
        }
        suppress = true;
        try {
            updateReportActions(inv);
            if (inv == null) {
                nameField.clear();
                descriptionField.clear();
                conclusionField.clear();
                if (observationTitle != null) {
                    observationTitle.clear();
                }
                if (observationNote != null) {
                    observationNote.clear();
                }
                evidenceList.setItems(FXCollections.observableArrayList());
                observationList.setItems(FXCollections.observableArrayList());
                reportHistory.setItems(FXCollections.observableArrayList());
                emptyEvidenceBox.setVisible(true);
                emptyEvidenceBox.setManaged(true);
                healthLabel.setText("");
                rangeLabel.setText("Range: —");
                status("");
                if (reportStatus != null) {
                    reportStatus.setText("");
                }
                showReportSelection(null);
                return;
            }
            appService.engineeringInvestigations().setActiveInvestigationId(inv.investigationId());
            nameField.setText(inv.name());
            descriptionField.setText(inv.description());
            statusBox.getSelectionModel().select(inv.status());
            conclusionField.setText(inv.conclusion());
            rangeLabel.setText("Investigation range: "
                    + (inv.rangeStartNanos() == null ? "not set"
                    : com.stellar.platform.analysis.investigation.EngineeringFormats.range(
                    inv.rangeStartNanos(), inv.rangeEndNanos())));
            InvestigationHealth health = inv.health();
            String explain = String.join(" ", InvestigationValidity.explanations(inv.evidence()));
            healthLabel.setText("Health: " + health + (explain.isBlank() ? "" : " — " + explain));
            evidenceList.setItems(FXCollections.observableArrayList(inv.evidence()));
            boolean noEvidence = inv.evidence().isEmpty();
            emptyEvidenceBox.setVisible(noEvidence);
            emptyEvidenceBox.setManaged(noEvidence);
            observationList.setItems(FXCollections.observableArrayList(inv.observations()));
            applyConfig(inv.reportConfig());
            showReportSelection(inv);
            if (appService.metadataStore() != null) {
                reportHistory.setItems(FXCollections.observableArrayList(
                        appService.metadataStore().listInvestigationReports(inv.investigationId())));
            }
        } finally {
            suppress = false;
        }
    }

    private void showReportSelection(EngineeringInvestigation inv) {
        if (reportSignalList != null) {
            reportSignalList.getItems().clear();
        }
        if (annotationPicker != null) {
            annotationPicker.getItems().clear();
        }
        if (inv == null || appService == null) {
            if (reportSelectionSummary != null) {
                reportSelectionSummary.setText("");
            }
            return;
        }
        int staleSignals = 0;
        if (reportSignalList != null) {
            for (String blob : inv.signalKeyBlobs()) {
                com.stellar.platform.domain.catalog.SignalCatalogKey key =
                        com.stellar.platform.desktop.presentation.GraphInvestigationCodec.decodeKey(blob);
                if (key == null) {
                    staleSignals++;
                    reportSignalList.getItems().add((blob == null || blob.isBlank() ? "signal" : blob)
                            + " · signal mapping stale");
                } else {
                    reportSignalList.getItems().add(key.signalName());
                }
            }
        }
        int snapshots = 0;
        int events = 0;
        int stale = 0;
        int missingSnapshots = 0;
        boolean route = false;
        for (EngineeringEvidenceItem item : inv.evidence()) {
            if (item.stale()) {
                stale++;
            }
            if (item.kind() == com.stellar.platform.analysis.investigation.EvidenceKind.GRAPH_SNAPSHOT) {
                snapshots++;
                java.nio.file.Path png = com.stellar.platform.analysis.investigation.InvestigationStoragePaths.snapshot(
                        com.stellar.platform.storage.analysis.AnalysisStorageRoots.projectArtifactsRoot(inv.projectId()),
                        inv.investigationId(), item.referenceId());
                if (!java.nio.file.Files.isRegularFile(png)) {
                    missingSnapshots++;
                }
            } else if (item.kind() == com.stellar.platform.analysis.investigation.EvidenceKind.ANNOTATION) {
                events++;
            } else if (item.kind() == com.stellar.platform.analysis.investigation.EvidenceKind.ROUTE_MAP) {
                route = true;
            }
        }
        if (annotationPicker != null && appService.metadataStore() != null) {
            for (var annotation : appService.metadataStore().listGraphAnnotations(inv.projectId())) {
                String label = annotation.label() == null || annotation.label().isBlank()
                        ? annotation.annotationId() : annotation.label();
                annotationPicker.getItems().add(label + "\t" + annotation.annotationId());
            }
        }
        int gpsMatched = 0;
        int gpsRequested = 0;
        int gpsUnavailable = 0;
        int gpsStale = 0;
        int gpsNotRequested = 0;
        if (engineerEventList != null && appService.metadataStore() != null) {
            engineerEventList.getItems().clear();
            for (var bookmark : appService.metadataStore().listBookmarks(inv.projectId())) {
                if (!com.stellar.platform.desktop.presentation.InvestigationEventCodec.isManual(bookmark.eventType())) {
                    continue;
                }
                var gps = com.stellar.platform.desktop.presentation.GraphEventGps.read(bookmark);
                if (com.stellar.platform.desktop.presentation.GraphEventGps.MATCHED.equals(gps.status())) {
                    gpsMatched++;
                } else if (com.stellar.platform.desktop.presentation.GraphEventGps.REQUESTED.equals(gps.status())) {
                    gpsRequested++;
                } else if (com.stellar.platform.desktop.presentation.GraphEventGps.STALE.equals(gps.status())) {
                    gpsStale++;
                } else if (com.stellar.platform.desktop.presentation.GraphEventGps.NO_SAMPLE.equals(gps.status())
                        || com.stellar.platform.desktop.presentation.GraphEventGps.INCOMPATIBLE.equals(gps.status())) {
                    gpsUnavailable++;
                } else {
                    gpsNotRequested++;
                }
                String title = com.stellar.platform.desktop.presentation.InvestigationEventCodec.titleOf(bookmark.observation());
                String mark = com.stellar.platform.analysis.investigation.EngineerEventInclusion.linked(
                        inv.evidence(), bookmark.bookmarkId()) ? "included" : "saved";
                String when = com.stellar.platform.analysis.investigation.EngineeringFormats.seconds(
                        bookmark.timestampNanos());
                Long end = com.stellar.platform.desktop.presentation.EngineerEventDocuments.endNanos(bookmark);
                if (end != null && end >= bookmark.timestampNanos()) {
                    when = when + "–" + com.stellar.platform.analysis.investigation.EngineeringFormats
                            .seconds(end);
                }
                engineerEventList.getItems().add(title + " · " + bookmark.eventType() + " · " + when
                        + " · " + mark + " · " + gps.status() + "\t" + bookmark.bookmarkId());
            }
        }
        if (gpsEvidenceLabel != null) {
            gpsEvidenceLabel.setText((route
                    ? "A saved GPS route is attached. No coordinate is copied from another recording. "
                    : "No GPS route is attached. No route image will be created. ")
                    + "Event GPS: " + gpsMatched + " matched, " + gpsRequested + " requested, "
                    + gpsUnavailable + " unavailable, " + gpsStale + " stale, "
                    + gpsNotRequested + " not requested.");
        }
        if (reportSelectionSummary != null) {
            StringBuilder gaps = new StringBuilder();
            InvestigationReportConfig cfg = inv.reportConfig();
            if (cfg.shows(com.stellar.platform.analysis.investigation.EngineeringReportSection.SIGNAL_ANALYSIS)
                    && inv.signalKeyBlobs().isEmpty()) {
                gaps.append("Signal Analysis");
            }
            if (cfg.shows(com.stellar.platform.analysis.investigation.EngineeringReportSection.GRAPH_EVIDENCE)
                    && snapshots == 0) {
                if (!gaps.isEmpty()) {
                    gaps.append(", ");
                }
                gaps.append("Graph Evidence");
            }
            if (cfg.shows(com.stellar.platform.analysis.investigation.EngineeringReportSection.GPS_ROUTE)
                    && !route) {
                if (!gaps.isEmpty()) {
                    gaps.append(", ");
                }
                gaps.append("GPS Route Evidence");
            }
            if (cfg.shows(com.stellar.platform.analysis.investigation.EngineeringReportSection.OBSERVATIONS)
                    && inv.observations().isEmpty()) {
                if (!gaps.isEmpty()) {
                    gaps.append(", ");
                }
                gaps.append("Observations");
            }
            reportSelectionSummary.setText("Included: " + inv.signalKeyBlobs().size() + " signals, "
                    + snapshots + " snapshots, " + events + " events, route "
                    + (route ? "attached" : "not attached")
                    + (stale == 0 ? "" : ", " + stale + " stale")
                    + (staleSignals == 0 ? "" : ", " + staleSignals + " signal mapping stale")
                    + (missingSnapshots == 0 ? "" : ", " + missingSnapshots + " snapshot file missing")
                    + (gaps.isEmpty() ? "" : ". No selected evidence: " + gaps));
        }
    }

    @FXML
    private void onAddReportSignal() {
        EngineeringInvestigation current = selected();
        if (current == null || appService == null || appService.metadataStore() == null) {
            return;
        }
        var catalog = appService.metadataStore().listSignalCatalog(current.projectId());
        if (catalog.isEmpty()) {
            status("The project catalog has no signals.");
            return;
        }
        javafx.scene.control.ListView<com.stellar.platform.storage.metadata.SignalCatalogMetadata> choices =
                new javafx.scene.control.ListView<>();
        choices.getItems().addAll(catalog);
        choices.setPrefHeight(240);
        choices.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(com.stellar.platform.storage.metadata.SignalCatalogMetadata item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.signalName()
                        + (item.unit() == null || item.unit().isBlank() ? "" : "  " + item.unit())
                        + "  " + item.sampleCount() + " samples");
            }
        });
        javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Add report signal");
        dialog.setHeaderText("The Signal Workspace selection is left unchanged.");
        dialog.getDialogPane().setContent(choices);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.showAndWait().ifPresent(button -> {
            var chosen = choices.getSelectionModel().getSelectedItem();
            if (button != ButtonType.OK || chosen == null) {
                return;
            }
            List<String> blobs = new ArrayList<>(current.signalKeyBlobs());
            if (!blobs.contains(chosen.signalKey())) {
                blobs.add(chosen.signalKey());
            }
            appService.saveInvestigation(current.toBuilder().signalKeyBlobs(blobs).build());
            refresh();
            select(current.investigationId());
        });
    }

    @FXML
    private void onRemoveReportSignal() {
        moveReportSignal(Integer.MIN_VALUE);
    }

    @FXML
    private void onMoveReportSignalUp() {
        moveReportSignal(-1);
    }

    @FXML
    private void onMoveReportSignalDown() {
        moveReportSignal(1);
    }

    private void moveReportSignal(int delta) {
        EngineeringInvestigation current = selected();
        if (current == null || reportSignalList == null || appService == null) {
            return;
        }
        int index = reportSignalList.getSelectionModel().getSelectedIndex();
        List<String> blobs = new ArrayList<>(current.signalKeyBlobs());
        if (index < 0 || index >= blobs.size()) {
            return;
        }
        if (delta == Integer.MIN_VALUE) {
            blobs.remove(index);
        } else {
            int target = index + delta;
            if (target < 0 || target >= blobs.size()) {
                return;
            }
            String moved = blobs.remove(index);
            blobs.add(target, moved);
        }
        appService.saveInvestigation(current.toBuilder().signalKeyBlobs(blobs).build());
        refresh();
        select(current.investigationId());
    }

    @FXML
    private void onIncludeEvent() {
        EngineeringInvestigation current = selected();
        if (current == null || appService == null || annotationPicker == null
                || annotationPicker.getValue() == null) {
            return;
        }
        String value = annotationPicker.getValue();
        int tab = value.lastIndexOf('\t');
        String id = tab < 0 ? value : value.substring(tab + 1);
        String label = tab < 0 ? value : value.substring(0, tab);
        appService.attachAnnotationToInvestigation(id, label);
        refresh();
        select(current.investigationId());
    }

    @FXML
    private void onDefineEvent() {
        editEngineerEvent(null);
    }

    @FXML
    private void onEditEngineerEvent() {
        editEngineerEvent(selectedEngineerEventId());
    }

    @FXML
    private void onIncludeEngineerEvent() {
        EngineeringInvestigation current = selected();
        String eventId = selectedEngineerEventId();
        if (current == null || appService == null || eventId.isBlank()) {
            return;
        }
        appService.includeEngineerEvent(eventId);
        refresh();
        select(current.investigationId());
    }

    @FXML
    private void onEvaluateEvent() {
        String eventId = selectedEngineerEventId();
        if (appService == null || eventId.isBlank()) {
            return;
        }
        var bookmark = appService.findEngineeringBookmark(eventId);
        if (bookmark.isEmpty()) {
            return;
        }
        var event = com.stellar.platform.desktop.presentation.EngineerEventDocuments.decode(bookmark.get());
        if (event == null) {
            return;
        }
        EvaluationRuleEditor editor = new EvaluationRuleEditor(event, appService.listEvaluationRules());
        var choice = editor.dialog().showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        boolean run = "Run evaluation".equals(choice.get().getText());
        var saved = appService.saveEvaluationRule(editor.draft());
        if (saved == null) {
            return;
        }
        if (run) {
            var result = appService.evaluateAndStore(event, saved, null, true, editor.includeSelected());
            if (result != null && reportStatus != null) {
                reportStatus.setText(result.outcome().label()
                        + (result.reason().isBlank() ? "" : " — " + result.reason()));
            }
        }
        refresh();
        select(selected() == null ? "" : selected().investigationId());
    }

    @FXML
    private void onExcludeEngineerEvent() {
        EngineeringInvestigation current = selected();
        String eventId = selectedEngineerEventId();
        if (current == null || appService == null || eventId.isBlank()) {
            return;
        }
        appService.excludeEngineerEvent(eventId);
        refresh();
        select(current.investigationId());
    }

    private void editEngineerEvent(String eventId) {
        if (appService == null || selected() == null) {
            return;
        }
        String investigationId = selected().investigationId();
        com.stellar.platform.analysis.investigation.EngineerEventDefinition initial = null;
        if (eventId != null && !eventId.isBlank()) {
            var bookmark = appService.findEngineeringBookmark(eventId);
            if (bookmark.isEmpty()) {
                return;
            }
            initial = com.stellar.platform.desktop.presentation.EngineerEventDocuments.decode(bookmark.get());
            if (initial == null) {
                initial = new com.stellar.platform.analysis.investigation.EngineerEventDefinition(
                        bookmark.get().bookmarkId(),
                        com.stellar.platform.desktop.presentation.InvestigationEventCodec.titleOf(bookmark.get().observation()),
                        bookmark.get().eventType(),
                        bookmark.get().sourceId(),
                        "",
                        bookmark.get().timestampNanos(),
                        null,
                        bookmark.get().signalName().isBlank() ? List.of() : List.of(bookmark.get().signalName()),
                        com.stellar.platform.desktop.presentation.InvestigationEventCodec.noteOf(bookmark.get().observation()),
                        "", "", List.of(), false, "Draft", "", "", "");
            }
        }
        var drafted = showEventEditor(initial);
        if (drafted == null) {
            return;
        }
        String saved = appService.saveEngineerEvent(drafted);
        if (saved.isBlank()) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "The event was not saved.", ButtonType.OK);
            alert.setHeaderText("Check the event timestamps.");
            alert.showAndWait();
            return;
        }
        refresh();
        select(investigationId);
    }

    private String selectedEngineerEventId() {
        if (engineerEventList == null) {
            return "";
        }
        String value = engineerEventList.getSelectionModel().getSelectedItem();
        if (value == null) {
            return "";
        }
        int tab = value.lastIndexOf('\t');
        return tab < 0 ? "" : value.substring(tab + 1);
    }

    private com.stellar.platform.analysis.investigation.EngineerEventDefinition showEventEditor(
            com.stellar.platform.analysis.investigation.EngineerEventDefinition initial
    ) {
        Dialog<ButtonType> dialog = buildEventDialog(initial);
        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return null;
        }
        EventDraft draft = commitEventDialog(dialog);
        if (draft.error != null) {
            Alert alert = new Alert(Alert.AlertType.ERROR, draft.error, ButtonType.OK);
            alert.setHeaderText(draft.error.startsWith("Enter the original")
                    ? "The timestamp could not be read." : "The event was not saved.");
            alert.showAndWait();
            return null;
        }
        return draft.event;
    }

    private Dialog<ButtonType> buildEventDialog(
            com.stellar.platform.analysis.investigation.EngineerEventDefinition initial
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(initial == null || initial.id().isBlank() ? "Define Event" : "Edit Event");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ComboBox<String> category = new ComboBox<>();
        category.getItems().addAll(com.stellar.platform.desktop.presentation.InvestigationEventCodec.CATEGORIES);
        TextField name = new TextField();
        name.setPromptText("Event name");
        ComboBox<String> source = new ComboBox<>();
        if (appService.workspace() != null) {
            for (var file : appService.workspace().measurementFiles()) {
                source.getItems().add(file.fileName() + "\t" + file.id());
            }
        }
        ComboBox<String> shape = new ComboBox<>();
        shape.getItems().addAll("Point", "Time window");
        TextField start = new TextField();
        start.setPromptText("Seconds with a decimal, or the original nanosecond count");
        TextField end = new TextField();
        end.setPromptText("Window end. Leave blank for a point.");
        TextField signals = new TextField();
        signals.setPromptText("Signal names, separated by commas");
        ComboBox<String> snapshot = new ComboBox<>();
        snapshot.getItems().add("None");
        EngineeringInvestigation current = selected();
        if (current != null) {
            for (EngineeringEvidenceItem item : current.evidence()) {
                if (item.kind() == com.stellar.platform.analysis.investigation.EvidenceKind.GRAPH_SNAPSHOT) {
                    String label = item.caption().isBlank() ? item.referenceId() : item.caption();
                    snapshot.getItems().add(label + "\t" + item.referenceId());
                }
            }
        }
        snapshot.getSelectionModel().selectFirst();
        CheckBox gps = new CheckBox("Attach GPS evidence when a sample on this recording matches");
        TextArea expected = new TextArea();
        expected.setPromptText("Expected behaviour, if you want to record it");
        expected.setPrefRowCount(2);
        TextArea measured = new TextArea();
        measured.setPromptText("What was measured");
        measured.setPrefRowCount(2);
        TextArea comment = new TextArea();
        comment.setPromptText("Engineer comment");
        comment.setPrefRowCount(2);
        ComboBox<String> confirmation = new ComboBox<>();
        confirmation.getItems().addAll("Draft", "Confirmed");
        if (initial != null) {
            if (!category.getItems().contains(initial.category())) {
                category.getItems().add(initial.category());
            }
            category.getSelectionModel().select(initial.category());
            name.setText(initial.name());
            shape.getSelectionModel().select(initial.window() ? "Time window" : "Point");
            start.setText(Long.toString(initial.startNanos()));
            if (initial.endNanos() != null) {
                end.setText(Long.toString(initial.endNanos()));
            }
            signals.setText(String.join(", ", initial.signals()));
            gps.setSelected(initial.gpsAttached());
            expected.setText(initial.expected());
            measured.setText(initial.measured());
            comment.setText(initial.description());
            confirmation.getSelectionModel().select(initial.confirmation());
            for (String item : source.getItems()) {
                if (item.endsWith("\t" + initial.sourceId())) {
                    source.getSelectionModel().select(item);
                }
            }
            for (String item : snapshot.getItems()) {
                for (String id : initial.snapshotIds()) {
                    if (item.endsWith("\t" + id)) {
                        snapshot.getSelectionModel().select(item);
                    }
                }
            }
        }
        if (category.getValue() == null) {
            category.getSelectionModel().selectFirst();
        }
        if (shape.getValue() == null) {
            shape.getSelectionModel().selectFirst();
        }
        if (confirmation.getValue() == null) {
            confirmation.getSelectionModel().selectFirst();
        }
        VBox form = new VBox(8,
                new Label("Category"), category,
                new Label("Name"), name,
                new Label("Source measurement"), source,
                new Label("Event shape"), shape,
                new Label("Start"), start,
                new Label("End"), end,
                new Label("Signals"), signals,
                new Label("Graph snapshot"), snapshot,
                gps,
                new Label("Expected behaviour"), expected,
                new Label("Measured evidence"), measured,
                new Label("Engineer comment"), comment,
                new Label("Confirmation"), confirmation);
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(420);
        dialog.getDialogPane().setContent(scroll);
        dialog.setResultConverter(button -> button);
        dialog.getDialogPane().getProperties().put("stellar.event.editor", new EventEditorFields(
                initial, current, category, name, source, shape, start, end, signals, snapshot,
                gps, expected, measured, comment, confirmation));
        return dialog;
    }

    private EventDraft commitEventDialog(Dialog<ButtonType> dialog) {
        EventEditorFields fields = (EventEditorFields) dialog.getDialogPane().getProperties().get("stellar.event.editor");
        long startNanos;
        Long endNanos;
        try {
            startNanos = com.stellar.platform.desktop.presentation.EngineerEventDocuments.parseTime(fields.start.getText());
            boolean window = "Time window".equals(fields.shape.getValue());
            endNanos = window
                    ? com.stellar.platform.desktop.presentation.EngineerEventDocuments.parseTime(fields.end.getText())
                    : null;
        } catch (RuntimeException ex) {
            return EventDraft.failed("Enter the original timestamp.");
        }
        List<String> signalNames = new ArrayList<>();
        for (String part : fields.signals.getText().split(",")) {
            if (!part.isBlank()) {
                signalNames.add(part.strip());
            }
        }
        String sourceId = "";
        String sourceFile = "";
        if (fields.source.getValue() != null) {
            int tab = fields.source.getValue().lastIndexOf('\t');
            sourceFile = tab < 0 ? fields.source.getValue() : fields.source.getValue().substring(0, tab);
            sourceId = tab < 0 ? "" : fields.source.getValue().substring(tab + 1);
        }
        List<String> snapshotIds = new ArrayList<>();
        String snapshotNote = "No graph snapshot is linked.";
        java.util.Map<String, String> snapshotState = new java.util.LinkedHashMap<>();
        if (fields.snapshot.getValue() != null && fields.snapshot.getValue().contains("\t")) {
            int tab = fields.snapshot.getValue().lastIndexOf('\t');
            String snapshotId = fields.snapshot.getValue().substring(tab + 1);
            snapshotIds.add(snapshotId);
            snapshotNote = fields.snapshot.getValue().substring(0, tab);
            String state = com.stellar.platform.analysis.investigation.EngineerEventValidation.SNAPSHOT_OK;
            if (fields.investigation != null) {
                for (EngineeringEvidenceItem item : fields.investigation.evidence()) {
                    if (item.referenceId().equals(snapshotId) && item.stale()) {
                        snapshotNote = snapshotNote + " (stale, not regenerated)";
                        state = com.stellar.platform.analysis.investigation.EngineerEventValidation.SNAPSHOT_STALE;
                    }
                }
            }
            snapshotState.put(snapshotId, state);
        }
        boolean attachGps = fields.gps.isSelected();
        String gnss = attachGps
                ? "GPS evidence was requested. A coordinate is stored only when a GNSS sample on this recording matches the event. No coordinate is interpolated."
                : "GPS evidence was not attached. No coordinate was invented.";
        var draft = new com.stellar.platform.analysis.investigation.EngineerEventDefinition(
                fields.initial == null ? "" : fields.initial.id(),
                fields.name.getText(),
                fields.category.getValue(),
                sourceId,
                sourceFile,
                startNanos,
                endNanos,
                signalNames,
                fields.comment.getText(),
                fields.expected.getText(),
                fields.measured.getText(),
                snapshotIds,
                attachGps,
                fields.confirmation.getValue(),
                gnss,
                "",
                snapshotNote);
        java.util.Set<String> known = null;
        EngineeringInvestigation current = fields.investigation;
        if (current != null && !current.signalKeyBlobs().isEmpty()) {
            known = new LinkedHashSet<>();
            for (String blob : current.signalKeyBlobs()) {
                var key = com.stellar.platform.desktop.presentation.GraphInvestigationCodec.decodeKey(blob);
                if (key != null) {
                    known.add(key.signalName());
                }
            }
        }
        List<String> blocking = com.stellar.platform.analysis.investigation.EngineerEventValidation.blocking(
                draft, current == null ? null : current.rangeStartNanos(),
                current == null ? null : current.rangeEndNanos(), known);
        if (!blocking.isEmpty()) {
            return EventDraft.failed(String.join("\n", blocking));
        }
        List<String> warnings = com.stellar.platform.analysis.investigation.EngineerEventValidation.warnings(
                draft, snapshotState, true);
        return EventDraft.saved(draft.withStatements(gnss, String.join(" ", warnings), snapshotNote));
    }

    Dialog<ButtonType> eventEditorForTest(
            com.stellar.platform.analysis.investigation.EngineerEventDefinition initial
    ) {
        return buildEventDialog(initial);
    }

    String saveEventEditorForTest(Dialog<ButtonType> dialog) {
        EventDraft draft = commitEventDialog(dialog);
        if (draft.error != null || draft.event == null || selected() == null) {
            return draft.error == null ? "" : draft.error;
        }
        String investigationId = selected().investigationId();
        String saved = appService.saveEngineerEvent(draft.event);
        if (!saved.isBlank()) {
            refresh();
            select(investigationId);
        }
        return saved;
    }

    void setOpenGeneratedReportForTest(boolean open) {
        openGeneratedReport = open;
    }

    EvaluationRuleEditor evaluationEditorForTest(String eventId) {
        var event = com.stellar.platform.desktop.presentation.EngineerEventDocuments.decode(
                appService.findEngineeringBookmark(eventId).orElseThrow());
        return new EvaluationRuleEditor(event, appService.listEvaluationRules());
    }

    com.stellar.platform.analysis.investigation.eval.EvaluationRule saveEvaluationForTest(EvaluationRuleEditor editor) {
        var saved = appService.saveEvaluationRule(editor.draft());
        if (saved != null && selected() != null) {
            refresh();
            select(selected().investigationId());
        }
        return saved;
    }

    com.stellar.platform.analysis.investigation.eval.EvaluationResult runEvaluationForTest(
            EvaluationRuleEditor editor,
            java.util.List<com.stellar.platform.analysis.investigation.eval.EvaluationSeries> series,
            boolean clocksComparable
    ) {
        var saved = appService.saveEvaluationRule(editor.draft());
        if (saved == null) {
            return null;
        }
        return appService.evaluateAndStore(editor.event(), saved, series, clocksComparable, editor.includeSelected());
    }

    private static final class EventEditorFields {
        private final com.stellar.platform.analysis.investigation.EngineerEventDefinition initial;
        private final EngineeringInvestigation investigation;
        private final ComboBox<String> category;
        private final TextField name;
        private final ComboBox<String> source;
        private final ComboBox<String> shape;
        private final TextField start;
        private final TextField end;
        private final TextField signals;
        private final ComboBox<String> snapshot;
        private final CheckBox gps;
        private final TextArea expected;
        private final TextArea measured;
        private final TextArea comment;
        private final ComboBox<String> confirmation;

        private EventEditorFields(
                com.stellar.platform.analysis.investigation.EngineerEventDefinition initial,
                EngineeringInvestigation investigation,
                ComboBox<String> category,
                TextField name,
                ComboBox<String> source,
                ComboBox<String> shape,
                TextField start,
                TextField end,
                TextField signals,
                ComboBox<String> snapshot,
                CheckBox gps,
                TextArea expected,
                TextArea measured,
                TextArea comment,
                ComboBox<String> confirmation
        ) {
            this.initial = initial;
            this.investigation = investigation;
            this.category = category;
            this.name = name;
            this.source = source;
            this.shape = shape;
            this.start = start;
            this.end = end;
            this.signals = signals;
            this.snapshot = snapshot;
            this.gps = gps;
            this.expected = expected;
            this.measured = measured;
            this.comment = comment;
            this.confirmation = confirmation;
        }
    }

    private static final class EventDraft {
        private final com.stellar.platform.analysis.investigation.EngineerEventDefinition event;
        private final String error;

        private EventDraft(
                com.stellar.platform.analysis.investigation.EngineerEventDefinition event, String error
        ) {
            this.event = event;
            this.error = error;
        }

        private static EventDraft saved(
                com.stellar.platform.analysis.investigation.EngineerEventDefinition event
        ) {
            return new EventDraft(event, null);
        }

        private static EventDraft failed(String error) {
            return new EventDraft(null, error);
        }
    }

    private void persistConfig() {
        if (suppress) {
            return;
        }
        EngineeringInvestigation current = selected();
        if (current == null) {
            return;
        }
        appService.saveInvestigation(current.toBuilder().reportConfig(configFromUi()).build());
    }

    private InvestigationReportConfig configFromUi() {
        EngineeringReportTemplate template = templateBox.getValue() == null
                ? EngineeringReportTemplate.CLASSIC : templateBox.getValue();
        CheckBox[] boxes = v2Boxes();
        EngineeringReportSection[] sections = EngineeringReportSection.values();
        int mask = 0;
        for (int i = 0; i < sections.length && i < boxes.length; i++) {
            if (boxes[i].isSelected()) {
                mask |= 1 << sections[i].ordinal();
            }
        }
        return new InvestigationReportConfig(
                secProject.isSelected(), secSources.isSelected(), secSignals.isSelected(),
                secAnalysis.isSelected(), secDiagnostics.isSelected(), secFrequency.isSelected(),
                secGraphs.isSelected(), secWarnings.isSelected(), secAppendix.isSelected(),
                secPaths.isSelected(), true, template, mask,
                kpiConfigField.getText() == null ? "" : kpiConfigField.getText());
    }

    private void applyConfig(InvestigationReportConfig cfg) {
        boolean prev = suppress;
        suppress = true;
        secProject.setSelected(cfg.projectSummary());
        secSources.setSelected(cfg.sources());
        secSignals.setSelected(cfg.signalSummary());
        secAnalysis.setSelected(cfg.analysis());
        secDiagnostics.setSelected(cfg.diagnostics());
        secFrequency.setSelected(cfg.frequency());
        secGraphs.setSelected(cfg.graphEvidence());
        secWarnings.setSelected(cfg.warnings());
        secAppendix.setSelected(cfg.appendix());
        secPaths.setSelected(cfg.includeAbsolutePaths());
        CheckBox[] boxes = v2Boxes();
        EngineeringReportSection[] sections = EngineeringReportSection.values();
        for (int i = 0; i < sections.length && i < boxes.length; i++) {
            boxes[i].setSelected((cfg.sectionMask() & (1 << sections[i].ordinal())) != 0);
        }
        templateBox.setValue(cfg.template());
        if (kpiConfigField != null) {
            kpiConfigField.setText(cfg.kpiConfig());
        }
        showTemplate(cfg.template());
        suppress = prev;
    }

    private CheckBox[] v2Boxes() {
        return new CheckBox[] {
                v2Cover, v2Summary, v2Overview, v2Signals, v2Graphs, v2Route,
                v2Observations, v2Conclusions, v2Assumptions, v2Sources, v2Appendix, v2Kpis
        };
    }

    private void showTemplate(EngineeringReportTemplate template) {
        boolean v2 = template == EngineeringReportTemplate.ENGINEERING_V2;
        if (classicSections != null) {
            classicSections.setVisible(!v2);
            classicSections.setManaged(!v2);
        }
        if (v2Sections != null) {
            v2Sections.setVisible(v2);
            v2Sections.setManaged(v2);
        }
        if (descriptionField != null) {
            descriptionField.setPromptText(v2
                    ? "Executive summary (engineer-authored)"
                    : "");
        }
        if (kpiConfigLabel != null) {
            kpiConfigLabel.setVisible(v2);
            kpiConfigLabel.setManaged(v2);
        }
        if (kpiConfigField != null) {
            kpiConfigField.setVisible(v2);
            kpiConfigField.setManaged(v2);
        }
    }

    private EngineeringInvestigation selected() {
        return investigationList.getSelectionModel().getSelectedItem();
    }

    private void select(String investigationId) {
        investigationList.getItems().stream()
                .filter(i -> i.investigationId().equals(investigationId))
                .findFirst()
                .ifPresent(i -> investigationList.getSelectionModel().select(i));
    }

    void selectEvidenceForTest(String referenceId) {
        if (evidenceList == null || referenceId == null) {
            return;
        }
        for (EngineeringEvidenceItem item : evidenceList.getItems()) {
            if (referenceId.equals(item.referenceId())) {
                evidenceList.getSelectionModel().select(item);
                return;
            }
        }
    }

    java.util.List<String> evidenceReferenceOrderForTest() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (evidenceList == null) {
            return ids;
        }
        for (EngineeringEvidenceItem item : evidenceList.getItems()) {
            ids.add(item.referenceId());
        }
        return ids;
    }

    void setReportDestinationForTest(String name, Path directory) {
        reportNameOverride = name;
        reportDirectoryOverride = directory;
    }

    String reportStatusForTest() {
        return reportStatus == null || reportStatus.getText() == null ? "" : reportStatus.getText();
    }

    private void setReportBusy(boolean busy) {
        reportBusy = busy;
        updateReportActions(selected());
    }

    static boolean reportGenerateEnabled(boolean canGenerate, boolean generating) {
        return canGenerate && !generating;
    }

    private ContextMenu investigationContextMenu(EngineeringInvestigation item) {
        return new ContextMenu(menu("Rename Investigation", () -> renameInvestigation(item)));
    }

    private void renameInvestigation(EngineeringInvestigation item) {
        if (item == null || appService == null) {
            return;
        }
        javafx.scene.control.Dialog<ButtonType> dialog = renameDialog(item.name());
        java.util.Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
            return;
        }
        commitRename(item, renameField(dialog).getText());
    }

    private javafx.scene.control.Dialog<ButtonType> renameDialog(String currentName) {
        javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Rename Investigation");
        dialog.setHeaderText(null);
        ButtonType rename = new ButtonType("Rename", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, rename);
        TextField name = new TextField(currentName == null ? "" : currentName);
        name.setPromptText("Enter investigation name");
        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        dialog.getDialogPane().setContent(new VBox(8, new Label("Investigation Name *"), name, error));
        Button confirm = (Button) dialog.getDialogPane().lookupButton(rename);
        Runnable refreshButton = () -> {
            String trimmed = name.getText() == null ? "" : name.getText().trim();
            boolean valid = !trimmed.isEmpty();
            if (confirm != null) {
                confirm.setDisable(!valid);
            }
            error.setVisible(!valid && name.getText() != null && !name.getText().isEmpty());
            error.setManaged(error.isVisible());
            error.setText("Investigation Name must not be empty.");
        };
        if (confirm != null) {
            confirm.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                if (name.getText() == null || name.getText().trim().isEmpty()) {
                    refreshButton.run();
                    event.consume();
                }
            });
        }
        name.textProperty().addListener((obs, previous, value) -> refreshButton.run());
        refreshButton.run();
        return dialog;
    }

    private static TextField renameField(javafx.scene.control.Dialog<ButtonType> dialog) {
        if (dialog.getDialogPane().getContent() instanceof VBox body) {
            for (javafx.scene.Node node : body.getChildren()) {
                if (node instanceof TextField field) {
                    return field;
                }
            }
        }
        return new TextField("");
    }

    boolean commitRenameForTest(EngineeringInvestigation item, String raw) {
        return commitRename(item, raw);
    }

    javafx.scene.control.Dialog<ButtonType> renameDialogForTest(String currentName) {
        return renameDialog(currentName);
    }

    ContextMenu investigationContextMenuForTest(EngineeringInvestigation item) {
        return investigationContextMenu(item);
    }

    private boolean commitRename(EngineeringInvestigation item, String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || appService == null || item == null) {
            return false;
        }
        if (item.projectId() == null || !item.projectId().equals(appService.currentProjectId())) {
            return false;
        }
        if (trimmed.equals(item.name())) {
            return true;
        }
        EngineeringInvestigation saved = appService.saveInvestigation(item.toBuilder().name(trimmed).build());
        refresh();
        select(saved.investigationId());
        return item.investigationId().equals(saved.investigationId()) && trimmed.equals(saved.name());
    }

    static String evidenceLabel(EngineeringEvidenceItem item) {
        if (item == null) {
            return "";
        }
        String stale = item.stale() ? " [STALE]" : "";
        if (item.kind() == com.stellar.platform.analysis.investigation.EvidenceKind.GRAPH_SNAPSHOT
                && item.caption() != null && !item.caption().isBlank()) {
            return (item.sortOrder() + 1) + ". " + item.caption() + stale;
        }
        String caption = item.caption() == null || item.caption().isBlank() ? item.summary() : item.caption();
        return (item.sortOrder() + 1) + ". " + item.kind() + " — " + (caption == null ? "" : caption) + stale;
    }

    private void updateReportActions(EngineeringInvestigation investigation) {
        boolean enabled = reportGenerateEnabled(ReportAvailability.canGenerate(investigation), reportBusy);
        if (previewReportButton != null) {
            previewReportButton.setDisable(!enabled);
        }
        if (generateReportButton != null) {
            generateReportButton.setDisable(!enabled);
        }
        if (generatePdfButton != null) {
            generatePdfButton.setDisable(!enabled);
        }
        if (exportPackageButton != null) {
            exportPackageButton.setDisable(!enabled);
        }
    }

    private void status(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message);
        }
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "Investigation";
        }
        return name.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static void releaseEmptyClaim(Path destination) {
        if (destination == null) {
            return;
        }
        try {
            if (Files.isRegularFile(destination) && Files.size(destination) == 0) {
                Files.deleteIfExists(destination);
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Empty report reservation could not be removed", ex);
        }
    }

    private String promptReportName(String title, String content, String initial, InvestigationReportRow renaming) {
        String seed = initial == null ? "" : ReportNameAllocator.stemOf(initial);
        TextInputDialog dialog = new TextInputDialog(seed);
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        dialog.setContentText(content);
        while (true) {
            Optional<String> value = dialog.showAndWait();
            if (value.isEmpty()) {
                return null;
            }
            String error = ReportNameAllocator.validationError(value.get());
            if (error == null && renaming != null && renaming.destination() != null && !renaming.destination().isBlank()) {
                try {
                    Path directory = Path.of(renaming.destination()).getParent();
                    String currentFile = Path.of(renaming.destination()).getFileName().toString();
                    if (ReportNameAllocator.nameTaken(
                            value.get(), reportNamesIn(renaming.projectId(), directory), currentFile)) {
                        error = ReportNameAllocator.NAME_EXISTS;
                    }
                } catch (IOException ex) {
                    error = "Report could not be renamed.";
                }
            }
            if (error == null) {
                return ReportNameAllocator.requireDisplayName(value.get());
            }
            dialog.setHeaderText(error);
            dialog.getEditor().setText(value.get());
        }
    }

    private Collection<String> reportNamesIn(String projectId, Path directory) throws IOException {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (directory != null && Files.isDirectory(directory)) {
            try (var listed = Files.list(directory)) {
                listed.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .forEach(names::add);
            }
        }
        if (appService != null && appService.metadataStore() != null && projectId != null) {
            String folder = directory == null ? "" : directory.toAbsolutePath().normalize().toString();
            for (InvestigationReportRow report : appService.metadataStore().listProjectReports(projectId)) {
                if (report.destination() == null || report.destination().isBlank()) {
                    continue;
                }
                Path parent = Path.of(report.destination()).getParent();
                if (parent != null
                        && parent.toAbsolutePath().normalize().toString().equalsIgnoreCase(folder)
                        && Path.of(report.destination()).getFileName() != null) {
                    names.add(Path.of(report.destination()).getFileName().toString());
                }
            }
        }
        return names;
    }

    private static boolean reportFileMissing(InvestigationReportRow row) {
        if (row == null || row.destination() == null || row.destination().isBlank()) {
            return true;
        }
        try {
            return !Files.isRegularFile(Path.of(row.destination()));
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private static MenuItem menu(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    private static void openPath(Path path) {
        if (path == null || !Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Path open = Files.isRegularFile(path) ? path : path.resolve("report.html");
            if (Files.exists(open)) {
                Desktop.getDesktop().open(open.toFile());
            }
        } catch (Exception ignored) {
        }
    }

    private static String human(Exception ex) {
        String msg = ex.getMessage();
        return msg == null || msg.isBlank() ? "The operation could not be completed." : msg;
    }

    private static void showError(String title, String message, Exception ex) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        if (ex != null && ex.getMessage() != null) {
            javafx.scene.control.TextArea details = new javafx.scene.control.TextArea(ex.toString());
            details.setEditable(false);
            details.setWrapText(true);
            details.setPrefRowCount(8);
            alert.getDialogPane().setExpandableContent(details);
        }
        alert.showAndWait();
    }
}

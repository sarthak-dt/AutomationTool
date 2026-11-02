package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.analysis.diagnostics.DiagnosticFinding;
import com.stellar.platform.analysis.diagnostics.DiagnosticRunResult;
import com.stellar.platform.analysis.diagnostics.FindingEvidence;
import com.stellar.platform.analysis.diagnostics.RuleOutcome;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rules.BindingResolver;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.GenericEngineeringPack;
import com.stellar.platform.analysis.rules.RuleCategory;
import com.stellar.platform.analysis.rules.RuleOrigin;
import com.stellar.platform.analysis.rules.SignalBinding;
import com.stellar.platform.analysis.workflow.BindingCandidate;
import com.stellar.platform.analysis.workflow.BindingWorkspace;
import com.stellar.platform.analysis.workflow.FindingListFilter;
import com.stellar.platform.analysis.workflow.FindingReviewState;
import com.stellar.platform.analysis.workflow.ProjectCustomPack;
import com.stellar.platform.analysis.workflow.RuleListFilter;
import com.stellar.platform.analysis.workflow.RuleListPresentationModel;
import com.stellar.platform.analysis.workflow.RuleReadiness;
import com.stellar.platform.analysis.workflow.RuleReadinessEvaluator;
import com.stellar.platform.analysis.workflow.RunReadinessSummary;
import com.stellar.platform.analysis.workflow.StaleRunReason;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.storage.metadata.DiagnosticFindingReviewRow;
import com.stellar.platform.storage.metadata.DiagnosticRunMetadata;
import com.stellar.platform.desktop.ui.WorkstationLayoutMetrics;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Engineering → Diagnostics. Master/detail. No new Stage. Graph remains the canvas.
 */
public final class EngineeringDiagnosticsPane extends BorderPane {

    public record RunSpec(
            String packId,
            String range,
            List<String> enabledRuleIds,
            Map<String, SignalCatalogKey> bindings
    ) {}

    private final ComboBox<PackChoice> packBox = new ComboBox<>();
    private final ComboBox<String> rangeBox = new ComboBox<>();
    private final Label packMeta = new Label();
    private final Label scopeTimes = new Label();
    private final Label summary = new Label();
    private final Label staleBanner = new Label();
    private final TextField ruleSearch = new TextField();
    private final ComboBox<String> ruleCategory = new ComboBox<>();
    private final ComboBox<String> ruleStatus = new ComboBox<>();
    private final TableView<RuleStatusRow> rules = new TableView<>();
    private final TableView<BindingRow> bindingsTable = new TableView<>();
    private final TableView<FindingListRow> findings = new TableView<>();
    private final ComboBox<String> findingSeverity = new ComboBox<>();
    private final ComboBox<String> findingCategory = new ComboBox<>();
    private final TextField findingSearch = new TextField();
    private final Label counts = new Label("CRITICAL 0   WARNING 0   OBSERVATION 0");
    private final TextArea evidence = new TextArea();
    private final ComboBox<FindingReviewState> reviewBox = new ComboBox<>();
    private final TextArea note = new TextArea();
    private final CheckBox showFindings = new CheckBox("Show Diagnostic Findings");
    private final ListView<String> history = new ListView<>();
    private final Label banner = new Label("Generic engineering reference rules — not manufacturer-certified.");
    private final RuleListPresentationModel ruleModel = new RuleListPresentationModel();

    private DiagnosticRulePack pack = GenericEngineeringPack.pack();
    private final Map<String, DiagnosticRulePack> packs = new LinkedHashMap<>();
    private List<SignalCatalogEntry> catalog = List.of();
    private Map<String, SignalCatalogKey> bindings = new LinkedHashMap<>();
    private Map<String, Boolean> preferences = new LinkedHashMap<>();
    private Map<String, DiagnosticFindingReviewRow> reviews = new LinkedHashMap<>();
    private List<DiagnosticRunMetadata> runs = List.of();
    private DiagnosticRunResult last;
    private TimeWindow scopeWindow = TimeWindow.unbounded();
    private Consumer<RunSpec> runHandler = spec -> {};
    private Consumer<RunSpec> dryRunHandler = spec -> {};
    private Consumer<DiagnosticFinding> findingHandler = f -> {};
    private Consumer<DiagnosticFinding> annotateHandler = f -> {};
    private Consumer<DiagnosticFinding> evidenceSignalsHandler = f -> {};
    private Consumer<DiagnosticFinding> addToInvestigationHandler = f -> {};
    private Consumer<Path> exportHandler = path -> {};
    private Consumer<Path> importHandler = path -> {};
    private Consumer<Path> exportPackHandler = path -> {};
    private BiConsumer<String, SignalCatalogKey> bindHandler = (role, key) -> {};
    private BiConsumer<FindingReviewState, String> reviewHandler = (s, n) -> {};
    private Consumer<Boolean> overlayHandler = v -> {};
    private Consumer<String> historyHandler = id -> {};
    private Consumer<String> compareHandler = id -> {};
    private BiConsumer<String, Boolean> preferenceHandler = (id, en) -> {};
    private final SplitPane split = new SplitPane();
    private final VBox leftPanel = new VBox();
    private final VBox centerPanel = new VBox();
    private final VBox rightPanel = new VBox();
    private TableColumn<FindingListRow, String> findingsDurationCol;
    private TableColumn<FindingListRow, String> findingsReviewCol;

    public EngineeringDiagnosticsPane() {
        banner.getStyleClass().add("muted");
        banner.setWrapText(true);
        staleBanner.getStyleClass().add("muted");
        staleBanner.setWrapText(true);
        packMeta.getStyleClass().add("muted");
        packMeta.setWrapText(true);
        scopeTimes.getStyleClass().add("mono");
        summary.getStyleClass().add("muted");
        packs.put(GenericEngineeringPack.PACK_ID, GenericEngineeringPack.pack());
        packs.put(ProjectCustomPack.PACK_ID, ProjectCustomPack.empty());
        packBox.getItems().setAll(PackChoice.of(pack), PackChoice.of(ProjectCustomPack.empty()));
        packBox.getSelectionModel().selectFirst();
        packBox.setOnAction(e -> {
            PackChoice choice = packBox.getValue();
            if (choice != null && packs.containsKey(choice.packId())) {
                pack = packs.get(choice.packId());
                refreshAll();
            }
        });
        rangeBox.getItems().addAll("VIEWPORT", "CURSOR_A_TO_B", "FULL", "CUSTOM");
        rangeBox.getSelectionModel().select("VIEWPORT");
        rangeBox.setOnAction(e -> refreshSummary());
        Button run = new Button("Run Diagnostics");
        run.getStyleClass().add("primary");
        run.setTooltip(new Tooltip("Execute ready rules on the selected scope. Does not reprocess MF4/DBC."));
        run.setOnAction(e -> runHandler.accept(currentSpec()));
        Button dry = new Button("Preview Rule / Dry-run");
        dry.setTooltip(new Tooltip("Bounded preview over the current viewport. Not persisted as a diagnostic run."));
        dry.setOnAction(e -> dryRunHandler.accept(currentSpec()));
        Button export = new Button("Export CSV");
        export.setOnAction(e -> exportHandler.accept(null));
        Button annotate = new Button("Create Annotation from Finding");
        annotate.setOnAction(e -> selectedFinding().ifPresent(annotateHandler));
        Button evidenceSignals = new Button("Show Evidence Signals");
        evidenceSignals.setTooltip(new Tooltip("Add finding-related signals without clearing the graph selection."));
        evidenceSignals.setOnAction(e -> selectedFinding().ifPresent(evidenceSignalsHandler));
        Button addToInv = new Button("Add to Investigation Evidence");
        addToInv.setOnAction(e -> selectedFinding().ifPresent(addToInvestigationHandler));
        Button importPack = new Button("Import pack");
        importPack.setOnAction(e -> importHandler.accept(null));
        Button exportPack = new Button("Export pack");
        exportPack.setOnAction(e -> exportPackHandler.accept(null));
        Button prev = new Button("Previous Finding");
        Button next = new Button("Next Finding");
        prev.setOnAction(e -> moveFinding(-1));
        next.setOnAction(e -> moveFinding(1));
        showFindings.setSelected(true);
        showFindings.setOnAction(e -> overlayHandler.accept(showFindings.isSelected()));
        evidence.setEditable(false);
        evidence.setPrefRowCount(10);
        evidence.getStyleClass().add("mono");
        evidence.setPromptText("Select a finding for evidence and recommended checks.");
        note.setPromptText("Engineer observation — stored separately from measured evidence.");
        note.setPrefRowCount(3);
        reviewBox.getItems().addAll(FindingReviewState.values());
        reviewBox.getSelectionModel().select(FindingReviewState.NEW);
        Button saveReview = new Button("Save review");
        saveReview.setOnAction(e -> reviewHandler.accept(reviewBox.getValue(), note.getText()));
        ruleSearch.setPromptText("Search rules");
        ruleSearch.textProperty().addListener((o, a, b) -> refreshRuleStatus());
        ruleCategory.getItems().addAll("ALL");
        for (RuleCategory c : RuleCategory.values()) {
            ruleCategory.getItems().add(c.name());
        }
        ruleCategory.getSelectionModel().select("ALL");
        ruleCategory.setOnAction(e -> refreshRuleStatus());
        ruleStatus.getItems().addAll("ALL", "READY", "BINDING_REQUIRED", "MISSING_INPUT",
                "INVALID_CONFIGURATION", "INCOMPATIBLE_UNIT", "DISABLED");
        ruleStatus.getSelectionModel().select("ALL");
        ruleStatus.setOnAction(e -> refreshRuleStatus());
        findingSearch.setPromptText("Search findings");
        findingSearch.textProperty().addListener((o, a, b) -> refreshFindings());
        findingSeverity.getItems().addAll("ALL");
        for (AnalysisSeverity s : AnalysisSeverity.values()) {
            findingSeverity.getItems().add(s.name());
        }
        findingSeverity.getSelectionModel().select("ALL");
        findingSeverity.setOnAction(e -> refreshFindings());
        findingCategory.getItems().addAll("ALL");
        for (RuleCategory c : RuleCategory.values()) {
            findingCategory.getItems().add(c.name());
        }
        findingCategory.getSelectionModel().select("ALL");
        findingCategory.setOnAction(e -> refreshFindings());
        configureRules();
        configureBindings();
        configureFindings();
        history.setPrefHeight(90);
        history.setPlaceholder(new Label("No previous diagnostic runs."));
        history.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            int i = history.getSelectionModel().getSelectedIndex();
            if (i >= 0 && i < runs.size()) {
                historyHandler.accept(runs.get(i).runId());
            }
        });
        Button compare = new Button("Compare with current");
        compare.setOnAction(e -> {
            int i = history.getSelectionModel().getSelectedIndex();
            if (i >= 0 && i < runs.size()) {
                compareHandler.accept(runs.get(i).runId());
            }
        });

        run.getStyleClass().add("workstation-primary-button");
        prev.getStyleClass().add("workstation-secondary-button");
        next.getStyleClass().add("workstation-secondary-button");
        saveReview.getStyleClass().add("workstation-primary-button");
        saveReview.setText("Save Review");
        FlowPane packRow = new FlowPane(WorkstationLayoutMetrics.CONTROL_GAP, WorkstationLayoutMetrics.CONTROL_GAP,
                new Label("Rule pack"), packBox, new Label("Scope"), rangeBox, run, dry);
        packRow.getStyleClass().add("workstation-toolbar");
        FlowPane actions = new FlowPane(WorkstationLayoutMetrics.CONTROL_GAP, WorkstationLayoutMetrics.CONTROL_GAP,
                export, annotate, evidenceSignals, addToInv, importPack, exportPack, showFindings);
        actions.getStyleClass().add("workstation-toolbar");
        FlowPane nav = new FlowPane(WorkstationLayoutMetrics.CONTROL_GAP, WorkstationLayoutMetrics.CONTROL_GAP,
                prev, next, saveReview);
        nav.getStyleClass().add("workstation-toolbar");
        Label bindingTitle = new Label("Signal binding");
        bindingTitle.getStyleClass().add("workstation-section-title");
        Label rulesTitle = new Label("Rules");
        rulesTitle.getStyleClass().add("workstation-section-title");
        FlowPane ruleFilters = new FlowPane(WorkstationLayoutMetrics.CONTROL_GAP, WorkstationLayoutMetrics.CONTROL_GAP,
                ruleSearch, ruleCategory, ruleStatus);
        leftPanel.getStyleClass().addAll("diagnostics-side", "workstation-side-panel");
        leftPanel.setSpacing(WorkstationLayoutMetrics.CONTROL_GAP);
        leftPanel.getChildren().setAll(banner, packRow, packMeta, scopeTimes, summary,
                bindingTitle, bindingsTable, ruleFilters, rulesTitle, rules);
        VBox.setVgrow(rules, Priority.ALWAYS);
        leftPanel.setMinWidth(WorkstationLayoutMetrics.SIDE_PANEL_MIN);
        leftPanel.setPrefWidth(WorkstationLayoutMetrics.SIDE_PANEL_PREF);
        FlowPane findingFilters = new FlowPane(WorkstationLayoutMetrics.CONTROL_GAP, WorkstationLayoutMetrics.CONTROL_GAP,
                findingSearch, findingSeverity, findingCategory);
        Label findingsTitle = new Label("Findings");
        findingsTitle.getStyleClass().add("workstation-section-title");
        centerPanel.getStyleClass().addAll("diagnostics-center", "workstation-center-panel");
        centerPanel.setSpacing(WorkstationLayoutMetrics.CONTROL_GAP);
        centerPanel.getChildren().setAll(findingsTitle, counts, findingFilters, findings, staleBanner);
        VBox.setVgrow(findings, Priority.ALWAYS);
        centerPanel.setMinWidth(WorkstationLayoutMetrics.CENTER_PANEL_MIN);
        Label detailsTitle = new Label("Finding details / evidence");
        detailsTitle.getStyleClass().add("workstation-section-title");
        Label reviewTitle = new Label("Review");
        reviewTitle.getStyleClass().add("workstation-section-title");
        Label noteTitle = new Label("Engineer observation");
        noteTitle.getStyleClass().add("workstation-section-title");
        Label historyTitle = new Label("Run history");
        historyTitle.getStyleClass().add("workstation-section-title");
        rightPanel.getStyleClass().addAll("diagnostics-details", "workstation-side-panel");
        rightPanel.setSpacing(WorkstationLayoutMetrics.CONTROL_GAP);
        rightPanel.getChildren().setAll(detailsTitle, evidence, reviewTitle, reviewBox, noteTitle, note, nav,
                historyTitle, history, compare, actions);
        VBox.setVgrow(evidence, Priority.ALWAYS);
        rightPanel.setMinWidth(WorkstationLayoutMetrics.SIDE_PANEL_MIN);
        rightPanel.setPrefWidth(WorkstationLayoutMetrics.SIDE_PANEL_PREF);
        SplitPane.setResizableWithParent(leftPanel, false);
        SplitPane.setResizableWithParent(rightPanel, false);
        split.getStyleClass().add("diagnostics-split");
        split.getItems().setAll(leftPanel, centerPanel, rightPanel);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.22, 0.72);
        getStyleClass().add("workstation-page");
        setPadding(new Insets(8, 10, 8, 10));
        setCenter(split);
        findings.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.LEFT) {
                moveFinding(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.RIGHT) {
                moveFinding(1);
                e.consume();
            }
        });
        refreshAll();
    }

    public void setRunHandler(Consumer<RunSpec> handler) {
        runHandler = handler == null ? spec -> {} : handler;
    }

    public void setDryRunHandler(Consumer<RunSpec> handler) {
        dryRunHandler = handler == null ? spec -> {} : handler;
    }

    public void setFindingHandler(Consumer<DiagnosticFinding> handler) {
        findingHandler = handler == null ? f -> {} : handler;
    }

    public void setAnnotateHandler(Consumer<DiagnosticFinding> handler) {
        annotateHandler = handler == null ? f -> {} : handler;
    }

    public void setEvidenceSignalsHandler(Consumer<DiagnosticFinding> handler) {
        evidenceSignalsHandler = handler == null ? f -> {} : handler;
    }

    public void setAddToInvestigationHandler(Consumer<DiagnosticFinding> handler) {
        addToInvestigationHandler = handler == null ? f -> {} : handler;
    }

    public void setExportHandler(Consumer<Path> handler) {
        exportHandler = handler == null ? path -> {} : handler;
    }

    public void setImportHandler(Consumer<Path> handler) {
        importHandler = handler == null ? path -> {} : handler;
    }

    public void setExportPackHandler(Consumer<Path> handler) {
        exportPackHandler = handler == null ? path -> {} : handler;
    }

    public void setBindHandler(BiConsumer<String, SignalCatalogKey> handler) {
        bindHandler = handler == null ? (r, k) -> {} : handler;
    }

    public void setReviewHandler(BiConsumer<FindingReviewState, String> handler) {
        reviewHandler = handler == null ? (s, n) -> {} : handler;
    }

    public void setOverlayToggleHandler(Consumer<Boolean> handler) {
        overlayHandler = handler == null ? v -> {} : handler;
    }

    public void setHistoryHandler(Consumer<String> handler) {
        historyHandler = handler == null ? id -> {} : handler;
    }

    public void setCompareHandler(Consumer<String> handler) {
        compareHandler = handler == null ? id -> {} : handler;
    }

    public void setPreferenceHandler(BiConsumer<String, Boolean> handler) {
        preferenceHandler = handler == null ? (id, en) -> {} : handler;
    }

    public boolean findingsVisible() {
        return showFindings.isSelected();
    }

    public void setCatalog(List<SignalCatalogEntry> entries) {
        catalog = entries == null ? List.of() : List.copyOf(entries);
        refreshAll();
    }

    public void setBindings(Map<String, SignalCatalogKey> next) {
        bindings = new LinkedHashMap<>(next == null ? Map.of() : next);
        refreshAll();
    }

    public Map<String, SignalCatalogKey> bindings() {
        return Map.copyOf(bindings);
    }

    public void bindRole(String role, SignalCatalogKey key) {
        if (role != null && key != null) {
            bindings.put(role, key);
            refreshAll();
        }
    }

    public void setPacks(List<DiagnosticRulePack> next) {
        packs.clear();
        packBox.getItems().clear();
        if (next != null) {
            for (DiagnosticRulePack p : next) {
                packs.put(p.packId(), p);
                packBox.getItems().add(PackChoice.of(p));
            }
        }
        if (packs.containsKey(pack.packId())) {
            pack = packs.get(pack.packId());
        } else if (!packs.isEmpty()) {
            pack = packs.values().iterator().next();
        }
        packBox.getItems().stream()
                .filter(c -> c.packId().equals(pack.packId()))
                .findFirst()
                .ifPresent(packBox.getSelectionModel()::select);
        refreshAll();
    }

    public DiagnosticRulePack pack() {
        return pack;
    }

    public void setPack(DiagnosticRulePack next) {
        if (next == null) {
            return;
        }
        pack = next;
        packs.put(next.packId(), next);
        if (packBox.getItems().stream().noneMatch(c -> c.packId().equals(next.packId()))) {
            packBox.getItems().add(PackChoice.of(next));
        }
        packBox.getItems().stream()
                .filter(c -> c.packId().equals(next.packId()))
                .findFirst()
                .ifPresent(packBox.getSelectionModel()::select);
        refreshAll();
    }

    public String selectedRange() {
        return rangeBox.getValue();
    }

    public void setScopeWindow(TimeWindow window, String label) {
        scopeWindow = window == null ? TimeWindow.unbounded() : window;
        scopeTimes.setText(label == null || label.isBlank()
                ? RunReadinessSummary.of(pack, List.of(), Map.of(), scopeWindow).scopeLabel()
                : label);
        refreshSummary();
    }

    public void setPreferences(Map<String, Boolean> next) {
        preferences = new LinkedHashMap<>(next == null ? Map.of() : next);
        refreshAll();
    }

    public void setReviews(Map<String, DiagnosticFindingReviewRow> next) {
        reviews = new LinkedHashMap<>(next == null ? Map.of() : next);
        refreshFindings();
    }

    public void setRunHistory(List<DiagnosticRunMetadata> next) {
        runs = next == null ? List.of() : List.copyOf(next);
        List<String> labels = new ArrayList<>();
        for (DiagnosticRunMetadata run : runs) {
            String stale = StaleRunReason.staleStatus(run.status()) ? " STALE" : "";
            labels.add(run.createdAt() + "  " + run.packId() + " v" + run.packVersion()
                    + "  " + run.status() + stale + "  findings via reopen");
        }
        history.setItems(FXCollections.observableArrayList(labels));
    }

    public void showResult(DiagnosticRunResult result, String report) {
        last = result;
        if (result != null && StaleRunReason.staleMessage(result.message())) {
            staleBanner.setText(result.message());
        } else {
            staleBanner.setText("");
        }
        refreshFindings();
        if (result == null) {
            evidence.setText(report == null ? "" : report);
        } else if (report != null && !report.isBlank() && findings.getItems().isEmpty()) {
            evidence.setText(report);
        }
        refreshRuleStatus();
        refreshSummary();
    }

    public List<DiagnosticFinding> currentFindings() {
        return last == null ? List.of() : last.findings();
    }

    public List<DiagnosticFinding> visibleFindings() {
        return findings.getItems().stream()
                .map(FindingListRow::finding)
                .filter(f -> f != null)
                .toList();
    }

    public java.util.Optional<DiagnosticFinding> selectedFinding() {
        FindingListRow row = findings.getSelectionModel().getSelectedItem();
        return row == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(row.finding());
    }

    private RunSpec currentSpec() {
        List<String> enabled = pack.rules().stream()
                .filter(r -> preferences.getOrDefault(r.ruleId(), r.enabled()))
                .map(DiagnosticRuleDefinition::ruleId)
                .toList();
        return new RunSpec(pack.packId(), rangeBox.getValue(), enabled, Map.copyOf(bindings));
    }

    private boolean bindingsViewStale;

    /** Binding tables stay unbuilt until this pane is on screen. */
    public void refreshIfStale() {
        if (bindingsViewStale) {
            refreshAll();
        }
    }

    private void refreshAll() {
        if (getScene() == null) {
            bindingsViewStale = true;
            return;
        }
        bindingsViewStale = false;
        packMeta.setText(originLabel(pack) + "  " + pack.name() + "  v" + pack.packVersion()
                + "  ·  " + pack.rules().size() + " rules\n" + pack.description());
        refreshBindings();
        refreshRuleStatus();
        refreshSummary();
        refreshFindings();
    }

    private void refreshSummary() {
        Map<String, SignalBinding> resolved = BindingWorkspace.resolveShared(pack, catalog, bindings);
        RunReadinessSummary s = RunReadinessSummary.of(pack, currentSpec().enabledRuleIds(), resolved, scopeWindow);
        summary.setText(s.selected() + " rules selected   " + s.ready() + " ready   "
                + s.unavailable() + " unavailable   Signals: " + s.boundSignals() + " bound   Scope: "
                + (scopeTimes.getText() == null || scopeTimes.getText().isBlank() ? s.scopeLabel() : scopeTimes.getText())
                + (s.skippedReasons().isEmpty() ? "" : "\nSkipped: " + String.join("; ", s.skippedReasons())));
    }

    private void refreshBindings() {
        List<BindingRow> rows = new ArrayList<>();
        Map<String, SignalBinding> resolved = BindingWorkspace.resolveShared(pack, catalog, bindings);
        BindingWorkspace.uniqueRoles(pack).forEach(input -> {
            SignalBinding b = resolved.get(input.role());
            List<BindingCandidate> candidates = BindingWorkspace.candidates(input, catalog);
            String current = b != null && b.bound()
                    ? b.key().signalName() + " [" + b.signalUnit() + "]"
                    : (b == null ? "—" : b.state().name());
            String status = b == null ? "MISSING_INPUT" : switch (b.state()) {
                case BOUND -> "Ready";
                case AMBIGUOUS -> candidates.size() + " candidates — Binding required";
                case STALE -> "STALE / MISSING";
                default -> b.state().name();
            };
            rows.add(new BindingRow(input.role(), input.expectedUnit(), current, status, candidates));
        });
        bindingsTable.setItems(FXCollections.observableArrayList(rows));
    }

    private void refreshRuleStatus() {
        ruleModel.setSource(pack.rules());
        Map<String, SignalBinding> shared = BindingWorkspace.resolveShared(pack, catalog, bindings);
        Map<String, RuleReadiness> readiness = new LinkedHashMap<>();
        for (DiagnosticRuleDefinition rule : pack.rules()) {
            Map<String, SignalBinding> per = BindingResolver.resolve(rule, catalog, bindings);
            boolean enabled = preferences.getOrDefault(rule.ruleId(), rule.enabled());
            readiness.put(rule.ruleId(), RuleReadinessEvaluator.evaluate(rule, per, enabled));
        }
        ruleModel.setReadiness(readiness);
        RuleCategory cat = "ALL".equals(ruleCategory.getValue()) ? null : RuleCategory.valueOf(ruleCategory.getValue());
        RuleReadiness ready = "ALL".equals(ruleStatus.getValue()) ? null : RuleReadiness.valueOf(ruleStatus.getValue());
        ruleModel.setFilter(new RuleListFilter(ruleSearch.getText(), cat, ready, null));
        List<RuleStatusRow> rows = new ArrayList<>();
        for (DiagnosticRuleDefinition rule : ruleModel.view()) {
            Map<String, SignalBinding> per = BindingResolver.resolve(rule, catalog, bindings);
            RuleReadiness status = readiness.getOrDefault(rule.ruleId(), RuleReadiness.MISSING_INPUT);
            String detail = RuleReadinessEvaluator.reason(status, per);
            if (last != null) {
                for (RuleOutcome o : last.outcomes()) {
                    if (o.ruleId().equals(rule.ruleId())) {
                        detail = o.state() + " — " + o.reason();
                    }
                }
            }
            boolean enabled = preferences.getOrDefault(rule.ruleId(), rule.enabled());
            rows.add(new RuleStatusRow(rule.ruleId(), rule.name(), rule.category().name(),
                    status.name(), detail, enabled));
        }
        rules.setItems(FXCollections.observableArrayList(rows));
    }

    private void refreshFindings() {
        if (last == null) {
            findings.setItems(FXCollections.observableArrayList());
            counts.setText("CRITICAL 0   WARNING 0   OBSERVATION 0");
            return;
        }
        counts.setText("CRITICAL " + count(AnalysisSeverity.CRITICAL)
                + "   WARNING " + count(AnalysisSeverity.WARNING)
                + "   OBSERVATION " + count(AnalysisSeverity.OBSERVATION)
                + "   INFO " + count(AnalysisSeverity.INFO));
        AnalysisSeverity sev = "ALL".equals(findingSeverity.getValue())
                ? null : AnalysisSeverity.valueOf(findingSeverity.getValue());
        RuleCategory cat = "ALL".equals(findingCategory.getValue())
                ? null : RuleCategory.valueOf(findingCategory.getValue());
        FindingListFilter filter = new FindingListFilter(sev, cat, "", findingSearch.getText());
        List<FindingListRow> rows = new ArrayList<>();
        for (DiagnosticFinding f : filter.apply(last.findings())) {
            long end = f.endTimestampNanos() == null ? f.startTimestampNanos() : f.endTimestampNanos();
            double dur = Math.max(0, (end - f.startTimestampNanos()) / 1e9);
            DiagnosticFindingReviewRow review = reviews.get(f.findingId());
            rows.add(new FindingListRow(
                    String.format(Locale.ROOT, "%.3f–%.3f s", f.startTimestampNanos() / 1e9, end / 1e9),
                    f.severity().name(),
                    f.ruleId(),
                    f.summary(),
                    String.format(Locale.ROOT, "%.3f s", dur),
                    review == null ? FindingReviewState.NEW.name() : review.reviewState(),
                    f));
        }
        findings.setItems(FXCollections.observableArrayList(rows));
    }

    private void configureRules() {
        rules.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        rules.setPrefHeight(180);
        rules.getColumns().add(col("Rule", RuleStatusRow::ruleId, 160));
        rules.getColumns().add(col("Name", RuleStatusRow::name, 180));
        rules.getColumns().add(col("Category", RuleStatusRow::category, 110));
        rules.getColumns().add(col("Readiness", RuleStatusRow::status, 150));
        rules.getColumns().add(col("Detail", RuleStatusRow::detail, 280));
        TableColumn<RuleStatusRow, String> en = col("Enabled", r -> r.enabled() ? "yes" : "no", 70);
        rules.getColumns().add(en);
        rules.setRowFactory(v -> {
            TableRow<RuleStatusRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (row.isEmpty()) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    boolean next = !row.getItem().enabled();
                    preferences.put(row.getItem().ruleId(), next);
                    preferenceHandler.accept(row.getItem().ruleId(), next);
                    refreshRuleStatus();
                    refreshSummary();
                }
            });
            return row;
        });
        rules.setTooltip(new Tooltip("Double-click to enable/disable for this project. Built-in definitions are not modified."));
    }

    private void configureBindings() {
        bindingsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        bindingsTable.setPrefHeight(140);
        bindingsTable.setPlaceholder(new Label("No bound signals. Bind expected roles before running diagnostics."));
        bindingsTable.getColumns().add(col("Role", BindingRow::role,
                WorkstationLayoutMetrics.BINDING_ROLE_MIN, 140));
        bindingsTable.getColumns().add(col("Expected", BindingRow::expected,
                WorkstationLayoutMetrics.BINDING_EXPECTED_MIN, 88));
        bindingsTable.getColumns().add(col("Bound", BindingRow::current,
                WorkstationLayoutMetrics.BINDING_BOUND_MIN, 180));
        bindingsTable.getColumns().add(col("Status", BindingRow::status,
                WorkstationLayoutMetrics.BINDING_STATUS_MIN, 180));
        bindingsTable.setRowFactory(v -> {
            TableRow<BindingRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (row.isEmpty() || row.getItem().candidates().isEmpty()) {
                    return;
                }
                BindingRow item = row.getItem();
                if (item.candidates().size() == 1) {
                    bindHandler.accept(item.role(), item.candidates().getFirst().key());
                    return;
                }
                ComboBox<String> chooser = new ComboBox<>();
                for (BindingCandidate c : item.candidates()) {
                    chooser.getItems().add(c.signalName() + " | " + c.source() + " ch" + c.channel()
                            + " [" + c.unit() + "] " + c.sourceType() + " n=" + c.sampleCount());
                }
                chooser.setOnAction(ev -> {
                    int i = chooser.getSelectionModel().getSelectedIndex();
                    if (i >= 0) {
                        bindHandler.accept(item.role(), item.candidates().get(i).key());
                    }
                });
                evidence.setText("Ambiguous binding for " + item.role() + " — select a candidate:\n"
                        + String.join("\n", chooser.getItems()));
                if (!getChildren().contains(chooser)) {
                    setBottom(chooser);
                }
            });
            return row;
        });
    }

    private void configureFindings() {
        findings.getStyleClass().add("diagnostics-findings-table");
        findings.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        findings.setPrefHeight(220);
        findings.setMinWidth(WorkstationLayoutMetrics.CENTER_PANEL_MIN);
        findings.setPlaceholder(new Label("Run diagnostics after resolving bindings."));
        findings.getColumns().add(col("Time", FindingListRow::time,
                WorkstationLayoutMetrics.FINDINGS_TIME_MIN, 120));
        findings.getColumns().add(col("Severity", FindingListRow::severity,
                WorkstationLayoutMetrics.FINDINGS_SEVERITY_MIN, 96));
        findings.getColumns().add(col("Rule", FindingListRow::rule,
                WorkstationLayoutMetrics.FINDINGS_RULE_MIN, 140));
        TableColumn<FindingListRow, String> summary = col("Summary", FindingListRow::title,
                WorkstationLayoutMetrics.FINDINGS_SUMMARY_MIN, 280);
        summary.setMaxWidth(Double.MAX_VALUE);
        findings.getColumns().add(summary);
        findingsDurationCol = col("Duration", FindingListRow::duration,
                WorkstationLayoutMetrics.FINDINGS_DURATION_MIN, 88);
        findingsReviewCol = col("Review", FindingListRow::review,
                WorkstationLayoutMetrics.FINDINGS_REVIEW_MIN, 96);
        findings.getColumns().add(findingsDurationCol);
        findings.getColumns().add(findingsReviewCol);
        findings.setRowFactory(v -> {
            TableRow<FindingListRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (row.isEmpty() || row.getItem().finding() == null) {
                    return;
                }
                showFinding(row.getItem().finding());
            });
            return row;
        });
    }

    private void showFinding(DiagnosticFinding finding) {
        evidence.setText(fullEvidence(finding));
        DiagnosticFindingReviewRow review = reviews.get(finding.findingId());
        reviewBox.getSelectionModel().select(review == null
                ? FindingReviewState.NEW : FindingReviewState.valueOf(review.reviewState()));
        note.setText(review == null ? "" : review.engineerNote());
        findingHandler.accept(finding);
    }

    private void moveFinding(int delta) {
        int i = findings.getSelectionModel().getSelectedIndex() + delta;
        if (i < 0 || i >= findings.getItems().size()) {
            return;
        }
        findings.getSelectionModel().select(i);
        findings.scrollTo(i);
        FindingListRow row = findings.getItems().get(i);
        if (row.finding() != null) {
            showFinding(row.finding());
        }
    }

    private static <T> TableColumn<T, String> col(String title, java.util.function.Function<T, String> f, double w) {
        return col(title, f, Math.max(64, w * 0.85), w);
    }

    private static <T> TableColumn<T, String> col(
            String title,
            java.util.function.Function<T, String> f,
            double min,
            double pref
    ) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setMinWidth(min);
        c.setPrefWidth(pref);
        c.setCellValueFactory(v -> new SimpleStringProperty(f.apply(v.getValue())));
        return c;
    }

    /**
     * Demo-stable 3-panel layout. Width changes do not move panels or reload data.
     */
    void applyResponsiveLayout(double width) {
        if (!split.getItems().contains(leftPanel)
                || !split.getItems().contains(centerPanel)
                || !split.getItems().contains(rightPanel)) {
            split.getItems().setAll(leftPanel, centerPanel, rightPanel);
        }
        if (getRight() == rightPanel) {
            setRight(null);
        }
        if (findingsDurationCol != null) {
            findingsDurationCol.setVisible(true);
        }
        if (findingsReviewCol != null) {
            findingsReviewCol.setVisible(true);
        }
        split.setDividerPositions(0.22, 0.72);
    }

    private long count(AnalysisSeverity severity) {
        if (last == null) {
            return 0;
        }
        return last.findings().stream().filter(f -> f.severity() == severity).count();
    }

    private static String originLabel(DiagnosticRulePack pack) {
        boolean custom = ProjectCustomPack.PACK_ID.equals(pack.packId())
                || pack.rules().stream().anyMatch(r -> r.origin() == RuleOrigin.PROJECT_CUSTOM);
        return custom ? "PROJECT CUSTOM" : "BUILT-IN";
    }

    private static String fullEvidence(DiagnosticFinding finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rule            ").append(finding.ruleId()).append(" v").append(finding.ruleVersion()).append('\n');
        sb.append("Severity        ").append(finding.severity()).append('\n');
        long end = finding.endTimestampNanos() == null
                ? finding.startTimestampNanos() : finding.endTimestampNanos();
        sb.append(String.format(Locale.ROOT, "Time            %.3f s – %.3f s%n",
                finding.startTimestampNanos() / 1e9, end / 1e9));
        sb.append(String.format(Locale.ROOT, "Duration        %.3f s%n",
                Math.max(0, (end - finding.startTimestampNanos()) / 1e9)));
        sb.append("Explanation     ").append(finding.summary()).append('\n');
        sb.append('\n').append("Evidence\n");
        finding.evidence().forEach(e -> sb.append("  ").append(e.role()).append(": ").append(e.statement()).append('\n'));
        if (!finding.trace().isEmpty()) {
            sb.append('\n').append("Evaluation trace\n");
            finding.trace().forEach(t -> sb.append(t.passed() ? "  PASS  " : "  FAIL  ")
                    .append(t.clause()).append("  ").append(t.observed()).append('\n'));
        }
        if (!finding.guidance().isEmpty()) {
            sb.append('\n').append("Recommended checks\n");
            finding.guidance().forEach(g -> sb.append("  - ").append(g).append('\n'));
        }
        if (!finding.signalKeys().isEmpty()) {
            sb.append('\n').append("Signal references\n");
            finding.signalKeys().forEach(k -> sb.append("  ").append(k.signalName()).append('\n'));
        }
        sb.append('\n').append("Observed condition; not a proven physical root cause.");
        return sb.toString();
    }

    SplitPane splitForTest() {
        return split;
    }

    TableView<FindingListRow> findingsTableForTest() {
        return findings;
    }

    VBox detailsPanelForTest() {
        return rightPanel;
    }

    TableColumn<FindingListRow, String> findingsDurationColumnForTest() {
        return findingsDurationCol;
    }

    TableColumn<FindingListRow, String> findingsReviewColumnForTest() {
        return findingsReviewCol;
    }

    public record RuleStatusRow(String ruleId, String name, String category, String status, String detail, boolean enabled) {}
    public record FindingListRow(String time, String severity, String rule, String title, String duration, String review,
                                 DiagnosticFinding finding) {}
    public record BindingRow(String role, String expected, String current, String status,
                             List<BindingCandidate> candidates) {}
    public record PackChoice(String packId, String label) {
        public static PackChoice of(DiagnosticRulePack pack) {
            String origin = ProjectCustomPack.PACK_ID.equals(pack.packId()) ? "PROJECT CUSTOM" : "BUILT-IN";
            return new PackChoice(pack.packId(), origin + " — " + pack.name() + " v" + pack.packVersion());
        }

        @Override
        public String toString() {
            return label;
        }
    }
}

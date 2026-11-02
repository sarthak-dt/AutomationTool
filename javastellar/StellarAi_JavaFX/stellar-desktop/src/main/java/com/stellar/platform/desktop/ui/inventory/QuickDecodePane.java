package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.CoverageClassificationPolicy;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.QuickDecodeDecisionResolver;
import com.stellar.platform.desktop.presentation.QuickDecodeSelectionModel;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Virtualized, project-level Quick Decode UI.
 *
 * <p>The pane intentionally contains presentation logic only. Matching/recommendation decisions
 * come from {@link QuickDecodeDecisionResolver}; user intent is stored sparsely in
 * {@link QuickDecodeSelectionModel}. A {@link TreeTableView} is used instead of a VBox-per-channel
 * so hundreds of measurement files do not create thousands of live ComboBox/ProgressBar nodes.</p>
 */
public final class QuickDecodePane extends BorderPane {

    private static final NumberFormat COUNT = NumberFormat.getIntegerInstance(Locale.US);
    private static final double SIDEBAR_WIDTH = 248.0;
    private static final double SIDEBAR_HIDE_WIDTH = 1180.0;

    private final TextField search = new TextField();
    private final Button autoBestButton = new Button("Auto Select Best DBCs");
    private final Button selectAllButton = new Button("Select All Channels");
    private final Button deselectAllButton = new Button("Deselect All");
    private final Button clearOverridesButton = new Button("Clear All Overrides");
    private final Button decodeButton = new Button("Decode All Ready Channels");
    private final Button compactProceedButton = new Button("Review & Process");
    private final Button applyReviewButton = new Button("Apply to Review channels");
    private final Label bottomSummary = new Label();
    private final Label readyMetric = new Label("0 Ready");
    private final Label noMatchMetric = new Label("0 No Match");
    private final Label noCanDataMetric = new Label("0 No CAN Data");
    private final Label reviewMetric = new Label("0 Review");
    private final Label selectedMetric = new Label("0 Selected");
    private final VBox dbcUsageBox = new VBox(7);
    private final VBox sidebar = new VBox(12);
    private final TreeTableView<QuickRow> table = new TreeTableView<>();
    private final Label emptyState = new Label("Add measurement and DBC files to build an Auto Decode plan.");
    private final StackPane centerStack = new StackPane();
    /** User-controlled disclosure state. Sources start collapsed and stay expanded only when requested. */
    private final Set<ProjectSourceId> expandedSources = new LinkedHashSet<>();

    private final CoverageClassificationPolicy coveragePolicy = CoverageClassificationPolicy.defaults();
    private DesktopAppService appService;
    /** Full unfiltered project rows; search is a presentation filter only. */
    private List<QuickRow> allRows = List.of();
    /** Per-source presentation rollups rebuilt from authoritative channel decisions on each refresh. */
    private Map<ProjectSourceId, SourceRollup> sourceRollups = Map.of();
    private Consumer<SourceChannelKey> onStudyChannel;
    private Runnable onProceed;
    private Runnable onAddFiles;
    private Runnable modelListener;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    /** Explicit mode lifecycle; Detailed Mapping must not keep this heavy UI active. */
    private volatile boolean modeActive;
    private boolean refreshing;

    public QuickDecodePane() {
        getStyleClass().addAll("quick-decode-root", "workstation-page");
        setMinSize(0, 0);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        configureToolbar();
        configureTable();
        configureSidebar();
        configureBottomBar();

        centerStack.getStyleClass().add("quick-decode-center-stack");
        centerStack.setMinSize(0, 0);
        centerStack.getChildren().addAll(table, emptyState);
        emptyState.getStyleClass().addAll("muted", "quick-decode-empty");
        emptyState.setWrapText(true);
        emptyState.setMaxWidth(460);
        StackPane.setAlignment(emptyState, Pos.CENTER);

        BorderPane body = new BorderPane();
        body.getStyleClass().add("quick-decode-body");
        body.setCenter(centerStack);
        body.setRight(sidebar);
        body.setMinSize(0, 0);
        BorderPane.setMargin(sidebar, new Insets(0, 0, 0, 10));
        setCenter(body);

        widthProperty().addListener((obs, oldValue, newValue) -> updateResponsiveLayout(newValue.doubleValue()));
        updateResponsiveLayout(getWidth());
    }

    public void bind(
            DesktopAppService appService,
            Consumer<SourceChannelKey> onStudyChannel,
            Runnable onProceed,
            Runnable onAddFiles
    ) {
        Objects.requireNonNull(appService, "appService");
        if (this.appService != null && modelListener != null) {
            this.appService.definitionMatchingModel().removeListener(modelListener);
            this.appService.quickDecodeSelectionModel().removeListener(modelListener);
        }
        this.appService = appService;
        this.onStudyChannel = onStudyChannel;
        this.onProceed = onProceed;
        this.onAddFiles = onAddFiles;
        modelListener = this::requestRefresh;
        appService.definitionMatchingModel().addListener(modelListener);
        appService.quickDecodeSelectionModel().addListener(modelListener);
        requestRefresh();
    }


    /**
     * Activates/deactivates this mode without relying on scene-graph visibility as application state.
     * The owning workspace detaches inactive mode roots, so only one heavy JavaFX control tree is
     * eligible for CSS/layout at a time.
     */
    void setModeActive(boolean active) {
        boolean changed = this.modeActive != active;
        this.modeActive = active;
        if (active && changed) {
            requestRefresh();
        }
    }

    private void requestRefresh() {
        if (!isEffectivelyActive()) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            if (!refreshing) {
                refresh();
            }
            return;
        }
        // Matching is asynchronous and can publish candidates/mappings in bursts. At most one
        // FX refresh is queued; it always rebuilds from the latest authoritative model snapshot.
        if (!refreshQueued.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            refreshQueued.set(false);
            if (isEffectivelyActive()) {
                refresh();
            }
        });
    }

    public void refresh() {
        if (appService == null || refreshing || !isEffectivelyActive()) {
            return;
        }
        refreshing = true;
        try {
            allRows = buildAllRows();
            sourceRollups = buildSourceRollups(allRows);
            Set<SourceChannelKey> liveKeys = allRows.stream()
                    .flatMap(source -> source.children().stream())
                    .map(QuickRow::key)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            // Search/filtering must never delete hidden manual overrides or exclusions.
            appService.quickDecodeSelectionModel().retainChannels(liveKeys);
            rebuildTree(filterRows(allRows));
            // Plan metrics always describe the complete project, not the current search result.
            updateSummary(allRows);
        } finally {
            refreshing = false;
        }
    }

    private boolean isEffectivelyActive() {
        if (!modeActive) {
            return false;
        }
        Node node = this;
        while (node != null) {
            if (!node.isVisible() || !node.isManaged()) {
                return false;
            }
            node = node.getParent();
        }
        return true;
    }

    private void configureToolbar() {
        search.setPromptText("Search source / channel / DBC...");
        search.getStyleClass().add("search-field");
        search.setPrefWidth(300);
        search.setMinWidth(180);
        search.textProperty().addListener((obs, oldValue, newValue) -> refresh());

        Button compareAll = new Button("Compare All DBCs");
        compareAll.getStyleClass().addAll("secondary-button", "compact-button");
        compareAll.setTooltip(new Tooltip("Use Custom Decode to inspect channel-level candidate evidence."));
        compareAll.setOnAction(e -> {
            QuickRow first = firstVisibleChannel();
            if (first != null && onStudyChannel != null) {
                onStudyChannel.accept(first.key());
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, search, spacer, compareAll);
        toolbar.getStyleClass().add("quick-decode-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 12, 8, 12));
        setTop(toolbar);
    }

    private void configureTable() {
        table.getStyleClass().add("quick-decode-table");
        table.setShowRoot(false);
        table.setTableMenuButtonVisible(false);
        // Source rows place the strategy label above Override All. The combo needs 28px,
        // so the row is locked to 76px. A shorter fixed stride let the cell stay taller
        // than the row step and overlap the next row.
        table.setFixedCellSize(76);
        table.setColumnResizePolicy(TreeTableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setMinSize(0, 0);
        table.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        table.setPlaceholder(new Label("Waiting for CAN inventory and DBC matching..."));

        TreeTableColumn<QuickRow, QuickRow> includeCol = column("", 46, 46, 46);
        includeCol.setCellFactory(col -> new IncludeCell());

        TreeTableColumn<QuickRow, QuickRow> channelCol = column("Source / Channel", 200, 260, 420);
        channelCol.setCellFactory(col -> new ChannelCell());

        TreeTableColumn<QuickRow, QuickRow> idsCol = column("CAN IDs", 78, 86, 100);
        idsCol.setCellFactory(col -> new TextCell(row -> row == null ? "" : COUNT.format(row.canIds())));

        TreeTableColumn<QuickRow, QuickRow> channelsCol = column("Channels", 68, 76, 90);
        channelsCol.setCellFactory(col -> new TextCell(row -> {
            if (row == null) {
                return "";
            }
            return row.sourceRow() ? COUNT.format(fullSourceRow(row).children().size()) : "—";
        }));

        TreeTableColumn<QuickRow, QuickRow> recommendedCol = column("Recommended DBC(s)", 180, 220, 300);
        recommendedCol.setCellFactory(col -> new RecommendationCell());

        TreeTableColumn<QuickRow, QuickRow> selectedCol = column("Selected DBC", 180, 205, 270);
        selectedCol.setCellFactory(col -> new SelectedDbcCell());

        TreeTableColumn<QuickRow, QuickRow> idCoverageCol = column("CAN ID Coverage", 130, 145, 180);
        idCoverageCol.setCellFactory(col -> new CoverageCell(false));

        TreeTableColumn<QuickRow, QuickRow> frameCoverageCol = column("Frame Coverage", 125, 140, 175);
        frameCoverageCol.setCellFactory(col -> new CoverageCell(true));

        TreeTableColumn<QuickRow, QuickRow> decisionCol = column("Decision", 108, 118, 138);
        decisionCol.setCellFactory(col -> new DecisionCell());

        TreeTableColumn<QuickRow, QuickRow> statusCol = column("Status", 118, 145, 190);
        statusCol.setCellFactory(col -> new StatusCell());

        TreeTableColumn<QuickRow, QuickRow> actionCol = column("Actions", 82, 90, 100);
        actionCol.setCellFactory(col -> new ActionCell());

        table.getColumns().setAll(
                includeCol, channelCol, idsCol, channelsCol, recommendedCol, selectedCol,
                idCoverageCol, frameCoverageCol, decisionCol, statusCol, actionCol);
        // Keep the disclosure triangle / tree indentation in the Source / Channel column rather
        // than the checkbox column. This keeps source and child checkboxes on one vertical axis.
        table.setTreeColumn(channelCol);

        table.setRowFactory(tv -> new TreeTableRow<>() {
            {
                setMinHeight(76);
                setPrefHeight(76);
                setMaxHeight(76);
            }
            @Override
            protected void updateItem(QuickRow row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().removeAll("quick-source-row", "quick-channel-row", "quick-no-match-row");
                if (empty || row == null) {
                    return;
                }
                getStyleClass().add(row.sourceRow() ? "quick-source-row" : "quick-channel-row");
                if (!row.sourceRow() && decision(row).status() == QuickDecodeDecisionResolver.Status.NO_MATCH) {
                    getStyleClass().add("quick-no-match-row");
                }
            }
        });
    }

    private void configureSidebar() {
        sidebar.getStyleClass().add("quick-decode-sidebar");
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setMinWidth(SIDEBAR_WIDTH);
        sidebar.setMaxWidth(SIDEBAR_WIDTH);
        sidebar.setPadding(new Insets(10));

        Label title = new Label("Plan Overview");
        title.getStyleClass().add("quick-decode-section-title");
        readyMetric.getStyleClass().addAll("quick-metric", "quick-ready");
        noMatchMetric.getStyleClass().addAll("quick-metric", "quick-no-match");
        noCanDataMetric.getStyleClass().addAll("quick-metric", "quick-no-can-data");
        reviewMetric.getStyleClass().addAll("quick-metric", "quick-review");
        selectedMetric.getStyleClass().addAll("quick-metric", "quick-selected");
        VBox metrics = new VBox(7, selectedMetric, readyMetric, noMatchMetric, reviewMetric, noCanDataMetric);

        decodeButton.setText("Review & Process");
        decodeButton.getStyleClass().addAll("primary-button", "quick-decode-primary", "quick-proceed-sidebar",
                "workstation-primary-button");
        decodeButton.setMaxWidth(Double.MAX_VALUE);
        decodeButton.setDisable(true);
        decodeButton.setOnAction(e -> proceedToProcessing());

        Label usageTitle = new Label("DBC Usage (Selected)");
        usageTitle.getStyleClass().add("quick-decode-section-title");
        dbcUsageBox.getStyleClass().add("quick-dbc-usage");

        Label actionsTitle = new Label("Quick Actions");
        actionsTitle.getStyleClass().add("quick-decode-section-title");
        for (Button b : List.of(selectAllButton, deselectAllButton, autoBestButton, clearOverridesButton)) {
            b.getStyleClass().addAll("secondary-button", "compact-button", "quick-action-button");
            b.setMaxWidth(Double.MAX_VALUE);
        }
        selectAllButton.setOnAction(e -> setAllIncluded(true));
        deselectAllButton.setOnAction(e -> setAllIncluded(false));
        autoBestButton.setOnAction(e -> {
            if (appService != null) {
                appService.quickDecodeSelectionModel().resetToRecommendations(allProjectChannelKeys());
            }
        });
        clearOverridesButton.setOnAction(e -> {
            if (appService != null) {
                appService.quickDecodeSelectionModel().clearAllOverrides();
            }
        });
        VBox actions = new VBox(6, selectAllButton, deselectAllButton, autoBestButton, clearOverridesButton);

        Label legendTitle = new Label("Legend");
        legendTitle.getStyleClass().add("quick-decode-section-title");
        VBox legend = new VBox(5,
                legendLine("AUTO", "Deterministic DBC applied automatically"),
                legendLine("MANUAL", "Engineer override in Auto or Custom Decode"),
                legendLine("Ready with warning", "Native-only, or auto-selected from equally matching DBCs"),
                legendLine("No CAN Data", "Measurement inventory completed but no CAN channels were discovered"),
                legendLine("Needs Setup", "True blocker — a DBC cannot be applied safely"));

        sidebar.getChildren().setAll(
                title, metrics, decodeButton, new Separator(), usageTitle, dbcUsageBox,
                new Separator(), actionsTitle, actions,
                new Separator(), legendTitle, legend);
    }

    private void configureBottomBar() {
        Button addFiles = new Button("Add More Measurement Files");
        addFiles.getStyleClass().addAll("secondary-button", "compact-button");
        addFiles.setOnAction(e -> {
            if (onAddFiles != null) {
                onAddFiles.run();
            }
        });
        compactProceedButton.getStyleClass().addAll("primary-button", "quick-decode-primary",
                "workstation-primary-button");
        compactProceedButton.setDisable(true);
        compactProceedButton.setOnAction(e -> proceedToProcessing());
        applyReviewButton.getStyleClass().addAll("secondary-button", "compact-button", "secondary-action");
        applyReviewButton.setMinWidth(Region.USE_PREF_SIZE);
        applyReviewButton.setVisible(false);
        applyReviewButton.setManaged(false);
        applyReviewButton.setTooltip(new Tooltip(
                "Apply the selected DBC only to Review channels where it matches CAN IDs. No Match channels are not changed."));
        applyReviewButton.setOnAction(e -> applySelectedDbcToReviewChannels());
        // Wide layouts expose the primary CTA in Plan Overview. This compact copy is used only
        // when the sidebar is responsively hidden, so navigation is never lost on small laptops.
        compactProceedButton.setVisible(false);
        compactProceedButton.setManaged(false);
        bottomSummary.getStyleClass().add("quick-decode-summary");
        bottomSummary.setMinWidth(0);
        bottomSummary.setMaxWidth(Double.MAX_VALUE);
        bottomSummary.setTextOverrun(OverrunStyle.ELLIPSIS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(10, bottomSummary, spacer, applyReviewButton, addFiles, compactProceedButton);
        bottom.getStyleClass().addAll("quick-decode-bottom", "workstation-action-bar");
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(8, 10, 8, 10));
        setBottom(bottom);
    }

    private void refreshApplyReviewButton() {
        if (appService == null) {
            applyReviewButton.setVisible(false);
            applyReviewButton.setManaged(false);
            return;
        }
        java.util.Optional<DefinitionSourceId> pending = appService.explicitDbcPendingReviewApply();
        if (pending.isEmpty()) {
            applyReviewButton.setVisible(false);
            applyReviewButton.setManaged(false);
            return;
        }
        int count = appService.compatibleReviewChannelCount(pending.get());
        applyReviewButton.setText(count == 1
                ? "Apply to 1 Review channel"
                : "Apply to " + count + " Review channels");
        applyReviewButton.setVisible(count > 0);
        applyReviewButton.setManaged(count > 0);
    }

    private void applySelectedDbcToReviewChannels() {
        if (appService == null) {
            return;
        }
        appService.explicitDbcPendingReviewApply().ifPresent(id -> {
            appService.applySelectedDbcToCompatibleReviewChannels(id);
            refresh();
        });
    }

    private void proceedToProcessing() {
        if (onProceed != null && !decodeButton.isDisabled()) {
            onProceed.run();
        }
    }

    private List<QuickRow> buildAllRows() {
        if (appService == null) {
            return List.of();
        }
        List<QuickRow> out = new ArrayList<>();
        for (WorkspaceFile file : appService.workspace().measurementFiles()) {
            CanSourceInventory inventory = appService.sourceInventoryModel().inventoryFor(file.id()).orElse(null);
            if (inventory == null || inventory.status() != CanInventoryStatus.INVENTORY_READY) {
                continue;
            }
            ProjectSourceId sourceId = file.projectSourceId();
            String alias = appService.sourceAliasRegistry().aliasFor(sourceId);
            List<QuickRow> channels = new ArrayList<>(inventory.channels().size());
            for (CanChannelInventory channel : inventory.channels()) {
                SourceChannelKey key = SourceChannelKey.of(sourceId, channel.channelId());
                channels.add(QuickRow.channel(
                        sourceId,
                        alias,
                        file.fileName(),
                        key,
                        channel.displayName(),
                        channel.uniqueMessageCount(),
                        channel.frameCount()
                ));
            }
            out.add(QuickRow.source(
                    sourceId,
                    alias,
                    file.fileName(),
                    inventory.globalUniqueCanIdentityCount(),
                    inventory.totalCanFrames(),
                    channels
            ));
        }
        return List.copyOf(out);
    }

    private Map<ProjectSourceId, SourceRollup> buildSourceRollups(List<QuickRow> sources) {
        if (appService == null || sources == null || sources.isEmpty()) {
            return Map.of();
        }
        Map<ProjectSourceId, SourceRollup> rollups = new LinkedHashMap<>();
        for (QuickRow source : sources) {
            Map<DefinitionSourceId, RecommendationCount> recommendationCounts = new LinkedHashMap<>();
            int recommendationReview = 0;
            int recommendationNoMatch = 0;
            int recommendationNotApplicable = 0;
            int ready = 0;
            int review = 0;
            int noMatch = 0;
            int notApplicable = 0;
            int excluded = 0;

            for (QuickRow child : source.children()) {
                QuickDecodeDecisionResolver.Decision d = decision(child);
                switch (d.status()) {
                    case READY -> ready++;
                    case REVIEW -> review++;
                    case NO_MATCH -> noMatch++;
                    case NOT_APPLICABLE -> notApplicable++;
                    case EXCLUDED -> excluded++;
                }

                if (d.recommendation() != null) {
                    SourceChannelDefinitionCandidate candidate = d.recommendation();
                    RecommendationCount current = recommendationCounts.get(candidate.definitionSourceId());
                    recommendationCounts.put(candidate.definitionSourceId(), current == null
                            ? new RecommendationCount(candidate.definitionDisplayName(), 1)
                            : new RecommendationCount(current.displayName(), current.count() + 1));
                    continue;
                }

                ChannelRecommendationKind kind = appService.definitionMatchingModel().mapping(child.key())
                        .map(mapping -> mapping.recommendationKind())
                        .orElse(ChannelRecommendationKind.NO_MATCH);
                switch (kind) {
                    case AMBIGUOUS -> recommendationReview++;
                    case NOT_APPLICABLE -> recommendationNotApplicable++;
                    case NO_MATCH, CLEAR_BEST_MATCH -> recommendationNoMatch++;
                }
            }

            List<RecommendationCount> recommendations = recommendationCounts.values().stream()
                    .sorted(Comparator.comparingInt(RecommendationCount::count).reversed()
                            .thenComparing(RecommendationCount::displayName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            rollups.put(source.sourceId(), new SourceRollup(
                    source.children().size(),
                    recommendations,
                    recommendationReview,
                    recommendationNoMatch,
                    recommendationNotApplicable,
                    ready, review, noMatch, notApplicable, excluded));
        }
        return Map.copyOf(rollups);
    }

    private SourceRollup sourceRollup(QuickRow sourceRow) {
        if (sourceRow == null) {
            return SourceRollup.empty();
        }
        return sourceRollups.getOrDefault(sourceRow.sourceId(), SourceRollup.empty());
    }

    private String sourceStrategyText(QuickRow sourceRow) {
        if (appService == null || sourceRow == null) {
            return "Best per Channel";
        }
        QuickRow fullSource = fullSourceRow(sourceRow);
        Map<DefinitionSourceId, Integer> overrides = new LinkedHashMap<>();
        int overrideCount = 0;
        for (QuickRow child : fullSource.children()) {
            DefinitionSourceId override = appService.quickDecodeSelectionModel().overrideFor(child.key()).orElse(null);
            if (override != null) {
                overrideCount++;
                overrides.merge(override, 1, Integer::sum);
            }
        }
        if (overrideCount == 0) {
            return "Best per Channel";
        }
        if (overrideCount == fullSource.children().size() && overrides.size() == 1) {
            DefinitionSourceId id = overrides.keySet().iterator().next();
            String displayName = sourceDefinitions(fullSource).stream()
                    .filter(candidate -> candidate.definitionSourceId().equals(id))
                    .map(SourceChannelDefinitionCandidate::definitionDisplayName)
                    .findFirst()
                    .orElse("Selected DBC");
            return "Override All · " + displayName;
        }
        return "Custom Overrides · " + overrideCount + "/" + fullSource.children().size();
    }

    private List<QuickRow> filterRows(List<QuickRow> sources) {
        String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            return sources;
        }
        List<QuickRow> filteredSources = new ArrayList<>();
        for (QuickRow source : sources) {
            boolean sourceMatches = source.alias().toLowerCase(Locale.ROOT).contains(query)
                    || source.fileName().toLowerCase(Locale.ROOT).contains(query);
            List<QuickRow> children = sourceMatches
                    ? source.children()
                    : source.children().stream().filter(row -> matchesQuery(row, query)).toList();
            if (!children.isEmpty()) {
                filteredSources.add(QuickRow.source(
                        source.sourceId(), source.alias(), source.fileName(), source.canIds(), source.frames(), children));
            }
        }
        return List.copyOf(filteredSources);
    }

    private boolean matchesQuery(QuickRow row, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        StringBuilder haystack = new StringBuilder()
                .append(row.alias()).append(' ')
                .append(row.fileName()).append(' ')
                .append(row.channelLabel());
        if (row.key() != null && appService != null) {
            QuickDecodeDecisionResolver.Decision d = appService.quickDecodeDecisionResolver().resolve(row.key());
            if (d.recommendation() != null) {
                haystack.append(' ').append(d.recommendation().definitionDisplayName());
            }
            if (d.selected() != null) {
                haystack.append(' ').append(d.selected().definitionDisplayName());
            }
        }
        return haystack.toString().toLowerCase(Locale.ROOT).contains(query);
    }

    private void rebuildTree(List<QuickRow> sources) {
        TreeItem<QuickRow> root = new TreeItem<>();
        boolean searching = search.getText() != null && !search.getText().trim().isBlank();
        Set<ProjectSourceId> liveSources = sources.stream()
                .map(QuickRow::sourceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        expandedSources.retainAll(liveSources);
        for (QuickRow source : sources) {
            TreeItem<QuickRow> sourceItem = new TreeItem<>(source);
            // Default is intentionally collapsed for a dense project overview. Search expands
            // matching sources temporarily so a filtered channel is never hidden from the user.
            sourceItem.setExpanded(searching || expandedSources.contains(source.sourceId()));
            sourceItem.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                if (searching) {
                    return;
                }
                if (isExpanded) {
                    expandedSources.add(source.sourceId());
                } else {
                    expandedSources.remove(source.sourceId());
                }
            });
            for (QuickRow child : source.children()) {
                sourceItem.getChildren().add(new TreeItem<>(child));
            }
            root.getChildren().add(sourceItem);
        }
        table.setRoot(root);
        boolean empty = sources.isEmpty();
        table.setVisible(!empty);
        table.setManaged(!empty);
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
    }

    private void updateSummary(List<QuickRow> sources) {
        if (appService == null) {
            return;
        }
        int selected = 0;
        int ready = 0;
        int noMatch = 0;
        int review = 0;
        int noCanDataSources = 0;
        int noMatchSources = 0;
        int total = 0;
        long ids = 0L;
        long frames = 0L;
        Map<String, Integer> dbcUsage = new LinkedHashMap<>();
        for (QuickRow source : sources) {
            if (source.children().isEmpty()) {
                noCanDataSources++;
                continue;
            }
            boolean sourceHasNoMatchChannel = false;
            for (QuickRow row : source.children()) {
                total++;
                ids += row.canIds();
                frames += row.frames();
                QuickDecodeDecisionResolver.Decision d = decision(row);
                if (d.included()) {
                    selected++;
                    if (d.status() == QuickDecodeDecisionResolver.Status.NO_MATCH) {
                        sourceHasNoMatchChannel = true;
                    }
                }
                if (d.decodable()) {
                    ready++;
                    dbcUsage.merge(d.selected().definitionDisplayName(), 1, Integer::sum);
                } else if (d.included() && d.status() == QuickDecodeDecisionResolver.Status.NO_MATCH) {
                    noMatch++;
                } else if (d.included() && d.status() == QuickDecodeDecisionResolver.Status.REVIEW) {
                    review++;
                }
            }
            if (sourceHasNoMatchChannel) {
                noMatchSources++;
            }
        }
        DesktopAppService.DecisionProcessingReadiness readiness = appService.decisionProcessingReadiness();
        int configured = readiness.dbcConfiguredChannels();
        int warned = readiness.nativeOnlyChannels();
        int needsSetup = readiness.unresolvedChannels();
        selectedMetric.setText(selected + " Selected");
        readyMetric.setText(configured + " Configured");
        readyMetric.setTooltip(new Tooltip(configured
                + " selected channel(s) have a valid Auto or Manual DBC mapping."));
        noMatchMetric.setText(warned + " Ready with warning");
        noMatchMetric.setTooltip(new Tooltip(warned + " selected channel(s) have zero CAN-ID overlap with every uploaded DBC across "
                + noMatchSources + " source(s). Processing can continue without DBC-decoded CAN signals."));
        reviewMetric.setText(needsSetup + " Needs Setup");
        reviewMetric.setTooltip(new Tooltip(needsSetup == 0
                ? "No Auto Decode blockers. Review & Process can continue."
                : needsSetup + " channel(s) cannot have a DBC applied safely."));
        noCanDataMetric.setText(noCanDataSources + " No CAN Data");
        noCanDataMetric.setTooltip(new Tooltip("Measurement files with inventory ready but no CAN channels discovered. These are not counted as DBC No Match."));
        String summary = sources.size() + " Sources  ·  " + selected + "/" + total
                + " Channels Selected  ·  " + COUNT.format(ids) + " CAN IDs  ·  "
                + COUNT.format(frames) + " Frames  ·  " + (configured + warned) + " Processable";
        if (needsSetup > 0) {
            summary += "  ·  " + needsSetup + " Needs Setup";
        }
        bottomSummary.setText(summary);
        decodeButton.setText("Review & Process");
        boolean canProceed = appService.canProceedFromDecisionWorkspace();
        // Disabled buttons never show tooltips, so the blocking reason lives on the summary label.
        bottomSummary.setTooltip(new Tooltip(canProceed
                ? summary
                : summary + "\n" + appService.decisionProcessingHint()));
        decodeButton.setDisable(!canProceed);
        compactProceedButton.setDisable(!canProceed);
        refreshApplyReviewButton();
        String proceedHelp;
        if (!canProceed) {
            proceedHelp = appService.decisionProcessingHint();
        } else if (warned > 0 && configured == 0) {
            proceedHelp = "No matching DBC is available. Processing can continue, but DBC-decoded CAN signals will not be generated.";
        } else if (warned > 0) {
            proceedHelp = configured + " DBC-configured and " + warned
                    + " native-only channel(s) will be included. Native-only channels produce native/raw outputs only.";
        } else if (needsSetup > 0) {
            proceedHelp = configured + " configured channel(s) will be included; channels that Need Setup stay out of the plan.";
        } else {
            proceedHelp = "Continue with " + configured + " selected configured channel(s) in one processing plan.";
        }
        decodeButton.setTooltip(new Tooltip(proceedHelp));
        compactProceedButton.setTooltip(new Tooltip(proceedHelp));
        rebuildDbcUsage(dbcUsage, Math.max(1, ready));
    }

    private void rebuildDbcUsage(Map<String, Integer> usage, int ready) {
        dbcUsageBox.getChildren().clear();
        if (usage.isEmpty()) {
            Label none = new Label("No matching DBC is available. Native/raw outputs can still be processed.");
            none.getStyleClass().add("muted");
            none.setWrapText(true);
            dbcUsageBox.getChildren().add(none);
            return;
        }
        usage.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> {
                    Label name = new Label(entry.getKey());
                    name.setTextOverrun(OverrunStyle.ELLIPSIS);
                    name.setMaxWidth(145);
                    Label count = new Label(entry.getValue() + (entry.getValue() == 1 ? " channel" : " channels"));
                    count.getStyleClass().add("muted");
                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    HBox line = new HBox(5, name, spacer, count);
                    ProgressBar bar = new ProgressBar(entry.getValue() / (double) ready);
                    bar.setMaxWidth(Double.MAX_VALUE);
                    VBox block = new VBox(3, line, bar);
                    dbcUsageBox.getChildren().add(block);
                });
    }

    private void setAllIncluded(boolean included) {
        if (appService == null) {
            return;
        }
        List<SourceChannelKey> keys = allProjectChannelKeys();
        if (included) {
            appService.quickDecodeSelectionModel().selectAll(keys);
        } else {
            appService.quickDecodeSelectionModel().deselectAll(keys);
        }
    }

    private List<SourceChannelKey> allProjectChannelKeys() {
        return allRows.stream()
                .flatMap(source -> source.children().stream())
                .map(QuickRow::key)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<QuickRow> allVisibleChannelRows() {
        TreeItem<QuickRow> root = table.getRoot();
        if (root == null) {
            return List.of();
        }
        List<QuickRow> rows = new ArrayList<>();
        for (TreeItem<QuickRow> source : root.getChildren()) {
            for (TreeItem<QuickRow> child : source.getChildren()) {
                if (child.getValue() != null && !child.getValue().sourceRow()) {
                    rows.add(child.getValue());
                }
            }
        }
        return rows;
    }

    private QuickRow firstVisibleChannel() {
        return allVisibleChannelRows().stream().findFirst().orElse(null);
    }

    private QuickDecodeDecisionResolver.Decision decision(QuickRow row) {
        if (appService == null || row == null || row.key() == null) {
            return new QuickDecodeDecisionResolver.Decision(
                    row == null ? SourceChannelKey.of(ProjectSourceId.of("unbound"), 0) : row.key(),
                    false, null, null,
                    QuickDecodeDecisionResolver.SelectionOrigin.NONE,
                    QuickDecodeDecisionResolver.Status.EXCLUDED);
        }
        return appService.quickDecodeDecisionResolver().resolve(row.key());
    }

    private List<SourceChannelDefinitionCandidate> candidates(QuickRow row) {
        if (appService == null || row == null || row.key() == null) {
            return List.of();
        }
        return appService.candidatesFor(row.key()).stream()
                .sorted(Comparator
                        .comparingLong(SourceChannelDefinitionCandidate::matchedUniqueMessageCount).reversed()
                        .thenComparing(Comparator.comparingLong(SourceChannelDefinitionCandidate::matchedFrameCount).reversed())
                        .thenComparing(SourceChannelDefinitionCandidate::definitionDisplayName))
                .toList();
    }

    private List<SourceChannelDefinitionCandidate> sourceDefinitions(QuickRow sourceRow) {
        QuickRow fullSource = fullSourceRow(sourceRow);
        Map<DefinitionSourceId, SourceChannelDefinitionCandidate> unique = new LinkedHashMap<>();
        for (QuickRow child : fullSource.children()) {
            for (SourceChannelDefinitionCandidate candidate : candidates(child)) {
                unique.putIfAbsent(candidate.definitionSourceId(), candidate);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private void applyDefinitionToSource(QuickRow sourceRow, DefinitionSourceId definitionId) {
        if (appService == null || sourceRow == null || definitionId == null) {
            return;
        }
        QuickDecodeSelectionModel model = appService.quickDecodeSelectionModel();
        QuickRow fullSource = fullSourceRow(sourceRow);
        model.setOverrides(fullSource.children().stream().map(QuickRow::key).toList(), definitionId);
    }

    private QuickRow fullSourceRow(QuickRow row) {
        QuickRow required = Objects.requireNonNull(row, "row");
        return allRows.stream()
                .filter(candidate -> candidate.sourceId().equals(required.sourceId()))
                .findFirst()
                .orElse(required);
    }

    private void updateResponsiveLayout(double width) {
        boolean show = width <= 0 || width >= SIDEBAR_HIDE_WIDTH;
        sidebar.setVisible(show);
        sidebar.setManaged(show);
        compactProceedButton.setVisible(!show);
        compactProceedButton.setManaged(!show);
    }

    private static Node legendLine(String badgeText, String explanation) {
        Label badge = new Label(badgeText);
        badge.getStyleClass().addAll("quick-legend-badge", "quick-legend-" + badgeText.toLowerCase(Locale.ROOT).replace(' ', '-'));
        Label text = new Label(explanation);
        text.getStyleClass().add("muted");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(7, badge, text);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static TreeTableColumn<QuickRow, QuickRow> column(
            String title,
            double min,
            double pref,
            double max
    ) {
        TreeTableColumn<QuickRow, QuickRow> column = new TreeTableColumn<>(title);
        column.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().getValue()));
        column.setMinWidth(min);
        column.setPrefWidth(pref);
        column.setMaxWidth(max);
        column.setSortable(false);
        column.setReorderable(false);
        return column;
    }

    private final class IncludeCell extends TreeTableCell<QuickRow, QuickRow> {
        @Override
        protected void updateItem(QuickRow row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            setGraphic(null);
            if (empty || row == null || appService == null) {
                return;
            }
            CheckBox check = new CheckBox();
            if (row.sourceRow()) {
                if (row.children().isEmpty()) {
                    check.setSelected(false);
                    check.setDisable(true);
                    check.setTooltip(new Tooltip("No CAN channels discovered for this measurement source."));
                    setAlignment(Pos.CENTER);
                    setGraphic(check);
                    return;
                }
                boolean all = row.children().stream().allMatch(c -> appService.quickDecodeSelectionModel().included(c.key()));
                boolean any = row.children().stream().anyMatch(c -> appService.quickDecodeSelectionModel().included(c.key()));
                check.setSelected(all);
                check.setIndeterminate(!all && any);
                check.setAllowIndeterminate(false);
                check.setOnAction(e -> {
                    List<SourceChannelKey> keys = row.children().stream().map(QuickRow::key).toList();
                    if (check.isSelected()) {
                        appService.quickDecodeSelectionModel().selectAll(keys);
                    } else {
                        appService.quickDecodeSelectionModel().deselectAll(keys);
                    }
                });
            } else {
                check.setSelected(appService.quickDecodeSelectionModel().included(row.key()));
                check.setOnAction(e -> appService.quickDecodeSelectionModel().setIncluded(row.key(), check.isSelected()));
            }
            setAlignment(Pos.CENTER);
            setGraphic(check);
        }
    }

    private final class ChannelCell extends TreeTableCell<QuickRow, QuickRow> {
        @Override
        protected void updateItem(QuickRow row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            setGraphic(null);
            if (empty || row == null) {
                return;
            }
            Label primary = new Label(row.sourceRow() ? row.fileName() : row.channelLabel());
            primary.getStyleClass().add(row.sourceRow() ? "quick-source-name" : "quick-channel-name");
            primary.setTextOverrun(OverrunStyle.ELLIPSIS);
            if (row.sourceRow()) {
                primary.setTooltip(new Tooltip(row.fileName()));
                setGraphic(primary);
                return;
            }
            Label secondary = new Label(row.fileName());
            secondary.getStyleClass().add("muted");
            secondary.setTextOverrun(OverrunStyle.ELLIPSIS);
            secondary.setMaxWidth(270);
            VBox box = new VBox(1, primary, secondary);
            setGraphic(box);
        }
    }

    private final class RecommendationCell extends TreeTableCell<QuickRow, QuickRow> {
        @Override
        protected void updateItem(QuickRow row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            setGraphic(null);
            if (empty || row == null) {
                return;
            }
            if (row.sourceRow()) {
                SourceRollup rollup = sourceRollup(row);
                String summary = rollup.recommendationSummary();
                Label label = new Label(summary);
                label.getStyleClass().add("quick-source-dbc-summary");
                label.setTextOverrun(OverrunStyle.ELLIPSIS);
                label.setMaxWidth(Double.MAX_VALUE);
                label.setTooltip(new Tooltip(summary));
                setGraphic(label);
                return;
            }
            QuickDecodeDecisionResolver.Decision d = decision(row);
            SourceChannelDefinitionCandidate applied = d.selected() != null ? d.selected() : d.recommendation();
            if (applied == null) {
                boolean needsSetup = d.status() == QuickDecodeDecisionResolver.Status.REVIEW;
                Label label = new Label(needsSetup ? "Needs Setup" : "Native only");
                label.getStyleClass().add(needsSetup ? "quick-review-text" : "quick-no-match-text");
                label.setTooltip(new Tooltip(needsSetup
                        ? "A DBC cannot be applied safely. Use Custom Decode to inspect this channel."
                        : "No DBC assigned."));
                setGraphic(label);
                return;
            }
            Label name = new Label(applied.definitionDisplayName());
            name.getStyleClass().add("quick-dbc-name");
            Label provenance = new Label(d.manualOverride() ? "MANUAL" : "AUTO");
            provenance.getStyleClass().add(d.manualOverride() ? "quick-manual" : "quick-best-badge");
            HBox top = new HBox(5, name, provenance);
            top.setAlignment(Pos.CENTER_LEFT);
            Label coverage = new Label(d.manualOverride()
                    ? "Applied manually: " + formatIdCoverage(applied)
                    : "Applied automatically: " + formatIdCoverage(applied));
            coverage.getStyleClass().add("muted");
            setGraphic(new VBox(1, top, coverage));
        }
    }

    private final class SelectedDbcCell extends TreeTableCell<QuickRow, QuickRow> {
        @Override
        protected void updateItem(QuickRow row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            setGraphic(null);
            if (empty || row == null || appService == null) {
                return;
            }
            ComboBox<DbcChoice> combo = new ComboBox<>();
            combo.getStyleClass().add("quick-dbc-combo");
            combo.setMaxWidth(Double.MAX_VALUE);
            if (row.sourceRow()) {
                if (row.children().isEmpty()) {
                    Label notApplicable = new Label("N/A");
                    notApplicable.getStyleClass().add("muted");
                    notApplicable.setTooltip(new Tooltip("No DBC can be selected because this source has no discovered CAN channels."));
                    setGraphic(notApplicable);
                    return;
                }
                combo.getStyleClass().add("quick-source-override-combo");
                combo.setPromptText("Override All…");
                combo.setMinHeight(28);
                combo.setPrefHeight(28);
                combo.setMaxHeight(28);
                List<SourceChannelDefinitionCandidate> sourceDefs = sourceDefinitions(row);
                combo.getItems().setAll(sourceDefs.stream()
                        .map(candidate -> new DbcChoice(candidate.definitionSourceId(),
                                candidate.definitionDisplayName(), 0L, 0L, 0.0))
                        .toList());
                Label strategy = new Label(sourceStrategyText(row));
                strategy.getStyleClass().add("quick-source-strategy");
                strategy.setTextOverrun(OverrunStyle.ELLIPSIS);
                strategy.setMaxWidth(Double.MAX_VALUE);
                strategy.setTooltip(new Tooltip(
                        "Default: each channel uses its own best DBC. Override All applies one DBC to every channel without changing the recommendations."));
                combo.setOnAction(e -> {
                    DbcChoice choice = combo.getValue();
                    if (choice != null) {
                        applyDefinitionToSource(row, choice.id());
                        combo.getSelectionModel().clearSelection();
                    }
                });
                combo.setTooltip(new Tooltip(
                        "Apply one DBC to every channel in this source. Per-channel recommendations remain visible."));
                VBox box = new VBox(2, strategy, combo);
                box.setFillWidth(true);
                box.setAlignment(Pos.CENTER_LEFT);
                setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
                return;
            } else {
                List<SourceChannelDefinitionCandidate> candidates = candidates(row);
                combo.getItems().setAll(candidates.stream().map(DbcChoice::of).toList());
                QuickDecodeDecisionResolver.Decision d = decision(row);
                if (d.selected() != null) {
                    DefinitionSourceId selectedId = d.selected().definitionSourceId();
                    combo.getItems().stream()
                            .filter(choice -> choice.id().equals(selectedId))
                            .findFirst()
                            .ifPresent(combo::setValue);
                }
                combo.setOnAction(e -> {
                    DbcChoice choice = combo.getValue();
                    if (choice != null) {
                        appService.quickDecodeSelectionModel().setOverride(row.key(), choice.id());
                    }
                });
            }
            setGraphic(combo);
        }
    }

    private final class CoverageCell extends TreeTableCell<QuickRow, QuickRow> {
        private final boolean frameCoverage;

        private CoverageCell(boolean frameCoverage) {
            this.frameCoverage = frameCoverage;
        }

        @Override
        protected void updateItem(QuickRow row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            setGraphic(null);
            if (empty || row == null || row.sourceRow()) {
                return;
            }
            QuickDecodeDecisionResolver.Decision d = decision(row);
            SourceChannelDefinitionCandidate c = d.selected();
            if (c == null) {
                Label none = new Label("—");
                none.getStyleClass().add("muted");
                setGraphic(none);
                return;
            }
            double ratio = frameCoverage ? c.frameCoverageRatio() : c.coverageRatio();
            String text = frameCoverage
                    ? formatPercent(ratio)
                    : formatIdCoverage(c);
            Label label = new Label(text);
            ProgressBar bar = new ProgressBar(ratio);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.getStyleClass().add(coverageStyle(ratio));
            VBox box = new VBox(3, label, bar);
            box.setFillWidth(true);
            setGraphic(box);
        }
    }

    private final class DecisionCell extends TreeTableCell<QuickRow, QuickRow> {
        @Override
        protected void updateItem(QuickRow row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            setGraphic(null);
            if (empty || row == null || row.sourceRow()) {
                return;
            }
            QuickDecodeDecisionResolver.Decision d = decision(row);
            String text = d.manualOverride() ? "MANUAL" : d.origin() == QuickDecodeDecisionResolver.SelectionOrigin.AUTO_RECOMMENDATION
                    ? "AUTO" : "—";
            Label badge = new Label(text);
            badge.setWrapText(true);
            badge.getStyleClass().addAll("quick-decision-badge",
                    d.manualOverride() ? "quick-manual" : "quick-auto");
            setGraphic(badge);
        }
    }

    private final class StatusCell extends TreeTableCell<QuickRow, QuickRow> {
        @Override
        protected void updateItem(QuickRow row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            setGraphic(null);
            if (empty || row == null) {
                return;
            }
            if (row.sourceRow()) {
                SourceRollup rollup = sourceRollup(row);
                String summary = rollup.statusSummary();
                Label label = new Label(summary);
                label.setTextOverrun(OverrunStyle.ELLIPSIS);
                label.setMaxWidth(Double.MAX_VALUE);
                label.getStyleClass().addAll("quick-status-badge", rollup.statusStyleClass());
                label.setTooltip(new Tooltip(summary));
                setGraphic(label);
                return;
            }
            QuickDecodeDecisionResolver.Decision d = decision(row);
            String text = switch (d.status()) {
                case READY -> d.autoSelectedWithWarning() ? "Ready with warning" : "Ready";
                case NO_MATCH -> "Ready with warning";
                case REVIEW -> "Needs Setup";
                case NOT_APPLICABLE -> "N/A";
                case EXCLUDED -> "Excluded";
            };
            Label label = new Label(text);
            label.getStyleClass().addAll("quick-status-badge", "quick-status-" + text.toLowerCase(Locale.ROOT).replace(' ', '-').replace("/", ""));
            if (d.autoSelectedWithWarning()) {
                label.setTooltip(new Tooltip(
                        "Auto-selected from matching definitions. Use Custom Decode to review or override."));
            } else if (d.status() == QuickDecodeDecisionResolver.Status.NO_MATCH) {
                label.setTooltip(new Tooltip(d.selected() != null
                        ? "Selected DBC has 0 matching CAN IDs for this channel. Processing can continue without DBC-decoded CAN signals."
                        : "No DBC assigned. Processing can continue, but DBC-decoded CAN signals will not be generated."));
            } else if (d.status() == QuickDecodeDecisionResolver.Status.REVIEW) {
                label.setTooltip(new Tooltip(
                        "A DBC cannot be applied safely. Use Custom Decode to inspect this channel."));
            }
            setGraphic(label);
        }
    }

    private final class ActionCell extends TreeTableCell<QuickRow, QuickRow> {
        @Override
        protected void updateItem(QuickRow row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            setGraphic(null);
            if (empty || row == null || row.sourceRow()) {
                return;
            }
            Button study = new Button("Study");
            study.getStyleClass().addAll("secondary-button", "compact-button", "quick-study-button");
            study.setOnAction(e -> {
                if (onStudyChannel != null) {
                    onStudyChannel.accept(row.key());
                }
            });
            setGraphic(study);
        }
    }

    private static final class TextCell extends TreeTableCell<QuickRow, QuickRow> {
        private final java.util.function.Function<QuickRow, String> textProvider;

        private TextCell(java.util.function.Function<QuickRow, String> textProvider) {
            this.textProvider = textProvider;
        }

        @Override
        protected void updateItem(QuickRow row, boolean empty) {
            super.updateItem(row, empty);
            setGraphic(null);
            setText(empty || row == null ? null : textProvider.apply(row));
            setAlignment(Pos.CENTER_RIGHT);
        }
    }

    private static String formatIdCoverage(SourceChannelDefinitionCandidate c) {
        return COUNT.format(c.matchedUniqueMessageCount()) + "/" + COUNT.format(c.observedUniqueMessageCount())
                + " · " + formatPercent(c.coverageRatio());
    }

    private static String formatPercent(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", Math.max(0.0, Math.min(1.0, ratio)) * 100.0);
    }

    private String coverageStyle(double ratio) {
        return switch (coveragePolicy.classify(ratio)) {
            case NONE -> "quick-coverage-none";
            case LOW -> "quick-coverage-low";
            case MEDIUM -> "quick-coverage-medium";
            case HIGH -> "quick-coverage-high";
        };
    }

    private record RecommendationCount(String displayName, int count) {
    }

    private record SourceRollup(
            int channelCount,
            List<RecommendationCount> recommendations,
            int recommendationReview,
            int recommendationNoMatch,
            int recommendationNotApplicable,
            int ready,
            int review,
            int noMatch,
            int notApplicable,
            int excluded
    ) {
        private static SourceRollup empty() {
            return new SourceRollup(0, List.of(), 0, 0, 0, 0, 0, 0, 0, 0);
        }

        private String recommendationSummary() {
            List<String> parts = new ArrayList<>();
            for (RecommendationCount recommendation : recommendations) {
                parts.add(recommendation.displayName() + " · " + recommendation.count() + " Ch");
            }
            if (recommendationReview > 0) {
                parts.add("Review · " + recommendationReview + " Ch");
            }
            if (recommendationNoMatch > 0) {
                parts.add("No Match · " + recommendationNoMatch + " Ch");
            }
            if (recommendationNotApplicable > 0) {
                parts.add("N/A · " + recommendationNotApplicable + " Ch");
            }
            if (parts.isEmpty() && channelCount == 0) {
                return "No CAN channels discovered";
            }
            return parts.isEmpty() ? "Waiting for recommendation" : String.join("  •  ", parts);
        }

        private String statusSummary() {
            List<String> parts = new ArrayList<>();
            if (ready > 0) {
                parts.add(ready + " Ready");
            }
            if (review > 0) {
                parts.add(review + " Review");
            }
            if (noMatch > 0) {
                parts.add(noMatch + " No Match");
            }
            if (notApplicable > 0) {
                parts.add(notApplicable + " N/A");
            }
            if (excluded > 0) {
                parts.add(excluded + " Excluded");
            }
            if (parts.isEmpty() && channelCount == 0) {
                return "No CAN Data";
            }
            return parts.isEmpty() ? channelCount + " Channels" : String.join(" · ", parts);
        }

        private String statusStyleClass() {
            if (noMatch > 0) {
                return "quick-status-no-match";
            }
            if (review > 0) {
                return "quick-status-review";
            }
            if (ready > 0 && ready == channelCount) {
                return "quick-status-ready";
            }
            return "quick-status-excluded";
        }
    }

    private record DbcChoice(
            DefinitionSourceId id,
            String displayName,
            long matched,
            long observed,
            double coverage
    ) {
        static DbcChoice of(SourceChannelDefinitionCandidate candidate) {
            return new DbcChoice(
                    candidate.definitionSourceId(),
                    candidate.definitionDisplayName(),
                    candidate.matchedUniqueMessageCount(),
                    candidate.observedUniqueMessageCount(),
                    candidate.coverageRatio());
        }

        @Override
        public String toString() {
            if (observed <= 0L) {
                return displayName;
            }
            if (matched <= 0L) {
                return displayName + " — No Match";
            }
            return displayName + " — " + matched + "/" + observed + " · " + formatPercent(coverage);
        }
    }

    private record QuickRow(
            ProjectSourceId sourceId,
            String alias,
            String fileName,
            SourceChannelKey key,
            String channelLabel,
            long canIds,
            long frames,
            boolean sourceRow,
            List<QuickRow> children
    ) {
        static QuickRow source(
                ProjectSourceId sourceId,
                String alias,
                String fileName,
                long canIds,
                long frames,
                List<QuickRow> children
        ) {
            return new QuickRow(sourceId, alias, fileName, null, "", canIds, frames, true, List.copyOf(children));
        }

        static QuickRow channel(
                ProjectSourceId sourceId,
                String alias,
                String fileName,
                SourceChannelKey key,
                String channelLabel,
                long canIds,
                long frames
        ) {
            return new QuickRow(sourceId, alias, fileName, key, channelLabel, canIds, frames, false, List.of());
        }
    }

    // Test hooks keep layout assertions deterministic without exposing implementation mutability.
    TreeTableView<QuickRow> tableForTest() {
        return table;
    }

    VBox sidebarForTest() {
        return sidebar;
    }

    Button decodeButtonForTest() {
        return decodeButton;
    }

    Button compactProceedButtonForTest() {
        return compactProceedButton;
    }

    String readyMetricTextForTest() {
        return readyMetric.getText();
    }

    String sourceRecommendationSummaryForTest(ProjectSourceId sourceId) {
        return sourceRollups.getOrDefault(sourceId, SourceRollup.empty()).recommendationSummary();
    }

    String sourceStatusSummaryForTest(ProjectSourceId sourceId) {
        return sourceRollups.getOrDefault(sourceId, SourceRollup.empty()).statusSummary();
    }

    Button applyReviewButtonForTest() {
        return applyReviewButton;
    }

    String bottomSummaryTextForTest() {
        return bottomSummary.getText();
    }

    String bottomSummaryTooltipForTest() {
        return bottomSummary.getTooltip() == null ? "" : bottomSummary.getTooltip().getText();
    }

    String noMatchMetricTextForTest() {
        return noMatchMetric.getText();
    }

    String noCanDataMetricTextForTest() {
        return noCanDataMetric.getText();
    }

    String sourceStrategyTextForTest(ProjectSourceId sourceId) {
        return allRows.stream()
                .filter(row -> row.sourceId().equals(sourceId))
                .findFirst()
                .map(this::sourceStrategyText)
                .orElse("Best per Channel");
    }

    List<String> columnTitlesForTest() {
        return table.getColumns().stream().map(TreeTableColumn::getText).toList();
    }

    boolean sourceExpandedForTest(ProjectSourceId sourceId) {
        if (table.getRoot() == null) {
            return false;
        }
        return table.getRoot().getChildren().stream()
                .filter(item -> item.getValue() != null && item.getValue().sourceId().equals(sourceId))
                .findFirst()
                .map(TreeItem::isExpanded)
                .orElse(false);
    }
}

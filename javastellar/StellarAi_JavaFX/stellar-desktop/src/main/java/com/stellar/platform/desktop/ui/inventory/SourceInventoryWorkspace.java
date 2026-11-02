package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.CanMessageInventoryRow;
import com.stellar.platform.desktop.presentation.ChannelMappingPreview;
import com.stellar.platform.desktop.presentation.CoverageClassificationPolicy;
import com.stellar.platform.desktop.presentation.CoverageClassificationPolicy.CoverageQuality;
import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.InventoryEnrichmentMatchState;
import com.stellar.platform.desktop.presentation.InventoryStudySet;
import com.stellar.platform.desktop.presentation.ProjectDefinitionMatchingCoordinator;
import com.stellar.platform.desktop.presentation.ProjectDefinitionMatchingModel;
import com.stellar.platform.desktop.presentation.SourceChannelEnrichmentContext;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver;
import com.stellar.platform.desktop.presentation.SourceInventoryEnrichmentService;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.CanTypeFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.ContentFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.MatchFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryWorkspaceModel;
import com.stellar.platform.desktop.presentation.SourceInventoryWorkspaceModel.ChannelView;
import com.stellar.platform.desktop.presentation.SourceInventoryWorkspaceModel.SourceView;
import com.stellar.platform.desktop.presentation.SourceInventoryWorkspaceModel.WorkspaceSnapshot;
import com.stellar.platform.desktop.presentation.SourceInventoryWorkspaceQuery;
import com.stellar.platform.desktop.presentation.WorkingDbcSelectionStore.DisplayState;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelDefinitionComparison;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.ui.StellarIcons;
import com.stellar.platform.desktop.ui.WorkstationLayoutMetrics;
import com.stellar.platform.desktop.ui.inventory.ChannelSignalExplorerPane.ExplorerContext;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.inventory.CanInventoryDiagnostic;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.stage.FileChooser;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Unified Decision Workspace: source/channel inspection + DBC mapping in one screen.
 * Consumes existing {@link CanSourceInventory} and matching models only — no reparsing.
 */
public final class SourceInventoryWorkspace extends BorderPane {

    private static final double NAV_MIN = 240;
    private static final double NAV_PREF = 255;
    private static final double NAV_MAX = 270;
    private static final double INSPECTOR_MIN = WorkstationLayoutMetrics.SIDE_PANEL_MIN;
    private static final double INSPECTOR_PREF = WorkstationLayoutMetrics.SIDE_PANEL_PREF;
    private static final double INSPECTOR_MAX = 360;
    private static final double CH_LABEL_MIN = 72;
    private static final double CH_IDS_W = 56;
    private static final double CH_SIG_W = 72;
    private static final double STATUS_BADGE_W = 64;
    private static final String INVENTORY_SEMANTICS_HELP =
            "Unique CAN IDs are source-wide union; table rows preserve per-channel observations.\n"
                    + "Channel-ID matches are per-channel, not source-global unique.";

    private final Label title = new Label("SOURCE INVENTORY");
    private final Label workspaceSummary = new Label("");
    private final TextField searchField = new TextField();
    private final ComboBox<String> canTypeCombo = new ComboBox<>();
    private final ComboBox<String> matchCombo = new ComboBox<>();
    private final ComboBox<String> contentCombo = new ComboBox<>();
    private final Button clearButton = new Button("Reset");
    private final Label resultCountLabel = new Label("");
    private final Label scopeTitle = new Label("");
    private final Label scopeFile = new Label("");
    private final Label scopeSummary = new Label("");
    private final Label compactMetaLine = new Label("");
    private final SourceChannelContextHeader contextHeader = new SourceChannelContextHeader();
    private final Label dbcContextLabel = new Label("");
    private final Label coverageLabel = new Label("");
    private final Label unionHint = new Label(INVENTORY_SEMANTICS_HELP);
    private final Button semanticsHelpButton = new Button("ⓘ");
    private final ToggleButton chipAll = new ToggleButton("All");
    private final ToggleButton chipMatched = new ToggleButton("Matched");
    private final ToggleButton chipUnmatched = new ToggleButton("Unmatched");
    private final HBox quickFilterBar = new HBox(6);
    private final Button selectAllMatchedButton = new Button("Add All Matched (0)");
    private final Button selectVisibleButton = new Button("Add Filtered (0)");
    private final Button clearStudyButton = new Button("Clear Study Set");
    private final Button openStudySetButton = new Button("Open Study Set");
    private final Label studySetSummaryLabel = new Label("Study Set: 0 CAN Messages");
    private final HBox studyBar = new HBox(8);
    private final HBox scopeRow = new HBox(8);
    private final HBox selectionActionRow = new HBox(8);
    private final HBox studyActionRow = new HBox(8);
    private final VBox studySelectionStack = new VBox(6);
    private final Label scopeRowCaption = new Label("Scope");
    private final Tooltip selectVisibleSameSetTooltip = new Tooltip(
            "Current view already contains all matched CAN messages.");
    private final Tooltip selectVisibleDefaultTooltip = new Tooltip(
            "Add matched CAN messages currently included by the active search and filters.");
    private final Tooltip openStudySetTooltip = new Tooltip(
            "Explore signal definitions from selected matched CAN messages.");
    private final Tooltip closeStudySetTooltip = new Tooltip("Close the Study Set drawer.");
    private final InventoryStudySet studySet = new InventoryStudySet();
    private final CanInventoryDetailsInspector detailsInspector = new CanInventoryDetailsInspector();
    private final Button expandInspectorButton = new Button();
    private final Label centerState = new Label("");
    private final ProgressIndicator indexingIndicator = new ProgressIndicator();
    private final Button addFilesButton = new Button("Add Measurement Files");
    private final VBox emptyPane = new VBox(10);
    private final HBox tableFooter = new HBox(10);
    private final Label mappingStatusBadge = new Label("");
    private final Label dbcNameLabel = new Label("");
    private final HBox mappingLine = new HBox(8);
    private String cachedIdsMeta = "";
    private String cachedFramesMeta = "";
    private String cachedMappingMeta = "";
    private String cachedIdsValue = "";
    private String cachedIdsCaption = "CAN IDs";
    private String cachedFramesValue = "";
    private String cachedChannelPrimary = "";
    private String cachedChannelCaption = "Channel";
    private String cachedMatchText = "";
    private String cachedCoverageText = "";
    private String cachedMatchTooltip = "";
    private String cachedMappingTooltip = "";
    private String cachedAggregateDetail = "";
    private boolean cachedShowDbcTile;
    private boolean cachedActiveChannel;
    private SourceView cachedContextSource;

    private final TreeView<NavNode> navigator = new TreeView<>();
    private final ScrollPane navScroll = new ScrollPane();
    private final ScrollPane centerScroll = new ScrollPane();
    private final VBox centerColumn = new VBox();
    private final HBox navMetricsHeader = new HBox(6);
    private final Label navIdsHead = new Label("CAN IDs");
    private final Label navMatchedHead = new Label("DBC Matched");
    private final VBox navBody = new VBox(4);
    private final VBox leftNavPane = new VBox(8);
    private final CheckBox studyHeaderCheck = new CheckBox();
    private boolean syncingStudyHeaderCheck;
    private final TreeItem<NavNode> navRoot = new TreeItem<>(NavNode.root());
    private final TableView<CanMessageInventoryRow> table = new TableView<>();
    private final ObservableList<CanMessageInventoryRow> tableRows = FXCollections.observableArrayList();
    private final SplitPane split = new SplitPane();
    private final NumberFormat intFormat = NumberFormat.getIntegerInstance(Locale.US);
    private final Label tablePlaceholder = new Label("No CAN message identities in this scope.");

    private final SourceInventoryWorkspaceModel model = new SourceInventoryWorkspaceModel();
    private final DbcCandidateStrip candidateStrip = new DbcCandidateStrip();
    private final ChannelQuickSelector channelSelector = new ChannelQuickSelector();
    private final ChannelSignalExplorerPane signalExplorer = new ChannelSignalExplorerPane();
    private final DecisionInspectorDrawer inspectorDrawer =
            new DecisionInspectorDrawer(signalExplorer, detailsInspector);
    private final DecisionActionBar decisionActionBar = new DecisionActionBar();
    private final StackPane mappingStage = new StackPane();
    private final Map<String, Integer> lastMappingChannelBySource = new HashMap<>();
    private Integer activeMappingChannelId;
    private final DecisionWorkspaceSummary workspaceCounts = new DecisionWorkspaceSummary();
    private final CoverageClassificationPolicy coveragePolicy = CoverageClassificationPolicy.defaults();
    private final ComboBox<String> stateFilter = new ComboBox<>();
    private final ComboBox<String> coverageFilter = new ComboBox<>();
    private final CheckBox unmappedOnly = new CheckBox("Unmapped only");
    private final Label stateFilterCaption = new Label("State");
    private final Label coverageFilterCaption = new Label("Coverage");
    private final FlowPane statusPills = new FlowPane();
    private final Set<SourceChannelKey> attentionKeys = new HashSet<>();
    private DesktopAppService appService;
    private Runnable onAddMeasurementFiles;
    private Runnable onProceedToProcessing;
    private Consumer<DecisionWorkspaceMode> onDecisionModeChanged;
    private WorkspaceSnapshot snapshot;
    private boolean syncingSelection;
    private boolean syncingMatchFilterUi;
    private boolean inspectorCollapsed;
    private boolean restoreInspectOnExpand;
    private boolean restoreDrawerOnExpand;
    private DecisionInspectorDrawer.Tab rememberedDrawerTab = DecisionInspectorDrawer.Tab.SIGNALS;
    private double rememberedInspectorDivider = 0.78;
    private int structuralRebuildCountForTest;
    private final Button moreFiltersButton = new Button("More");
    private final Label canTypeFilterCaption = new Label("CAN");
    private final Label dbcMatchFilterCaption = new Label("Match");
    private final Label contentFilterCaption = new Label("Content");
    private final Label searchFilterCaption = new Label("Search");
    private final Label idsMetricBadge = new Label("");
    private final Label framesMetricBadge = new Label("");
    private final HBox scopeMetricsRow = new HBox(8);
    private final HBox contextActions = new HBox(8);
    private final Button headerInspectButton = new Button("Inspect");
    private final Button headerSignalsButton = new Button("Signals");
    private final Button headerStudyButton = new Button("Study Set (0)");
    private final Button detailedFiltersButton = new Button("Filters");
    private final TextField navigatorSearchField = new TextField();
    private final HBox toolbar = new HBox(8);
    private HBox detailedFilterDrawer;
    private BorderPane centerPane;
    private BorderPane inventoryWorkspace;
    /** Lazily constructed so Detailed Mapping does not pay JavaFX skin/CSS cost for Quick Decode. */
    private QuickDecodePane quickDecodePane;
    private final StackPane decisionModeHost = new StackPane();
    private BorderPane detailedModePane;
    /** Selected by the Project Files "Decode Strategy" chrome — not a second in-page chooser. */
    private DecisionWorkspaceMode activeDecisionMode = DecisionWorkspaceMode.QUICK_DECODE;

    public SourceInventoryWorkspace() {
        getStyleClass().addAll("source-inventory-root", "decision-workspace-root");
        setMinHeight(0);
        setPrefHeight(Region.USE_COMPUTED_SIZE);
        setMaxHeight(Double.MAX_VALUE);
        setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(this, Priority.ALWAYS);

        title.getStyleClass().add("source-inventory-title");
        workspaceSummary.getStyleClass().add("source-inventory-summary");
        scopeTitle.getStyleClass().add("source-inventory-scope-title");
        scopeFile.getStyleClass().add("muted");
        scopeSummary.getStyleClass().add("source-inventory-scope-summary");
        unionHint.getStyleClass().addAll("muted", "source-inventory-union-hint");
        unionHint.setWrapText(true);
        unionHint.setVisible(false);
        unionHint.setManaged(false);
        semanticsHelpButton.getStyleClass().addAll("secondary-button", "compact-button", "inventory-semantics-help");
        semanticsHelpButton.setTooltip(new Tooltip(INVENTORY_SEMANTICS_HELP));
        semanticsHelpButton.setFocusTraversable(false);
        resultCountLabel.getStyleClass().addAll("muted", "source-inventory-result-count", "workspace-footer-metric");
        centerState.getStyleClass().add("muted");
        centerState.setWrapText(true);
        indexingIndicator.setMaxSize(28, 28);
        indexingIndicator.setVisible(false);
        indexingIndicator.setManaged(false);
        studySetSummaryLabel.getStyleClass().add("inventory-study-set-summary");
        studySetSummaryLabel.setVisible(false);
        studySetSummaryLabel.setManaged(false);
        openStudySetButton.getStyleClass().addAll("secondary-button", "compact-button", "inventory-study-action");
        openStudySetButton.setVisible(false);
        openStudySetButton.setManaged(false);
        mappingStatusBadge.getStyleClass().add("inventory-mapping-badge");
        dbcNameLabel.getStyleClass().add("inventory-dbc-name");
        mappingLine.getStyleClass().add("inventory-mapping-line");
        mappingLine.setAlignment(Pos.CENTER_LEFT);
        mappingLine.setVisible(false);
        mappingLine.setManaged(false);
        compactMetaLine.getStyleClass().addAll("muted", "inventory-compact-meta");
        compactMetaLine.setTextOverrun(OverrunStyle.ELLIPSIS);
        compactMetaLine.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(compactMetaLine, Priority.ALWAYS);

        searchField.setPromptText("Search source / channel / CAN / signal...");
        searchField.getStyleClass().add("search-field");
        searchField.setMinHeight(28);
        searchField.setPrefHeight(30);
        searchField.setMaxHeight(32);
        searchField.setMinWidth(200);
        searchField.setPrefWidth(280);
        searchField.setMaxWidth(360);
        HBox.setHgrow(searchField, Priority.SOMETIMES);

        // Detailed Mapping keeps navigation search in the source/channel panel so the center
        // workspace stays decision-focused. It is bound to the existing search field/model;
        // this is presentation-only and does not change filtering semantics.
        navigatorSearchField.setPromptText("Search sources or channels...");
        navigatorSearchField.getStyleClass().addAll("search-field", "detailed-nav-search");
        navigatorSearchField.setMinHeight(30);
        navigatorSearchField.setPrefHeight(32);
        navigatorSearchField.setMaxHeight(34);
        navigatorSearchField.setMinWidth(0);
        navigatorSearchField.setMaxWidth(Double.MAX_VALUE);
        navigatorSearchField.textProperty().bindBidirectional(searchField.textProperty());
        VBox.setVgrow(navigatorSearchField, Priority.NEVER);

        canTypeCombo.getItems().setAll("All", "Standard", "Extended");
        canTypeCombo.getSelectionModel().select("All");
        canTypeCombo.setPromptText("CAN: All");
        canTypeCombo.setMinWidth(96);
        canTypeCombo.setPrefWidth(110);
        canTypeCombo.setMaxWidth(130);
        canTypeCombo.setMinHeight(28);
        canTypeCombo.setPrefHeight(30);
        canTypeCombo.setMaxHeight(32);

        matchCombo.getItems().setAll("All", "Matched", "Unmatched", "No DBC", "Ambiguous");
        matchCombo.getSelectionModel().select("All");
        matchCombo.setPromptText("Match: All");
        matchCombo.setMinWidth(118);
        matchCombo.setPrefWidth(132);
        matchCombo.setMaxWidth(150);
        matchCombo.setMinHeight(28);
        matchCombo.setPrefHeight(30);
        matchCombo.setMaxHeight(32);

        contentCombo.getItems().setAll("All", "Has Signals", "No Signals");
        contentCombo.getSelectionModel().select("All");
        contentCombo.setPromptText("Content: All");
        contentCombo.setMinWidth(118);
        contentCombo.setPrefWidth(132);
        contentCombo.setMaxWidth(150);
        contentCombo.setMinHeight(28);
        contentCombo.setPrefHeight(30);
        contentCombo.setMaxHeight(32);
        stateFilter.getItems().setAll(
                "All", "Needs Decision", "Recommended", "Selected", "Confirmed", "Needs Attention");
        stateFilter.getSelectionModel().select("All");
        stateFilter.setPromptText("State: All");
        stateFilter.setMinWidth(132);
        stateFilter.setPrefWidth(148);
        stateFilter.setMaxWidth(168);
        stateFilter.setMinHeight(28);
        stateFilter.setPrefHeight(30);
        stateFilter.setMaxHeight(32);
        coverageFilter.getItems().setAll("All", "High", "Medium", "Low", "None");
        coverageFilter.getSelectionModel().select("All");
        coverageFilter.setPromptText("Coverage: All");
        coverageFilter.setMinWidth(110);
        coverageFilter.setPrefWidth(122);
        coverageFilter.setMaxWidth(140);
        coverageFilter.setMinHeight(28);
        coverageFilter.setPrefHeight(30);
        coverageFilter.setMaxHeight(32);
        unmappedOnly.setMinHeight(28);
        unmappedOnly.setPrefHeight(30);
        unmappedOnly.getStyleClass().add("decision-unmapped-toggle");
        clearButton.getStyleClass().addAll("secondary-button", "compact-button");
        clearButton.setMinWidth(Region.USE_PREF_SIZE);
        clearButton.setMinHeight(30);
        clearButton.setPrefHeight(32);
        moreFiltersButton.setText("More Filters");
        moreFiltersButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button");
        moreFiltersButton.setMinWidth(Region.USE_PREF_SIZE);
        moreFiltersButton.setMinHeight(28);
        moreFiltersButton.setGraphic(StellarIcons.filter());
        moreFiltersButton.setGraphicTextGap(4);
        moreFiltersButton.setTooltip(new Tooltip("CAN type, coverage, and unmapped filters."));
        addFilesButton.getStyleClass().addAll("primary-button", "compact-button");
        addFilesButton.setGraphic(StellarIcons.plus());
        addFilesButton.setGraphicTextGap(6);

        for (Label caption : List.of(
                searchFilterCaption, canTypeFilterCaption, dbcMatchFilterCaption, contentFilterCaption,
                stateFilterCaption, coverageFilterCaption)) {
            caption.getStyleClass().add("inventory-filter-caption");
        }
        searchFilterCaption.setVisible(false);
        searchFilterCaption.setManaged(false);
        applyLabeledCombo(canTypeCombo, "CAN");
        applyLabeledCombo(matchCombo, "Match");
        applyLabeledCombo(contentCombo, "Content");
        applyLabeledCombo(stateFilter, "State");
        applyLabeledCombo(coverageFilter, "Coverage");
        HBox canTypeFilter = inlineFilter(canTypeFilterCaption, canTypeCombo);
        HBox matchFilter = inlineFilter(dbcMatchFilterCaption, matchCombo);
        HBox contentFilter = inlineFilter(contentFilterCaption, contentCombo);
        HBox stateFilterBox = inlineFilter(stateFilterCaption, stateFilter);
        HBox coverageFilterBox = inlineFilter(coverageFilterCaption, coverageFilter);
        canTypeFilterCaption.setVisible(false);
        canTypeFilterCaption.setManaged(false);
        dbcMatchFilterCaption.setVisible(false);
        dbcMatchFilterCaption.setManaged(false);
        contentFilterCaption.setVisible(false);
        contentFilterCaption.setManaged(false);
        stateFilterCaption.setVisible(false);
        stateFilterCaption.setManaged(false);
        coverageFilterCaption.setVisible(false);
        coverageFilterCaption.setManaged(false);
        HBox filterActions = new HBox(6, moreFiltersButton, clearButton, semanticsHelpButton);
        filterActions.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("source-inventory-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setMinHeight(42);
        toolbar.setPrefHeight(44);
        toolbar.setMaxHeight(46);
        Region filterSpacer = new Region();
        HBox.setHgrow(filterSpacer, Priority.ALWAYS);
        // The original search field remains the authoritative bound property for existing
        // search/filter listeners. It is visually represented by navigatorSearchField in Detailed
        // Mapping, so do not render a second search control in the optional filter drawer.
        searchField.setVisible(false);
        searchField.setManaged(false);
        toolbar.getChildren().setAll(
                searchField, matchFilter, contentFilter, stateFilterBox, filterSpacer, filterActions);

        // Title/summary live in Project Files compact chrome — avoid duplicate heading here.
        title.setVisible(false);
        title.setManaged(false);
        workspaceSummary.setVisible(false);
        workspaceSummary.setManaged(false);
        statusPills.getStyleClass().add("decision-status-pills");
        statusPills.setHgap(6);
        statusPills.setVgap(4);
        statusPills.setAlignment(Pos.CENTER_RIGHT);
        statusPills.setPrefWrapLength(420);
        HBox header = new HBox(10, toolbar, statusPills);
        detailedFilterDrawer = header;
        header.getStyleClass().add("source-inventory-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 10, 4, 10));
        header.setMinHeight(42);
        header.setPrefHeight(Region.USE_COMPUTED_SIZE);
        header.setMaxHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(toolbar, Priority.ALWAYS);

        configureNavigator();
        configureTable();

        Label sourcesNavTitle = new Label("SOURCES & CHANNELS");
        sourcesNavTitle.getStyleClass().add("source-inventory-nav-title");
        navIdsHead.getStyleClass().add("source-inventory-nav-col-head");
        navMatchedHead.getStyleClass().add("source-inventory-nav-col-head");
        navIdsHead.setMinWidth(CH_IDS_W);
        navIdsHead.setPrefWidth(CH_IDS_W);
        navIdsHead.setMaxWidth(CH_IDS_W);
        navIdsHead.setAlignment(Pos.CENTER_RIGHT);
        navMatchedHead.setMinWidth(CH_SIG_W);
        navMatchedHead.setPrefWidth(CH_SIG_W);
        navMatchedHead.setMaxWidth(CH_SIG_W);
        navMatchedHead.setAlignment(Pos.CENTER_RIGHT);
        navMatchedHead.setTooltip(new Tooltip(
                "CAN IDs matched by the currently selected/recommended DBC"));
        Region navHeadSpacer = new Region();
        HBox.setHgrow(navHeadSpacer, Priority.ALWAYS);
        navMetricsHeader.setAlignment(Pos.CENTER_RIGHT);
        navMetricsHeader.setPadding(new Insets(0, 8, 0, 0));
        navMetricsHeader.setMinWidth(0);
        navMetricsHeader.setPrefWidth(Region.USE_COMPUTED_SIZE);
        navMetricsHeader.setMaxWidth(Double.MAX_VALUE);
        navMetricsHeader.getChildren().setAll(navHeadSpacer, navIdsHead, navMatchedHead);

        navigator.setMinWidth(0);
        navigator.setPrefWidth(Region.USE_COMPUTED_SIZE);
        navigator.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(navigator, Priority.ALWAYS);
        navBody.getChildren().setAll(navMetricsHeader, navigator);
        navBody.setMinWidth(0);
        navBody.setPrefWidth(Region.USE_COMPUTED_SIZE);
        navBody.setMaxWidth(Double.MAX_VALUE);
        navBody.setFillWidth(true);

        navScroll.setContent(navBody);
        navScroll.setFitToHeight(true);
        navScroll.setFitToWidth(true);
        navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.setPannable(true);
        navScroll.setMinWidth(0);
        navScroll.setMinHeight(0);
        navScroll.getStyleClass().add("source-inventory-nav-scroll");
        VBox.setVgrow(navScroll, Priority.ALWAYS);

        leftNavPane.getStyleClass().add("source-inventory-navigator");
        leftNavPane.setMinWidth(NAV_MIN);
        leftNavPane.setPrefWidth(NAV_PREF);
        leftNavPane.setMaxWidth(NAV_MAX);
        leftNavPane.setPadding(new Insets(8, 8, 8, 8));
        leftNavPane.setMinHeight(0);
        leftNavPane.getChildren().setAll(sourcesNavTitle, navigatorSearchField, navScroll, workspaceCounts);
        SplitPane.setResizableWithParent(leftNavPane, false);

        dbcContextLabel.getStyleClass().add("source-inventory-dbc-context");
        coverageLabel.getStyleClass().addAll("muted", "source-inventory-coverage");
        coverageLabel.setVisible(false);
        coverageLabel.setManaged(false);
        idsMetricBadge.setVisible(false);
        idsMetricBadge.setManaged(false);
        framesMetricBadge.setVisible(false);
        framesMetricBadge.setManaged(false);
        scopeMetricsRow.setVisible(false);
        scopeMetricsRow.setManaged(false);
        scopeFile.setVisible(false);
        scopeFile.setManaged(false);
        compactMetaLine.setVisible(false);
        compactMetaLine.setManaged(false);
        configureQuickFilterChips();
        configureStudyBar();
        configureContextActions();

        detailedFiltersButton.getStyleClass().addAll(
                "secondary-button", "secondary-action", "compact-button", "detailed-filters-toggle");
        detailedFiltersButton.setGraphic(StellarIcons.filter());
        detailedFiltersButton.setGraphicTextGap(5);
        detailedFiltersButton.setMinWidth(Region.USE_PREF_SIZE);
        detailedFiltersButton.setTooltip(new Tooltip("Show or hide Custom Decode filters"));
        contextHeader.setTrailingActions(headerSignalsButton, headerStudyButton, detailedFiltersButton);

        scopeRowCaption.getStyleClass().add("inventory-scope-caption");
        scopeRow.getStyleClass().addAll("inventory-scope-row", "inventory-quick-scope-bar");
        scopeRow.setAlignment(Pos.CENTER_LEFT);
        scopeRow.getChildren().setAll(scopeRowCaption, quickFilterBar);

        selectionActionRow.getStyleClass().add("inventory-selection-action-row");
        selectionActionRow.setAlignment(Pos.CENTER_LEFT);
        // Per-row and header checkboxes stay the primary Study path; these remain wired for the
        // narrower bulk operations that the header checkbox already covers.
        selectionActionRow.getChildren().setAll(selectVisibleButton, clearStudyButton);
        selectionActionRow.setVisible(false);
        selectionActionRow.setManaged(false);

        // Single compact table command row: All / Matched / Unmatched scopes, plus the one bulk
        // Study action the header checkbox cannot express (whole scope, ignoring active filters).
        HBox tableCommandRow = new HBox(10);
        tableCommandRow.getStyleClass().add("inventory-table-command-row");
        tableCommandRow.setAlignment(Pos.CENTER_LEFT);
        tableCommandRow.setMinHeight(28);
        tableCommandRow.setPrefHeight(30);
        tableCommandRow.setMaxHeight(32);
        Region tableCommandSpacer = new Region();
        HBox.setHgrow(tableCommandSpacer, Priority.ALWAYS);
        tableCommandRow.getChildren().setAll(scopeRow, tableCommandSpacer, selectAllMatchedButton);

        // Retained empty hooks for older tests / style classes.
        studyActionRow.getChildren().clear();
        studySelectionStack.getChildren().clear();
        studySelectionStack.setVisible(false);
        studySelectionStack.setManaged(false);
        studyBar.getChildren().clear();

        channelSelector.setOnSelect(this::onChannelStripSelected);
        VBox compactContext = new VBox(4, contextHeader, channelSelector, candidateStrip, tableCommandRow);
        compactContext.getStyleClass().addAll("source-inventory-scope-header", "channel-decision-header");
        compactContext.setPadding(new Insets(2, 8, 2, 8));
        compactContext.setMinHeight(Region.USE_PREF_SIZE);
        compactContext.setPrefHeight(Region.USE_COMPUTED_SIZE);
        compactContext.setMaxHeight(Region.USE_PREF_SIZE);
        compactContext.setFillWidth(true);

        emptyPane.setAlignment(Pos.CENTER);
        emptyPane.getChildren().setAll(centerState, addFilesButton);
        emptyPane.setVisible(false);
        emptyPane.setManaged(false);
        StackPane centerBody = new StackPane(table, emptyPane, indexingIndicator);
        StackPane.setAlignment(emptyPane, Pos.CENTER);
        StackPane.setAlignment(indexingIndicator, Pos.CENTER);
        centerBody.setMinHeight(200);
        centerBody.setPrefHeight(360);
        centerBody.setMaxHeight(Double.MAX_VALUE);
        table.setMinHeight(200);
        table.setPrefHeight(360);
        table.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(table, Priority.ALWAYS);

        tableFooter.getStyleClass().add("source-inventory-table-footer");
        tableFooter.setAlignment(Pos.CENTER_LEFT);
        tableFooter.setPadding(new Insets(4, 8, 4, 8));
        tableFooter.setMinHeight(30);
        tableFooter.setPrefHeight(32);
        tableFooter.setMaxHeight(36);
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        tableFooter.getChildren().setAll(resultCountLabel, footerSpacer);
        // Keep the center card table-first. The decision action bar is mounted at the Detailed
        // Mapping mode root so it spans navigator + table + optional inspector like one product.
        VBox tableBottom = new VBox(0, tableFooter);
        tableBottom.setMinHeight(Region.USE_PREF_SIZE);
        tableBottom.setMaxHeight(Region.USE_PREF_SIZE);
        tableBottom.setFillWidth(true);

        // The header, DBC strip and CAN table share one vertical scroll. The decision
        // action bar stays on the mode pane, outside this scroll, so it remains visible.
        centerColumn.setFillWidth(true);
        centerColumn.setMinWidth(0);
        centerColumn.setMaxWidth(Double.MAX_VALUE);
        centerColumn.getChildren().setAll(compactContext, centerBody, tableBottom);
        VBox.setVgrow(centerBody, Priority.ALWAYS);
        centerScroll.setContent(centerColumn);
        centerScroll.setFitToWidth(true);
        centerScroll.setFitToHeight(true);
        centerScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        centerScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        centerScroll.setPannable(false);
        centerScroll.setMinHeight(0);
        centerScroll.setMinWidth(0);
        centerScroll.getStyleClass().add("custom-decode-center-scroll");

        inventoryWorkspace = new BorderPane();
        inventoryWorkspace.getStyleClass().add("source-inventory-surface");
        inventoryWorkspace.setCenter(centerScroll);
        inventoryWorkspace.setMinWidth(0);
        inventoryWorkspace.setMinHeight(0);
        inventoryWorkspace.setMaxWidth(Double.MAX_VALUE);
        inventoryWorkspace.setMaxHeight(Double.MAX_VALUE);
        BorderPane.setAlignment(centerScroll, Pos.CENTER);

        centerPane = new BorderPane();
        centerPane.getStyleClass().add("source-inventory-detail");
        centerPane.setCenter(inventoryWorkspace);
        centerPane.setMinWidth(0);
        centerPane.setMinHeight(0);
        centerPane.setMaxWidth(Double.MAX_VALUE);
        centerPane.setMaxHeight(Double.MAX_VALUE);

        detailsInspector.bindStudySet(studySet);
        detailsInspector.setOnClearStudyRequest(this::clearStudySet);
        detailsInspector.setOnCollapseRequest(this::collapseInspector);
        inspectorDrawer.setOnClose(this::collapseInspector);
        inspectorDrawer.setMinWidth(INSPECTOR_MIN);
        inspectorDrawer.setPrefWidth(INSPECTOR_PREF);
        inspectorDrawer.setMaxWidth(INSPECTOR_MAX);
        SplitPane.setResizableWithParent(inspectorDrawer, false);

        expandInspectorButton.setVisible(false);
        expandInspectorButton.setManaged(false);

        SplitPane.setResizableWithParent(centerPane, true);
        // Default: inspector drawer closed so the CAN table dominates width.
        split.getItems().setAll(leftNavPane, centerPane);
        inspectorCollapsed = true;
        inspectorDrawer.setVisible(false);
        inspectorDrawer.setManaged(false);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.17);
        split.getStyleClass().add("source-inventory-split");
        split.setMaxWidth(Double.MAX_VALUE);
        split.setMaxHeight(Double.MAX_VALUE);
        BorderPane.setAlignment(split, Pos.CENTER);

        detailedModePane = new BorderPane();
        detailedModePane.getStyleClass().addAll("detailed-mapping-mode", "workstation-page");

        // Filters are secondary in Detailed Mapping. Keep them collapsed by default and let the
        // engineer reveal them on demand. The same controls/listeners are reused; no matching,
        // parsing or mapping behaviour is duplicated here.
        header.getStyleClass().add("detailed-filter-drawer");
        header.setVisible(false);
        header.setManaged(false);
        detailedFiltersButton.setOnAction(e -> {
            boolean show = !header.isManaged();
            header.setManaged(show);
            header.setVisible(show);
            detailedFiltersButton.getStyleClass().remove("detailed-filters-toggle-active");
            if (show) {
                detailedFiltersButton.getStyleClass().add("detailed-filters-toggle-active");
            }
        });

        detailedModePane.setTop(header);
        mappingStage.getChildren().setAll(split);
        mappingStage.setMinSize(0, 0);
        mappingStage.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        StackPane.setAlignment(split, Pos.CENTER);
        detailedModePane.setCenter(mappingStage);
        detailedModePane.setBottom(decisionActionBar);
        BorderPane.setMargin(decisionActionBar, new Insets(0, 10, 8, 10));
        detailedModePane.setMinSize(0, 0);
        detailedModePane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        decisionModeHost.getStyleClass().add("decision-mode-host");
        decisionModeHost.setMinSize(0, 0);
        decisionModeHost.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        // Keep exactly one heavy workspace in the scene graph at a time. JavaFX applies CSS/skins
        // to invisible children too; retaining both the TableView-heavy Detailed workspace and the
        // TreeTableView-heavy Quick workspace caused cumulative FX-thread stalls in long UI suites
        // and would waste memory in production. Quick Decode is created only when first requested.
        decisionModeHost.getChildren().setAll(detailedModePane);
        setCenter(decisionModeHost);
        showDecisionMode(DecisionWorkspaceMode.QUICK_DECODE);
        visibleProperty().addListener((obs, oldValue, visible) -> {
            if (visible) {
                refreshActiveQuickMode();
            }
        });
        managedProperty().addListener((obs, oldValue, managed) -> {
            if (managed) {
                refreshActiveQuickMode();
            }
        });

        searchField.textProperty().addListener((o, a, b) -> {
            model.setSearchText(b);
            applyQueryOnly();
        });
        canTypeCombo.valueProperty().addListener((o, a, b) -> {
            model.setCanTypeFilter(toCanType(b));
            applyQueryOnly();
        });
        matchCombo.valueProperty().addListener((o, a, b) -> {
            if (syncingMatchFilterUi) {
                return;
            }
            model.setMatchFilter(toMatchFilter(b));
            syncQuickFilterChipsFromModel();
            applyQueryOnly();
        });
        contentCombo.valueProperty().addListener((o, a, b) -> {
            model.setContentFilter(toContentFilter(b));
            applyQueryOnly();
        });
        clearButton.setOnAction(e -> {
            searchField.clear();
            canTypeCombo.getSelectionModel().select("All");
            syncingMatchFilterUi = true;
            try {
                matchCombo.getSelectionModel().select("All");
            } finally {
                syncingMatchFilterUi = false;
            }
            contentCombo.getSelectionModel().select("All");
            stateFilter.getSelectionModel().select("All");
            coverageFilter.getSelectionModel().select("All");
            unmappedOnly.setSelected(false);
            model.clearFilters();
            syncQuickFilterChipsFromModel();
            if (snapshot != null) {
                renderNavigator(snapshot);
            }
            applyQueryOnly();
        });
        stateFilter.valueProperty().addListener((o, a, b) -> {
            if (snapshot != null) {
                renderNavigator(snapshot);
            }
            refreshDecisionChrome();
        });
        coverageFilter.valueProperty().addListener((o, a, b) -> {
            if (snapshot != null) {
                renderNavigator(snapshot);
            }
            refreshDecisionChrome();
        });
        unmappedOnly.selectedProperty().addListener((o, a, b) -> {
            if (snapshot != null) {
                renderNavigator(snapshot);
            }
            refreshDecisionChrome();
        });
        ContextMenu moreFiltersMenu = new ContextMenu();
        VBox moreFiltersPane = new VBox(8, canTypeFilter, coverageFilterBox, unmappedOnly);
        moreFiltersPane.setPadding(new Insets(8));
        CustomMenuItem moreFiltersItem = new CustomMenuItem(moreFiltersPane, false);
        MenuItem noDbcItem = new MenuItem("No DBC");
        MenuItem ambiguousItem = new MenuItem("Ambiguous");
        noDbcItem.setOnAction(e -> {
            syncingMatchFilterUi = true;
            try {
                matchCombo.getSelectionModel().select("No DBC");
            } finally {
                syncingMatchFilterUi = false;
            }
            model.setMatchFilter(MatchFilter.NO_DBC);
            syncQuickFilterChipsFromModel();
            applyQueryOnly();
        });
        ambiguousItem.setOnAction(e -> {
            syncingMatchFilterUi = true;
            try {
                matchCombo.getSelectionModel().select("Ambiguous");
            } finally {
                syncingMatchFilterUi = false;
            }
            model.setMatchFilter(MatchFilter.AMBIGUOUS);
            syncQuickFilterChipsFromModel();
            applyQueryOnly();
        });
        moreFiltersMenu.getItems().setAll(
                moreFiltersItem, new SeparatorMenuItem(), noDbcItem, ambiguousItem);
        moreFiltersButton.setOnAction(e -> {
            if (moreFiltersMenu.isShowing()) {
                moreFiltersMenu.hide();
            } else {
                moreFiltersMenu.show(moreFiltersButton, javafx.geometry.Side.BOTTOM, 0, 4);
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (syncingSelection) {
                return;
            }
            // Row focus updates inspect data only — drawer opens via Inspect / Study Set.
            detailsInspector.setSelection(b);
            updateHeaderActionEnablement();
        });
        addFilesButton.setOnAction(e -> {
            if (onAddMeasurementFiles != null) {
                onAddMeasurementFiles.run();
            }
        });

        showEmptyProject();
        studySet.addListener(this::onStudySetChanged);
        onStudySetChanged();
    }

    public void bind(DesktopAppService appService, Runnable onAddMeasurementFiles) {
        this.appService = Objects.requireNonNull(appService, "appService");
        this.onAddMeasurementFiles = onAddMeasurementFiles;
        bindQuickDecodePaneIfCreated();
        candidateStrip.bind(appService);
        candidateStrip.setOnMappingChanged(this::refreshEnrichmentOnly);
        decisionActionBar.setOnConfirmSelected(this::confirmSelectedChannel);
        decisionActionBar.setOnApplyReview(this::applySelectedDbcToCompatibleReviewChannels);
        decisionActionBar.setOnChooseDbcForAll(this::chooseDbcForActiveChannel);
        decisionActionBar.setOnCompare(candidateStrip::compareVisible);
        decisionActionBar.setOnMarkNeedsAttention(this::markSelectedNeedsAttention);
        decisionActionBar.setOnExport(this::exportMappingMatrix);
        decisionActionBar.setOnProceed(() -> {
            if (appService != null) {
                appService.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
                acceptPendingDbcIfNeeded();
                appService.prepareProcessingFromDecisionWorkspace();
            }
            if (onProceedToProcessing != null) {
                onProceedToProcessing.run();
            }
        });
        // Product default on bind: Auto Decode. Custom Decode only after explicit click.
        showDecisionMode(DecisionWorkspaceMode.QUICK_DECODE);
    }

    /** Product entry point: Quick Decode is the default mode in the full application. */
    public void showQuickDecode() {
        showDecisionMode(DecisionWorkspaceMode.QUICK_DECODE);
    }

    /** Opens the existing channel-level study/mapping workflow without mutating its state. */
    public void showDetailedMapping() {
        showDecisionMode(DecisionWorkspaceMode.DETAILED_MAPPING);
    }

    public void setOnProceedToProcessing(Runnable onProceedToProcessing) {
        this.onProceedToProcessing = onProceedToProcessing;
    }

    /** Presentation callback used by Project Files chrome; does not participate in plan generation. */
    public void setOnDecisionModeChanged(Consumer<DecisionWorkspaceMode> onDecisionModeChanged) {
        this.onDecisionModeChanged = onDecisionModeChanged;
    }

    /** Presentation focus for Processing → Decision Workspace navigation. */
    public void focusChannel(SourceChannelKey channel) {
        if (channel == null) {
            return;
        }
        showDecisionMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        model.selectChannel(channel.sourceId(), channel.channelId());
        rememberMappingChannel(channel.sourceId(), channel.channelId());
        if (appService != null) {
            refreshFromModel();
        }
    }

    private void openChannelInDetailedMapping(SourceChannelKey channel) {
        if (channel == null) {
            return;
        }
        showDecisionMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        model.selectChannel(channel.sourceId(), channel.channelId());
        rememberMappingChannel(channel.sourceId(), channel.channelId());
        refreshFromModel();
    }

    private void showDecisionMode(DecisionWorkspaceMode mode) {
        DecisionWorkspaceMode effective = mode == null ? DecisionWorkspaceMode.QUICK_DECODE : mode;
        boolean quick = effective == DecisionWorkspaceMode.QUICK_DECODE;

        if (quick) {
            QuickDecodePane pane = ensureQuickDecodePane();
            pane.setModeActive(true);
            // Only the active mode participates in CSS/layout/skin creation.
            if (decisionModeHost.getChildren().size() != 1
                    || decisionModeHost.getChildren().getFirst() != pane) {
                decisionModeHost.getChildren().setAll(pane);
            }
        } else {
            if (quickDecodePane != null) {
                quickDecodePane.setModeActive(false);
            }
            if (detailedModePane != null
                    && (decisionModeHost.getChildren().size() != 1
                    || decisionModeHost.getChildren().getFirst() != detailedModePane)) {
                decisionModeHost.getChildren().setAll(detailedModePane);
            }
        }

        activeDecisionMode = effective;
        if (appService != null) {
            appService.setDecisionWorkspaceMode(effective);
            if (quick && quickDecodePane != null) {
                quickDecodePane.refresh();
            }
        }
        if (onDecisionModeChanged != null) {
            onDecisionModeChanged.accept(effective);
        }
    }

    private QuickDecodePane ensureQuickDecodePane() {
        if (quickDecodePane == null) {
            quickDecodePane = new QuickDecodePane();
            bindQuickDecodePaneIfCreated();
        }
        return quickDecodePane;
    }

    private void bindQuickDecodePaneIfCreated() {
        if (quickDecodePane == null || appService == null) {
            return;
        }
        quickDecodePane.bind(
                appService,
                this::openChannelInDetailedMapping,
                () -> {
                    if (this.appService != null) {
                        this.appService.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
                    }
                    if (this.onProceedToProcessing != null) {
                        this.onProceedToProcessing.run();
                    }
                },
                onAddMeasurementFiles);
    }

    private void refreshActiveQuickMode() {
        if (appService != null
                && activeDecisionMode == DecisionWorkspaceMode.QUICK_DECODE
                && quickDecodePane != null) {
            quickDecodePane.refresh();
        }
    }

    DecisionWorkspaceMode decisionModeForTest() {
        return appService == null
                ? activeDecisionMode
                : appService.decisionWorkspaceMode();
    }

    /** True when the retired in-page "Decode Mode" strip is still in the scene. */
    public boolean hasDuplicateDecodeModeStripForTest() {
        Node bar = lookup(".decision-mode-bar");
        return bar != null && bar.isVisible();
    }

    QuickDecodePane quickDecodePaneForTest() {
        return ensureQuickDecodePane();
    }

    boolean quickDecodeConstructedForTest() {
        return quickDecodePane != null;
    }

    boolean quickDecodeAttachedForTest() {
        return quickDecodePane != null && quickDecodePane.getParent() == decisionModeHost;
    }

    int decisionModeHostChildCountForTest() {
        return decisionModeHost.getChildren().size();
    }

    public void refreshFromModel() {
        if (appService == null) {
            return;
        }
        refreshActiveQuickMode();
        List<WorkspaceFile> measurements = appService.workspace().measurementFiles();
        snapshot = model.rebuild(
                measurements,
                appService.sourceInventoryModel(),
                appService.sourceAliasRegistry(),
                appService::inventoryJobState
        );
        renderNavigator(snapshot);
        renderCenter(snapshot);
        updateWorkspaceSummary(snapshot);
        refreshDecisionChrome();
    }

    /**
     * Mapping-only refresh: re-query enrichment without rebuilding navigator or reparsing inventory.
     */
    public void refreshEnrichmentOnly() {
        if (appService == null) {
            return;
        }
        refreshActiveQuickMode();
        if (snapshot == null) {
            return;
        }
        applyQueryOnly();
        renderDbcContext(snapshot);
        refreshNavigatorMatchedCounts();
        // Refresh Study Set DBC/signal metadata for currently visible enriched rows only.
        studySet.refreshMetadata(tableRows);
        detailsInspector.setSelection(table.getSelectionModel().getSelectedItem());
        refreshDecisionChrome();
    }

    int structuralRebuildCountForTest() {
        return structuralRebuildCountForTest;
    }

    void resetStructuralRebuildCountForTest() {
        structuralRebuildCountForTest = 0;
    }

    public SourceInventoryWorkspaceModel modelForTest() {
        return model;
    }

    public TableView<CanMessageInventoryRow> tableForTest() {
        return table;
    }

    TreeView<NavNode> navigatorForTest() {
        return navigator;
    }

    public VBox leftNavPaneForTest() {
        return leftNavPane;
    }

    public ScrollPane navScrollForTest() {
        return navScroll;
    }

    public CheckBox studyHeaderCheckForTest() {
        return studyHeaderCheck;
    }

    public TextField searchFieldForTest() {
        return searchField;
    }

    public String navDbcMatchedTextForTest(ProjectSourceId sourceId, int channelId) {
        NavNode node = findNavNode(sourceId, channelId);
        return node == null ? null : node.framesText();
    }

    public String navSignalsTextForTest(ProjectSourceId sourceId, int channelId) {
        return navDbcMatchedTextForTest(sourceId, channelId);
    }

    public String navIdsTextForTest(ProjectSourceId sourceId, int channelId) {
        NavNode node = findNavNode(sourceId, channelId);
        return node == null ? null : node.idsText();
    }

    public String navAllChannelsMatchedTextForTest(ProjectSourceId sourceId) {
        NavNode node = findNavNode(sourceId, -1);
        return node == null ? null : node.framesText();
    }

    public String navAllChannelsSignalsTextForTest(ProjectSourceId sourceId) {
        return navAllChannelsMatchedTextForTest(sourceId);
    }

    public String centerMatchTextForTest() {
        return cachedMatchText;
    }

    public Label navMatchedHeadForTest() {
        return navMatchedHead;
    }

    public Label navIdsHeadForTest() {
        return navIdsHead;
    }

    public Label navRenderedMatchedLabelForTest(ProjectSourceId sourceId, int channelId) {
        NavNode want = findNavNode(sourceId, channelId);
        if (want == null) {
            return null;
        }
        navigator.applyCss();
        navigator.layout();
        for (Node n : navigator.lookupAll(".decision-nav-matched")) {
            if (!(n instanceof Label matched) || !matched.isManaged()) {
                continue;
            }
            if (!(matched.getParent() instanceof HBox row) || row.getChildren().isEmpty()) {
                continue;
            }
            Label name = null;
            for (Node child : row.getChildren()) {
                if (child instanceof Label label
                        && label.getStyleClass().contains("source-inventory-channel-label")) {
                    name = label;
                    break;
                }
            }
            if (name != null && want.channelLabel().equals(name.getText())) {
                return matched;
            }
        }
        return null;
    }

    private NavNode findNavNode(ProjectSourceId sourceId, int channelId) {
        if (sourceId == null) {
            return null;
        }
        for (TreeItem<NavNode> sourceItem : navRoot.getChildren()) {
            NavNode sourceNode = sourceItem.getValue();
            if (sourceNode == null || !sourceId.equals(sourceNode.sourceId())) {
                continue;
            }
            for (TreeItem<NavNode> child : sourceItem.getChildren()) {
                NavNode n = child.getValue();
                if (n == null) {
                    continue;
                }
                if (channelId < 0 && n.kind() == NavKind.ALL_CHANNELS) {
                    return n;
                }
                if (n.kind() == NavKind.CHANNEL && n.channelId() == channelId) {
                    return n;
                }
            }
        }
        return null;
    }

    Label scopeTitleForTest() {
        return contextHeader.titleLabelForTest();
    }

    public SourceChannelContextHeader contextHeaderForTest() {
        return contextHeader;
    }

    Label resultCountForTest() {
        return resultCountLabel;
    }

    HBox toolbarForTest() {
        return toolbar;
    }

    TextField navigatorSearchFieldForTest() {
        return navigatorSearchField;
    }

    Button detailedFiltersButtonForTest() {
        return detailedFiltersButton;
    }

    HBox detailedFilterDrawerForTest() {
        return detailedFilterDrawer;
    }

    BorderPane detailedModePaneForTest() {
        return detailedModePane;
    }

    public double navMinWidthForTest() {
        return NAV_MIN;
    }

    public double navPrefWidthForTest() {
        return NAV_PREF;
    }

    public double navMaxWidthForTest() {
        return NAV_MAX;
    }

    SplitPane splitForTest() {
        return split;
    }

    public int navigatorHorizontalScrollBarCountForTest() {
        int count = 0;
        for (javafx.scene.Node n : leftNavPane.lookupAll(".scroll-bar")) {
            if (n instanceof javafx.scene.control.ScrollBar bar
                    && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL
                    && bar.isVisible()
                    && bar.getWidth() > 8
                    && bar.getHeight() > 2
                    && bar.getOpacity() > 0.05) {
                count++;
            }
        }
        return count;
    }

    public boolean hasHorizontalNavigatorScrollForTest() {
        if (navScroll.getHbarPolicy() == ScrollPane.ScrollBarPolicy.NEVER) {
            return false;
        }
        javafx.scene.Node content = navScroll.getContent();
        if (content != null && navScroll.getWidth() > 1) {
            double contentMin = Math.max(content.getLayoutBounds().getWidth(), content.minWidth(-1));
            if (contentMin > navScroll.getWidth() + 1) {
                return true;
            }
        }
        for (javafx.scene.Node n : navScroll.lookupAll(".scroll-bar")) {
            if (n instanceof javafx.scene.control.ScrollBar bar
                    && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL
                    && bar.isVisible()
                    && bar.getWidth() > 0
                    && bar.getHeight() > 0
                    && bar.getOpacity() > 0.01) {
                return true;
            }
        }
        return false;
    }

    public boolean hasHorizontalTableScrollForTest() {
        for (javafx.scene.Node n : table.lookupAll(".scroll-bar")) {
            if (n instanceof javafx.scene.control.ScrollBar bar
                    && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL
                    && bar.isVisible()
                    && bar.getWidth() > 0
                    && bar.getHeight() > 0
                    && bar.getOpacity() > 0.01) {
                return true;
            }
        }
        return false;
    }
    public boolean hasOuterVerticalScrollAncestorForTest() {
        javafx.scene.Node n = getParent();
        while (n != null) {
            if (n instanceof javafx.scene.control.ScrollPane sp
                    && sp.getVbarPolicy() != javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER) {
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    private void configureNavigator() {
        navigator.setRoot(navRoot);
        navigator.setShowRoot(false);
        navigator.getStyleClass().add("source-inventory-tree");
        navigator.setMinWidth(0);
        navigator.setPrefWidth(Region.USE_COMPUTED_SIZE);
        navigator.setMaxWidth(Double.MAX_VALUE);
        navigator.setFixedCellSize(-1);
        // Outer ScrollPane owns horizontal overflow at a fixed content width (no viewport binding).
        navigator.setCellFactory(tv -> new InventoryNavCell());
        navigator.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (syncingSelection || b == null || b.getValue() == null) {
                return;
            }
            NavNode node = b.getValue();
            if (node.kind() == NavKind.SOURCE || node.kind() == NavKind.ALL_CHANNELS) {
                model.selectAllChannels(node.sourceId());
            } else if (node.kind() == NavKind.CHANNEL) {
                model.selectChannel(node.sourceId(), node.channelId());
                rememberMappingChannel(node.sourceId(), node.channelId());
            }
            for (TreeItem<NavNode> child : navRoot.getChildren()) {
                NavNode n = child.getValue();
                if (n != null && n.sourceId() != null) {
                    model.setExpanded(n.sourceId(), child.isExpanded());
                }
            }
            if (snapshot != null) {
                snapshot = new WorkspaceSnapshot(
                        snapshot.sources(),
                        snapshot.measurementSourceCount(),
                        snapshot.totalCanChannels(),
                        model.selectedSourceId(),
                        model.selectedChannelId(),
                        model.searchText(),
                        model.canTypeFilter(),
                        model.matchFilter(),
                        model.contentFilter()
                );
                renderCenter(snapshot);
                // Do not navigator.refresh() here — full cell refresh during selection
                // can re-enter TreeView layout and hang the FX thread.
            }
        });
    }

    private final class InventoryNavCell extends TreeCell<NavNode> {
        private final VBox sourceBox = new VBox(2);
        private final HBox sourceTop = new HBox(8);
        private final Label aliasLabel = new Label();
        private final Label formatBadge = new Label();
        private final Label fileLabel = new Label();
        private final Region sourceSpacer = new Region();
        private final Label statusBadge = new Label();
        private final Label metaLabel = new Label();
        private final HBox channelBox = new HBox(8);
        private final Label channelLabel = new Label();
        private final Label stateMark = new Label();
        private final Label idsLabel = new Label();
        private final Label matchedLabel = new Label();

        private InventoryNavCell() {
            aliasLabel.getStyleClass().add("source-inventory-filename");
            aliasLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            aliasLabel.setMinWidth(0);
            aliasLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(aliasLabel, Priority.ALWAYS);
            formatBadge.getStyleClass().addAll("decision-format-badge", "measurement-source-badge");
            formatBadge.setMinWidth(Region.USE_PREF_SIZE);
            fileLabel.getStyleClass().add("source-inventory-filename");
            fileLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            fileLabel.setMinWidth(0);
            fileLabel.setMaxWidth(Double.MAX_VALUE);
            fileLabel.setTooltip(null);
            fileLabel.setVisible(false);
            fileLabel.setManaged(false);
            HBox.setHgrow(sourceSpacer, Priority.ALWAYS);
            sourceSpacer.setMinWidth(4);
            sourceSpacer.setPrefWidth(4);
            statusBadge.setMinWidth(STATUS_BADGE_W);
            statusBadge.setPrefWidth(STATUS_BADGE_W);
            statusBadge.setMaxWidth(STATUS_BADGE_W);
            statusBadge.setAlignment(Pos.CENTER);
            statusBadge.getStyleClass().add("source-inventory-status-badge");
            metaLabel.getStyleClass().addAll("muted", "source-inventory-source-meta");
            metaLabel.setWrapText(false);
            metaLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            metaLabel.setMinWidth(0);
            metaLabel.setMaxWidth(Double.MAX_VALUE);
            // LINE 1: alias + READY — filename on its own line so suffix+.ext stay readable.
            sourceTop.setAlignment(Pos.CENTER_LEFT);
            sourceTop.setMinWidth(0);
            sourceTop.setMaxWidth(Double.MAX_VALUE);
            sourceTop.getChildren().setAll(aliasLabel, formatBadge, sourceSpacer, statusBadge);
            sourceBox.getChildren().setAll(sourceTop, fileLabel, metaLabel);
            sourceBox.getStyleClass().add("source-inventory-source-block");
            sourceBox.setMinWidth(0);
            sourceBox.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(sourceBox, Priority.ALWAYS);

            channelLabel.getStyleClass().add("source-inventory-channel-label");
            channelLabel.setMinWidth(Region.USE_COMPUTED_SIZE);
            channelLabel.setPrefWidth(CH_LABEL_MIN);
            channelLabel.setMaxWidth(Double.MAX_VALUE);
            channelLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            channelLabel.setGraphicTextGap(4);
            HBox.setHgrow(channelLabel, Priority.ALWAYS);
            stateMark.getStyleClass().add("decision-nav-state-mark");
            stateMark.setMinWidth(10);
            stateMark.setPrefWidth(10);
            stateMark.setMaxWidth(10);
            stateMark.setAlignment(Pos.CENTER);
            stateMark.setTextOverrun(OverrunStyle.CLIP);
            idsLabel.getStyleClass().addAll("muted", "source-inventory-channel-meta");
            idsLabel.setMinWidth(28);
            idsLabel.setPrefWidth(CH_IDS_W);
            idsLabel.setMaxWidth(CH_IDS_W);
            idsLabel.setAlignment(Pos.CENTER_RIGHT);
            idsLabel.setTextOverrun(OverrunStyle.CLIP);
            matchedLabel.getStyleClass().addAll(
                    "muted", "source-inventory-channel-meta", "decision-channel-coverage", "decision-nav-matched");
            matchedLabel.setMinWidth(28);
            matchedLabel.setPrefWidth(CH_SIG_W);
            matchedLabel.setMaxWidth(CH_SIG_W);
            matchedLabel.setAlignment(Pos.CENTER_RIGHT);
            matchedLabel.setTextOverrun(OverrunStyle.CLIP);
            matchedLabel.setVisible(true);
            matchedLabel.setManaged(true);
            channelBox.setAlignment(Pos.CENTER_LEFT);
            channelBox.setMinWidth(0);
            channelBox.setPrefWidth(Region.USE_COMPUTED_SIZE);
            channelBox.setMaxWidth(Double.MAX_VALUE);
            channelBox.getStyleClass().add("source-inventory-channel-row");
            channelBox.getChildren().setAll(channelLabel, idsLabel, matchedLabel);
            HBox.setHgrow(channelBox, Priority.ALWAYS);
            widthProperty().addListener((o, a, b) -> {
                sizeSourceRow();
                sizeChannelRow();
            });
        }

        private void sizeSourceRow() {
            if (getGraphic() != sourceBox) {
                return;
            }
            fitRow(sourceBox, rowWidth(52));
        }

        private void sizeChannelRow() {
            if (getGraphic() != channelBox) {
                return;
            }
            int level = 0;
            TreeItem<NavNode> item = getTreeItem();
            while (item != null && item.getParent() != null) {
                level++;
                item = item.getParent();
            }
            double reserve = level <= 1 ? 34 : 42;
            fitRow(channelBox, rowWidth(reserve));
        }

        private void fitRow(Region box, double avail) {
            if (Math.abs(box.getMaxWidth() - avail) < 0.5) {
                return;
            }
            box.setMinWidth(0);
            box.setPrefWidth(avail);
            box.setMaxWidth(avail);
            if (getTreeView() != null) {
                getTreeView().requestLayout();
            }
        }

        private double rowWidth(double disclosureReserve) {
            double view = getTreeView() != null && getTreeView().getWidth() > 1
                    ? getTreeView().getWidth() : getWidth();
            if (view <= 1) {
                return 40;
            }
            return Math.max(40, view - getInsets().getLeft() - getInsets().getRight() - disclosureReserve);
        }

        @Override
        protected void updateItem(NavNode item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(
                    "source-inventory-source",
                    "source-inventory-channel",
                    "source-inventory-selected",
                    "selected-channel",
                    "measurement-source");
            if (empty || item == null || item.kind() == NavKind.ROOT) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            setText(null);
            setTooltip(item.tooltip() == null || item.tooltip().isBlank()
                    ? null : new Tooltip(item.tooltip()));
            if (item.kind() == NavKind.SOURCE) {
                aliasLabel.setText(item.alias());
                aliasLabel.setTooltip(new Tooltip(item.alias() == null || item.alias().isBlank()
                        ? item.tooltip() : item.alias()));
                formatBadge.setText(formatToken(item.metaLine()));
                fileLabel.setText(item.compactName());
                metaLabel.setText(item.metaLine());
                applyStatusBadge(statusBadge, item.status());
                setGraphic(sourceBox);
                sizeSourceRow();
                getStyleClass().addAll("source-inventory-source", "measurement-source");
            } else {
                channelLabel.setText(item.channelLabel());
                applyChannelStateMark(stateMark, item.status(), item.kind());
                channelLabel.setGraphic(stateMark.isManaged() ? stateMark : null);
                idsLabel.setText(item.idsText());
                matchedLabel.setText(item.framesText());
                matchedLabel.setVisible(true);
                matchedLabel.setManaged(true);
                String tip = item.tooltip();
                if (tip == null || tip.isBlank()) {
                    tip = item.channelLabel() + " · " + item.idsText()
                            + (item.framesText() == null || item.framesText().isBlank()
                            ? "" : " · " + item.framesText());
                }
                setTooltip(new Tooltip(tip));
                setGraphic(channelBox);
                sizeChannelRow();
                getStyleClass().add("source-inventory-channel");
            }
            if (isSelectedNav(item)) {
                getStyleClass().addAll("source-inventory-selected", "selected-channel");
            }
        }

        private static void applyChannelStateMark(Label mark, String status, NavKind kind) {
            mark.getStyleClass().removeAll(
                    "decision-nav-state-recommended",
                    "decision-nav-state-selected",
                    "decision-nav-state-confirmed",
                    "decision-nav-state-attention");
            if (kind != NavKind.CHANNEL || status == null || status.isBlank()) {
                mark.setText("");
                mark.setVisible(false);
                mark.setManaged(false);
                return;
            }
            mark.setVisible(true);
            mark.setManaged(true);
            switch (status) {
                case "RECOMMENDED" -> {
                    mark.setText("\u25CF");
                    mark.getStyleClass().add("decision-nav-state-recommended");
                }
                case "SELECTED" -> {
                    mark.setText("\u25CB");
                    mark.getStyleClass().add("decision-nav-state-selected");
                }
                case "CONFIRMED" -> {
                    mark.setText("\u2713");
                    mark.getStyleClass().add("decision-nav-state-confirmed");
                }
                case "NEEDS_ATTENTION" -> {
                    mark.setText("\u25CF");
                    mark.getStyleClass().add("decision-nav-state-attention");
                }
                default -> mark.setText("");
            }
        }
    }

    private static String formatToken(String metaLine) {
        if (metaLine == null || metaLine.isBlank()) {
            return "";
        }
        int sep = metaLine.indexOf('·');
        String token = sep < 0 ? metaLine.trim() : metaLine.substring(0, sep).trim();
        return token.isBlank() ? "" : token;
    }

    private static void applyStatusBadge(Label badge, String status) {
        badge.getStyleClass().removeAll(
                "status-badge", "badge", "badge-ready", "badge-running",
                "badge-invalid", "badge-unsupported", "inventory-status-ready",
                "inventory-status-warning");
        badge.getStyleClass().addAll("status-badge", "badge");
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        badge.setText(s.isEmpty() ? "—" : s);
        if ("READY".equals(s)) {
            badge.getStyleClass().addAll("badge-ready", "inventory-status-ready");
        } else if ("INDEXING".equals(s) || "WAITING".equals(s) || "ADDED".equals(s)) {
            badge.getStyleClass().add("badge-running");
        } else {
            badge.getStyleClass().addAll("badge-unsupported", "inventory-status-warning");
        }
    }

    private boolean isSelectedNav(NavNode item) {
        if (item == null || model.selectedSourceId() == null) {
            return false;
        }
        if (!item.sourceId().equals(model.selectedSourceId())) {
            return false;
        }
        if (item.kind() == NavKind.CHANNEL) {
            return model.selectedChannelId().isPresent()
                    && model.selectedChannelId().getAsInt() == item.channelId();
        }
        if (item.kind() == NavKind.ALL_CHANNELS || item.kind() == NavKind.SOURCE) {
            return model.isAllChannelsSelected();
        }
        return false;
    }

    private void configureTable() {
        table.setItems(tableRows);
        table.setMinWidth(0);
        table.setMaxWidth(Double.MAX_VALUE);
        table.getStyleClass().add("can-inventory-table");
        // Unconstrained columns + native TableView H-scrollbar when prefs exceed viewport.
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(tablePlaceholder);
        table.setFixedCellSize(32);
        table.setRowFactory(tv -> {
            TableRow<CanMessageInventoryRow> row = new TableRow<>();
            row.getStyleClass().add("can-inventory-row");
            return row;
        });

        TableColumn<CanMessageInventoryRow, Boolean> studyCol = new TableColumn<>("");
        studyCol.getStyleClass().add("inventory-study-col");
        studyCol.setMinWidth(38);
        studyCol.setPrefWidth(42);
        studyCol.setMaxWidth(46);
        studyCol.setResizable(false);
        studyCol.setSortable(false);
        studyHeaderCheck.setFocusTraversable(false);
        studyHeaderCheck.setAllowIndeterminate(true);
        studyHeaderCheck.getStyleClass().add("inventory-study-header-check");
        studyHeaderCheck.setOnAction(e -> {
            if (syncingStudyHeaderCheck) {
                return;
            }
            onStudyHeaderCheckClicked();
        });
        studyCol.setGraphic(studyHeaderCheck);
        studyCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(studySet.contains(c.getValue())));
        studyCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox box = new CheckBox();
            {
                box.getStyleClass().add("inventory-study-check");
                box.setFocusTraversable(false);
                box.setOnAction(e -> {
                    CanMessageInventoryRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row == null) {
                        return;
                    }
                    if (box.isSelected()) {
                        if (!InventoryStudySet.isEligible(row)) {
                            box.setSelected(false);
                            return;
                        }
                        studySet.add(row);
                    } else {
                        studySet.remove(row);
                    }
                });
            }
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                CanMessageInventoryRow row = getTableRow().getItem();
                boolean eligible = InventoryStudySet.isEligible(row);
                box.setDisable(!eligible && !studySet.contains(row));
                box.setSelected(studySet.contains(row));
                setGraphic(box);
                setAlignment(Pos.CENTER);
            }
        });

        TableColumn<CanMessageInventoryRow, String> idCol = new TableColumn<>("CAN ID");
        idCol.getStyleClass().add("inventory-can-id-col");
        idCol.setMinWidth(90);
        idCol.setPrefWidth(105);
        idCol.setMaxWidth(130);
        idCol.setResizable(true);
        idCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().canIdHex()));
        idCol.setCellFactory(col -> new TableCell<>() {
            {
                getStyleClass().add("inventory-can-id-cell");
                setAlignment(Pos.CENTER_LEFT);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                CanMessageInventoryRow row = getTableRow().getItem();
                setText(item);
                setTextOverrun(OverrunStyle.CLIP);
                setTooltip(new Tooltip(row.canIdHex() + "  (" + row.typeFull()
                        + ", decimal " + row.canIdDecimal() + ")"));
            }
        });

        TableColumn<CanMessageInventoryRow, String> channelCol = new TableColumn<>("Channel");
        channelCol.setMinWidth(70);
        channelCol.setPrefWidth(82);
        channelCol.setMaxWidth(100);
        channelCol.setResizable(true);
        channelCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().channelLabel()));
        channelCol.setCellFactory(col -> new TableCell<>() {
            {
                setAlignment(Pos.CENTER);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        });

        TableColumn<CanMessageInventoryRow, Long> framesCol = new TableColumn<>("Frames");
        framesCol.setMinWidth(100);
        framesCol.setPrefWidth(120);
        framesCol.setMaxWidth(145);
        framesCol.setResizable(true);
        framesCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().frameCount()));
        framesCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(intFormat.format(item));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        TableColumn<CanMessageInventoryRow, String> matchCol = new TableColumn<>("Match");
        matchCol.setMinWidth(95);
        matchCol.setPrefWidth(110);
        matchCol.setMaxWidth(130);
        matchCol.setResizable(true);
        matchCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().matchBadge()));
        matchCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();
            {
                badge.getStyleClass().add("inventory-match-badge");
                badge.setMaxWidth(118);
                badge.setTextOverrun(OverrunStyle.ELLIPSIS);
                setAlignment(Pos.CENTER);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                badge.getStyleClass().removeAll(
                        "match-matched", "match-unmatched", "match-no-dbc", "match-ambiguous");
                if (empty || item == null || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                CanMessageInventoryRow row = getTableRow().getItem();
                badge.setText(item);
                badge.getStyleClass().add(switch (row.matchState()) {
                    case MATCHED -> "match-matched";
                    case UNMATCHED -> "match-unmatched";
                    case NO_DBC -> "match-no-dbc";
                    case AMBIGUOUS -> "match-ambiguous";
                });
                setGraphic(badge);
                setTooltip(new Tooltip(row.matchState().tooltip()));
            }
        });

        TableColumn<CanMessageInventoryRow, String> msgCol = new TableColumn<>("DBC Message");
        msgCol.setMinWidth(170);
        msgCol.setPrefWidth(210);
        msgCol.setMaxWidth(300);
        msgCol.setResizable(true);
        msgCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().dbcMessageDisplay()));
        msgCol.setCellFactory(col -> new TableCell<>() {
            {
                setAlignment(Pos.CENTER_LEFT);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item);
                setTextOverrun(OverrunStyle.ELLIPSIS);
                setTooltip(item.equals("\u2014") ? null : new Tooltip(item));
            }
        });

        TableColumn<CanMessageInventoryRow, String> sigCol = new TableColumn<>("Signals");
        sigCol.setMinWidth(65);
        sigCol.setPrefWidth(80);
        sigCol.setMaxWidth(95);
        sigCol.setResizable(true);
        sigCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().signalsDisplay()));
        sigCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item);
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        TableColumn<CanMessageInventoryRow, CanMessageInventoryRow> detailsCol = new TableColumn<>("");
        detailsCol.getStyleClass().add("inventory-details-col");
        detailsCol.setMinWidth(40);
        detailsCol.setPrefWidth(44);
        detailsCol.setMaxWidth(50);
        detailsCol.setResizable(false);
        detailsCol.setSortable(false);
        detailsCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        detailsCol.setCellFactory(col -> new TableCell<>() {
            private final Button inspectButton = new Button();
            {
                inspectButton.setGraphic(StellarIcons.search(12, com.stellar.platform.desktop.ui.theme.StellarTheme.ICON_MUTED));
                inspectButton.getStyleClass().addAll("secondary-button", "compact-button", "inventory-inspect-button");
                inspectButton.setFocusTraversable(false);
                inspectButton.setTooltip(new Tooltip("Inspect CAN message"));
                inspectButton.setOnAction(e -> {
                    CanMessageInventoryRow row = getItem();
                    if (row == null) {
                        return;
                    }
                    openInspectDrawer(row);
                });
            }
            @Override
            protected void updateItem(CanMessageInventoryRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                setGraphic(inspectButton);
                setAlignment(Pos.CENTER);
            }
        });

        table.getColumns().setAll(
                studyCol, idCol, channelCol, framesCol, matchCol, msgCol, sigCol, detailsCol);

        MenuItem copyId = new MenuItem("Copy CAN ID");
        copyId.setOnAction(e -> {
            CanMessageInventoryRow row = table.getSelectionModel().getSelectedItem();
            if (row == null) {
                return;
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(row.canIdHex());
            Clipboard.getSystemClipboard().setContent(content);
        });
        table.setContextMenu(new ContextMenu(copyId));
    }

    private void renderNavigator(WorkspaceSnapshot snap) {
        syncingSelection = true;
        try {
            Map<String, Boolean> wasExpanded = new HashMap<>();
            for (TreeItem<NavNode> child : navRoot.getChildren()) {
                if (child.getValue() != null && child.getValue().sourceId() != null) {
                    wasExpanded.put(child.getValue().sourceId().value(), child.isExpanded());
                }
            }
            navRoot.getChildren().clear();
            if (snap == null || snap.sources().isEmpty()) {
                structuralRebuildCountForTest++;
                return;
            }
            TreeItem<NavNode> select = null;
            for (SourceView source : snap.sources()) {
                boolean expanded = wasExpanded.getOrDefault(
                        source.sourceId().value(), source.expanded());
                model.setExpanded(source.sourceId(), expanded);
                String status = SourceInventoryWorkspaceModel.statusLabel(source.jobState(), source.inventory());
                String meta = source.formatBadge()
                        + " · " + source.channelCount() + " Ch"
                        + " · " + intFormat.format(source.uniqueIds()) + " Unique CAN IDs"
                        + " · " + SourceDisplayNameResolver.compactFrameCount(source.frames()) + " Frames";
                String tip = source.fullPath()
                        + "\n" + meta
                        + "\n" + status;
                TreeItem<NavNode> sourceItem = new TreeItem<>(NavNode.source(
                        source.sourceId(),
                        source.fullFileName(),
                        source.compactName(),
                        status,
                        meta,
                        tip
                ));

                TreeItem<NavNode> allItem = new TreeItem<>(NavNode.metricsRow(
                        NavKind.ALL_CHANNELS,
                        source.sourceId(),
                        -1,
                        "All Channels",
                        intFormat.format(source.uniqueIds()),
                        "\u2014",
                        "Source-global unique CAN ID union (not per-channel sum).\n"
                                + "DBC Matched is per-channel mapping; All Channels is not summed."
                ));
                sourceItem.getChildren().add(allItem);
                for (ChannelView ch : source.channels()) {
                    SourceChannelKey channelKey = SourceChannelKey.of(source.sourceId(), ch.channelId());
                    if (!channelPassesDecisionFilters(channelKey)) {
                        continue;
                    }
                    String coverageText = channelCoverageText(channelKey);
                    String matchedText = channelDbcMatchedText(channelKey);
                    String idsText = intFormat.format(ch.uniqueIds());
                    TreeItem<NavNode> chItem = new TreeItem<>(NavNode.metricsRow(
                            NavKind.CHANNEL,
                            source.sourceId(),
                            ch.channelId(),
                            ch.label(),
                            idsText,
                            matchedText,
                            channelNavStatus(channelKey),
                            channelNavigatorTip(ch.label(), idsText, matchedText, coverageText)
                    ));
                    sourceItem.getChildren().add(chItem);
                    if (snap.selectedSourceId() != null
                            && snap.selectedSourceId().equals(source.sourceId())
                            && snap.selectedChannelId().isPresent()
                            && snap.selectedChannelId().getAsInt() == ch.channelId()) {
                        select = chItem;
                    }
                }
                if (snap.selectedSourceId() != null
                        && snap.selectedSourceId().equals(source.sourceId())
                        && snap.selectedChannelId().isEmpty()) {
                    select = allItem;
                }
                sourceItem.setExpanded(expanded);
                navRoot.getChildren().add(sourceItem);
            }
            structuralRebuildCountForTest++;
            if (select != null) {
                navigator.getSelectionModel().select(select);
            }
        } finally {
            syncingSelection = false;
        }
    }

    private void renderCenter(WorkspaceSnapshot snap) {
        if (snap == null || snap.sources().isEmpty()) {
            showEmptyProject();
            return;
        }
        SourceView source = snap.sources().stream()
                .filter(s -> s.sourceId().equals(snap.selectedSourceId()))
                .findFirst()
                .orElse(snap.sources().getFirst());
        ProjectInventoryJobState job = source.jobState();
        CanSourceInventory inv = source.inventory();
        boolean inventoryReady = inv != null && inv.status() == CanInventoryStatus.INVENTORY_READY;

        if (!inventoryReady && (job == ProjectInventoryJobState.INDEXING
                || job == ProjectInventoryJobState.WAITING
                || job == ProjectInventoryJobState.ADDED)) {
            scopeTitle.setText(source.fullFileName() + " · Indexing");
            scopeFile.setText(source.compactName());
            cachedContextSource = source;
            cachedActiveChannel = false;
            cachedIdsValue = "";
            cachedFramesValue = "";
            cachedChannelPrimary = "";
            clearMappingFields();
            paintContextHeader("running", "Inspecting source inventory...");
            unionHint.setVisible(false);
            unionHint.setManaged(false);
            table.setVisible(false);
            table.setManaged(false);
            centerState.setText("Inspecting source inventory...");
            emptyPane.setVisible(true);
            emptyPane.setManaged(true);
            addFilesButton.setVisible(false);
            addFilesButton.setManaged(false);
            indexingIndicator.setVisible(true);
            indexingIndicator.setManaged(true);
            resultCountLabel.setText("");
            tableRows.clear();
            return;
        }

        if (!inventoryReady && (job == ProjectInventoryJobState.FAILED
                || job == ProjectInventoryJobState.REQUIRES_ATTENTION
                || (inv != null && inv.status() == CanInventoryStatus.FAILED))) {
            scopeTitle.setText(source.fullFileName() + " · Requires Attention");
            scopeFile.setText(source.compactName());
            cachedContextSource = source;
            cachedActiveChannel = false;
            cachedIdsValue = "";
            cachedFramesValue = "";
            cachedChannelPrimary = "";
            clearMappingFields();
            paintContextHeader("attention", diagnosticSummary(inv));
            unionHint.setVisible(false);
            unionHint.setManaged(false);
            table.setVisible(false);
            table.setManaged(false);
            centerState.setText(diagnosticSummary(inv));
            emptyPane.setVisible(true);
            emptyPane.setManaged(true);
            addFilesButton.setVisible(false);
            addFilesButton.setManaged(false);
            indexingIndicator.setVisible(false);
            indexingIndicator.setManaged(false);
            resultCountLabel.setText("");
            tableRows.clear();
            return;
        }

        indexingIndicator.setVisible(false);
        indexingIndicator.setManaged(false);
        addFilesButton.setVisible(false);
        addFilesButton.setManaged(false);

        if (inv == null || inv.channels().isEmpty()) {
            scopeTitle.setText(source.fullFileName() + " / All Channels");
            scopeFile.setText(source.compactName());
            cachedContextSource = source;
            cachedActiveChannel = false;
            cachedChannelPrimary = "";
            cachedIdsValue = "";
            cachedFramesValue = "";
            clearMappingFields();
            paintContextHeader(statusStyleFor(source), "No CAN channels observed.");
            unionHint.setVisible(false);
            unionHint.setManaged(false);
            tableRows.clear();
            table.setVisible(true);
            table.setManaged(true);
            centerState.setText("No CAN channels observed.");
            emptyPane.setVisible(true);
            emptyPane.setManaged(true);
            addFilesButton.setVisible(false);
            addFilesButton.setManaged(false);
            resultCountLabel.setText("Showing 0 of 0 IDs");
            return;
        }

        hideEmptyOverlay();
        table.setVisible(true);
        table.setManaged(true);

        OptionalInt channel = snap.selectedChannelId();
        cachedContextSource = source;
        if (channel.isPresent()) {
            scopeTitle.setText(source.fullFileName() + " / Ch" + channel.getAsInt());
            ChannelView ch = source.channels().stream()
                    .filter(c -> c.channelId() == channel.getAsInt())
                    .findFirst()
                    .orElse(null);
            scopeFile.setText(source.compactName());
            cachedActiveChannel = true;
            cachedChannelPrimary = "Ch" + channel.getAsInt();
            cachedChannelCaption = "Channel";
            if (ch != null) {
                cachedIdsValue = intFormat.format(ch.uniqueIds());
                cachedIdsCaption = "CAN IDs";
                cachedFramesValue = intFormat.format(ch.frames());
                setScopeMetrics(cachedIdsValue + " CAN IDs", cachedFramesValue + " Frames");
            } else {
                cachedIdsValue = "";
                cachedFramesValue = "";
                setScopeMetrics("", "");
            }
            unionHint.setVisible(false);
            unionHint.setManaged(false);
        } else {
            scopeTitle.setText(source.fullFileName() + " / All Channels");
            scopeFile.setText(source.compactName());
            cachedActiveChannel = false;
            cachedChannelPrimary = source.channelCount()
                    + (source.channelCount() == 1 ? " Channel" : " Channels");
            cachedChannelCaption = "Channels";
            cachedIdsValue = intFormat.format(source.uniqueIds());
            cachedIdsCaption = "Unique CAN IDs";
            cachedFramesValue = intFormat.format(source.frames());
            setScopeMetrics(
                    cachedChannelPrimary + " · " + cachedIdsValue + " Unique CAN IDs",
                    cachedFramesValue + " Frames");
            unionHint.setVisible(false);
            unionHint.setManaged(false);
        }
        applyQueryOnly();
    }

    private void applyQueryOnly() {
        if (snapshot == null || appService == null) {
            return;
        }
        snapshot = new WorkspaceSnapshot(
                snapshot.sources(),
                snapshot.measurementSourceCount(),
                snapshot.totalCanChannels(),
                model.selectedSourceId(),
                model.selectedChannelId(),
                model.searchText(),
                model.canTypeFilter(),
                model.matchFilter(),
                model.contentFilter()
        );
        SourceInventoryWorkspaceQuery.MessageQueryResult result =
                model.querySelected(snapshot, this::enrichmentContextFor);
        CanMessageInventoryRow previouslySelected = table.getSelectionModel().getSelectedItem();
        syncingSelection = true;
        try {
            tableRows.setAll(result.rows());
            studySet.refreshMetadata(tableRows);
            boolean restored = false;
            if (previouslySelected != null) {
                for (CanMessageInventoryRow row : tableRows) {
                    if (row.channelKey().equals(previouslySelected.channelKey())
                            && row.canId() == previouslySelected.canId()
                            && row.extended() == previouslySelected.extended()) {
                        table.getSelectionModel().select(row);
                        restored = true;
                        break;
                    }
                }
            }
            if (!restored) {
                table.getSelectionModel().clearSelection();
                detailsInspector.clearSelection();
            } else {
                detailsInspector.setSelection(table.getSelectionModel().getSelectedItem());
            }
        } finally {
            syncingSelection = false;
        }
        long shown = result.rows().size();
        long total = result.totalRowsBeforeFilter();
        resultCountLabel.setText("Showing " + shown + " of " + total + " IDs");
        boolean filteredEmpty = shown == 0 && total > 0;
        tablePlaceholder.setText(filteredEmpty
                ? "No CAN IDs match the current filter."
                : "No CAN message identities in this scope.");
        updateQuickFilterChipCounts(result);
        renderDbcContext(snapshot);
        updateStudyActionEnablement();
        updateStudyHeaderCheck();
        refineColumnPrefsFromSample();
        refreshDecisionChrome();
    }

    /**
     * One-shot bounded preferred-width refinement from currently loaded rows.
     * Does not scan full inventory; clamps to declared min/max; no continuous rebinding.
     */
    private void refineColumnPrefsFromSample() {
        if (table.getColumns().size() < 7 || tableRows.isEmpty()) {
            return;
        }
        int sample = Math.min(40, tableRows.size());
        double msgChars = "DBC Message".length();
        double framesChars = "Frames".length();
        for (int i = 0; i < sample; i++) {
            CanMessageInventoryRow row = tableRows.get(i);
            String msg = row.dbcMessageDisplay();
            if (msg != null && !msg.isBlank() && !"\u2014".equals(msg)) {
                msgChars = Math.max(msgChars, msg.length());
            }
            framesChars = Math.max(framesChars, intFormat.format(row.frameCount()).length());
        }
        @SuppressWarnings("unchecked")
        TableColumn<CanMessageInventoryRow, ?> framesCol =
                (TableColumn<CanMessageInventoryRow, ?>) table.getColumns().get(3);
        @SuppressWarnings("unchecked")
        TableColumn<CanMessageInventoryRow, ?> msgCol =
                (TableColumn<CanMessageInventoryRow, ?>) table.getColumns().get(5);
        double framesPref = clamp(framesChars * 8.5 + 20, 100, 145);
        double msgPref = clamp(msgChars * 7.2 + 28, 170, 300);
        if (Math.abs(framesCol.getPrefWidth() - framesPref) > 4) {
            framesCol.setPrefWidth(framesPref);
        }
        if (Math.abs(msgCol.getPrefWidth() - msgPref) > 6) {
            msgCol.setPrefWidth(msgPref);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateWorkspaceSummary(WorkspaceSnapshot snap) {
        if (snap == null || snap.measurementSourceCount() == 0) {
            workspaceSummary.setText("No measurement sources available.");
            return;
        }
        workspaceSummary.setText(
                snap.measurementSourceCount() + " Measurement Sources · "
                        + snap.totalCanChannels() + " CAN Channels");
    }

    private void showEmptyProject() {
        scopeTitle.setText("Select a source and channel");
        scopeFile.setText("");
        cachedContextSource = null;
        cachedActiveChannel = false;
        cachedIdsValue = "";
        cachedFramesValue = "";
        cachedChannelPrimary = "";
        clearMappingFields();
        paintContextHeader(null, "No measurement sources available.");
        detailsInspector.clearSelection();
        unionHint.setVisible(false);
        unionHint.setManaged(false);
        tableRows.clear();
        table.setVisible(false);
        table.setManaged(false);
        centerState.setText("Select a source and channel to inspect its CAN inventory.");
        emptyPane.setVisible(true);
        emptyPane.setManaged(true);
        indexingIndicator.setVisible(false);
        indexingIndicator.setManaged(false);
        addFilesButton.setVisible(true);
        addFilesButton.setManaged(true);
        resultCountLabel.setText("");
        workspaceSummary.setText("No measurement sources available.");
        candidateStrip.clear();
        channelSelector.clear();
        signalExplorer.clear();
        activeMappingChannelId = null;
        workspaceCounts.refresh(0, 0, 0, 0);
        statusPills.getChildren().clear();
        decisionActionBar.refresh("No channel selected", "", false, false, false, false, "");
    }

    /** True when the large duplicate SOURCE INVENTORY heading is not shown in this pane. */
    public boolean hasDuplicateWorkspaceTitleForTest() {
        return title.isManaged() && title.isVisible();
    }

    public String workspaceSummaryTextForTest() {
        return workspaceSummary.getText();
    }

    private void hideEmptyOverlay() {
        emptyPane.setVisible(false);
        emptyPane.setManaged(false);
        addFilesButton.setVisible(false);
        addFilesButton.setManaged(false);
    }

    private static String diagnosticSummary(CanSourceInventory inventory) {
        if (inventory == null || inventory.diagnostics().isEmpty()) {
            return "Inventory requires attention. Re-inspect the source if available.";
        }
        StringBuilder sb = new StringBuilder();
        for (CanInventoryDiagnostic d : inventory.diagnostics()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(d.message() == null ? d.code().name() : d.message());
        }
        return sb.toString();
    }

    private SourceChannelEnrichmentContext enrichmentContextFor(SourceChannelKey key) {
        if (appService == null || key == null) {
            return SourceChannelEnrichmentContext.noDbc(key);
        }
        java.util.function.Function<DefinitionSourceId, java.util.Optional<com.stellar.platform.desktop.presentation.ProjectDefinitionMatchingCoordinator.ParsedDefinition>> lookup =
                id -> appService.definitionMatchingCoordinator().parsedDefinition(id);
        SourceChannelMappingEntry mapping = appService.channelMapping(key).orElse(null);
        Optional<DefinitionSourceId> displayed = appService.workingDbcSelections()
                .displayedDefinition(key, mapping);
        if (displayed.isPresent()) {
            Optional<SourceChannelDefinitionCandidate> candidate = candidateFor(key, displayed.get());
            if (candidate.isPresent() && !isPersistedSelection(candidate.get(), mapping)) {
                return SourceInventoryEnrichmentService.contextForPreview(
                        key,
                        candidate.get(),
                        lookup);
            }
        }
        return SourceInventoryEnrichmentService.contextFor(key, mapping, lookup);
    }

    private static boolean isPersistedSelection(
            SourceChannelDefinitionCandidate candidate,
            SourceChannelMappingEntry mapping
    ) {
        if (mapping == null || candidate == null) {
            return false;
        }
        if (mapping.status() == ChannelMappingStatus.CONFIRMED || mapping.status() == ChannelMappingStatus.MANUAL) {
            return mapping.mappedDefinitionId().isPresent()
                    && mapping.mappedDefinitionId().get().equals(candidate.definitionSourceId());
        }
        return mapping.status() == ChannelMappingStatus.RECOMMENDED
                && mapping.recommendedDefinitionId().isPresent()
                && mapping.recommendedDefinitionId().get().equals(candidate.definitionSourceId());
    }

    private void renderDbcContext(WorkspaceSnapshot snap) {
        if (snap == null || snap.selectedSourceId() == null || appService == null) {
            clearMappingLine();
            return;
        }
        ProjectSourceId sourceId = snap.selectedSourceId();
        OptionalInt channel = snap.selectedChannelId();
        if (channel.isPresent()) {
            SourceChannelKey key = SourceChannelKey.of(sourceId, channel.getAsInt());
            SourceChannelEnrichmentContext ctx = enrichmentContextFor(key);
            if (ctx.ambiguous()) {
                setMappingLine("DBC", "", "NEEDS ATTENTION",
                        intFormat.format(ctx.observedUniqueMessageCount()) + " observed · confirm a DBC in Mapping",
                        "attention");
                return;
            }
            if (!ctx.hasDbc()) {
                setMappingLine("DBC", "No confirmed DBC", "NEEDS ATTENTION",
                        intFormat.format(ctx.observedUniqueMessageCount()) + " observed · raw inventory",
                        "attention");
                return;
            }
            String badge;
            String style;
            DisplayState displayState = mappingDisplayState(key, appService.channelMapping(key).orElse(null));
            if (displayState == DisplayState.SELECTED) {
                badge = "SELECTED";
                style = "manual";
            } else if (displayState == DisplayState.CONFIRMED) {
                badge = "CONFIRMED";
                style = "confirmed";
            } else if (displayState == DisplayState.RECOMMENDED || ctx.recommendedUnconfirmed()) {
                badge = "RECOMMENDED";
                style = "recommended";
            } else if (displayState == DisplayState.NEEDS_ATTENTION) {
                badge = "NEEDS ATTENTION";
                style = "attention";
            } else if (ctx.mappingStatus().isPresent()) {
                ChannelMappingStatus status = ctx.mappingStatus().get();
                badge = status.badgeLabel();
                style = switch (status) {
                    case CONFIRMED -> "confirmed";
                    case MANUAL -> "manual";
                    default -> "recommended";
                };
            } else {
                badge = "MAPPED";
                style = "confirmed";
            }
            long observed = ctx.observedUniqueMessageCount();
            long matched = ctx.matchedUniqueMessageCount();
            Optional<ChannelMappingPreview.Snapshot> preview = mappingPreviewFor(key);
            if (preview.isPresent()) {
                observed = preview.get().observedCount();
                matched = preview.get().matchedCount();
            }
            String coverage = intFormat.format(matched) + " / " + intFormat.format(observed) + " matched";
            if (observed > 0) {
                double pct = (100.0 * matched) / observed;
                coverage += " · " + String.format(Locale.US, "%.2f%%", pct);
            }
            setMappingLine("DBC", ctx.definitionDisplayName(), badge, coverage, style);
            return;
        }
        // All Channels — source overview stays in the header; channel strip carries DBC choices.
        clearMappingFields();
        paintContextHeader(null, null);
        semanticsHelpButton.setTooltip(new Tooltip(INVENTORY_SEMANTICS_HELP));
    }

    private void clearMappingLine() {
        clearMappingFields();
        paintContextHeader(null, null);
    }

    private void clearMappingFields() {
        dbcContextLabel.setText("");
        dbcNameLabel.setText("");
        mappingStatusBadge.setText("");
        mappingStatusBadge.getStyleClass().removeAll(
                "mapping-confirmed", "mapping-recommended", "mapping-manual", "mapping-attention", "mapping-mapped");
        coverageLabel.setText("");
        cachedMappingMeta = "";
        cachedShowDbcTile = false;
        cachedMatchText = "";
        cachedCoverageText = "";
        cachedMatchTooltip = "";
        cachedMappingTooltip = "";
        cachedAggregateDetail = "";
    }

    private void setMappingLine(String prefix, String dbcName, String badge, String coverage, String style) {
        dbcContextLabel.setText(prefix == null ? "" : prefix);
        dbcNameLabel.setText(dbcName == null ? "" : dbcName);
        mappingStatusBadge.setText(badge == null ? "" : badge);
        mappingStatusBadge.getStyleClass().removeAll(
                "mapping-confirmed", "mapping-recommended", "mapping-manual", "mapping-attention", "mapping-mapped");
        if (style != null && !style.isBlank()) {
            mappingStatusBadge.getStyleClass().add("mapping-" + style);
        }
        coverageLabel.setText(coverage == null ? "" : coverage);

        boolean allChannels = snapshot != null && snapshot.selectedChannelId().isEmpty();
        cachedShowDbcTile = !allChannels && dbcName != null && !dbcName.isBlank()
                && !"No confirmed DBC".equals(dbcName)
                && !"Channel mappings".equals(dbcName);
        cachedAggregateDetail = "";
        cachedMatchText = "";
        cachedCoverageText = "";
        cachedMatchTooltip = "";
        cachedMappingTooltip = "";

        if ("recommended".equals(style)) {
            cachedMappingTooltip = "Best current DBC candidate. Explicit confirmation is still required.";
        } else if (badge != null && !badge.isBlank()) {
            cachedMappingTooltip = "Mapping state: " + badge;
        }

        if (allChannels && "Channel mappings".equals(dbcName)) {
            cachedShowDbcTile = false;
            cachedAggregateDetail = "DBC Mapping: Channel mappings"
                    + (coverage == null || coverage.isBlank() ? "" : "  ·  " + coverage);
        } else if (coverage != null && !coverage.isBlank()) {
            // coverage currently: "9/56 matched · 16.07%" or attention text
            int sep = coverage.indexOf(" · ");
            if (sep > 0 && coverage.contains("matched")) {
                cachedMatchText = coverage.substring(0, sep).trim();
                cachedCoverageText = coverage.substring(sep + 3).trim();
                cachedMatchTooltip = cachedMatchText.replace(" matched", "")
                        + " of observed CAN IDs match the current DBC.";
            } else if (coverage.contains("matched")) {
                cachedMatchText = coverage.trim();
                cachedMatchTooltip = cachedMatchText.replace(" matched", "")
                        + " observed CAN IDs match the current DBC.";
            } else {
                cachedAggregateDetail = coverage;
            }
        }

        StringBuilder mapping = new StringBuilder();
        if (prefix != null && !prefix.isBlank()) {
            mapping.append(prefix);
        }
        if (dbcName != null && !dbcName.isBlank()) {
            if (!mapping.isEmpty()) {
                mapping.append(' ');
            }
            mapping.append(dbcName);
        }
        if (badge != null && !badge.isBlank()) {
            if (!mapping.isEmpty()) {
                mapping.append("  ");
            }
            mapping.append(badge);
        }
        if (coverage != null && !coverage.isBlank()) {
            if (!mapping.isEmpty()) {
                mapping.append("  ·  ");
            }
            mapping.append(coverage);
        }
        cachedMappingMeta = mapping.toString();
        paintContextHeader(null, null);
    }

    private void setScopeMetrics(String idsText, String framesText) {
        scopeSummary.setText("");
        scopeSummary.setVisible(false);
        scopeSummary.setManaged(false);
        cachedIdsMeta = idsText == null ? "" : idsText;
        cachedFramesMeta = framesText == null ? "" : framesText;
        idsMetricBadge.setVisible(false);
        idsMetricBadge.setManaged(false);
        framesMetricBadge.setVisible(false);
        framesMetricBadge.setManaged(false);
        scopeMetricsRow.setVisible(false);
        scopeMetricsRow.setManaged(false);
        paintContextHeader(null, null);
    }

    private void paintContextHeader(String forcedStatusStyle, String messageOverride) {
        SourceView source = cachedContextSource;
        String title = scopeTitle.getText() == null ? "" : scopeTitle.getText();
        if (source == null) {
            contextHeader.showMessage(title.isBlank() ? "Select a source and channel" : title,
                    messageOverride == null ? "No measurement sources available." : messageOverride);
            return;
        }
        if (messageOverride != null && !messageOverride.isBlank()
                && (cachedIdsValue == null || cachedIdsValue.isBlank())
                && (forcedStatusStyle != null)) {
            contextHeader.show(
                    title,
                    SourceInventoryWorkspaceModel.statusLabel(source.jobState(), source.inventory()),
                    forcedStatusStyle,
                    false,
                    source.formatBadge(),
                    source.compactName(),
                    source.fullFileName(),
                    measurementTooltip(source),
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    false,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    messageOverride);
            return;
        }

        String status = SourceInventoryWorkspaceModel.statusLabel(source.jobState(), source.inventory());
        String statusStyle = forcedStatusStyle != null ? forcedStatusStyle : statusStyleFor(source);
        contextHeader.show(
                title,
                status,
                statusStyle,
                cachedActiveChannel,
                source.formatBadge(),
                source.compactName(),
                source.fullFileName(),
                measurementTooltip(source),
                cachedChannelPrimary,
                cachedChannelCaption,
                cachedIdsValue,
                cachedIdsCaption,
                cachedFramesValue,
                "Frames",
                cachedShowDbcTile,
                dbcNameLabel.getText(),
                contextDbcCaption(),
                mappingStatusBadge.getText(),
                mappingStyleToken(),
                cachedMatchText,
                cachedCoverageText,
                cachedMatchTooltip,
                cachedMappingTooltip,
                cachedAggregateDetail);
    }

    private String contextDbcCaption() {
        String state = mappingStatusBadge.getText() == null ? "" : mappingStatusBadge.getText().trim();
        if ("RECOMMENDED".equalsIgnoreCase(state)) {
            return "Recommended DBC";
        }
        if ("CONFIRMED".equalsIgnoreCase(state)) {
            return "Confirmed DBC";
        }
        if ("SELECTED".equalsIgnoreCase(state) || "MANUAL".equalsIgnoreCase(state)) {
            return "Selected DBC";
        }
        return "DBC File";
    }

    private String mappingStyleToken() {
        for (String s : mappingStatusBadge.getStyleClass()) {
            if (s.startsWith("mapping-")) {
                return s.substring("mapping-".length());
            }
        }
        return "";
    }

    private static String statusStyleFor(SourceView source) {
        String label = SourceInventoryWorkspaceModel.statusLabel(source.jobState(), source.inventory());
        if ("READY".equals(label)) {
            return "ready";
        }
        if ("INDEXING".equals(label) || "WAITING".equals(label) || "ADDED".equals(label)) {
            return "running";
        }
        return "attention";
    }

    private String measurementTooltip(SourceView source) {
        if (source == null) {
            return "";
        }
        StringBuilder tip = new StringBuilder();
        tip.append("File: ").append(source.fullFileName() == null ? source.compactName() : source.fullFileName());
        if (source.formatBadge() != null && !source.formatBadge().isBlank()) {
            tip.append("\nType: ").append(source.formatBadge());
        }
        if (appService != null) {
            for (WorkspaceFile file : appService.workspace().measurementFiles()) {
                if (file.projectSourceId().equals(source.sourceId()) && file.sizeBytes() > 0) {
                    tip.append("\nSize: ").append(formatBytes(file.sizeBytes()));
                    break;
                }
            }
        }
        if (source.fullPath() != null && !source.fullPath().isBlank()) {
            tip.append("\nPath: ").append(source.fullPath());
        }
        return tip.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private void configureStudyBar() {
        selectAllMatchedButton.getStyleClass().addAll("secondary-button", "compact-button", "inventory-study-action");
        selectVisibleButton.getStyleClass().addAll("secondary-button", "compact-button", "inventory-study-action");
        clearStudyButton.getStyleClass().addAll("secondary-button", "compact-button", "inventory-study-action");
        preventActionEllipsis(selectAllMatchedButton);
        preventActionEllipsis(selectVisibleButton);
        preventActionEllipsis(clearStudyButton);
        preventActionEllipsis(headerStudyButton);
        preventActionEllipsis(headerSignalsButton);
        selectAllMatchedButton.setTooltip(new Tooltip(
                "Add all matched CAN messages in the selected source/channel scope to the Study Set."));
        selectVisibleButton.setTooltip(selectVisibleDefaultTooltip);
        clearStudyButton.setTooltip(new Tooltip("Clear Study Set — filters and source/channel scope stay."));
        studyBar.getStyleClass().add("inventory-study-bar");
        studyBar.setAlignment(Pos.CENTER_LEFT);
        studyBar.getChildren().clear();
        selectAllMatchedButton.setOnAction(e -> selectAllMatchedIntoStudy());
        selectVisibleButton.setOnAction(e -> selectVisibleIntoStudy());
        clearStudyButton.setOnAction(e -> clearStudySet());
        openStudySetButton.setOnAction(e -> toggleStudyDrawer());
    }

    private static void preventActionEllipsis(Button button) {
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setMaxWidth(Region.USE_PREF_SIZE);
        button.setWrapText(false);
    }

    private void toggleStudyDrawer() {
        if (!inspectorCollapsed && inspectorDrawer.isStudyTab()) {
            collapseInspector();
            return;
        }
        inspectorDrawer.showTab(DecisionInspectorDrawer.Tab.STUDY);
        expandInspector();
        updateStudyDrawerChrome();
    }

    private void toggleSignalsDrawer() {
        if (!inspectorCollapsed && inspectorDrawer.isSignalsTab()) {
            collapseInspector();
            return;
        }
        inspectorDrawer.showTab(DecisionInspectorDrawer.Tab.SIGNALS);
        expandInspector();
        updateStudyDrawerChrome();
    }

    private void updateStudyDrawerChrome() {
        boolean studyOpen = !inspectorCollapsed && inspectorDrawer.isStudyTab();
        boolean signalsOpen = !inspectorCollapsed && inspectorDrawer.isSignalsTab();
        headerStudyButton.getStyleClass().remove("inventory-study-toggle-active");
        headerSignalsButton.getStyleClass().remove("decision-inspector-tab-active");
        if (studyOpen) {
            headerStudyButton.getStyleClass().add("inventory-study-toggle-active");
            headerStudyButton.setTooltip(closeStudySetTooltip);
        } else {
            headerStudyButton.setTooltip(openStudySetTooltip);
        }
        if (signalsOpen) {
            headerSignalsButton.getStyleClass().add("decision-inspector-tab-active");
        }
        // Legacy hidden control kept for older tests that fire Open Study Set.
        openStudySetButton.setText(studyOpen ? "Close Study Set" : "Open Study Set");
    }

    private void configureContextActions() {
        headerInspectButton.setVisible(false);
        headerInspectButton.setManaged(false);
        headerSignalsButton.getStyleClass().addAll(
                "secondary-button", "secondary-action", "compact-button", "decision-inspector-tab");
        headerSignalsButton.setMinWidth(Region.USE_PREF_SIZE);
        headerSignalsButton.setOnAction(e -> toggleSignalsDrawer());
        headerSignalsButton.setTooltip(new Tooltip("Open Signal Explorer"));
        headerStudyButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button", "inventory-study-toggle");
        headerStudyButton.setGraphic(StellarIcons.starOutline());
        headerStudyButton.setGraphicTextGap(6);
        headerStudyButton.setMinWidth(Region.USE_PREF_SIZE);
        headerStudyButton.setOnAction(e -> toggleStudyDrawer());
        headerStudyButton.setTooltip(openStudySetTooltip);
        contextActions.setAlignment(Pos.CENTER_RIGHT);
        contextActions.getStyleClass().add("inventory-context-actions");
        contextActions.getChildren().setAll(headerSignalsButton, headerStudyButton);
        updateHeaderActionEnablement();
    }

    private void openInspectDrawer(CanMessageInventoryRow row) {
        if (row == null) {
            return;
        }
        syncingSelection = true;
        try {
            table.getSelectionModel().select(row);
        } finally {
            syncingSelection = false;
        }
        inspectorDrawer.showInspect();
        expandInspector();
        detailsInspector.setSelection(row);
        updateHeaderActionEnablement();
    }

    private void updateHeaderActionEnablement() {
        // Inspect is row-level Details only.
        headerInspectButton.setDisable(true);
    }

    private static HBox inlineFilter(Label caption, javafx.scene.Node control) {
        HBox box = new HBox(4, caption, control);
        box.getStyleClass().add("inventory-filter-inline");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static void applyLabeledCombo(ComboBox<String> combo, String role) {
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(role + ": " + (empty || item == null || item.isBlank() ? "All" : item));
            }
        });
        combo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item);
            }
        });
    }

    private static VBox labeledFilter(Label caption, javafx.scene.Node control) {
        VBox box = new VBox(3, caption, control);
        box.getStyleClass().add("inventory-filter-field");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void onStudySetChanged() {
        // Refresh checkbox cells only — do not rebuild table items or re-query inventory.
        table.refresh();
        updateStudyActionEnablement();
        int n = studySet.size();
        studySetSummaryLabel.setText("Study Set: " + n + (n == 1 ? " CAN Message" : " CAN Messages"));
        headerStudyButton.setText("Study Set (" + n + ")");
        inspectorDrawer.setStudyCount(n);
        updateStudyHeaderCheck();
    }

    private void updateStudyActionEnablement() {
        List<CanMessageInventoryRow> allMatchedEligible = eligibleMatchedInCurrentScope();
        int allMatchedCount = allMatchedEligible.size();
        int currentViewCount = countEligible(tableRows);
        selectAllMatchedButton.setText("Add All Matched (" + allMatchedCount + ")");
        selectVisibleButton.setText("Add Filtered (" + currentViewCount + ")");

        int missingAll = studySet.missingEligibleCount(allMatchedEligible);
        int missingView = studySet.missingEligibleCount(tableRows);
        boolean sameEligibleSet = allMatchedCount == currentViewCount;
        selectAllMatchedButton.setDisable(missingAll == 0);
        if (sameEligibleSet && allMatchedCount > 0) {
            selectVisibleButton.setDisable(true);
            selectVisibleButton.setTooltip(selectVisibleSameSetTooltip);
        } else {
            selectVisibleButton.setDisable(missingView == 0);
            selectVisibleButton.setTooltip(selectVisibleDefaultTooltip);
        }
        clearStudyButton.setDisable(studySet.isEmpty());
        updateStudyDrawerChrome();
    }

    private static int countEligible(Iterable<CanMessageInventoryRow> rows) {
        int n = 0;
        for (CanMessageInventoryRow row : rows) {
            if (InventoryStudySet.isEligible(row)) {
                n++;
            }
        }
        return n;
    }

    /**
     * MATCHED rows with usable signal definitions in the current Source/Channel scope,
     * ignoring Search / Type / Match / Content filters.
     */
    private List<CanMessageInventoryRow> eligibleMatchedInCurrentScope() {
        if (snapshot == null || snapshot.selectedSourceId() == null || appService == null) {
            return List.of();
        }
        SourceView source = snapshot.sources().stream()
                .filter(s -> s.sourceId().equals(snapshot.selectedSourceId()))
                .findFirst()
                .orElse(null);
        if (source == null || source.inventory() == null) {
            return List.of();
        }
        SourceInventoryWorkspaceQuery.MessageQueryResult result = SourceInventoryWorkspaceQuery.queryMessages(
                source.sourceId(),
                source.inventory(),
                snapshot.selectedChannelId(),
                "",
                CanTypeFilter.ALL,
                MatchFilter.ALL,
                ContentFilter.ALL,
                this::enrichmentContextFor
        );
        List<CanMessageInventoryRow> eligible = new ArrayList<>();
        for (CanMessageInventoryRow row : result.rows()) {
            if (InventoryStudySet.isEligible(row)) {
                eligible.add(row);
            }
        }
        return eligible;
    }

    /** Bulk: add all eligible MATCHED identities in current scope. Returns newly added count. */
    public int selectAllMatchedIntoStudy() {
        int added = studySet.addAll(eligibleMatchedInCurrentScope());
        if (added > 0) {
            inspectorDrawer.showTab(DecisionInspectorDrawer.Tab.STUDY);
            expandInspector();
        }
        return added;
    }

    /** Bulk: add eligible MATCHED rows currently visible after active filters. */
    public int selectVisibleIntoStudy() {
        int added = studySet.addAll(tableRows);
        if (added > 0) {
            inspectorDrawer.showTab(DecisionInspectorDrawer.Tab.STUDY);
            expandInspector();
        }
        return added;
    }

    /** Clear Study Set only — filters, source/channel, and inspected row stay. */
    public void clearStudySet() {
        studySet.clear();
    }

    public InventoryStudySet studySetForTest() {
        return studySet;
    }

    public Button selectAllMatchedButtonForTest() {
        return selectAllMatchedButton;
    }

    public Button selectVisibleButtonForTest() {
        return selectVisibleButton;
    }

    public Button clearStudyButtonForTest() {
        return clearStudyButton;
    }

    public Button openStudySetButtonForTest() {
        return headerStudyButton;
    }

    public Button headerStudyButtonForTest() {
        return headerStudyButton;
    }

    public HBox scopeRowForTest() {
        return scopeRow;
    }

    public VBox studySelectionStackForTest() {
        return studySelectionStack;
    }

    public BorderPane inventoryWorkspaceForTest() {
        return inventoryWorkspace;
    }

    public boolean isTableBorderPaneCenterForTest() {
        if (inventoryWorkspace == null || inventoryWorkspace.getCenter() != centerScroll
                || centerScroll.getContent() != centerColumn) {
            return false;
        }
        for (javafx.scene.Node child : centerColumn.getChildren()) {
            if (child instanceof StackPane stack && stack.getChildren().contains(table)) {
                return true;
            }
        }
        return false;
    }

    public ScrollPane centerScrollForTest() {
        return centerScroll;
    }

    /** Primary Study Set access is the single Study Set (N) toggle. */
    public boolean hasCenterStudySetCountLabelForTest() {
        return headerStudyButton.isManaged() && headerStudyButton.isVisible()
                && headerStudyButton.getText() != null
                && headerStudyButton.getText().startsWith("Study Set (");
    }

    public boolean actionLabelsFullyVisibleForTest() {
        return textFullyVisible(chipAll)
                && textFullyVisible(chipMatched)
                && textFullyVisible(chipUnmatched)
                && textFullyVisible(selectAllMatchedButton)
                && textFullyVisible(selectVisibleButton)
                && textFullyVisible(clearStudyButton)
                && textFullyVisible(headerStudyButton);
    }

    private static boolean textFullyVisible(Labeled labeled) {
        if (labeled == null || !labeled.isVisible() || !labeled.isManaged()) {
            return false;
        }
        String text = labeled.getText();
        if (text == null || text.isBlank()) {
            return false;
        }
        return labeled.getMinWidth() == Region.USE_PREF_SIZE
                || labeled.getWidth() + 1.0 >= labeled.prefWidth(-1);
    }

    private void configureQuickFilterChips() {
        for (ToggleButton chip : new ToggleButton[]{chipAll, chipMatched, chipUnmatched}) {
            chip.getStyleClass().addAll("inventory-quick-filter", "inventory-quick-scope");
            chip.setFocusTraversable(false);
            chip.setMinHeight(30);
            chip.setPrefHeight(32);
            chip.setMaxHeight(34);
            chip.setMinWidth(Region.USE_PREF_SIZE);
            chip.setMaxWidth(Region.USE_PREF_SIZE);
            chip.setWrapText(false);
        }
        chipAll.setMinWidth(72);
        chipMatched.setMinWidth(90);
        chipUnmatched.setMinWidth(100);
        chipAll.setSelected(true);
        chipAll.getStyleClass().add("inventory-quick-filter-active");
        quickFilterBar.getStyleClass().addAll("inventory-quick-filter-bar", "inventory-quick-scope-bar");
        quickFilterBar.setAlignment(Pos.CENTER_LEFT);
        quickFilterBar.getChildren().setAll(chipAll, chipMatched, chipUnmatched);
        chipAll.setOnAction(e -> applyMatchFilterFromChip(MatchFilter.ALL));
        chipMatched.setOnAction(e -> applyMatchFilterFromChip(MatchFilter.MATCHED));
        chipUnmatched.setOnAction(e -> applyMatchFilterFromChip(MatchFilter.UNMATCHED));
    }

    private void applyMatchFilterFromChip(MatchFilter filter) {
        if (syncingMatchFilterUi) {
            return;
        }
        model.setMatchFilter(filter);
        syncingMatchFilterUi = true;
        try {
            matchCombo.getSelectionModel().select(matchFilterLabel(filter));
        } finally {
            syncingMatchFilterUi = false;
        }
        syncQuickFilterChipsFromModel();
        applyQueryOnly();
    }

    private void syncQuickFilterChipsFromModel() {
        MatchFilter filter = model.matchFilter();
        chipAll.setSelected(filter == MatchFilter.ALL);
        chipMatched.setSelected(filter == MatchFilter.MATCHED);
        chipUnmatched.setSelected(filter == MatchFilter.UNMATCHED);
        styleChip(chipAll, filter == MatchFilter.ALL);
        styleChip(chipMatched, filter == MatchFilter.MATCHED);
        styleChip(chipUnmatched, filter == MatchFilter.UNMATCHED);
    }

    private static void styleChip(ToggleButton chip, boolean active) {
        chip.getStyleClass().remove("inventory-quick-filter-active");
        if (active) {
            chip.getStyleClass().add("inventory-quick-filter-active");
        }
    }

    private void updateQuickFilterChipCounts(SourceInventoryWorkspaceQuery.MessageQueryResult result) {
        long total = result == null ? 0 : result.totalRowsBeforeFilter();
        long matched = result == null || result.matchedInScope() < 0 ? 0 : result.matchedInScope();
        long unmatched = result == null || result.unmatchedInScope() < 0 ? 0 : result.unmatchedInScope();
        chipAll.setText("All " + total);
        chipMatched.setText("Matched " + matched);
        chipUnmatched.setText("Unmatched " + unmatched);
        syncQuickFilterChipsFromModel();
    }

    private void refreshDecisionChrome() {
        refreshStatusPills();
        refreshWorkspaceCounts();
        resolveActiveMappingChannel();
        refreshChannelStrip();
        SourceChannelKey key = selectedChannelKey();
        if (key == null) {
            candidateStrip.clear();
            signalExplorer.clear();
            boolean canProceed = appService != null && appService.canProceedFromDecisionWorkspace();
            String hint = appService == null ? "" : appService.decisionProcessingHint();
            String readyTip = appService == null ? "" : appService.decisionProcessingReadyTooltip();
            decisionActionBar.refresh(new DecisionActionBar.ActionState(
                    selectionContextLabel(), "", "", "", false, false, false, canProceed, hint, readyTip,
                    compatibleReviewCount(null)));
            return;
        }
        candidateStrip.refresh(key);
        Optional<SourceChannelMappingEntry> mapping = appService == null
                ? Optional.empty() : appService.channelMapping(key);
        DbcCandidateStrip.DbcOption selected = candidateStrip.selectedOption();
        String dbcName = selected != null && selected.candidate() != null
                ? selected.displayName()
                : mapping.map(this::activeDbcName).orElse("");
        String dbcStatus = currentDbcStatusLabel(key, mapping.orElse(null), selected);
        DisplayState displayState = mappingDisplayState(key, mapping.orElse(null));
        SourceView source = selectedSourceView();
        boolean sourceLevel = model.selectedChannelId().isEmpty();
        String explorerTitle = source == null
                ? "Signal Explorer"
                : sourceLevel
                ? "Signal Explorer — " + source.fullFileName()
                : "Signal Explorer — " + source.fullFileName() + " / Ch" + key.channelId();
        signalExplorer.show(new ExplorerContext(
                explorerTitle,
                catalogEntriesForExplorer(source),
                explorerInventoryRows(source, key),
                mapping.orElse(null),
                dbcName,
                sourceLevel,
                true
        ));
        boolean persistedAccepted = displayState == DisplayState.CONFIRMED;
        String confirmLabel = switch (displayState) {
            case SELECTED -> "Confirm Selected DBC";
            case RECOMMENDED -> "Confirm Recommended";
            default -> "";
        };
        boolean canConfirm = displayState == DisplayState.SELECTED
                || displayState == DisplayState.RECOMMENDED;
        boolean canCompare = appService != null && !appService.comparisonsFor(key).isEmpty();
        boolean canProceed = appService != null && appService.canProceedFromDecisionWorkspace();
        String hint = appService == null ? "" : appService.decisionProcessingHint();
        String readyTip = appService == null ? "" : appService.decisionProcessingReadyTooltip();
        decisionActionBar.refresh(new DecisionActionBar.ActionState(
                selectionContextLabel(),
                dbcName,
                dbcStatus,
                confirmLabel,
                canConfirm && !persistedAccepted,
                persistedAccepted,
                canCompare,
                canProceed,
                hint,
                readyTip,
                compatibleReviewCount(selected == null || selected.candidate() == null
                        ? null : selected.candidate().definitionSourceId())));
    }

    private int compatibleReviewCount(DefinitionSourceId definitionId) {
        if (appService == null || definitionId == null) {
            return 0;
        }
        return appService.compatibleReviewChannelCount(definitionId);
    }

    private void applySelectedDbcToCompatibleReviewChannels() {
        if (appService == null) {
            return;
        }
        DbcCandidateStrip.DbcOption selected = candidateStrip.selectedOption();
        if (selected == null || selected.candidate() == null) {
            return;
        }
        appService.applySelectedDbcToCompatibleReviewChannels(selected.candidate().definitionSourceId());
        refreshEnrichmentOnly();
    }

    private String currentDbcStatusLabel(
            SourceChannelKey key,
            SourceChannelMappingEntry mapping,
            DbcCandidateStrip.DbcOption selected
    ) {
        DisplayState state = mappingDisplayState(key, mapping);
        return switch (state) {
            case SELECTED -> "SELECTED";
            case CONFIRMED -> "CONFIRMED";
            case RECOMMENDED -> "RECOMMENDED";
            case NEEDS_ATTENTION -> "NEEDS ATTENTION";
            case NONE -> selected != null && selected.candidate() != null
                    && !isPersistedSelection(selected.candidate(), mapping)
                    ? "SELECTED"
                    : (mapping == null ? "" : mapping.status().badgeLabel());
        };
    }

    private DisplayState mappingDisplayState(SourceChannelKey key, SourceChannelMappingEntry mapping) {
        if (appService == null || key == null) {
            return DisplayState.NONE;
        }
        return appService.workingDbcSelections().displayState(key, mapping);
    }

    private SourceChannelKey selectedChannelKey() {
        if (model.selectedSourceId() == null) {
            return null;
        }
        if (model.selectedChannelId().isPresent()) {
            return SourceChannelKey.of(model.selectedSourceId(), model.selectedChannelId().getAsInt());
        }
        if (activeMappingChannelId != null) {
            return SourceChannelKey.of(model.selectedSourceId(), activeMappingChannelId);
        }
        return null;
    }

    private String selectionContextLabel() {
        SourceView source = selectedSourceView();
        if (source == null) {
            return "No channel selected";
        }
        if (activeMappingChannelId == null) {
            return "Selected: " + source.fullFileName();
        }
        if (model.selectedChannelId().isEmpty()) {
            return "Selected: " + source.fullFileName() + " / All Channels · Ch" + activeMappingChannelId;
        }
        return "Selected: " + source.fullFileName() + " / Ch" + activeMappingChannelId;
    }

    private void resolveActiveMappingChannel() {
        SourceView source = selectedSourceView();
        if (source == null || source.channels().isEmpty()) {
            activeMappingChannelId = null;
            return;
        }
        if (model.selectedChannelId().isPresent()) {
            rememberMappingChannel(source.sourceId(), model.selectedChannelId().getAsInt());
            activeMappingChannelId = model.selectedChannelId().getAsInt();
            return;
        }
        Integer last = lastMappingChannelBySource.get(source.sourceId().value());
        if (last != null && source.channels().stream().anyMatch(c -> c.channelId() == last)) {
            activeMappingChannelId = last;
            return;
        }
        activeMappingChannelId = source.channels().getFirst().channelId();
        rememberMappingChannel(source.sourceId(), activeMappingChannelId);
    }

    private void rememberMappingChannel(ProjectSourceId sourceId, int channelId) {
        if (sourceId == null) {
            return;
        }
        lastMappingChannelBySource.put(sourceId.value(), channelId);
        activeMappingChannelId = channelId;
    }

    private void onChannelStripSelected(int channelId) {
        if (model.selectedSourceId() == null) {
            return;
        }
        rememberMappingChannel(model.selectedSourceId(), channelId);
        if (model.selectedChannelId().isPresent()) {
            model.selectChannel(model.selectedSourceId(), channelId);
            if (snapshot != null) {
                snapshot = new WorkspaceSnapshot(
                        snapshot.sources(),
                        snapshot.measurementSourceCount(),
                        snapshot.totalCanChannels(),
                        model.selectedSourceId(),
                        model.selectedChannelId(),
                        model.searchText(),
                        model.canTypeFilter(),
                        model.matchFilter(),
                        model.contentFilter()
                );
                renderCenter(snapshot);
            }
            return;
        }
        refreshDecisionChrome();
    }

    private void refreshChannelStrip() {
        SourceView source = selectedSourceView();
        if (source == null || source.channels().isEmpty()) {
            channelSelector.clear();
            return;
        }
        List<ChannelQuickSelector.ChannelChip> chips = new ArrayList<>();
        for (ChannelView channel : source.channels()) {
            SourceChannelKey key = SourceChannelKey.of(source.sourceId(), channel.channelId());
            String bestDbc = "";
            String coverage = "";
            if (appService != null) {
                Optional<SourceChannelMappingEntry> mapping = appService.channelMapping(key);
                if (mapping.isPresent()) {
                    bestDbc = activeDbcName(mapping.get());
                    coverage = channelCoverageText(key);
                } else {
                    List<SourceChannelDefinitionCandidate> candidates = appService.candidatesFor(key);
                    if (!candidates.isEmpty()) {
                        SourceChannelDefinitionCandidate top = candidates.stream()
                                .max(Comparator
                                        .comparingLong(SourceChannelDefinitionCandidate::matchedUniqueMessageCount)
                                        .thenComparingDouble(SourceChannelDefinitionCandidate::coverageRatio))
                                .orElse(candidates.getFirst());
                        bestDbc = top.definitionDisplayName();
                        NumberFormat pct = NumberFormat.getNumberInstance(Locale.US);
                        pct.setMaximumFractionDigits(0);
                        coverage = pct.format(top.coveragePercent()) + "%";
                    }
                }
            }
            chips.add(new ChannelQuickSelector.ChannelChip(
                    channel.channelId(),
                    channel.label(),
                    intFormat.format(channel.uniqueIds()) + " IDs",
                    bestDbc,
                    coverage
            ));
        }
        channelSelector.show(chips, activeMappingChannelId);
    }

    private SourceView selectedSourceView() {
        if (snapshot == null || model.selectedSourceId() == null) {
            return null;
        }
        return snapshot.sources().stream()
                .filter(s -> s.sourceId().equals(model.selectedSourceId()))
                .findFirst()
                .orElse(null);
    }

    private List<SignalCatalogEntry> catalogEntriesForExplorer(SourceView source) {
        if (appService == null || source == null) {
            return List.of();
        }
        SignalCatalog catalog = appService.signalsModel().catalog();
        if (catalog == null || catalog.entries().isEmpty()) {
            return List.of();
        }
        List<SignalCatalogEntry> out = new ArrayList<>();
        for (SignalCatalogEntry entry : catalog.entries()) {
            if (!catalogEntryBelongsToSource(entry, source, catalog)) {
                continue;
            }
            if (entry.sourceType() == SignalSourceType.DBC_CAN
                    && activeMappingChannelId != null
                    && entry.channel() > 0
                    && entry.channel() != activeMappingChannelId) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    private static boolean catalogEntryBelongsToSource(
            SignalCatalogEntry entry,
            SourceView source,
            SignalCatalog catalog
    ) {
        String file = entry.sourceFile();
        if (file == null || file.isBlank()) {
            file = catalog.measurementSource();
        }
        if (file == null || file.isBlank()) {
            return true;
        }
        return fileEquals(file, source.fullFileName())
                || fileEquals(file, source.compactName())
                || fileEquals(file, source.fullPath())
                || (source.fullPath() != null && source.fullPath().endsWith(file))
                || (source.fullFileName() != null && source.fullFileName().endsWith(file));
    }

    private static boolean fileEquals(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private List<CanMessageInventoryRow> explorerInventoryRows(SourceView source, SourceChannelKey key) {
        if (source == null || source.inventory() == null || key == null) {
            return List.of();
        }
        return SourceInventoryWorkspaceQuery.queryMessages(
                source.sourceId(),
                source.inventory(),
                OptionalInt.of(key.channelId()),
                "",
                CanTypeFilter.ALL,
                MatchFilter.ALL,
                ContentFilter.ALL,
                this::enrichmentContextFor
        ).rows();
    }

    private String activeDbcName(SourceChannelMappingEntry mapping) {
        if (mapping.status() == ChannelMappingStatus.CONFIRMED || mapping.status() == ChannelMappingStatus.MANUAL) {
            return mapping.mappedDisplayName();
        }
        return mapping.recommendedDisplayName();
    }

    private void refreshStatusPills() {
        statusPills.getChildren().clear();
        if (appService == null) {
            return;
        }
        int sources = snapshot == null ? 0 : snapshot.measurementSourceCount();
        int channels = snapshot == null ? 0 : snapshot.totalCanChannels();
        int dbcs = appService.workspace().definitionSourceCount();
        ProjectDefinitionMatchingModel.MatchingSummary summary = appService.definitionMatchingModel().summary();
        statusPills.getChildren().setAll(
                pill(sources + (sources == 1 ? " Source" : " Sources"), "decision-pill"),
                pill(channels + (channels == 1 ? " Channel" : " Channels"), "decision-pill"),
                pill(dbcs + (dbcs == 1 ? " DBC" : " DBCs"), "decision-pill-dbc"),
                pill(summary.candidateCount() + " Comparisons", "decision-pill"),
                pill(summary.confirmedCount() + " Confirmed", "decision-pill-ready")
        );
    }

    private static Label pill(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().addAll("decision-status-pill", style);
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.setTextOverrun(OverrunStyle.CLIP);
        return label;
    }

    private void refreshWorkspaceCounts() {
        if (appService == null) {
            workspaceCounts.refresh(0, 0, 0, 0);
            return;
        }
        int channels = 0;
        int high = 0;
        int attention = 0;
        int unmapped = 0;
        for (SourceChannelMappingEntry entry : appService.definitionMatchingModel().allMappings()) {
            channels++;
            CoverageQuality quality = coveragePolicy.classify(entry.coverageRatio());
            if (quality == CoverageQuality.HIGH) {
                high++;
            }
            if (isNeedsAttention(entry, quality) || attentionKeys.contains(entry.sourceChannelKey())) {
                attention++;
            }
            if (entry.status() == ChannelMappingStatus.UNMAPPED
                    || entry.status() == ChannelMappingStatus.NO_MATCH) {
                unmapped++;
            }
        }
        if (channels == 0 && snapshot != null) {
            channels = snapshot.totalCanChannels();
        }
        workspaceCounts.refresh(channels, high, attention, unmapped);
    }

    private boolean channelPassesDecisionFilters(SourceChannelKey key) {
        if (appService == null || key == null) {
            return true;
        }
        Optional<SourceChannelMappingEntry> mapping = appService.channelMapping(key);
        String state = stateFilter.getValue() == null ? "All" : stateFilter.getValue();
        if (!"All".equals(state)) {
            DisplayState displayState = mappingDisplayState(key, mapping.orElse(null));
            if (!stateMatchesDisplay(displayState, mapping.orElse(null), state)) {
                return false;
            }
        }
        String coverage = coverageFilter.getValue() == null ? "All" : coverageFilter.getValue();
        if (!"All".equals(coverage)) {
            CoverageQuality quality = coveragePolicy.classify(mapping.map(SourceChannelMappingEntry::coverageRatio).orElse(0.0));
            if (!quality.name().equalsIgnoreCase(coverage)) {
                return false;
            }
        }
        if (unmappedOnly.isSelected()) {
            if (mapping.isEmpty()) {
                return true;
            }
            ChannelMappingStatus status = mapping.get().status();
            return status == ChannelMappingStatus.UNMAPPED
                    || status == ChannelMappingStatus.NO_MATCH
                    || status == ChannelMappingStatus.AMBIGUOUS
                    || attentionKeys.contains(key);
        }
        return true;
    }

    private static boolean stateMatchesDisplay(
            DisplayState displayState,
            SourceChannelMappingEntry mapping,
            String filter
    ) {
        return switch (filter) {
            case "Needs Decision" -> displayState == DisplayState.RECOMMENDED
                    || displayState == DisplayState.SELECTED
                    || displayState == DisplayState.NEEDS_ATTENTION
                    || displayState == DisplayState.NONE;
            case "Recommended" -> displayState == DisplayState.RECOMMENDED;
            case "Selected" -> displayState == DisplayState.SELECTED;
            case "Confirmed" -> displayState == DisplayState.CONFIRMED;
            case "Needs Attention" -> displayState == DisplayState.NEEDS_ATTENTION
                    || (mapping != null && isNeedsAttention(
                    mapping, CoverageClassificationPolicy.defaults().classify(mapping.coverageRatio())));
            default -> true;
        };
    }

    private static boolean isNeedsAttention(SourceChannelMappingEntry entry, CoverageQuality quality) {
        if (entry.status() == ChannelMappingStatus.AMBIGUOUS
                || entry.status() == ChannelMappingStatus.NO_MATCH) {
            return true;
        }
        return entry.status() == ChannelMappingStatus.RECOMMENDED
                && (quality == CoverageQuality.LOW || quality == CoverageQuality.NONE);
    }

    private String channelCoverageText(SourceChannelKey key) {
        Optional<ChannelMappingPreview.Snapshot> preview = mappingPreviewFor(key);
        if (preview.isPresent() && preview.get().observedCount() > 0) {
            NumberFormat pct = NumberFormat.getNumberInstance(Locale.US);
            pct.setMaximumFractionDigits(0);
            return pct.format(preview.get().coverageRatio() * 100.0) + "%";
        }
        if (appService == null || key == null) {
            return "";
        }
        return appService.channelMapping(key)
                .map(entry -> {
                    NumberFormat pct = NumberFormat.getNumberInstance(Locale.US);
                    pct.setMaximumFractionDigits(0);
                    return pct.format(entry.coveragePercent()) + "%";
                })
                .orElse("");
    }

    /**
     * DBC Matched: observed CAN IDs matched by the current/preview definition.
     * Identity is {@link SourceChannelKey} + {@link DefinitionSourceId}. Unknown mapping is "\u2014".
     */
    private String channelDbcMatchedText(SourceChannelKey key) {
        Optional<ChannelMappingPreview.Snapshot> preview = mappingPreviewFor(key);
        if (preview.isEmpty()) {
            return "\u2014";
        }
        return intFormat.format(preview.get().matchedCount());
    }

    /**
     * Same comparison snapshot used by the center "matched / observed" line.
     */
    private Optional<ChannelMappingPreview.Snapshot> mappingPreviewFor(SourceChannelKey key) {
        if (appService == null || key == null) {
            return Optional.empty();
        }
        Optional<DefinitionSourceId> definitionId = navigatorDefinitionId(key);
        if (definitionId.isEmpty()) {
            return Optional.empty();
        }
        Optional<ProjectDefinitionMatchingCoordinator.ParsedDefinition> parsed =
                appService.definitionMatchingCoordinator().parsedDefinition(definitionId.get());
        if (parsed.isEmpty() || parsed.get().database() == null) {
            return Optional.empty();
        }
        SourceChannelDefinitionComparison comparison = appService.definitionMatchingModel()
                .comparison(key, definitionId.get())
                .orElse(null);
        SourceChannelDefinitionCandidate candidate = null;
        for (SourceChannelDefinitionCandidate c : appService.candidatesFor(key)) {
            if (definitionId.get().equals(c.definitionSourceId())) {
                candidate = c;
                break;
            }
        }
        SourceChannelMappingEntry mapping = appService.channelMapping(key).orElse(null);
        return ChannelMappingPreview.resolve(key, definitionId.get(), comparison, candidate, mapping);
    }

    /**
     * Current navigator DBC: preview for the active source/channel, else confirmed/manual,
     * else the persisted recommendation. Does not confirm mappings.
     */
    private Optional<DefinitionSourceId> navigatorDefinitionId(SourceChannelKey key) {
        if (appService == null || key == null) {
            return Optional.empty();
        }
        SourceChannelMappingEntry mapping = appService.channelMapping(key).orElse(null);
        return appService.workingDbcSelections().displayedDefinition(key, mapping);
    }

    private Optional<SourceChannelDefinitionCandidate> candidateFor(
            SourceChannelKey key,
            DefinitionSourceId definitionId
    ) {
        if (appService == null || key == null || definitionId == null) {
            return Optional.empty();
        }
        for (SourceChannelDefinitionCandidate candidate : appService.candidatesFor(key)) {
            if (definitionId.equals(candidate.definitionSourceId())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private String channelNavStatus(SourceChannelKey key) {
        DisplayState state = mappingDisplayState(key, appService == null
                ? null : appService.channelMapping(key).orElse(null));
        return switch (state) {
            case RECOMMENDED, SELECTED, CONFIRMED, NEEDS_ATTENTION -> state.name();
            case NONE -> "";
        };
    }

    private void refreshNavigatorMatchedCounts() {
        if (snapshot == null) {
            return;
        }
        for (TreeItem<NavNode> sourceItem : navRoot.getChildren()) {
            NavNode sourceNode = sourceItem.getValue();
            if (sourceNode == null || sourceNode.sourceId() == null) {
                continue;
            }
            SourceView source = snapshot.sources().stream()
                    .filter(s -> sourceNode.sourceId().equals(s.sourceId()))
                    .findFirst()
                    .orElse(null);
            if (source == null) {
                continue;
            }
            for (TreeItem<NavNode> child : sourceItem.getChildren()) {
                NavNode node = child.getValue();
                if (node == null || node.kind() != NavKind.CHANNEL) {
                    continue;
                }
                ChannelView channel = null;
                for (ChannelView ch : source.channels()) {
                    if (ch.channelId() == node.channelId()) {
                        channel = ch;
                        break;
                    }
                }
                if (channel == null) {
                    continue;
                }
                SourceChannelKey key = SourceChannelKey.of(source.sourceId(), channel.channelId());
                String matchedText = channelDbcMatchedText(key);
                String status = channelNavStatus(key);
                if (matchedText.equals(node.framesText()) && status.equals(node.status())) {
                    continue;
                }
                String coverageText = channelCoverageText(key);
                child.setValue(NavNode.metricsRow(
                        NavKind.CHANNEL,
                        source.sourceId(),
                        channel.channelId(),
                        node.channelLabel(),
                        node.idsText(),
                        matchedText,
                        status,
                        channelNavigatorTip(node.channelLabel(), node.idsText(), matchedText, coverageText)
                ));
            }
        }
    }

    private static String channelNavigatorTip(
            String label,
            String idsText,
            String matchedText,
            String coverageText
    ) {
        return label
                + "\nCAN IDs: " + idsText
                + "\nDBC Matched: " + matchedText
                + (coverageText == null || coverageText.isBlank() ? "" : "\nCoverage: " + coverageText);
    }

    private void onStudyHeaderCheckClicked() {
        int eligible = countEligible(tableRows);
        int selected = countSelectedEligibleInView();
        if (eligible == 0) {
            updateStudyHeaderCheck();
            return;
        }
        if (selected >= eligible) {
            studySet.removeAll(tableRows);
        } else {
            studySet.addAll(tableRows);
        }
    }

    private int countSelectedEligibleInView() {
        int n = 0;
        for (CanMessageInventoryRow row : tableRows) {
            if (InventoryStudySet.isEligible(row) && studySet.contains(row)) {
                n++;
            }
        }
        return n;
    }

    private void updateStudyHeaderCheck() {
        syncingStudyHeaderCheck = true;
        try {
            int eligible = countEligible(tableRows);
            int selected = countSelectedEligibleInView();
            studyHeaderCheck.setDisable(eligible == 0);
            if (eligible == 0 || selected == 0) {
                studyHeaderCheck.setIndeterminate(false);
                studyHeaderCheck.setSelected(false);
            } else if (selected >= eligible) {
                studyHeaderCheck.setIndeterminate(false);
                studyHeaderCheck.setSelected(true);
            } else {
                studyHeaderCheck.setSelected(false);
                studyHeaderCheck.setIndeterminate(true);
            }
        } finally {
            syncingStudyHeaderCheck = false;
        }
    }

    private void confirmSelectedChannel() {
        SourceChannelKey key = selectedChannelKey();
        if (appService == null || key == null) {
            return;
        }
        DbcCandidateStrip.DbcOption selected = candidateStrip.selectedOption();
        SourceChannelMappingEntry mapping = appService.channelMapping(key).orElse(null);
        if (selected != null && selected.candidate() != null
                && !isPersistedSelection(selected.candidate(), mapping)) {
            appService.chooseChannelDefinition(key, selected.candidate().definitionSourceId());
        } else {
            appService.confirmChannelRecommendation(key);
        }
        refreshEnrichmentOnly();
    }

    private void acceptPendingDbcIfNeeded() {
        SourceChannelKey key = selectedChannelKey();
        if (appService == null || key == null) {
            return;
        }
        DbcCandidateStrip.DbcOption selected = candidateStrip.selectedOption();
        SourceChannelMappingEntry mapping = appService.channelMapping(key).orElse(null);
        if (selected != null && selected.candidate() != null
                && !isPersistedSelection(selected.candidate(), mapping)
                && !selected.recommended()) {
            appService.chooseChannelDefinition(key, selected.candidate().definitionSourceId());
        }
    }

    private void chooseDbcForActiveChannel() {
        SourceChannelKey key = selectedChannelKey();
        if (appService == null || key == null) {
            return;
        }
        List<SourceChannelDefinitionCandidate> candidates = appService.candidatesFor(key);
        if (candidates.isEmpty()) {
            return;
        }
        List<SourceChannelDefinitionCandidate> ranked = candidates.stream()
                .sorted(Comparator
                        .comparingLong(SourceChannelDefinitionCandidate::matchedUniqueMessageCount).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                SourceChannelDefinitionCandidate::coverageRatio).reversed()))
                .toList();
        Dialog<SourceChannelDefinitionCandidate> dialog = new Dialog<>();
        dialog.setTitle("Change DBC");
        dialog.setHeaderText("Choose a DBC for the active source/channel.");
        ButtonType selectType = new ButtonType("Use This DBC", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(selectType, ButtonType.CANCEL);
        ListView<String> list = new ListView<>();
        list.getItems().setAll(ranked.stream()
                .map(c -> c.definitionDisplayName() + "  ·  "
                        + NumberFormat.getNumberInstance(Locale.US).format(c.coveragePercent()) + "%")
                .toList());
        list.getSelectionModel().select(0);
        list.setPrefHeight(160);
        dialog.getDialogPane().setContent(list);
        dialog.setResultConverter(button -> {
            if (button != selectType) {
                return null;
            }
            int idx = list.getSelectionModel().getSelectedIndex();
            return idx >= 0 && idx < ranked.size() ? ranked.get(idx) : null;
        });
        Optional<SourceChannelDefinitionCandidate> chosen = dialog.showAndWait();
        chosen.ifPresent(candidate ->
                appService.chooseChannelDefinition(key, candidate.definitionSourceId()));
    }

    private void chooseDbcForCurrentSource() {
        if (appService == null || model.selectedSourceId() == null) {
            return;
        }
        SourceChannelKey key = selectedChannelKey();
        if (key == null) {
            if (snapshot == null) {
                return;
            }
            SourceView source = snapshot.sources().stream()
                    .filter(s -> s.sourceId().equals(model.selectedSourceId()))
                    .findFirst()
                    .orElse(null);
            if (source == null || source.channels().isEmpty()) {
                return;
            }
            key = SourceChannelKey.of(source.sourceId(), source.channels().getFirst().channelId());
        }
        List<SourceChannelDefinitionCandidate> candidates = appService.candidatesFor(key);
        if (candidates.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Choose DBC for All");
            alert.setHeaderText("No DBC candidates");
            alert.setContentText("Add DBC definitions to assign one DBC to every channel of this source.");
            alert.showAndWait();
            return;
        }
        List<SourceChannelDefinitionCandidate> ranked = candidates.stream()
                .sorted(Comparator
                        .comparingLong(SourceChannelDefinitionCandidate::matchedUniqueMessageCount).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                SourceChannelDefinitionCandidate::coverageRatio).reversed()))
                .toList();
        Dialog<SourceChannelDefinitionCandidate> dialog = new Dialog<>();
        dialog.setTitle("Choose DBC for All");
        dialog.setHeaderText("Apply one DBC to every channel of the selected source.");
        ButtonType selectType = new ButtonType("Apply to all channels", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(selectType, ButtonType.CANCEL);
        ListView<String> list = new ListView<>();
        list.getItems().setAll(ranked.stream()
                .map(c -> c.definitionDisplayName() + "  ·  "
                        + NumberFormat.getNumberInstance(Locale.US).format(c.coveragePercent()) + "%")
                .toList());
        list.getSelectionModel().select(0);
        list.setPrefHeight(200);
        dialog.getDialogPane().setContent(list);
        dialog.setResultConverter(button -> {
            if (button != selectType) {
                return null;
            }
            int idx = list.getSelectionModel().getSelectedIndex();
            return idx >= 0 && idx < ranked.size() ? ranked.get(idx) : null;
        });
        Optional<SourceChannelDefinitionCandidate> chosen = dialog.showAndWait();
        if (chosen.isEmpty() || snapshot == null) {
            return;
        }
        DefinitionSourceId definitionId = chosen.get().definitionSourceId();
        ProjectSourceId sourceId = model.selectedSourceId();
        snapshot.sources().stream()
                .filter(s -> s.sourceId().equals(sourceId))
                .findFirst()
                .ifPresent(source -> {
                    for (ChannelView channel : source.channels()) {
                        appService.chooseChannelDefinition(
                                SourceChannelKey.of(sourceId, channel.channelId()), definitionId);
                    }
                });
    }

    private void markSelectedNeedsAttention() {
        SourceChannelKey key = selectedChannelKey();
        if (key == null) {
            return;
        }
        attentionKeys.add(key);
        refreshWorkspaceCounts();
        if (snapshot != null) {
            renderNavigator(snapshot);
        }
    }

    private void exportMappingMatrix() {
        if (appService == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export mapping matrix");
        chooser.setInitialFileName("stellar-mapping-matrix.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File target = getScene() == null ? null : chooser.showSaveDialog(getScene().getWindow());
        if (target == null) {
            return;
        }
        StringBuilder csv = new StringBuilder();
        csv.append("Source,Channel,Status,DBC,Coverage %,Matched IDs,Observed IDs\n");
        for (SourceChannelMappingEntry entry : appService.definitionMatchingModel().allMappings()) {
            String dbc = entry.mappedDisplayName().isBlank()
                    ? entry.recommendedDisplayName() : entry.mappedDisplayName();
            csv.append(csvCell(entry.sourceChannelKey().sourceId().value())).append(',')
                    .append("Ch").append(entry.sourceChannelKey().channelId()).append(',')
                    .append(csvCell(entry.status().badgeLabel())).append(',')
                    .append(csvCell(dbc)).append(',')
                    .append(entry.coveragePercent()).append(',')
                    .append(entry.matchedUniqueMessageCount()).append(',')
                    .append(entry.observedUniqueMessageCount()).append('\n');
        }
        try {
            Files.writeString(target.toPath(), csv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Export mapping matrix");
            alert.setHeaderText("Could not write the mapping summary.");
            alert.setContentText(ex.getMessage());
            alert.showAndWait();
        }
    }

    private static String csvCell(String value) {
        String text = value == null ? "" : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private void collapseInspector() {
        if (inspectorCollapsed) {
            return;
        }
        if (split.getDividers().size() >= 2) {
            rememberedInspectorDivider = split.getDividerPositions()[1];
        }
        rememberedDrawerTab = inspectorDrawer.tab();
        restoreInspectOnExpand = inspectorDrawer.isInspectActive();
        restoreDrawerOnExpand = true;
        inspectorCollapsed = true;
        reconcileInspectorPlacement();
        expandInspectorButton.setVisible(false);
        expandInspectorButton.setManaged(false);
        if (split.getDividers().size() >= 1) {
            split.setDividerPositions(0.17);
        }
        updateStudyDrawerChrome();
    }

    private void expandInspector() {
        if (!inspectorCollapsed) {
            reconcileInspectorPlacement();
            updateStudyDrawerChrome();
            return;
        }
        inspectorCollapsed = false;
        if (restoreDrawerOnExpand) {
            if (restoreInspectOnExpand) {
                inspectorDrawer.showInspect();
            } else {
                inspectorDrawer.showTab(rememberedDrawerTab);
            }
            restoreDrawerOnExpand = false;
        }
        expandInspectorButton.setVisible(false);
        expandInspectorButton.setManaged(false);
        reconcileInspectorPlacement();
        inspectorDrawer.applyCss();
        inspectorDrawer.autosize();
        inspectorDrawer.layout();
        updateStudyDrawerChrome();
    }

    /**
     * Demo-stable placement: inspector stays in the SplitPane when open. Overlaying it on the
     * CAN table caused clipped controls and overlap at laptop widths.
     */
    private void reconcileInspectorPlacement() {
        if (inspectorCollapsed) {
            split.getItems().remove(inspectorDrawer);
            mappingStage.getChildren().remove(inspectorDrawer);
            inspectorDrawer.setVisible(false);
            inspectorDrawer.setManaged(false);
            return;
        }
        inspectorDrawer.setMinWidth(INSPECTOR_MIN);
        inspectorDrawer.setPrefWidth(INSPECTOR_PREF);
        inspectorDrawer.setMaxWidth(INSPECTOR_MAX);
        inspectorDrawer.setMaxHeight(Double.MAX_VALUE);
        inspectorDrawer.setVisible(true);
        inspectorDrawer.setManaged(true);
        mappingStage.getChildren().remove(inspectorDrawer);
        inspectorDrawer.setViewOrder(0);
        if (!split.getItems().contains(inspectorDrawer)) {
            split.getItems().add(inspectorDrawer);
            double right = rememberedInspectorDivider <= 0.05 ? 0.78 : rememberedInspectorDivider;
            split.setDividerPositions(0.17, right);
        }
    }

    /** Regression: bottom detail insertion must not exist. */
    public boolean hasBottomDetailPaneForTest() {
        return getBottom() != null
                || (centerPane != null && centerPane.getBottom() != null)
                || split.getItems().stream().anyMatch(n ->
                n.getStyleClass().contains("source-inventory-signal-detail"));
    }

    public DbcCandidateStrip candidateStripForTest() {
        return candidateStrip;
    }

    public ComboBox<String> stateFilterForTest() {
        return stateFilter;
    }

    public int visibleNavChannelCountForTest() {
        int count = 0;
        for (TreeItem<NavNode> sourceItem : navRoot.getChildren()) {
            for (TreeItem<NavNode> child : sourceItem.getChildren()) {
                NavNode node = child.getValue();
                if (node != null && node.kind() == NavKind.CHANNEL) {
                    count++;
                }
            }
        }
        return count;
    }

    public String navChannelStatusForTest(ProjectSourceId sourceId, int channelId) {
        NavNode node = findNavNode(sourceId, channelId);
        return node == null ? null : node.status();
    }

    public ChannelSignalExplorerPane signalExplorerForTest() {
        return signalExplorer;
    }

    public ChannelQuickSelector channelSelectorForTest() {
        return channelSelector;
    }

    public OptionalInt activeMappingChannelForTest() {
        return activeMappingChannelId == null ? OptionalInt.empty() : OptionalInt.of(activeMappingChannelId);
    }

    public boolean isSourceContextForTest() {
        return model.selectedSourceId() != null && model.selectedChannelId().isEmpty();
    }

    public DecisionActionBar actionBarForTest() {
        return decisionActionBar;
    }

    public CanInventoryDetailsInspector detailsInspectorForTest() {
        return detailsInspector;
    }

    public boolean isInspectorCollapsedForTest() {
        return inspectorCollapsed;
    }

    public boolean inspectorOverlaysForTest() {
        return !inspectorCollapsed
                && mappingStage.getChildren().contains(inspectorDrawer)
                && !split.getItems().contains(inspectorDrawer);
    }

    public void collapseInspectorForTest() {
        collapseInspector();
    }

    public void expandInspectorForTest() {
        expandInspector();
    }

    public Button headerSignalsButtonForTest() {
        return headerSignalsButton;
    }

    public DecisionInspectorDrawer inspectorDrawerForTest() {
        return inspectorDrawer;
    }

    public boolean inspectorConsumesNoWidthForTest() {
        return inspectorCollapsed
                && !split.getItems().contains(inspectorDrawer)
                && !inspectorDrawer.isManaged()
                && !inspectorDrawer.isVisible();
    }

    public boolean decisionHeaderRowsDoNotOverlapForTest() {
        return !nodesOverlap(contextHeader, channelSelector)
                && !nodesOverlap(channelSelector, candidateStrip)
                && !nodesOverlap(candidateStrip.comboForTest(), candidateStrip.mappingSummaryRowForTest())
                && !nodesOverlap(channelSelector, candidateStrip.mappingSummaryRowForTest())
                && !nodesOverlap(contextHeader, candidateStrip.mappingSummaryRowForTest());
    }

    public double inspectorDrawerWidthForTest() {
        return inspectorDrawer.isManaged() ? inspectorDrawer.getWidth() : 0;
    }

    private static boolean nodesOverlap(Node a, Node b) {
        if (a == null || b == null || !a.isVisible() || !b.isVisible()
                || !a.isManaged() || !b.isManaged()) {
            return false;
        }
        Bounds ba = a.localToScene(a.getBoundsInLocal());
        Bounds bb = b.localToScene(b.getBoundsInLocal());
        if (ba.getWidth() <= 0 || ba.getHeight() <= 0 || bb.getWidth() <= 0 || bb.getHeight() <= 0) {
            return false;
        }
        return ba.intersects(bb);
    }

    public ToggleButton chipAllForTest() { return chipAll; }
    public ToggleButton chipMatchedForTest() { return chipMatched; }
    public ToggleButton chipUnmatchedForTest() { return chipUnmatched; }
    public ComboBox<String> matchComboForTest() { return matchCombo; }

    public Label canTypeFilterCaptionForTest() { return canTypeFilterCaption; }
    public Label dbcMatchFilterCaptionForTest() { return dbcMatchFilterCaption; }
    public Label contentFilterCaptionForTest() { return contentFilterCaption; }

    public boolean usesUnconstrainedTableResizeForTest() {
        return table.getColumnResizePolicy() == TableView.UNCONSTRAINED_RESIZE_POLICY;
    }

    public double tableHeightForTest() {
        return table.getHeight();
    }

    private static String matchFilterLabel(MatchFilter filter) {
        if (filter == null) {
            return "All";
        }
        return switch (filter) {
            case MATCHED -> "Matched";
            case UNMATCHED -> "Unmatched";
            case NO_DBC -> "No DBC";
            case AMBIGUOUS -> "Ambiguous";
            case ALL -> "All";
        };
    }

    private static MatchFilter toMatchFilter(String label) {
        if (label == null) {
            return MatchFilter.ALL;
        }
        return switch (label) {
            case "Matched" -> MatchFilter.MATCHED;
            case "Unmatched" -> MatchFilter.UNMATCHED;
            case "No DBC" -> MatchFilter.NO_DBC;
            case "Ambiguous" -> MatchFilter.AMBIGUOUS;
            default -> MatchFilter.ALL;
        };
    }

    private static ContentFilter toContentFilter(String label) {
        if (label == null) {
            return ContentFilter.ALL;
        }
        return switch (label) {
            case "Has Signals" -> ContentFilter.HAS_SIGNALS;
            case "No Signals" -> ContentFilter.NO_SIGNALS;
            default -> ContentFilter.ALL;
        };
    }

    private static CanTypeFilter toCanType(String label) {
        if (label == null) {
            return CanTypeFilter.ALL;
        }
        return switch (label) {
            case "Standard" -> CanTypeFilter.STANDARD;
            case "Extended" -> CanTypeFilter.EXTENDED;
            default -> CanTypeFilter.ALL;
        };
    }

    enum NavKind { ROOT, SOURCE, ALL_CHANNELS, CHANNEL }

    static final class NavNode {
        private final NavKind kind;
        private final ProjectSourceId sourceId;
        private final int channelId;
        private final String alias;
        private final String compactName;
        private final String status;
        private final String metaLine;
        private final String channelLabel;
        private final String idsText;
        private final String framesText;
        private final String tooltip;

        private NavNode(
                NavKind kind,
                ProjectSourceId sourceId,
                int channelId,
                String alias,
                String compactName,
                String status,
                String metaLine,
                String channelLabel,
                String idsText,
                String framesText,
                String tooltip
        ) {
            this.kind = kind;
            this.sourceId = sourceId;
            this.channelId = channelId;
            this.alias = alias == null ? "" : alias;
            this.compactName = compactName == null ? "" : compactName;
            this.status = status == null ? "" : status;
            this.metaLine = metaLine == null ? "" : metaLine;
            this.channelLabel = channelLabel == null ? "" : channelLabel;
            this.idsText = idsText == null ? "" : idsText;
            this.framesText = framesText == null ? "" : framesText;
            this.tooltip = tooltip == null ? "" : tooltip;
        }

        static NavNode root() {
            return new NavNode(NavKind.ROOT, null, -1, "", "", "", "", "", "", "", "");
        }

        static NavNode source(
                ProjectSourceId id,
                String alias,
                String compactName,
                String status,
                String metaLine,
                String tip
        ) {
            return new NavNode(NavKind.SOURCE, id, -1, alias, compactName, status, metaLine,
                    "", "", "", tip);
        }

        static NavNode metricsRow(
                NavKind kind,
                ProjectSourceId id,
                int channelId,
                String channelLabel,
                String idsText,
                String framesText,
                String tip
        ) {
            return metricsRow(kind, id, channelId, channelLabel, idsText, framesText, "", tip);
        }

        static NavNode metricsRow(
                NavKind kind,
                ProjectSourceId id,
                int channelId,
                String channelLabel,
                String idsText,
                String framesText,
                String status,
                String tip
        ) {
            return new NavNode(kind, id, channelId, "", "", status, "",
                    channelLabel, idsText, framesText, tip);
        }

        NavKind kind() { return kind; }
        ProjectSourceId sourceId() { return sourceId; }
        int channelId() { return channelId; }
        String alias() { return alias; }
        String compactName() { return compactName; }
        String status() { return status; }
        String metaLine() { return metaLine; }
        String channelLabel() { return channelLabel; }
        String idsText() { return idsText; }
        String framesText() { return framesText; }
        String tooltip() { return tooltip; }
    }
}

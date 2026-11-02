package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.SampleDisplay;
import com.stellar.platform.desktop.presentation.AlignedWorkspaceSnapshot;
import com.stellar.platform.desktop.presentation.SignalBrowserFilter;
import com.stellar.platform.desktop.presentation.SignalChannelLabels;
import com.stellar.platform.desktop.presentation.SignalsProcessingContext;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.presentation.WorkspaceDisplayMode;
import com.stellar.platform.desktop.presentation.WorkspacePrecision;
import com.stellar.platform.desktop.ui.DesktopSceneGraphRelease;
import com.stellar.platform.desktop.ui.StellarIcons;
import com.stellar.platform.desktop.ui.UiFormat;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.export.AlignedSignalCsvExporter;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.EventHandler;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.geometry.VPos;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Center Active Signal Workspace: chips, navigation toolbar, timestamp-aligned table.
 */
public final class ActiveSignalWorkspacePane extends VBox {

    private final Label titleLabel = new Label("Active Signal Workspace (0)");
    private final Button focusTableButton = new Button("Focus Table");
    private final Button addSelectedButton = new Button("Add Selected");
    private final Button clearAllButton = new Button("Clear All");
    private final Button analyzeSelectedButton = new Button("Analyze Signals");
    private final Button mapRouteButton = new Button("Map Route");
    private final StackPane analyzeActionHost = new StackPane();
    private final Button addSignalsButton = new Button("Add Signals");
    private final Button signalDetailsButton = new Button("Signal Details");
    private final Button exitFocusButton = new Button("Exit Focus");
    private final HBox headerActions = new HBox(SignalsLayoutMetrics.CONTROL_GAP);
    private final GridPane commandRow = new GridPane();
    private final HBox chipBar = new HBox(8);
    private final ScrollPane chipScroll = new ScrollPane(chipBar);
    private final Button chipScrollLeft = new Button();
    private final Button chipScrollRight = new Button();
    private final HBox chipStrip = new HBox(4, chipScrollLeft, chipScroll, chipScrollRight);
    private final Button moreSignalsButton = new Button();
    private final ListView<SignalCatalogEntry> selectedSignalsList = new ListView<>();
    private final Popup selectedSignalsPopup = new Popup();
    private List<SignalCatalogEntry> lastMembership = List.of();

    private final Label rangeLabel = new Label("");
    private final Label statusSep = new Label("\u2022");
    private final ComboBox<WorkspaceDisplayMode> displayModeBox = new ComboBox<>();
    private final ComboBox<WorkspacePrecision> precisionBox = new ComboBox<>();
    private final Label displayLabel = new Label("Display Mode");
    private final Label precisionLabel = new Label("Precision");
    private final Label navigateLabel = new Label("Navigate");
    private final ComboBox<NavigateTarget> navigateModeBox = new ComboBox<>();
    private final TextField navigateField = new TextField();
    private final Button navigateGoButton = new Button("Go");
    private final Button prevWindowButton = new Button("\u2039");
    private final Button nextWindowButton = new Button("\u203A");
    private final HBox windowNavBox = new HBox(4);
    private final Label navFeedback = new Label("");
    private final ProgressIndicator busyIndicator = new ProgressIndicator();
    private final HBox toolbar = new SignalsToolbar();
    private final HBox utilityRow = new HBox(4);
    private final Region utilitySpacer = new Region();

    private final TableView<AlignedSampleRow> table = new TableView<>();
    private final Label emptyTitle = new Label("Add signals to start analysis");
    private final Label emptyBullet1 = new Label("• Double-click a signal");
    private final Label emptyBullet2 = new Label("• Select All adds the filtered list");
    private final Label emptyBullet3 = new Label("• Drag when supported");
    private final Label emptyContextTitle = new Label("Current context");
    private final Label emptyContextFile = new Label();
    private final Label emptyContextDbc = new Label();
    private final Label emptyContextChannels = new Label();
    private final Label emptyContextCount = new Label();
    private final Region displaySplit = toolbarSplit();
    private boolean contextActionsMounted;
    private final VBox emptyBullets = new VBox(4, emptyBullet1, emptyBullet2, emptyBullet3);
    private final VBox emptyContextBox = new VBox(3,
            emptyContextTitle, emptyContextFile, emptyContextDbc, emptyContextChannels, emptyContextCount);
    private final VBox emptyState = new VBox(12);
    private final StackPane tableHost = new StackPane();

    /** Compact inline status strip (replaces large summary cards). */
    private final HBox statusStrip = new HBox(8);
    private final Label statusSelected = new Label();
    private final Label statusAlignment = new Label();
    private final Label statusRows = new Label();
    private final Label statusStart = new Label();
    private final Label statusEnd = new Label();
    private final Label statusChannels = new Label();
    private WorkspaceFocusMode focusMode = WorkspaceFocusMode.NORMAL;
    private Consumer<WorkspaceFocusMode> onFocusModeChanged = mode -> { };
    private Runnable onAddSignalsRequest = () -> { };
    private Runnable onSignalDetailsRequest = () -> { };
    private Runnable onAnalyzeSelectedRequest = () -> { };
    private Runnable onMapRouteRequest = () -> { };
    private final HBox displayGroup = new HBox(SignalsLayoutMetrics.CONTROL_GAP);
    private final HBox precisionGroup = new HBox(SignalsLayoutMetrics.CONTROL_GAP);
    private final HBox navigateGroup = new HBox(4);
    private final HBox rowsGroup = new HBox(SignalsLayoutMetrics.CONTROL_GAP);
    private final Label pageLabel = new Label("1 / 1");
    private final Button firstWindowButton = new Button("|<");
    private final Button lastWindowButton = new Button(">|");
    private final HBox pagingStrip = new HBox(8);
    private final Label tableFooterLabel = new Label();
    private final Label footerRangeLabel = new Label();
    private final Label footerStartLabel = new Label();
    private final Label footerEndLabel = new Label();
    private final Label footerDurationLabel = new Label();
    private final HBox tableFooter = new HBox(10);
    private final HBox footerTimingRow = new HBox(8);
    private boolean footerTimingWrapped;
    private final ComboBox<Integer> rowsBox = new ComboBox<>();
    private final Label rowsLabel = new Label("Rows");
    private final Button settingsButton = new Button();
    private final Button exportButton = new Button();
    private final MenuButton exportCsvButton = new MenuButton();
    private final AtomicBoolean exportCancel = new AtomicBoolean();
    private boolean applyingRowsValue;
    private final HBox toolbarActions = new HBox(4);
    private final CheckMenuItem indexColumnItem = new CheckMenuItem("Index");
    private final CheckMenuItem timestampColumnItem = new CheckMenuItem("Timestamp");
    private final CheckMenuItem deltaColumnItem = new CheckMenuItem("\u0394t (Sample Interval)");
    private final ContextMenu columnsMenu = new ContextMenu();
    private TableColumn<AlignedSampleRow, String> indexColumn;
    private TableColumn<AlignedSampleRow, String> timestampColumn;
    private TableColumn<AlignedSampleRow, String> deltaColumn;

    private SignalsScreenModel model;
    private Supplier<List<SignalCatalogEntry>> browserSelection = List::of;
    private Runnable onWorkspaceChanged = () -> { };
    private Runnable onFocusChanged = () -> { };
    private SignalsProcessingContext analysisContext = SignalsProcessingContext.empty();
    private int availableSignalCount;
    private AlignedWorkspaceSnapshot lastSnap = AlignedWorkspaceSnapshot.empty();
    private List<SignalCatalogKey> lastColumnKeys = List.of();
    private SignalCatalogKey styledFocus;
    private boolean focusStyleDirty = true;
    private WorkspaceDisplayMode displayMode = WorkspaceDisplayMode.PHYSICAL;
    private WorkspacePrecision precision = WorkspacePrecision.AUTO;
    private boolean disposed;
    /** One deferred structure reload. A later click updates membership immediately and is included. */
    private boolean structureReloadQueued;
    private int applySerial;
    private int reloadCount;
    private final PauseTransition feedbackPause = new PauseTransition(Duration.millis(1200));
    private final EventHandler<KeyEvent> escapeFilter = e -> {
        if (e.getCode() == KeyCode.ESCAPE) {
            clearFeedback();
        }
    };
    private final EventHandler<KeyEvent> tableKeyFilter = this::onTableKey;

    public ActiveSignalWorkspacePane() {
        getStyleClass().addAll("active-signal-workspace", "workspace-main");
        setSpacing(SignalsLayoutMetrics.PANEL_GAP);
        setPadding(new Insets(
                SignalsLayoutMetrics.INNER_PADDING,
                SignalsLayoutMetrics.INNER_PADDING,
                SignalsLayoutMetrics.INNER_PADDING,
                SignalsLayoutMetrics.INNER_PADDING));
        setFillWidth(true);
        setMinWidth(SignalsLayoutMetrics.WORKSPACE_MIN_WIDTH);
        setMaxWidth(Double.MAX_VALUE);
        setMinHeight(0);
        setMaxHeight(Double.MAX_VALUE);

        titleLabel.getStyleClass().add("section-header");
        titleLabel.setMinWidth(Region.USE_PREF_SIZE);
        titleLabel.setMaxWidth(Region.USE_PREF_SIZE);
        titleLabel.setWrapText(false);
        titleLabel.setTextOverrun(OverrunStyle.CLIP);
        titleLabel.setEllipsisString("");
        addSelectedButton.getStyleClass().addAll("primary-button", "compact-button", "icon-text-button");
        clearAllButton.getStyleClass().addAll("secondary-button", "compact-button");
        analyzeSelectedButton.getStyleClass().addAll(
                "primary-button", "compact-button", "icon-text-button", "analyze-action");
        mapRouteButton.getStyleClass().addAll(
                "secondary-button", "compact-button", "icon-text-button", "signals-context-action");
        analyzeActionHost.getStyleClass().add("analyze-action-host");
        analyzeActionHost.getChildren().add(analyzeSelectedButton);
        focusTableButton.getStyleClass().addAll("secondary-button", "compact-button", "focus-table-toggle");
        addSignalsButton.getStyleClass().addAll("primary-button", "compact-button", "icon-text-button");
        signalDetailsButton.getStyleClass().addAll("secondary-button", "compact-button");
        exitFocusButton.getStyleClass().addAll("secondary-button", "compact-button");
        addSelectedButton.setGraphic(StellarIcons.plus());
        analyzeSelectedButton.setGraphic(StellarIcons.activity());
        analyzeSelectedButton.setGraphicTextGap(6);
        mapRouteButton.setGraphic(StellarIcons.mapPin());
        mapRouteButton.setGraphicTextGap(6);
        mapRouteButton.setDisable(true);
        addSelectedButton.setGraphicTextGap(6);
        addSignalsButton.setGraphic(StellarIcons.plus());
        addSignalsButton.setGraphicTextGap(6);
        for (Button action : List.of(
                addSelectedButton, clearAllButton, analyzeSelectedButton, mapRouteButton, focusTableButton,
                addSignalsButton, signalDetailsButton, exitFocusButton)) {
            lockContentWidth(action);
        }
        addSelectedButton.setOnAction(e -> addBrowserSelection());
        clearAllButton.setOnAction(e -> clearAll());
        analyzeSelectedButton.setOnAction(e -> onAnalyzeSelectedRequest.run());
        mapRouteButton.setOnAction(e -> onMapRouteRequest.run());
        installAnalyzeTooltip("Plot the signals in the active workspace. Add at least one signal first.");
        focusTableButton.setOnAction(e -> setFocusMode(WorkspaceFocusMode.FOCUS));
        exitFocusButton.setOnAction(e -> setFocusMode(WorkspaceFocusMode.NORMAL));
        addSignalsButton.setOnAction(e -> onAddSignalsRequest.run());
        signalDetailsButton.setOnAction(e -> onSignalDetailsRequest.run());
        focusTableButton.setTooltip(new Tooltip("Maximize the table; add signals and details via temporary drawers"));
        addSignalsButton.setTooltip(new Tooltip("Add signals without leaving Focus Mode"));
        signalDetailsButton.setTooltip(new Tooltip("Inspect signal details without leaving Focus Mode"));
        exitFocusButton.setTooltip(new Tooltip("Return to Browser + Inspector layout"));
        for (Button action : List.of(
                focusTableButton, addSelectedButton, analyzeSelectedButton, mapRouteButton, clearAllButton,
                addSignalsButton, signalDetailsButton, exitFocusButton)) {
            action.setMinHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
            action.setPrefHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
            action.setMaxHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
            action.setMinWidth(Region.USE_PREF_SIZE);
            action.setMaxWidth(Region.USE_PREF_SIZE);
            action.setWrapText(false);
            action.setTextOverrun(OverrunStyle.CLIP);
            action.setEllipsisString("");
        }
        // Widths are content-driven: every button sizes to its own text and never below it.

        headerActions.setAlignment(Pos.CENTER_RIGHT);
        headerActions.setMinWidth(Region.USE_PREF_SIZE);
        headerActions.getStyleClass().add("workspace-command-bar");
        refreshHeaderActions();

        // Row 1: title with the primary workspace commands. layoutCommandRowForWidth wraps the
        // commands under the title only when both do not fit on one line.
        commandRow.setHgap(SignalsLayoutMetrics.CONTROL_GAP);
        commandRow.setVgap(4);
        commandRow.setMinWidth(0);
        commandRow.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(commandRow, Priority.NEVER);
        commandRow.getStyleClass().addAll("workspace-command-row", "workspace-title-row");
        layoutCommandRowForWidth(Double.MAX_VALUE);

        chipBar.getStyleClass().add("chip-bar");
        chipBar.setAlignment(Pos.CENTER_LEFT);
        chipBar.setFillHeight(true);
        chipBar.setMinHeight(SignalsLayoutMetrics.CHIP_HEIGHT);
        chipBar.setPrefHeight(SignalsLayoutMetrics.CHIP_HEIGHT);
        chipBar.setMinWidth(Region.USE_PREF_SIZE);
        chipBar.setMaxWidth(Region.USE_PREF_SIZE);
        chipBar.setPadding(Insets.EMPTY);
        chipBar.setSpacing(SignalsLayoutMetrics.CONTROL_GAP);
        double chipStripHeight = SignalsLayoutMetrics.CHIP_HEIGHT + 4;
        chipScroll.setFitToHeight(true);
        chipScroll.setFitToWidth(false);
        chipScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        chipScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chipScroll.setPannable(true);
        chipScroll.setMinHeight(chipStripHeight);
        chipScroll.setPrefHeight(chipStripHeight);
        chipScroll.setMaxHeight(chipStripHeight);
        chipScroll.setMinWidth(0);
        chipScroll.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(chipScroll, Priority.ALWAYS);
        VBox.setVgrow(chipScroll, Priority.NEVER);
        chipScroll.getStyleClass().addAll("chip-scroll", "workspace-chip-strip");
        chipScroll.widthProperty().addListener((o, a, b) -> refreshChipScrollPolicy());
        chipScroll.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (!event.isShiftDown() && Math.abs(event.getDeltaX()) <= Math.abs(event.getDeltaY())) {
                return;
            }
            double delta = event.getDeltaX() != 0 ? event.getDeltaX() : event.getDeltaY();
            double span = Math.max(1, chipBar.getWidth() - chipScroll.getViewportBounds().getWidth());
            chipScroll.setHvalue(Math.clamp(chipScroll.getHvalue() - delta / span, 0, 1));
            event.consume();
        });
        configureChipScrollButton(chipScrollLeft, StellarIcons.previous(), "Scroll to earlier signals", -1);
        configureChipScrollButton(chipScrollRight, StellarIcons.next(), "Scroll to later signals", 1);
        chipStrip.setAlignment(Pos.CENTER_LEFT);
        chipStrip.setMinWidth(0);
        chipStrip.setMaxWidth(Double.MAX_VALUE);
        chipStrip.setMinHeight(chipStripHeight);
        chipStrip.setPrefHeight(chipStripHeight);
        chipStrip.setMaxHeight(chipStripHeight);
        chipStrip.getStyleClass().add("signal-chip-strip");
        VBox.setVgrow(chipStrip, Priority.NEVER);
        moreSignalsButton.getStyleClass().addAll("signal-chip-more", "compact-button", "secondary-button");
        moreSignalsButton.setMinHeight(SignalsLayoutMetrics.CHIP_HEIGHT);
        moreSignalsButton.setPrefHeight(SignalsLayoutMetrics.CHIP_HEIGHT);
        moreSignalsButton.setMaxHeight(SignalsLayoutMetrics.CHIP_HEIGHT);
        moreSignalsButton.setMinWidth(Region.USE_PREF_SIZE);
        moreSignalsButton.setMaxWidth(Region.USE_PREF_SIZE);
        moreSignalsButton.setOnAction(e -> toggleSelectedSignalsPopup());
        buildSelectedSignalsPopup();

        buildToolbar();
        buildStatusStrip();

        table.getSelectionModel().setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setMinHeight(0);
        table.setMaxHeight(Double.MAX_VALUE);
        table.setMaxWidth(Double.MAX_VALUE);
        table.setFixedCellSize(SignalsLayoutMetrics.TABLE_ROW_HEIGHT);
        table.getStyleClass().add("workspace-table");
        VBox.setVgrow(table, Priority.ALWAYS);
        table.addEventFilter(KeyEvent.KEY_PRESSED, tableKeyFilter);
        installTableContextMenu();
        addEventFilter(KeyEvent.KEY_PRESSED, escapeFilter);
        feedbackPause.setOnFinished(e -> {
            if (!disposed && navFeedback.getText() != null && !navFeedback.getText().isBlank()) {
                clearFeedback();
            }
        });

        emptyTitle.getStyleClass().add("empty-state-title");
        for (Label bullet : List.of(emptyBullet1, emptyBullet2, emptyBullet3)) {
            bullet.getStyleClass().add("empty-state-body");
            bullet.setWrapText(true);
        }
        emptyContextTitle.getStyleClass().add("section-subtitle");
        for (Label ctx : List.of(emptyContextFile, emptyContextDbc, emptyContextChannels, emptyContextCount)) {
            ctx.getStyleClass().add("muted");
            ctx.setWrapText(true);
            ctx.setMaxWidth(420);
        }
        emptyBullets.setAlignment(Pos.CENTER_LEFT);
        emptyContextBox.getStyleClass().add("workspace-empty-context");
        emptyContextBox.setAlignment(Pos.CENTER_LEFT);
        emptyState.getChildren().setAll(emptyTitle, emptyBullets, emptyContextBox);
        emptyState.getStyleClass().addAll("empty-state", "workspace-empty-card");
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setMaxWidth(520);
        emptyState.setMouseTransparent(false);

        pagingStrip.getStyleClass().addAll("workspace-paging-strip", "pagination-bar");
        pagingStrip.setAlignment(Pos.CENTER_LEFT);
        pagingStrip.setMinHeight(0);
        pagingStrip.setPrefHeight(0);
        pagingStrip.setMaxHeight(0);
        pagingStrip.setVisible(false);
        pagingStrip.setManaged(false);
        VBox.setVgrow(pagingStrip, Priority.NEVER);
        pageLabel.getStyleClass().add("workspace-page-label");
        windowNavBox.getStyleClass().add("workspace-window-nav");
        windowNavBox.setAlignment(Pos.CENTER_RIGHT);
        pagingStrip.getChildren().setAll(rangeLabel);

        tableFooter.getStyleClass().add("workspace-table-footer");
        tableFooter.setAlignment(Pos.CENTER_LEFT);
        tableFooter.setMinHeight(SignalsLayoutMetrics.FOOTER_HEIGHT);
        tableFooter.setPrefHeight(SignalsLayoutMetrics.FOOTER_HEIGHT);
        tableFooter.setMaxHeight(SignalsLayoutMetrics.FOOTER_HEIGHT + 4);
        VBox.setVgrow(tableFooter, Priority.NEVER);
        tableFooterLabel.getStyleClass().add("muted");
        tableFooterLabel.setWrapText(false);
        tableFooterLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        tableFooterLabel.setVisible(false);
        tableFooterLabel.setManaged(false);
        for (Label metric : List.of(footerRangeLabel, footerStartLabel, footerEndLabel, footerDurationLabel)) {
            metric.getStyleClass().add("workspace-footer-metric");
            metric.setMinWidth(Region.USE_PREF_SIZE);
            metric.setTextOverrun(OverrunStyle.ELLIPSIS);
        }
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        tableFooter.getChildren().setAll(
                footerRangeLabel, footerStartLabel, footerEndLabel, footerDurationLabel,
                footerSpacer, busyIndicator, windowNavBox);
        footerTimingRow.getStyleClass().add("workspace-footer-timing");
        footerTimingRow.setAlignment(Pos.CENTER_LEFT);
        footerTimingRow.setVisible(false);
        footerTimingRow.setManaged(false);
        VBox.setVgrow(footerTimingRow, Priority.NEVER);

        tableHost.getStyleClass().add("workspace-table-host");
        tableHost.getChildren().addAll(table, emptyState);
        tableHost.setMinWidth(0);
        tableHost.setMinHeight(0);
        tableHost.setMaxWidth(Double.MAX_VALUE);
        tableHost.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(tableHost, Priority.ALWAYS);
        StackPane.setAlignment(emptyState, Pos.CENTER);
        StackPane.setAlignment(table, Pos.TOP_LEFT);

        // Title carries Focus Table / Copy Table. Chips, then one toolbar, then the table.
        // The old utility row stayed managed between them and left a blank band.
        getChildren().addAll(
                commandRow, chipStrip, toolbar, statusStrip, tableHost, tableFooter, footerTimingRow);
        widthProperty().addListener((o, a, b) -> {
            double width = b == null ? 0 : b.doubleValue();
            layoutCommandRowForWidth(width);
            layoutToolbarForWidth(width);
            layoutFooterForWidth(width);
            refreshChipScrollPolicy();
        });
        layoutFooterForWidth(1600);

        SignalCatalogDragSupport.enableDropTarget(this, this::acceptDroppedKeys);
        SignalCatalogDragSupport.enableDropTarget(table, this::acceptDroppedKeys);
        SignalCatalogDragSupport.enableDropTarget(chipBar, this::acceptDroppedKeys);
        showEmpty(true);
    }

    private void buildStatusStrip() {
        statusStrip.getStyleClass().addAll("workspace-status-strip", "summary-row");
        statusStrip.setAlignment(Pos.CENTER_LEFT);
        statusStrip.setMinHeight(SignalsLayoutMetrics.STATUS_STRIP_HEIGHT);
        statusStrip.setPrefHeight(SignalsLayoutMetrics.STATUS_STRIP_HEIGHT);
        statusStrip.setMaxHeight(SignalsLayoutMetrics.STATUS_STRIP_HEIGHT);
        statusStrip.setMinWidth(0);
        statusStrip.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(statusStrip, Priority.NEVER);
        for (Label label : List.of(
                statusSelected, statusAlignment, statusRows, statusStart, statusEnd, statusChannels)) {
            label.getStyleClass().add("workspace-status-item");
            label.setMinWidth(Region.USE_PREF_SIZE);
            label.setMaxWidth(Double.MAX_VALUE);
            label.setWrapText(false);
            label.setTextOverrun(OverrunStyle.ELLIPSIS);
        }
        statusSelected.getStyleClass().add("workspace-status-item-accent");
        statusAlignment.getStyleClass().add("workspace-status-item-accent");
        // Compact summary only — row range / start / end live in the table footer.
        statusStrip.getChildren().setAll(
                statusSelected,
                statusDot(),
                statusAlignment,
                statusDot(),
                statusChannels);
        clearStatusStrip();
    }

    private static Label statusDot() {
        Label dot = new Label("\u2022");
        dot.getStyleClass().add("workspace-status-dot");
        dot.setMinWidth(Region.USE_PREF_SIZE);
        return dot;
    }

    private void buildToolbar() {
        rangeLabel.getStyleClass().addAll("workspace-range-label");
        rangeLabel.setWrapText(false);
        rangeLabel.setTextOverrun(OverrunStyle.CLIP);
        rangeLabel.setMinWidth(Region.USE_PREF_SIZE);

        for (Label label : List.of(displayLabel, precisionLabel, navigateLabel, rowsLabel)) {
            label.getStyleClass().add("toolbar-field-label");
            label.setMinWidth(Region.USE_PREF_SIZE);
            label.setPrefWidth(Region.USE_COMPUTED_SIZE);
            label.setMaxWidth(Region.USE_PREF_SIZE);
            label.setWrapText(false);
            label.setTextOverrun(OverrunStyle.CLIP);
            label.setEllipsisString("");
        }
        navigateLabel.setText("Navigate");

        displayModeBox.getItems().setAll(WorkspaceDisplayMode.values());
        displayModeBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(WorkspaceDisplayMode object) {
                return object == null ? "" : object.label();
            }

            @Override
            public WorkspaceDisplayMode fromString(String string) {
                return WorkspaceDisplayMode.PHYSICAL;
            }
        });
        displayModeBox.setValue(WorkspaceDisplayMode.PHYSICAL);
        sizeCombo(displayModeBox, SignalsLayoutMetrics.DISPLAY_COMBO_WIDTH);
        displayModeBox.setVisibleRowCount(2);
        displayModeBox.setTooltip(new Tooltip("Value display"));
        displayModeBox.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                displayMode = b;
                if (model != null) {
                    model.setDisplayMode(b);
                }
                table.refresh();
                updateTableFooter();
            }
        });

        precisionBox.getItems().setAll(WorkspacePrecision.values());
        precisionBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(WorkspacePrecision object) {
                return object == null ? "" : object.label();
            }

            @Override
            public WorkspacePrecision fromString(String string) {
                return WorkspacePrecision.AUTO;
            }
        });
        precisionBox.setValue(WorkspacePrecision.AUTO);
        sizeCombo(precisionBox, SignalsLayoutMetrics.PRECISION_COMBO_WIDTH);
        precisionBox.setVisibleRowCount(6);
        precisionBox.setTooltip(new Tooltip(WorkspacePrecision.AUTO.tooltip()));
        precisionBox.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                precision = b;
                if (model != null) {
                    model.setPrecision(b);
                }
                precisionBox.setTooltip(new Tooltip(b.tooltip()));
                table.refresh();
            }
        });

        rowsBox.getItems().setAll(1_000, 2_500, 5_000);
        rowsBox.setValue(5_000);
        sizeCombo(rowsBox, SignalsLayoutMetrics.ROWS_COMBO_WIDTH);
        rowsBox.setTooltip(new Tooltip("Rows loaded per aligned window"));
        rowsBox.valueProperty().addListener((o, a, b) -> {
            if (applyingRowsValue || b == null || model == null || model.samplePageSize() == b) {
                return;
            }
            int origin = lastSnap == null || lastSnap.rows().isEmpty() ? 0 : lastSnap.windowStartIndex();
            model.setSamplePageSize(b);
            int aligned = (int) ((Math.max(0, origin) / (long) b) * b);
            if (aligned <= 0) {
                reload(true);
            } else {
                jumpToIndexValue(aligned);
            }
        });

        navigateModeBox.getItems().setAll(NavigateTarget.values());
        navigateModeBox.setValue(NavigateTarget.INDEX);
        sizeCombo(navigateModeBox, SignalsLayoutMetrics.NAVIGATE_MODE_WIDTH);
        navigateModeBox.setVisibleRowCount(2);
        navigateModeBox.setTooltip(new Tooltip("Jump by the active index or by timestamp"));
        navigateModeBox.valueProperty().addListener((o, a, mode) -> applyNavigateMode(mode));

        configureGoButton(navigateGoButton);
        navigateGoButton.setOnAction(e -> jumpFromNavigate());
        sizeField(navigateField, SignalsLayoutMetrics.NAVIGATE_FIELD_WIDTH);
        navigateField.setOnAction(e -> jumpFromNavigate());
        applyNavigateMode(NavigateTarget.INDEX);

        configureWindowNavButton(firstWindowButton, StellarIcons.first(), "First data window");
        configureWindowNavButton(prevWindowButton, StellarIcons.previous(), "Previous data window");
        configureWindowNavButton(nextWindowButton, StellarIcons.next(), "Next data window");
        configureWindowNavButton(lastWindowButton, StellarIcons.last(), "Last data window");
        firstWindowButton.setOnAction(e -> jumpToIndexValue(0));
        prevWindowButton.setOnAction(e -> navigatePrevious());
        nextWindowButton.setOnAction(e -> navigateNext());
        lastWindowButton.setOnAction(e -> jumpToLastWindow());
        windowNavBox.getChildren().setAll(
                firstWindowButton, prevWindowButton, pageLabel, nextWindowButton, lastWindowButton);
        windowNavBox.setVisible(false);
        windowNavBox.setManaged(false);

        navFeedback.getStyleClass().addAll("muted", "nav-feedback");
        navFeedback.setTextOverrun(OverrunStyle.ELLIPSIS);
        navFeedback.setMaxWidth(180);
        busyIndicator.setMaxSize(16, 16);
        busyIndicator.setVisible(false);
        busyIndicator.setManaged(false);

        displayGroup.getStyleClass().add("analysis-toolbar-group");
        precisionGroup.getStyleClass().add("analysis-toolbar-group");
        navigateGroup.getStyleClass().add("analysis-toolbar-group");
        rowsGroup.getStyleClass().add("analysis-toolbar-group");
        displayGroup.setAlignment(Pos.CENTER_LEFT);
        precisionGroup.setAlignment(Pos.CENTER_LEFT);
        navigateGroup.setAlignment(Pos.CENTER_LEFT);
        rowsGroup.setAlignment(Pos.CENTER_LEFT);
        displayGroup.setSpacing(4);
        precisionGroup.setSpacing(4);
        rowsGroup.setSpacing(4);
        navigateGroup.setSpacing(4);

        displayGroup.getChildren().setAll(displayLabel, displayModeBox);
        precisionGroup.getChildren().setAll(precisionLabel, precisionBox);
        rowsGroup.getChildren().setAll(rowsLabel, rowsBox);
        navigateGroup.getChildren().setAll(navigateLabel, navigateModeBox, navigateField, navigateGoButton);
        for (HBox group : List.of(displayGroup, precisionGroup, rowsGroup, navigateGroup)) {
            group.setMinWidth(Region.USE_PREF_SIZE);
            group.setMaxWidth(Region.USE_PREF_SIZE);
        }

        settingsButton.setGraphic(StellarIcons.slidersSecondary());
        settingsButton.getStyleClass().addAll("compact-button", "icon-button", "workspace-toolbar-icon");
        settingsButton.setFocusTraversable(false);
        settingsButton.setTooltip(new Tooltip("Columns"));
        settingsButton.setOnAction(e -> {
            if (columnsMenu.isShowing()) {
                columnsMenu.hide();
            } else {
                columnsMenu.show(settingsButton, Side.BOTTOM, 0, 0);
            }
        });
        indexColumnItem.setSelected(true);
        timestampColumnItem.setSelected(true);
        deltaColumnItem.setSelected(false);
        indexColumnItem.setOnAction(e -> applyColumnVisibility());
        timestampColumnItem.setOnAction(e -> applyColumnVisibility());
        deltaColumnItem.setOnAction(e -> applyColumnVisibility());
        columnsMenu.getItems().setAll(indexColumnItem, timestampColumnItem, deltaColumnItem);
        exportButton.setText("Copy Table");
        exportButton.setGraphic(StellarIcons.copy());
        exportButton.setGraphicTextGap(6);
        exportButton.getStyleClass().addAll(
                "compact-button", "secondary-button", "icon-text-button", "workspace-copy-table-button");
        exportButton.setFocusTraversable(false);
        exportButton.setTooltip(new Tooltip(
                "Copy all currently displayed rows and headers to the clipboard (Excel-compatible)"));
        exportButton.setDisable(true);
        lockContentWidth(exportButton);
        exportButton.setOnAction(e -> copyDisplayedTable());
        exportCsvButton.setText("Export CSV");
        exportCsvButton.setGraphic(StellarIcons.download());
        exportCsvButton.setGraphicTextGap(6);
        exportCsvButton.getStyleClass().addAll(
                "compact-button", "primary-button", "icon-text-button", "workspace-export-csv-button");
        exportCsvButton.setFocusTraversable(false);
        exportCsvButton.setMnemonicParsing(false);
        exportCsvButton.setTooltip(new Tooltip(
                "Export Selected and Export All write the full aligned dataset, on every page. "
                        + "timestamp_ns and blank missing samples stay as they are. "
                        + "The visible page does not change the CSV."));
        exportCsvButton.setMinWidth(Region.USE_PREF_SIZE);
        exportCsvButton.setMaxWidth(Region.USE_PREF_SIZE);
        exportCsvButton.setWrapText(false);
        MenuItem exportSelected = new MenuItem("Selected Signals");
        MenuItem exportAll = new MenuItem("All Signals");
        exportSelected.setOnAction(e -> exportCsv(true));
        exportAll.setOnAction(e -> exportCsv(false));
        exportCsvButton.getItems().setAll(exportSelected, exportAll);
        toolbarActions.getStyleClass().add("workspace-toolbar-actions");
        toolbarActions.setAlignment(Pos.CENTER_RIGHT);
        toolbarActions.setMinWidth(Region.USE_PREF_SIZE);
        toolbarActions.setMaxWidth(Region.USE_PREF_SIZE);
        utilitySpacer.setMinWidth(0);
        HBox.setHgrow(utilitySpacer, Priority.ALWAYS);
        utilityRow.getStyleClass().add("workspace-utility-row");
        utilityRow.setAlignment(Pos.CENTER_RIGHT);
        utilityRow.setMinWidth(0);
        utilityRow.setMaxWidth(Double.MAX_VALUE);
        utilityRow.setMinHeight(SignalsLayoutMetrics.TOOLBAR_HEIGHT);
        utilityRow.setPrefHeight(SignalsLayoutMetrics.TOOLBAR_HEIGHT);
        utilityRow.setMaxHeight(SignalsLayoutMetrics.TOOLBAR_HEIGHT);
        utilityRow.getChildren().setAll(utilitySpacer, toolbarActions);
        utilityRow.setVisible(false);
        utilityRow.setManaged(false);
        utilityRow.setMinHeight(0);
        utilityRow.setPrefHeight(0);
        utilityRow.setMaxHeight(0);
        VBox.setVgrow(utilityRow, Priority.NEVER);
        refreshHeaderActions();

        toolbar.getStyleClass().addAll("workspace-toolbar", "analysis-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setSpacing(3);
        toolbar.setMinHeight(SignalsLayoutMetrics.TOOLBAR_HEIGHT);
        toolbar.setPrefHeight(SignalsLayoutMetrics.TOOLBAR_HEIGHT);
        toolbar.setMaxHeight(SignalsLayoutMetrics.TOOLBAR_HEIGHT);
        toolbar.setMinWidth(0);
        toolbar.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(toolbar, Priority.NEVER);
        layoutToolbarForWidth(1600);
    }

    /** Width the pane content may use, excluding its own padding. */
    private static double contentWidth(double paneWidth) {
        return Math.max(0, paneWidth - SignalsLayoutMetrics.INNER_PADDING * 2);
    }

    /**
     * Title row holds the workspace title plus Focus Table, settings, and Copy Table.
     * Those actions stay on this row so they do not open a blank band under the chips.
     */
    private void layoutCommandRowForWidth(double paneWidth) {
        commandRow.getChildren().clear();
        commandRow.getColumnConstraints().clear();
        double need = titleLabel.prefWidth(-1) + SignalsLayoutMetrics.CONTROL_GAP + headerActions.prefWidth(-1);
        boolean wrap = paneWidth != Double.MAX_VALUE && contentWidth(paneWidth) > 0
                && contentWidth(paneWidth) < need && !headerActions.getChildren().isEmpty();
        ColumnConstraints titleColumn = new ColumnConstraints();
        titleColumn.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints growColumn = new ColumnConstraints();
        growColumn.setHgrow(Priority.ALWAYS);
        growColumn.setMinWidth(0);
        commandRow.getColumnConstraints().setAll(titleColumn, growColumn);
        commandRow.add(titleLabel, 0, 0);
        if (wrap) {
            commandRow.add(headerActions, 0, 1, 2, 1);
            GridPane.setHalignment(headerActions, HPos.LEFT);
        } else {
            commandRow.add(headerActions, 1, 0);
            GridPane.setHalignment(headerActions, HPos.RIGHT);
        }
        GridPane.setValignment(titleLabel, VPos.CENTER);
        GridPane.setValignment(headerActions, VPos.CENTER);
        double rowHeight = SignalsLayoutMetrics.WORKSPACE_HEADER_HEIGHT;
        double height = wrap ? rowHeight + SignalsLayoutMetrics.CONTROL_HEIGHT + commandRow.getVgap() : rowHeight;
        commandRow.setMinHeight(height);
        commandRow.setPrefHeight(height);
        commandRow.setMaxHeight(height);
    }

    /**
     * One toolbar row: Display Mode, Precision, Rows, then a single Navigate control.
     * Index and time share that control. There is no second navigation row.
     */
    private void layoutToolbarForWidth(double paneWidth) {
        toolbar.getChildren().setAll(
                displayGroup, precisionGroup, rowsGroup, displaySplit, navigateGroup, navFeedback);
        HBox.setHgrow(navFeedback, Priority.ALWAYS);
        // Height follows SignalsToolbar: one content row, or a second row only when
        // the controls do not fit. A fixed second-row height would reserve empty space.
        toolbar.setMinHeight(SignalsLayoutMetrics.TOOLBAR_HEIGHT);
        toolbar.setPrefHeight(Region.USE_COMPUTED_SIZE);
        toolbar.setMaxHeight(SignalsLayoutMetrics.TOOLBAR_HEIGHT * 2);
        if (navigateGroup.getParent() != toolbar) {
            throw new IllegalStateException("toolbar structure broken: " + toolbarStructureForTest());
        }
    }

    private static String indexColumnTitle(int signalColumns) {
        return signalColumns > 1 ? "Aligned Index" : "Signal Index";
    }

    /**
     * Focus changes the highlighted chip and column. The table stays on the aligned timeline,
     * so a focus change does not issue another sample query.
     */
    public void reloadFocusedSignalIfNeeded() {
        refreshFocusPresentation();
    }

    /** Runtime parent report. Navigate stays a child of {@code toolbar}. */
    public String toolbarStructureForTest() {
        javafx.scene.Scene scene = getScene();
        String navigateParent = navigateGroup.getParent() == toolbar ? "toolbar"
                : String.valueOf(navigateGroup.getParent());
        NavigateTarget mode = navigateModeBox.getValue();
        return "scene=" + (scene == null ? 0 : scene.getWidth())
                + " workspace=" + getWidth()
                + " toolbar=" + toolbar.getWidth()
                + " navigateParent=" + navigateParent
                + " mode=" + (mode == null ? "" : mode.label());
    }

    private void applyNavigateMode(NavigateTarget mode) {
        if (mode == null) {
            return;
        }
        navigateField.setPromptText(mode.prompt());
        navigateField.setTooltip(new Tooltip(mode.tooltip()));
    }

    private void jumpFromNavigate() {
        if (navigateModeBox.getValue() == NavigateTarget.TIME) {
            jumpToTime();
        } else {
            jumpToIndex();
        }
    }

    private static Region toolbarSplit() {
        Region split = new Region();
        split.getStyleClass().add("signals-toolbar-separator");
        split.setMinWidth(1);
        split.setPrefWidth(1);
        split.setMaxWidth(1);
        split.setMinHeight(16);
        split.setPrefHeight(16);
        split.setMaxHeight(16);
        return split;
    }

    /**
     * Timing metadata drops to its own line when the footer cannot hold the page controls
     * and the range text together. The page controls stay on the first line.
     */
    private void layoutFooterForWidth(double paneWidth) {
        double available = contentWidth(paneWidth);
        boolean wrap = available > 0 && available < SignalsLayoutMetrics.FOOTER_WRAP_WIDTH;
        if (wrap == footerTimingWrapped) {
            return;
        }
        footerTimingWrapped = wrap;
        if (wrap) {
            tableFooter.getChildren().removeAll(footerStartLabel, footerEndLabel, footerDurationLabel);
            footerTimingRow.getChildren().setAll(footerStartLabel, footerEndLabel, footerDurationLabel);
            footerTimingRow.setVisible(true);
            footerTimingRow.setManaged(true);
            tableFooter.setMinHeight(SignalsLayoutMetrics.FOOTER_HEIGHT);
            tableFooter.setPrefHeight(SignalsLayoutMetrics.FOOTER_HEIGHT);
            tableFooter.setMaxHeight(SignalsLayoutMetrics.FOOTER_HEIGHT + 4);
        } else {
            footerTimingRow.getChildren().clear();
            footerTimingRow.setVisible(false);
            footerTimingRow.setManaged(false);
            tableFooter.getChildren().removeAll(footerStartLabel, footerEndLabel, footerDurationLabel);
            int insertAt = tableFooter.getChildren().indexOf(footerRangeLabel) + 1;
            if (insertAt < 1) {
                insertAt = 0;
            }
            tableFooter.getChildren().add(insertAt, footerStartLabel);
            tableFooter.getChildren().add(insertAt + 1, footerEndLabel);
            tableFooter.getChildren().add(insertAt + 2, footerDurationLabel);
        }
    }

    private static void lockContentWidth(Button button) {
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setPrefWidth(Region.USE_COMPUTED_SIZE);
        button.setMaxWidth(Region.USE_PREF_SIZE);
        button.setWrapText(false);
        button.setTextOverrun(OverrunStyle.CLIP);
        button.setEllipsisString("");
    }

    private void refreshChipScrollPolicy() {
        chipBar.applyCss();
        chipBar.autosize();
        double contentWidth = chipBar.prefWidth(-1);
        double viewWidth = chipScroll.getViewportBounds().getWidth();
        if (viewWidth <= 1) {
            viewWidth = chipScroll.getWidth();
        }
        boolean overflow = contentWidth > viewWidth + 2;
        chipScroll.setFitToWidth(false);
        chipScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        double stripHeight = SignalsLayoutMetrics.CHIP_HEIGHT + (overflow ? 14 : 4);
        lockStripHeight(chipScroll, stripHeight);
        lockStripHeight(chipStrip, stripHeight);
        chipScrollLeft.setDisable(!overflow);
        chipScrollRight.setDisable(!overflow);
    }

    /** Inline height wins over stylesheet rules that would keep a 46px chip band. */
    private static void lockStripHeight(Region region, double stripHeight) {
        region.setMinHeight(stripHeight);
        region.setPrefHeight(stripHeight);
        region.setMaxHeight(stripHeight);
        region.setStyle("-fx-min-height: " + stripHeight
                + "; -fx-pref-height: " + stripHeight
                + "; -fx-max-height: " + stripHeight + ";");
    }

    private void configureChipScrollButton(Button button, javafx.scene.Node icon, String tip, int direction) {
        button.setGraphic(icon);
        button.getStyleClass().addAll("compact-button", "icon-button", "signal-chip-scroll-button");
        button.setFocusTraversable(false);
        button.setMinWidth(28);
        button.setPrefWidth(28);
        button.setMaxWidth(28);
        button.setMinHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        button.setPrefHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        button.setMaxHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(e -> scrollChips(direction));
    }

    private void scrollChips(int direction) {
        double viewport = Math.max(1, chipScroll.getViewportBounds().getWidth());
        double content = Math.max(viewport, chipBar.getWidth());
        double page = viewport / content;
        chipScroll.setHvalue(Math.clamp(chipScroll.getHvalue() + direction * Math.max(0.15, page), 0, 1));
    }

    private static void sizeCombo(ComboBox<?> box, double width) {
        box.setMinWidth(width);
        box.setPrefWidth(width);
        box.setMaxWidth(width);
        box.setMinHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        box.setPrefHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        box.setMaxHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
    }

    private static void sizeField(TextField field, double width) {
        field.setMinWidth(width);
        field.setPrefWidth(width);
        field.setMaxWidth(width);
        field.setMinHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        field.setPrefHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        field.setMaxHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        HBox.setHgrow(field, Priority.NEVER);
    }

    private static void configureWindowNavButton(Button button, javafx.scene.Node icon, String tooltip) {
        button.setText(null);
        button.setGraphic(icon);
        button.getStyleClass().setAll("window-nav-button", "pagination-button", "icon-button");
        double size = SignalsLayoutMetrics.PAGINATION_BUTTON;
        button.setMinWidth(size);
        button.setPrefWidth(size);
        button.setMaxWidth(size);
        button.setMinHeight(size);
        button.setPrefHeight(size);
        button.setMaxHeight(size);
        button.setWrapText(false);
        button.setTextOverrun(OverrunStyle.CLIP);
        button.setEllipsisString("");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
    }

    private static void configureGoButton(Button button) {
        button.setText("Go");
        button.getStyleClass().addAll("compact-button", "toolbar-go-button");
        button.setMinWidth(SignalsLayoutMetrics.GO_BUTTON_WIDTH);
        button.setPrefWidth(SignalsLayoutMetrics.GO_BUTTON_WIDTH);
        button.setMaxWidth(SignalsLayoutMetrics.GO_BUTTON_WIDTH);
        button.setMinHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        button.setPrefHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        button.setMaxHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        button.setTextOverrun(OverrunStyle.CLIP);
        button.setEllipsisString("");
        button.setWrapText(false);
        button.setFocusTraversable(false);
    }

    public TableView<AlignedSampleRow> table() {
        return table;
    }

    public TextField indexFieldForTest() {
        return navigateField;
    }

    public Button indexGoButtonForTest() {
        return navigateGoButton;
    }

    public void selectNavigateTimeForTest() {
        navigateModeBox.setValue(NavigateTarget.TIME);
    }

    public String navigatePromptForTest() {
        return navigateField.getPromptText();
    }

    public void setDeltaColumnVisibleForTest(boolean visible) {
        deltaColumnItem.setSelected(visible);
        applyColumnVisibility();
    }

    public boolean isDeltaColumnVisibleForTest() {
        return deltaColumn != null && deltaColumn.isVisible();
    }

    public ContextMenu columnsMenuForTest() {
        return columnsMenu;
    }

    public Label titleLabel() {
        return titleLabel;
    }

    /** Test hook: status-strip membership count must track workspaceKeys. */
    public Label statusSelectedLabelForTest() {
        return statusSelected;
    }

    /** Test hook: workspace chip count (membership chrome). */
    public int chipCountForTest() {
        return chipBar.getChildren().size();
    }

    /** Test hook: signal columns only (excludes Index / Timestamp / Δt). */
    public int signalColumnCountForTest() {
        return installedSignalColumnKeys().size();
    }

    public int tableColumnCountForTest() {
        return table.getColumns().size();
    }

    public void setModel(SignalsScreenModel model) {
        this.model = Objects.requireNonNull(model, "model");
        displayMode = model.displayMode();
        precision = model.precision();
        displayModeBox.setValue(displayMode);
        precisionBox.setValue(precision);
        int page = model.samplePageSize();
        applyingRowsValue = true;
        try {
            if (!rowsBox.getItems().contains(page)) {
                rowsBox.getItems().add(page);
            }
            rowsBox.setValue(page);
        } finally {
            applyingRowsValue = false;
        }
        reload(false);
    }

    public void setBrowserSelectionSupplier(Supplier<List<SignalCatalogEntry>> supplier) {
        this.browserSelection = supplier == null ? List::of : supplier;
    }

    public void setOnWorkspaceChanged(Runnable listener) {
        this.onWorkspaceChanged = listener == null ? () -> { } : listener;
    }

    public void setOnFocusChanged(Runnable listener) {
        this.onFocusChanged = listener == null ? () -> { } : listener;
    }

    public void setOnFocusModeChanged(Consumer<WorkspaceFocusMode> listener) {
        this.onFocusModeChanged = listener == null ? mode -> { } : listener;
    }

    public void setOnAddSignalsRequest(Runnable listener) {
        this.onAddSignalsRequest = listener == null ? () -> { } : listener;
    }

    public void setOnSignalDetailsRequest(Runnable listener) {
        this.onSignalDetailsRequest = listener == null ? () -> { } : listener;
    }

    public void setOnAnalyzeSelectedRequest(Runnable listener) {
        this.onAnalyzeSelectedRequest = listener == null ? () -> { } : listener;
    }

    public void setOnMapRouteRequest(Runnable listener) {
        this.onMapRouteRequest = listener == null ? () -> { } : listener;
    }

    public void setMapRouteEnabled(boolean enabled, String reason) {
        mapRouteButton.setDisable(!enabled);
        String tip = reason == null ? "" : reason.strip();
        if (tip.isBlank()) {
            if (mapRouteButton.getTooltip() != null) {
                mapRouteButton.setTooltip(null);
            }
            return;
        }
        Tooltip existing = mapRouteButton.getTooltip();
        if (existing == null) {
            mapRouteButton.setTooltip(new Tooltip(tip));
        } else {
            existing.setText(tip);
        }
    }

    public void setAnalyzeEnabled(boolean enabled, String reason) {
        analyzeSelectedButton.setDisable(!enabled);
        String tip = engineerFacingAnalyzeReason(reason);
        if (tip.isBlank()) {
            tip = enabled
                    ? "Plot the signals in the active workspace."
                    : "Add at least one signal to the active workspace first.";
        }
        installAnalyzeTooltip(tip);
    }

    /** Tooltip / flash copy. Diagnostic "canAnalyze=" suffixes stay in logs only. */
    static String engineerFacingAnalyzeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        int marker = reason.indexOf(" canAnalyze=");
        if (marker < 0) {
            marker = reason.indexOf("canAnalyze=");
        }
        return marker < 0 ? reason.trim() : reason.substring(0, marker).trim();
    }

    private void installAnalyzeTooltip(String tip) {
        Tooltip previous = analyzeSelectedButton.getTooltip();
        if (previous != null) {
            Tooltip.uninstall(analyzeActionHost, previous);
        }
        Tooltip tooltip = new Tooltip(tip);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        tooltip.setAnchorLocation(javafx.stage.PopupWindow.AnchorLocation.CONTENT_TOP_RIGHT);
        analyzeSelectedButton.setTooltip(tooltip);
        Tooltip.install(analyzeActionHost, tooltip);
    }

    public void showAnalyzeBlocked(String message) {
        String human = engineerFacingAnalyzeReason(message);
        if (!human.isBlank()) {
            flashFeedback(human);
        }
    }

    public WorkspaceFocusMode focusMode() {
        return focusMode;
    }

    public boolean isFocusTable() {
        return focusMode == WorkspaceFocusMode.FOCUS;
    }

    public void setFocusMode(WorkspaceFocusMode mode) {
        WorkspaceFocusMode next = mode == null ? WorkspaceFocusMode.NORMAL : mode;
        if (this.focusMode == next) {
            refreshHeaderActions();
            return;
        }
        this.focusMode = next;
        getStyleClass().removeAll("workspace-focus-mode");
        if (next == WorkspaceFocusMode.FOCUS) {
            getStyleClass().add("workspace-focus-mode");
        }
        refreshHeaderActions();
        onFocusModeChanged.accept(next);
    }

    /** Moves Analyze Signals, Map Route, and Clear All into the top context strip. One instance of each. */
    public void mountContextActions(HBox host) {
        if (host == null) {
            return;
        }
        contextActionsMounted = true;
        host.getChildren().setAll(analyzeSelectedButton, mapRouteButton, clearAllButton);
        if (!analyzeSelectedButton.getStyleClass().contains("signals-context-action")) {
            analyzeSelectedButton.getStyleClass().add("signals-context-action");
        }
        if (!mapRouteButton.getStyleClass().contains("signals-context-action")) {
            mapRouteButton.getStyleClass().add("signals-context-action");
        }
        if (!clearAllButton.getStyleClass().contains("signals-context-action")) {
            clearAllButton.getStyleClass().add("signals-context-action");
        }
        refreshHeaderActions();
    }

    private void refreshHeaderActions() {
        if (focusMode == WorkspaceFocusMode.FOCUS) {
            toolbarActions.getChildren().setAll(settingsButton, exportButton, exportCsvButton);
        } else {
            toolbarActions.getChildren().setAll(focusTableButton, settingsButton, exportButton, exportCsvButton);
        }
        if (contextActionsMounted) {
            if (focusMode == WorkspaceFocusMode.FOCUS) {
                headerActions.getChildren().setAll(
                        addSignalsButton, signalDetailsButton, exitFocusButton, toolbarActions);
            } else {
                headerActions.getChildren().setAll(toolbarActions);
            }
        } else if (focusMode == WorkspaceFocusMode.FOCUS) {
            headerActions.getChildren().setAll(
                    addSignalsButton, signalDetailsButton, analyzeActionHost, clearAllButton,
                    exitFocusButton, toolbarActions);
        } else {
            headerActions.getChildren().setAll(
                    addSelectedButton, analyzeActionHost, clearAllButton, toolbarActions);
        }
        double width = getWidth() > 0 ? getWidth() : Double.MAX_VALUE;
        layoutCommandRowForWidth(width);
        layoutToolbarForWidth(getWidth() > 0 ? getWidth() : 1600);
    }

    public void onHide() {
        applySerial++;
        feedbackPause.stop();
        hideSelectedSignalsPopup();
        setBusy(false);
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        exportCancel.set(true);
        onHide();
        removeEventFilter(KeyEvent.KEY_PRESSED, escapeFilter);
        table.removeEventFilter(KeyEvent.KEY_PRESSED, tableKeyFilter);
        SignalCatalogDragSupport.clearDropTarget(this);
        SignalCatalogDragSupport.clearDropTarget(table);
        SignalCatalogDragSupport.clearDropTarget(chipBar);
        DesktopSceneGraphRelease.hideCombo(displayModeBox);
        DesktopSceneGraphRelease.hideCombo(precisionBox);
        DesktopSceneGraphRelease.hideCombo(rowsBox);
        columnsMenu.hide();
        chipBar.getChildren().clear();
        DesktopSceneGraphRelease.disposeTableView(table);
        lastColumnKeys = List.of();
        lastSnap = AlignedWorkspaceSnapshot.empty();
        model = null;
        browserSelection = List::of;
        onWorkspaceChanged = () -> { };
        onFocusChanged = () -> { };
        onFocusModeChanged = mode -> { };
        onAddSignalsRequest = () -> { };
        onSignalDetailsRequest = () -> { };
        onAnalyzeSelectedRequest = () -> { };
        onMapRouteRequest = () -> { };
        hideSelectedSignalsPopup();
        selectedSignalsList.getItems().clear();
        lastMembership = List.of();
        feedbackPause.setOnFinished(null);
    }

    boolean disposedForTest() {
        return disposed;
    }

    public int previewChipCountForTest() {
        long chips = chipBar.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("signal-chip"))
                .count();
        return (int) chips;
    }

    public String moreSignalsLabelForTest() {
        return moreSignalsButton.getText();
    }

    public boolean moreSignalsVisibleForTest() {
        return chipBar.getChildren().contains(moreSignalsButton);
    }

    public boolean analyzeEnabledForTest() {
        return !analyzeSelectedButton.isDisabled();
    }

    public String analyzeTooltipForTest() {
        Tooltip tip = analyzeSelectedButton.getTooltip();
        return tip == null ? "" : tip.getText();
    }

    public Button analyzeButtonForTest() {
        return analyzeSelectedButton;
    }

    public Button mapRouteButtonForTest() {
        return mapRouteButton;
    }

    public boolean mapRouteEnabledForTest() {
        return !mapRouteButton.isDisabled();
    }

    public String mapRouteTooltipForTest() {
        Tooltip tip = mapRouteButton.getTooltip();
        return tip == null ? "" : tip.getText();
    }

    public Button clearAllButtonForTest() {
        return clearAllButton;
    }

    public boolean contextActionsMountedForTest() {
        return contextActionsMounted;
    }

    public boolean analyzeInWorkspaceHeaderForTest() {
        return headerActions.getChildren().contains(analyzeActionHost)
                || headerActions.getChildren().contains(analyzeSelectedButton);
    }

    public boolean clearAllInWorkspaceHeaderForTest() {
        return headerActions.getChildren().contains(clearAllButton);
    }

    public boolean commandLabelsReadableForTest() {
        return "Analyze Signals".equals(analyzeSelectedButton.getText())
                && "Clear All".equals(clearAllButton.getText())
                && !analyzeSelectedButton.getText().contains("...")
                && !clearAllButton.getText().contains("...")
                && analyzeSelectedButton.getWidth() + 0.5 >= analyzeSelectedButton.prefWidth(-1)
                && clearAllButton.getWidth() + 0.5 >= clearAllButton.prefWidth(-1)
                && analyzeSelectedButton.getHeight() > 0
                && analyzeSelectedButton.getHeight() <= 32
                && clearAllButton.getHeight() > 0
                && clearAllButton.getHeight() <= 32;
    }

    public boolean commandRowContainsAnalyzeForTest() {
        return analyzeInWorkspaceHeaderForTest()
                && commandRow.getChildren().contains(headerActions);
    }

    public double toolbarHeightForTest() {
        return toolbar.getHeight() > 0 ? toolbar.getHeight() : toolbar.getPrefHeight();
    }

    public void openSelectedSignalsPopupForTest() {
        if (!selectedSignalsPopup.isShowing()) {
            toggleSelectedSignalsPopup();
        }
    }

    public int selectedSignalsPopupCountForTest() {
        return selectedSignalsList.getItems().size();
    }

    public void updateAnalysisContext(SignalsProcessingContext context, int catalogSignalCount) {
        this.analysisContext = context == null ? SignalsProcessingContext.empty() : context;
        this.availableSignalCount = Math.max(0, catalogSignalCount);
        refreshEmptyContext();
        if (model != null && !model.workspaceKeys().isEmpty()) {
            refreshSummaryBar(null);
        }
    }

    public void addEntries(List<SignalCatalogEntry> entries) {
        if (model == null || entries == null || entries.isEmpty()) {
            return;
        }
        int added = model.addToWorkspace(entries.stream().map(SignalCatalogEntry::key).toList());
        applyAddFeedback(added);
        reload(true);
        onWorkspaceChanged.run();
    }

    /**
     * Records membership on the click, then builds columns on the following pulse.
     * The checkbox can paint before the table structure changes. A second click before
     * that pulse updates the model and is included in the single queued reload.
     */
    public void addEntriesAfterPaint(List<SignalCatalogEntry> entries) {
        if (model == null || entries == null || entries.isEmpty()) {
            return;
        }
        int added = model.addToWorkspace(entries.stream().map(SignalCatalogEntry::key).toList());
        applyAddFeedback(added);
        enqueueStructureReload();
    }

    public void addPhysicalKeysAfterPaint(List<SignalCatalogKey> keys) {
        if (model == null || keys == null || keys.isEmpty()) {
            return;
        }
        int added = model.addPhysicalKeys(keys);
        applyAddFeedback(added);
        enqueueStructureReload();
    }

    public void removePhysicalKeysAfterPaint(List<SignalCatalogKey> keys) {
        if (model == null || keys == null || keys.isEmpty()) {
            return;
        }
        model.removePhysicalKeys(keys);
        enqueueStructureReload();
    }

    private void enqueueStructureReload() {
        if (structureReloadQueued) {
            return;
        }
        structureReloadQueued = true;
        Scene scene = getScene();
        if (scene == null || scene.getWindow() == null) {
            Platform.runLater(this::runStructureReload);
            return;
        }
        scene.addPostLayoutPulseListener(new Runnable() {
            @Override
            public void run() {
                scene.removePostLayoutPulseListener(this);
                Platform.runLater(ActiveSignalWorkspacePane.this::runStructureReload);
            }
        });
    }

    private void runStructureReload() {
        structureReloadQueued = false;
        if (disposed || model == null) {
            return;
        }
        reload(true);
        onWorkspaceChanged.run();
    }

    /**
     * One membership update and one sample reload for the current filtered physical keys.
     */
    public void addPhysicalKeys(List<SignalCatalogKey> keys) {
        if (model == null || keys == null || keys.isEmpty()) {
            return;
        }
        int added = model.addPhysicalKeys(keys);
        applyAddFeedback(added);
        reload(true);
        onWorkspaceChanged.run();
    }

    public void removePhysicalKeys(List<SignalCatalogKey> keys) {
        if (model == null || keys == null || keys.isEmpty()) {
            return;
        }
        model.removePhysicalKeys(keys);
        reload(true);
        onWorkspaceChanged.run();
    }

    public void addKeys(List<SignalCatalogKey> keys) {
        if (model == null || keys == null || keys.isEmpty()) {
            return;
        }
        int added = model.addToWorkspace(keys);
        applyAddFeedback(added);
        reload(true);
        onWorkspaceChanged.run();
    }

    private void applyAddFeedback(int added) {
        String limitFeedback = model == null ? "" : model.lastWorkspaceAddFeedback();
        if (limitFeedback != null && !limitFeedback.isBlank()) {
            showFeedback(limitFeedback);
        } else if (added == 0) {
            showFeedback("Signal already in workspace");
        } else {
            clearFeedback();
        }
    }

    public void reload() {
        reload(true);
    }

    public int reloadCountForTest() {
        return reloadCount;
    }

    public WorkspaceDisplayMode displayModeForTest() {
        return displayModeBox.getValue();
    }

    public int rowsValueForTest() {
        return rowsBox.getValue() == null ? 0 : rowsBox.getValue();
    }

    public boolean footerTimingWrappedForTest() {
        return footerTimingWrapped;
    }

    public ScrollPane chipScrollForTest() {
        return chipScroll;
    }

    public Button nextWindowButtonForTest() {
        return nextWindowButton;
    }

    public MenuButton exportCsvButtonForTest() {
        return exportCsvButton;
    }

    public String pageLabelForTest() {
        return pageLabel.getText();
    }

    public void reload(boolean forceSeriesReload) {
        reloadCount++;
        if (disposed || model == null) {
            return;
        }
        // workspaceKeys is the single membership SoT; workspaceEntries() always mirrors it 1:1.
        List<SignalCatalogKey> membershipKeys = model.workspaceKeys();
        List<SignalCatalogEntry> membership = model.workspaceEntries();
        syncMembershipChrome(membership);
        boolean empty = membershipKeys.isEmpty();
        showEmpty(empty);
        if (empty) {
            table.getItems().clear();
            table.getColumns().clear();
            exportButton.setDisable(true);
            lastColumnKeys = List.of();
            rangeLabel.setText("");
            updateWindowNavVisibility(AlignedWorkspaceSnapshot.empty());
            refreshEmptyContext();
            clearSummaryBar();
            setBusy(false);
            return;
        }
        if (!forceSeriesReload) {
            Optional<AlignedWorkspaceSnapshot> cached = model.reusableWorkspaceSnapshot();
            if (cached.isPresent()) {
                // Returning from Graphs builds a new table. The cached page is applied once.
                // A later refresh must not clear and rebuild that page or the grid flashes blank.
                if (!snapshotAlreadyShown(cached.get())) {
                    applySnapshot(cached.get());
                }
                setBusy(false);
                return;
            }
        }
        // Membership chrome (chips / N signals / columns) must track workspaceKeys immediately.
        // Sample rows fill in asynchronously — never leave "1 signal" chips beside "0 signals".
        // Drop the previous page before adding a column so the table does not reformat every
        // visible cell of the old rows against the new membership.
        if (forceSeriesReload) {
            table.getItems().clear();
            lastSnap = null;
        }
        syncColumns(membership);
        setBusy(true);
        final int serial = ++applySerial;
        model.loadWorkspaceAlignedAsync(forceSeriesReload, snap -> Platform.runLater(() -> {
            if (disposed || serial != applySerial) {
                return;
            }
            applySnapshot(snap);
            setBusy(false);
        }));
    }

    /**
     * Focus-only visual refresh. Never touches membership chrome (chips/title/status/columns) —
     * those update only through {@link #syncMembershipChrome} / {@link #reload}.
     */
    public void refreshFocusPresentation() {
        if (model == null) {
            return;
        }
        applyFocusedChipStyles(model.focusedWorkspaceKey());
        applyFocusedColumnStyles(model.focusedWorkspaceKey());
    }

    /**
     * Single path for membership chrome: chips, title, status "N signals", and (via callers)
     * table columns / empty presentation. Never a chips-only update.
     */
    private void syncMembershipChrome(List<SignalCatalogEntry> membership) {
        List<SignalCatalogEntry> safe = membership == null ? List.of() : membership;
        renderChips(safe);
        int n = safe.size();
        titleLabel.setText("Active Signal Workspace (" + n + (n == 1 ? " signal)" : " signals)"));
        refreshSummaryBar(null);
    }

    /** True when this table is already showing the cached page, so a refresh must not rebuild it. */
    private boolean snapshotAlreadyShown(AlignedWorkspaceSnapshot snap) {
        if (snap == null || snap.rows().isEmpty() || table.getItems().isEmpty()) {
            return false;
        }
        if (!columnsRepresentMembership(model.workspaceKeys())) {
            return false;
        }
        return table.getItems().equals(snap.rows());
    }

    private void applySnapshot(AlignedWorkspaceSnapshot snap) {
        if (disposed || model == null) {
            return;
        }
        List<SignalCatalogKey> membershipKeys = model.workspaceKeys();
        List<SignalCatalogEntry> membership = model.workspaceEntries();
        // Reconcile chrome from live keys first — async callbacks must not diverge chips vs columns.
        syncMembershipChrome(membership);
        if (membershipKeys.isEmpty()) {
            showEmpty(true);
            table.getItems().clear();
            table.getColumns().clear();
            exportButton.setDisable(true);
            lastColumnKeys = List.of();
            rangeLabel.setText("");
            updateWindowNavVisibility(AlignedWorkspaceSnapshot.empty());
            clearSummaryBar();
            return;
        }
        showEmpty(false);
        List<SignalCatalogEntry> columnEntries = membership;
        List<SignalCatalogKey> columnKeys = columnEntries.stream().map(SignalCatalogEntry::key).toList();
        if (!snap.signals().stream().map(SignalCatalogEntry::key).toList().equals(membershipKeys)) {
            refreshSummaryBar(null);
            return;
        }
        if (!columnsRepresentMembership(columnKeys)) {
            syncColumns(columnEntries);
        } else {
            applyFocusedColumnStyles(model.focusedWorkspaceKey());
        }
        lastSnap = snap;
        table.getItems().setAll(snap.rows());
        exportButton.setDisable(snap.rows().isEmpty());
        updateRangeLabel(snap);
        updateWindowNavVisibility(snap);
        refreshSummaryBar(snap);
        updateTableFooter();
        String unavailable = model.lastWorkspaceDataFeedback();
        if (unavailable != null && !unavailable.isBlank()) {
            showFeedback(unavailable);
        }
        snap.focusRowIndex().ifPresent(local -> {
            int idx = (int) local;
            if (idx >= 0 && idx < table.getItems().size()) {
                table.getSelectionModel().clearAndSelect(idx);
                table.scrollTo(idx);
            }
        });
    }

    /**
     * True when table signal columns (by userData key) exactly match {@code membershipKeys}
     * in order — never trust {@link #lastColumnKeys} alone.
     */
    private boolean columnsRepresentMembership(List<SignalCatalogKey> membershipKeys) {
        if (membershipKeys == null) {
            return false;
        }
        if (!membershipKeys.equals(lastColumnKeys)) {
            return false;
        }
        List<SignalCatalogKey> installed = installedSignalColumnKeys();
        return installed.equals(membershipKeys);
    }

    private List<SignalCatalogKey> installedSignalColumnKeys() {
        List<SignalCatalogKey> keys = new java.util.ArrayList<>();
        for (TableColumn<AlignedSampleRow, ?> col : table.getColumns()) {
            Object data = col.getUserData();
            if (data instanceof SignalCatalogKey key) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }

    private void updateRangeLabel(AlignedWorkspaceSnapshot snap) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        if (snap.rows().isEmpty()) {
            rangeLabel.setText("No rows loaded");
            return;
        }
        String range = "Rows " + nf.format(snap.firstGlobalIndex())
                + "\u2013" + nf.format(snap.lastGlobalIndex());
        if (snap.totalAlignedRows() > 0) {
            range += " of " + nf.format(snap.totalAlignedRows());
        }
        rangeLabel.setText(range);
        rangeLabel.setTooltip(new Tooltip(
                nf.format(snap.rows().size()) + " rows currently displayed"));
    }

    private void updateWindowNavVisibility(AlignedWorkspaceSnapshot snap) {
        boolean showPrev = snap.hasPrevious();
        boolean showNext = snap.hasNext();
        firstWindowButton.setDisable(!showPrev);
        prevWindowButton.setDisable(!showPrev);
        nextWindowButton.setDisable(!showNext);
        lastWindowButton.setDisable(!showNext);
        int pageSize = Math.max(1, snap.pageSize());
        int total = Math.max(0, snap.totalAlignedRows());
        int pages = total <= 0 ? 1 : ((total + pageSize - 1) / pageSize);
        int page = Math.min(pages, (snap.windowStartIndex() / pageSize) + 1);
        pageLabel.setText(page + " / " + pages);
        boolean showNav = total > pageSize || showPrev || showNext;
        windowNavBox.setVisible(showNav);
        windowNavBox.setManaged(showNav);
        firstWindowButton.setVisible(true);
        firstWindowButton.setManaged(true);
        prevWindowButton.setVisible(true);
        prevWindowButton.setManaged(true);
        nextWindowButton.setVisible(true);
        nextWindowButton.setManaged(true);
        lastWindowButton.setVisible(true);
        lastWindowButton.setManaged(true);
    }

    /**
     * Keeps signal columns aligned with workspace membership.
     * A column reads its signal by identity, so an add, removal, or reorder reuses the columns
     * that are already showing the surviving signals.
     */
    private void syncColumns(List<SignalCatalogEntry> signals) {
        List<SignalCatalogEntry> nextEntries = signals == null ? List.of() : signals;
        List<SignalCatalogKey> next = nextEntries.stream().map(SignalCatalogEntry::key).toList();
        List<SignalCatalogKey> installed = installedSignalColumnKeys();
        if (!fixedColumnsInstalled() || !signalTailMatches(installed)) {
            rebuildColumns(nextEntries);
            lastColumnKeys = next;
            return;
        }
        if (installed.equals(next)) {
            refreshIndexColumnTitle(nextEntries.size());
            applyFocusedColumnStyles(focusedWorkspaceKey());
            lastColumnKeys = next;
            return;
        }
        int from = table.getColumns().size() - installed.size();
        if (startsWith(next, installed)) {
            lastColumnKeys = next;
            for (int i = installed.size(); i < nextEntries.size(); i++) {
                addSignalColumn(nextEntries.get(i));
            }
            refreshIndexColumnTitle(nextEntries.size());
            applyFocusedColumnStyles(focusedWorkspaceKey());
            return;
        }
        if (startsWith(installed, next)) {
            lastColumnKeys = next;
            table.getColumns().remove(table.getColumns().size() - (installed.size() - next.size()), table.getColumns().size());
            refreshIndexColumnTitle(nextEntries.size());
            applyFocusedColumnStyles(focusedWorkspaceKey());
            return;
        }
        if (preservesOrder(installed, next)) {
            java.util.Set<SignalCatalogKey> keep = new java.util.HashSet<>(next);
            for (int i = installed.size() - 1; i >= 0; i--) {
                if (!keep.contains(installed.get(i))) {
                    table.getColumns().remove(from + i);
                }
            }
            lastColumnKeys = next;
            refreshIndexColumnTitle(nextEntries.size());
            applyFocusedColumnStyles(focusedWorkspaceKey());
            return;
        }
        java.util.Map<SignalCatalogKey, TableColumn<AlignedSampleRow, ?>> kept = new java.util.LinkedHashMap<>();
        for (int i = 0; i < installed.size(); i++) {
            kept.put(installed.get(i), table.getColumns().get(from + i));
        }
        lastColumnKeys = next;
        if (from < table.getColumns().size()) {
            table.getColumns().remove(from, table.getColumns().size());
        }
        for (SignalCatalogEntry entry : nextEntries) {
            TableColumn<AlignedSampleRow, ?> existing = kept.remove(entry.key());
            if (existing != null) {
                table.getColumns().add(existing);
            } else {
                addSignalColumn(entry);
            }
        }
        refreshIndexColumnTitle(nextEntries.size());
        applyFocusedColumnStyles(focusedWorkspaceKey());
    }

    /** {@code longer} begins with every key of {@code prefix}, in order. */
    private static boolean startsWith(List<SignalCatalogKey> longer, List<SignalCatalogKey> prefix) {
        if (prefix.size() > longer.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(longer.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code next} is {@code installed} with some keys removed and the survivors left in order. */
    private static boolean preservesOrder(List<SignalCatalogKey> installed, List<SignalCatalogKey> next) {
        int cursor = 0;
        for (SignalCatalogKey key : installed) {
            if (cursor < next.size() && key.equals(next.get(cursor))) {
                cursor++;
            }
        }
        return cursor == next.size();
    }

    private Optional<SignalCatalogKey> focusedWorkspaceKey() {
        return model == null ? Optional.empty() : model.focusedWorkspaceKey();
    }

    private boolean fixedColumnsInstalled() {
        return indexColumn != null
                && timestampColumn != null
                && deltaColumn != null
                && table.getColumns().contains(indexColumn)
                && table.getColumns().contains(timestampColumn)
                && table.getColumns().contains(deltaColumn);
    }

    /** Signal columns are a suffix of the table, in membership order. */
    private boolean signalTailMatches(List<SignalCatalogKey> installed) {
        if (installed.size() > table.getColumns().size()) {
            return false;
        }
        int from = table.getColumns().size() - installed.size();
        for (int i = 0; i < installed.size(); i++) {
            if (!installed.get(i).equals(table.getColumns().get(from + i).getUserData())) {
                return false;
            }
        }
        return true;
    }

    private void refreshIndexColumnTitle(int signalColumns) {
        if (indexColumn == null) {
            return;
        }
        String title = indexColumnTitle(signalColumns);
        indexColumn.setText(title);
        if (indexColumnItem != null) {
            indexColumnItem.setText(title);
        }
    }

    private void rebuildColumns(List<SignalCatalogEntry> signals) {
        focusStyleDirty = true;
        table.getColumns().clear();
        // Stable professional widths — never flex a lone signal across the workspace.
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        indexColumn = new TableColumn<>(indexColumnTitle(signals.size()));
        if (indexColumnItem != null) {
            indexColumnItem.setText(indexColumn.getText());
        }
        indexColumn.getStyleClass().add("workspace-index-column");
        indexColumn.setMinWidth(SignalsLayoutMetrics.INDEX_COL_MIN);
        indexColumn.setPrefWidth(SignalsLayoutMetrics.INDEX_COL_PREF);
        indexColumn.setMaxWidth(SignalsLayoutMetrics.INDEX_COL_MAX);
        indexColumn.setResizable(true);
        indexColumn.setSortable(false);
        indexColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(Long.toString(c.getValue().index())));
        indexColumn.setCellFactory(col -> new TableCell<>() {
            {
                setAlignment(Pos.CENTER_RIGHT);
                getStyleClass().add("workspace-index-cell");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                setText(item);
            }
        });
        table.getColumns().add(indexColumn);

        timestampColumn = new TableColumn<>("Timestamp (s)");
        timestampColumn.getStyleClass().add("workspace-timestamp-column");
        timestampColumn.setMinWidth(SignalsLayoutMetrics.TIMESTAMP_COL_MIN);
        timestampColumn.setPrefWidth(SignalsLayoutMetrics.TIMESTAMP_COL_PREF);
        timestampColumn.setMaxWidth(SignalsLayoutMetrics.TIMESTAMP_COL_MAX);
        timestampColumn.setResizable(true);
        timestampColumn.setSortable(false);
        timestampColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(UiFormat.nanos(c.getValue().timestampNanos())));
        timestampColumn.setCellFactory(col -> new TableCell<>() {
            private final Tooltip tip = new Tooltip();

            {
                setAlignment(Pos.CENTER_LEFT);
                getStyleClass().add("workspace-timestamp-cell");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                AlignedSampleRow row = getTableRow().getItem();
                setText(UiFormat.nanos(row.timestampNanos()));
                tip.setText(UiFormat.nanosExactTooltip(row.timestampNanos()));
                setTooltip(tip);
            }
        });
        table.getColumns().add(timestampColumn);

        deltaColumn = new TableColumn<>("\u0394t (ms)");
        deltaColumn.getStyleClass().add("workspace-delta-column");
        deltaColumn.setMinWidth(SignalsLayoutMetrics.DELTA_COL_MIN);
        deltaColumn.setPrefWidth(SignalsLayoutMetrics.DELTA_COL_PREF);
        deltaColumn.setMaxWidth(SignalsLayoutMetrics.DELTA_COL_MAX);
        deltaColumn.setResizable(true);
        deltaColumn.setSortable(false);
        deltaColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(""));
        deltaColumn.setCellFactory(col -> new TableCell<>() {
            {
                setAlignment(Pos.CENTER_RIGHT);
                getStyleClass().add("workspace-delta-cell");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("missing-value", "numeric-value");
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                int idx = getIndex();
                if (idx <= 0 || getTableView() == null || idx >= getTableView().getItems().size()) {
                    setText(AlignedSampleRow.MISSING);
                    getStyleClass().add("missing-value");
                    return;
                }
                AlignedSampleRow curr = getTableView().getItems().get(idx);
                AlignedSampleRow prev = getTableView().getItems().get(idx - 1);
                // Display-only delta from native timestamps in the current page. Never mutates model.
                setText(formatDeltaMillis(curr.timestampNanos() - prev.timestampNanos()));
                getStyleClass().add("numeric-value");
            }
        });
        Label deltaHeader = new Label("\u0394t (ms)");
        deltaHeader.getStyleClass().add("workspace-plain-header");
        deltaHeader.setAlignment(Pos.CENTER);
        deltaHeader.setMaxWidth(Double.MAX_VALUE);
        deltaColumn.setGraphic(deltaHeader);
        deltaColumn.setText(null);
        table.getColumns().add(deltaColumn);

        for (int i = 0; i < signals.size(); i++) {
            addSignalColumn(signals.get(i));
        }
        lastColumnKeys = signals.stream().map(SignalCatalogEntry::key).toList();

        Label tsHeader = new Label("Timestamp (s)");
        tsHeader.getStyleClass().add("workspace-plain-header");
        tsHeader.setAlignment(Pos.CENTER);
        tsHeader.setMaxWidth(Double.MAX_VALUE);
        timestampColumn.setGraphic(tsHeader);
        timestampColumn.setText(null);
        applyColumnVisibility();
    }

    private void applyColumnVisibility() {
        if (indexColumn != null) {
            indexColumn.setVisible(indexColumnItem.isSelected());
        }
        if (timestampColumn != null) {
            timestampColumn.setVisible(timestampColumnItem.isSelected());
        }
        if (deltaColumn != null) {
            deltaColumn.setVisible(deltaColumnItem.isSelected());
        }
    }

    private int columnIndex(SignalCatalogKey key) {
        if (key == null) {
            return -1;
        }
        for (int i = 0; i < lastColumnKeys.size(); i++) {
            if (key.equals(lastColumnKeys.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private void addSignalColumn(SignalCatalogEntry entry) {
        String details = columnIdentityTooltip(entry);
        SignalCatalogKey key = entry.key();
        TableColumn<AlignedSampleRow, String> col = new TableColumn<>();
        col.getStyleClass().add("workspace-signal-column");
        col.setMinWidth(SignalsLayoutMetrics.SIGNAL_COL_MIN);
        col.setPrefWidth(SignalsLayoutMetrics.SIGNAL_COL_PREF);
        col.setMaxWidth(SignalsLayoutMetrics.SIGNAL_COL_MAX);
        col.setResizable(true);
        col.setSortable(false);
        col.setUserData(key);
        col.setCellValueFactory(c -> new ReadOnlyStringWrapper(formatCell(c.getValue(), columnIndex(key))));
        col.setCellFactory(c -> new TableCell<>() {
            private final Tooltip tip = new Tooltip(details);

            {
                setTextOverrun(OverrunStyle.ELLIPSIS);
                setAlignment(Pos.CENTER_RIGHT);
                getStyleClass().add("workspace-signal-cell");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("missing-value", "numeric-value");
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                setText(item);
                if (AlignedSampleRow.MISSING.equals(item)) {
                    getStyleClass().add("missing-value");
                    tip.setText("No sample for this signal at this timestamp.\n" + details);
                } else {
                    getStyleClass().add("numeric-value");
                    tip.setText(item + "\n" + details);
                }
                setTooltip(tip);
            }
        });
        col.setGraphic(buildSignalColumnHeader(entry));
        col.setText(null);
        if (focusedWorkspaceKey().filter(entry.key()::equals).isPresent()) {
            col.getStyleClass().add("focused-signal-column");
        }
        table.getColumns().add(col);
    }

    private VBox buildSignalColumnHeader(SignalCatalogEntry entry) {
        VBox headerBox = new VBox(2);
        headerBox.setAlignment(Pos.CENTER);
        headerBox.getStyleClass().add("workspace-column-header");
        headerBox.setFillWidth(true);

        Tooltip headerTip = new Tooltip(SignalRowTooltip.text(entry));
        headerTip.setWrapText(true);
        headerTip.setMaxWidth(420);
        Label nameLabel = new Label(entry.signalName());
        nameLabel.getStyleClass().add("workspace-column-header-name");
        nameLabel.setTooltip(headerTip);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.setWrapText(false);
        nameLabel.setAlignment(Pos.CENTER);
        nameLabel.setMinHeight(Region.USE_PREF_SIZE);
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        headerBox.getChildren().add(nameLabel);
        headerBox.setMinHeight(Region.USE_PREF_SIZE);
        Tooltip.install(headerBox, headerTip);

        String channelMeta = headerChannelMeta(entry);
        if (!channelMeta.isBlank()) {
            Label metaLabel = new Label(channelMeta);
            metaLabel.getStyleClass().add("workspace-column-header-meta");
            metaLabel.setTextOverrun(OverrunStyle.CLIP);
            metaLabel.setEllipsisString("");
            metaLabel.setWrapText(false);
            metaLabel.setMinHeight(Region.USE_PREF_SIZE);
            metaLabel.setAlignment(Pos.CENTER);
            metaLabel.setMaxWidth(Double.MAX_VALUE);
            headerBox.getChildren().add(metaLabel);
        }

        String unit = entry.unit() == null ? "" : entry.unit().trim();
        if (!unit.isEmpty()) {
            Label unitLabel = new Label(unit);
            unitLabel.getStyleClass().add("workspace-column-header-unit");
            unitLabel.setTextOverrun(OverrunStyle.CLIP);
            unitLabel.setEllipsisString("");
            unitLabel.setWrapText(false);
            unitLabel.setMinHeight(Region.USE_PREF_SIZE);
            unitLabel.setAlignment(Pos.CENTER);
            unitLabel.setMaxWidth(Double.MAX_VALUE);
            headerBox.getChildren().add(unitLabel);
        }

        headerBox.setOnMouseClicked(e -> {
            if (model != null) {
                model.setFocusedSignalKey(entry.key());
                onFocusChanged.run();
                reloadFocusedSignalIfNeeded();
            }
        });
        return headerBox;
    }

    /** Available channel metadata only — never invents a Bus id. */
    private static String headerChannelMeta(SignalCatalogEntry entry) {
        if (entry == null
                || entry.sourceType() != com.stellar.platform.domain.catalog.SignalSourceType.DBC_CAN) {
            return "";
        }
        return "Ch " + entry.key().channel();
    }

    /** Display-only Δt formatting. Does not mutate timestamps. */
    static String formatDeltaMillis(long deltaNanos) {
        boolean negative = deltaNanos < 0;
        long abs = negative ? -deltaNanos : deltaNanos;
        long wholeMs = abs / 1_000_000L;
        long remNanos = abs % 1_000_000L;
        if (remNanos == 0L) {
            return (negative ? "-" : "") + Long.toString(wholeMs);
        }
        String frac = String.format(Locale.ROOT, "%06d", remNanos);
        int end = frac.length();
        while (end > 1 && frac.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 3) {
            end = 3;
        }
        return (negative ? "-" : "") + wholeMs + '.' + frac.substring(0, end);
    }

    private static String columnIdentityTooltip(SignalCatalogEntry entry) {
        String channel = SignalChannelLabels.full(entry);
        String unit = entry.unit() == null || entry.unit().isBlank() ? "—" : entry.unit();
        return entry.signalName()
                + "\nChannel: " + channel
                + "\nMessage: " + entry.key().messageName()
                + "\nUnit: " + unit;
    }

    private String formatCell(AlignedSampleRow row, int signalIndex) {
        if (row == null || signalIndex < 0) {
            return AlignedSampleRow.MISSING;
        }
        if (displayMode == WorkspaceDisplayMode.RAW) {
            return SampleDisplay.raw(row.rawAt(signalIndex), row.textualAt(signalIndex), precision);
        }
        return SampleDisplay.physical(
                row.physicalAt(signalIndex),
                row.rawAt(signalIndex),
                row.textualAt(signalIndex),
                row.validityAt(signalIndex),
                precision);
    }

    private void applyFocusedColumnStyles(Optional<SignalCatalogKey> focused) {
        SignalCatalogKey next = focused.orElse(null);
        if (!focusStyleDirty && java.util.Objects.equals(styledFocus, next)) {
            return;
        }
        focusStyleDirty = false;
        styledFocus = next;
        for (TableColumn<AlignedSampleRow, ?> col : table.getColumns()) {
            col.getStyleClass().remove("focused-signal-column");
            Object data = col.getUserData();
            if (focused.isPresent() && focused.get().equals(data)) {
                col.getStyleClass().add("focused-signal-column");
            }
        }
    }

    /** Updates chip focus styling in place — does not recreate membership chips. */
    private void applyFocusedChipStyles(Optional<SignalCatalogKey> focused) {
        SignalCatalogKey focusKey = focused.orElse(null);
        for (javafx.scene.Node node : chipBar.getChildren()) {
            if (!node.getStyleClass().contains("signal-chip")) {
                continue;
            }
            node.getStyleClass().remove("signal-chip-focused");
            if (focusKey != null && focusKey.equals(node.getUserData())) {
                node.getStyleClass().add("signal-chip-focused");
            }
        }
    }

    private void renderChips(List<SignalCatalogEntry> entries) {
        lastMembership = entries == null ? List.of() : List.copyOf(entries);
        if (disposed) {
            chipBar.getChildren().clear();
            return;
        }
        List<SignalCatalogKey> desired = lastMembership.stream().map(SignalCatalogEntry::key).toList();
        List<SignalCatalogKey> installed = chipKeys();
        if (!installed.isEmpty() && installed.equals(desired) && chipBar.getChildren().size() == installed.size()) {
            applyFocusedChipStyles(model == null ? Optional.empty() : model.focusedWorkspaceKey());
            return;
        }
        if (!installed.isEmpty() && startsWith(desired, installed) && chipBar.getChildren().size() == installed.size()) {
            for (int i = installed.size(); i < lastMembership.size(); i++) {
                chipBar.getChildren().add(buildChip(lastMembership.get(i)));
            }
            Platform.runLater(this::refreshChipScrollPolicy);
            return;
        }
        if (!installed.isEmpty() && preservesOrder(installed, desired) && chipBar.getChildren().size() == installed.size()) {
            java.util.Set<SignalCatalogKey> keep = new java.util.HashSet<>(desired);
            for (int i = chipBar.getChildren().size() - 1; i >= 0; i--) {
                javafx.scene.Node node = chipBar.getChildren().get(i);
                if (node.getUserData() instanceof SignalCatalogKey key && !keep.contains(key)) {
                    chipBar.getChildren().remove(i);
                }
            }
            Platform.runLater(this::refreshChipScrollPolicy);
            return;
        }
        chipBar.getChildren().clear();
        int preview = previewChipLimit();
        int shown = Math.min(preview, lastMembership.size());
        for (int i = 0; i < shown; i++) {
            chipBar.getChildren().add(buildChip(lastMembership.get(i)));
        }
        int hidden = lastMembership.size() - shown;
        if (hidden > 0) {
            moreSignalsButton.setText("+" + hidden + " more");
            moreSignalsButton.setTooltip(new Tooltip(
                    hidden + (hidden == 1 ? " more selected signal" : " more selected signals")));
            chipBar.getChildren().add(moreSignalsButton);
        } else {
            hideSelectedSignalsPopup();
        }
        Platform.runLater(this::refreshChipScrollPolicy);
    }

    private List<SignalCatalogKey> chipKeys() {
        List<SignalCatalogKey> keys = new java.util.ArrayList<>();
        for (javafx.scene.Node node : chipBar.getChildren()) {
            if (node.getUserData() instanceof SignalCatalogKey key) {
                keys.add(key);
            }
        }
        return keys;
    }

    private HBox buildChip(SignalCatalogEntry entry) {
            Optional<SignalCatalogKey> focused = model == null ? Optional.empty() : model.focusedWorkspaceKey();
            HBox chip = new HBox(6);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.setMinHeight(SignalsLayoutMetrics.CHIP_HEIGHT);
            chip.setPrefHeight(SignalsLayoutMetrics.CHIP_HEIGHT);
            chip.setMaxHeight(SignalsLayoutMetrics.CHIP_HEIGHT);
            chip.setMinWidth(SignalsLayoutMetrics.CHIP_MIN_WIDTH);
            chip.setPrefWidth(SignalsLayoutMetrics.CHIP_PREF_WIDTH);
            chip.setMaxWidth(SignalsLayoutMetrics.CHIP_MAX_WIDTH);
            chip.getStyleClass().add("signal-chip");
            chip.setUserData(entry.key());
            chip.setFocusTraversable(true);
            if (focused.isPresent() && focused.get().equals(entry.key())) {
                chip.getStyleClass().add("signal-chip-focused");
            }
            Label name = new Label(entry.signalName());
            name.getStyleClass().add("signal-chip-name");
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            name.setWrapText(false);
            name.setMinWidth(48);
            name.setMaxWidth(SignalsLayoutMetrics.CHIP_NAME_MAX_WIDTH);
            HBox.setHgrow(name, Priority.NEVER);
            Tooltip chipTip = new Tooltip(SignalRowTooltip.text(entry));
            chipTip.setWrapText(true);
            chipTip.setMaxWidth(420);
            name.setTooltip(chipTip);
            Tooltip.install(chip, chipTip);
            chip.getChildren().add(name);
            Label badge = new Label(SignalBrowserFilter.sourceBadge(entry.sourceType()));
            badge.getStyleClass().addAll("source-badge", "signal-source-badge", "badge",
                    SignalBrowserFilter.sourceStyleClass(entry.sourceType()));
            if (entry.sourceType() == com.stellar.platform.domain.catalog.SignalSourceType.DBC_CAN) {
                badge.getStyleClass().addAll("badge-dbc", "dbc-badge");
            } else if (entry.sourceType() == com.stellar.platform.domain.catalog.SignalSourceType.MF4_NATIVE) {
                badge.getStyleClass().addAll("badge-native", "native-badge");
            }
            badge.setMinWidth(Region.USE_PREF_SIZE);
            chip.getChildren().add(badge);
            String channelCompact = SignalChannelLabels.compact(entry);
            if (!"—".equals(channelCompact)) {
                Label channel = new Label(channelCompact);
                channel.getStyleClass().add("signal-chip-channel");
                channel.setMinWidth(Region.USE_PREF_SIZE);
                chip.getChildren().add(channel);
            }
            Button remove = new Button("×");
            remove.getStyleClass().add("chip-remove");
            remove.setMinWidth(Region.USE_PREF_SIZE);
            remove.setMinHeight(SignalsLayoutMetrics.CHIP_HEIGHT - 4);
            remove.setFocusTraversable(false);
            remove.setTooltip(new Tooltip("Remove " + entry.signalName()));
            remove.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
            remove.setOnAction(e -> removeChip(entry.key()));
            ContextMenu chipMenu = new ContextMenu();
            MenuItem copyName = new MenuItem("Copy Signal Name");
            copyName.setOnAction(e -> copyTextToClipboard(entry.signalName()));
            chipMenu.getItems().setAll(copyName);
            chip.setOnContextMenuRequested(e -> {
                chipMenu.show(chip, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            chip.setOnMouseClicked(e -> {
                if (model != null) {
                    model.setFocusedSignalKey(entry.key());
                    onFocusChanged.run();
                    reloadFocusedSignalIfNeeded();
                    chip.requestFocus();
                }
            });
            chip.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                    removeChip(entry.key());
                    e.consume();
                }
            });
            chip.getChildren().add(remove);
            return chip;
    }

    private int previewChipLimit() {
        // One horizontal strip. Extra signals scroll; they do not wrap or collapse into "+N more".
        return Integer.MAX_VALUE;
    }

    private void buildSelectedSignalsPopup() {
        selectedSignalsList.setPrefWidth(340);
        selectedSignalsList.setPrefHeight(280);
        selectedSignalsList.setMinHeight(120);
        selectedSignalsList.setCellFactory(list -> new ListCell<>() {
            private final Button remove = new Button("Remove");
            {
                remove.getStyleClass().addAll("compact-button", "secondary-button");
                remove.setOnAction(e -> {
                    SignalCatalogEntry item = getItem();
                    if (item != null) {
                        removeChip(item.key());
                    }
                });
            }

            @Override
            protected void updateItem(SignalCatalogEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item.signalName());
                setGraphic(remove);
            }
        });
        Label heading = new Label("Selected signals");
        heading.getStyleClass().add("section-subtitle");
        VBox box = new VBox(8, heading, selectedSignalsList);
        box.getStyleClass().add("selected-signals-popover");
        box.setPadding(new Insets(10));
        box.setPrefWidth(360);
        selectedSignalsPopup.getContent().setAll(box);
        selectedSignalsPopup.setAutoHide(true);
    }

    private void toggleSelectedSignalsPopup() {
        if (selectedSignalsPopup.isShowing()) {
            hideSelectedSignalsPopup();
            return;
        }
        selectedSignalsList.getItems().setAll(lastMembership);
        var screen = moreSignalsButton.localToScreen(0, moreSignalsButton.getHeight());
        if (screen == null) {
            selectedSignalsPopup.show(moreSignalsButton, 0, 0);
            return;
        }
        selectedSignalsPopup.show(moreSignalsButton, screen.getX(), screen.getY());
    }

    private void hideSelectedSignalsPopup() {
        if (selectedSignalsPopup.isShowing()) {
            selectedSignalsPopup.hide();
        }
    }

    private void removeChip(SignalCatalogKey key) {
        if (model == null) {
            return;
        }
        model.removeFromWorkspace(key);
        reload(true);
        onWorkspaceChanged.run();
    }

    private void navigatePrevious() {
        if (disposed || model == null) {
            return;
        }
        clearFeedback();
        setBusy(true);
        final int serial = ++applySerial;
        model.navigatePreviousWindowAsync(snap -> Platform.runLater(() -> {
            if (disposed || serial != applySerial) {
                return;
            }
            applySnapshot(snap);
            setBusy(false);
        }));
    }

    private void navigateNext() {
        if (disposed || model == null) {
            return;
        }
        clearFeedback();
        setBusy(true);
        final int serial = ++applySerial;
        model.navigateNextWindowAsync(snap -> Platform.runLater(() -> {
            if (disposed || serial != applySerial) {
                return;
            }
            applySnapshot(snap);
            setBusy(false);
        }));
    }

    private void jumpToIndex() {
        if (disposed) {
            return;
        }
        String text = navigateField.getText() == null ? "" : navigateField.getText().trim();
        if (text.isEmpty()) {
            showFeedback("Enter an index");
            return;
        }
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException ex) {
            showFeedback("Invalid index");
            return;
        }
        if (model == null) {
            return;
        }
        final int serial = ++applySerial;
        Optional<String> err = model.jumpToIndexAsync(value, snap -> Platform.runLater(() -> {
            if (disposed || serial != applySerial) {
                return;
            }
            if (snap.totalAlignedRows() > 0 && value >= snap.totalAlignedRows()) {
                showFeedback("Index out of range");
            } else {
                clearFeedback();
            }
            applySnapshot(snap);
            setBusy(false);
        }));
        if (err.isPresent()) {
            showFeedback(err.get());
            return;
        }
        setBusy(true);
    }

    private void jumpToTime() {
        if (disposed) {
            return;
        }
        String text = navigateField.getText() == null ? "" : navigateField.getText().trim();
        if (text.isEmpty()) {
            showFeedback("Enter a timestamp");
            return;
        }
        double seconds;
        try {
            seconds = Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            showFeedback("Invalid timestamp");
            return;
        }
        if (model == null) {
            return;
        }
        final int serial = ++applySerial;
        Optional<String> syncErr = model.jumpToTimeSecondsAsync(seconds, snap -> Platform.runLater(() -> {
            if (disposed || serial != applySerial) {
                return;
            }
            Optional<String> rangeErr = model.validateJumpTimeResult(seconds);
            if (rangeErr.isPresent()) {
                showFeedback(rangeErr.get());
            } else if (snap.focusRowIndex().isEmpty() && snap.totalAlignedRows() > 0) {
                showFeedback("Timestamp outside available range");
            } else {
                clearFeedback();
            }
            applySnapshot(snap);
            setBusy(false);
        }));
        if (syncErr.isPresent()) {
            showFeedback(syncErr.get());
            return;
        }
        setBusy(true);
    }

    private void onTableKey(KeyEvent e) {
        if (e.isControlDown() && e.getCode() == KeyCode.C) {
            if (copySelectedCell()) {
                flashFeedback("Copied");
            }
            e.consume();
        }
    }

    private void installTableContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem copyCell = new MenuItem("Copy Cell");
        MenuItem copyTs = new MenuItem("Copy Timestamp");
        MenuItem copyTable = new MenuItem("Copy Table with Headers");
        copyCell.setOnAction(e -> {
            if (copySelectedCell()) {
                flashFeedback("Copied");
            }
        });
        copyTs.setOnAction(e -> {
            if (copySelectedTimestamp()) {
                flashFeedback("Copied");
            }
        });
        copyTable.setOnAction(e -> copyDisplayedTable());
        menu.getItems().setAll(copyCell, copyTs, new javafx.scene.control.SeparatorMenuItem(), copyTable);
        table.setContextMenu(menu);
        table.setRowFactory(tv -> {
            TableRow<AlignedSampleRow> row = new TableRow<>();
            row.setOnContextMenuRequested(e -> {
                if (!row.isEmpty()) {
                    table.getSelectionModel().clearAndSelect(row.getIndex());
                }
            });
            return row;
        });
    }

    private void exportCsv(boolean selectedOnly) {
        if (disposed) {
            return;
        }
        if (model == null) {
            showFeedback(selectedOnly
                    ? "Select at least one signal to export."
                    : "No signals available to export.");
            return;
        }
        List<SignalCatalogEntry> columns = selectedOnly
                ? model.workspaceEntries()
                : model.signalsForFullExport();
        if (columns.isEmpty()) {
            showFeedback(selectedOnly
                    ? "Select at least one signal to export."
                    : "No signals available to export.");
            return;
        }
        SignalSeriesStore store = model.catalogStore();
        if (store == null) {
            showFeedback("No measurement is loaded.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(selectedOnly ? "Export Selected Signals" : "Export All Signals");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        chooser.setInitialFileName(selectedOnly ? "selected-signals.csv" : "all-signals.csv");
        Window owner = getScene() == null ? null : getScene().getWindow();
        java.io.File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }
        Path target = file.toPath();
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            target = target.resolveSibling(target.getFileName() + ".csv");
        }
        WorkspaceDisplayMode modeAtClick = displayMode == null ? WorkspaceDisplayMode.PHYSICAL : displayMode;
        List<SignalCatalogEntry> exportColumns = List.copyOf(columns);
        Path destination = target;
        exportCancel.set(false);
        exportCsvButton.setDisable(true);
        showFeedback("Exporting CSV…");
        Thread.ofVirtual().name("stellar-export-csv").start(() -> {
            AlignedSignalCsvExporter.ValueMode valueMode = modeAtClick == WorkspaceDisplayMode.RAW
                    ? AlignedSignalCsvExporter.ValueMode.RAW
                    : AlignedSignalCsvExporter.ValueMode.PHYSICAL;
            try {
                AlignedSignalCsvExporter.ExportResult result = new AlignedSignalCsvExporter().export(
                        store, exportColumns, valueMode, destination, exportCancel);
                Platform.runLater(() -> finishExport(result, destination));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (disposed) {
                        return;
                    }
                    exportCsvButton.setDisable(false);
                    String detail = ex.getMessage() == null ? "" : ex.getMessage().trim();
                    showFeedback(detail.isEmpty() ? "Export failed." : "Export failed: " + detail);
                });
            }
        });
    }

    private void finishExport(AlignedSignalCsvExporter.ExportResult result, Path destination) {
        if (disposed) {
            return;
        }
        exportCsvButton.setDisable(false);
        if (result.cancelled()) {
            showFeedback("Export interrupted. No file was saved.");
            return;
        }
        String rows = NumberFormat.getIntegerInstance(Locale.US).format(result.rowCount());
        String name = destination.getFileName() == null ? "CSV" : destination.getFileName().toString();
        showFeedback("Exported " + rows + " rows to " + name);
    }

    private void copyDisplayedTable() {
        AlignedWorkspaceSnapshot snapshot = lastSnap;
        if (snapshot == null || snapshot.rows().isEmpty() || snapshot.signals().isEmpty()) {
            showFeedback("No table rows to copy");
            return;
        }
        WorkspaceDisplayMode modeAtClick = displayMode;
        WorkspacePrecision precisionAtClick = precision;
        boolean includeIndex = indexColumnItem.isSelected();
        boolean includeTimestamp = timestampColumnItem.isSelected();
        boolean includeDelta = deltaColumnItem.isSelected();
        int displayedRows = snapshot.rows().size();
        int totalRows = snapshot.totalAlignedRows();
        String indexHeader = indexColumn == null ? "Index" : indexColumn.getText();

        exportButton.setDisable(true);
        showFeedback("Preparing table for clipboard…");
        Thread.ofVirtual().name("stellar-copy-table").start(() -> {
            final String tsv;
            try {
                tsv = WorkspaceClipboardFormatter.toTsv(
                        snapshot, modeAtClick, precisionAtClick,
                        includeIndex, includeTimestamp, includeDelta, indexHeader);
            } catch (RuntimeException ex) {
                Platform.runLater(() -> {
                    if (disposed) {
                        return;
                    }
                    showFeedback("Could not prepare table for clipboard");
                    exportButton.setDisable(table.getItems().isEmpty());
                });
                return;
            }
            Platform.runLater(() -> {
                if (disposed) {
                    return;
                }
                try {
                    if (copyTextToClipboard(tsv)) {
                        String rows = NumberFormat.getIntegerInstance(Locale.US).format(displayedRows);
                        if (totalRows > displayedRows) {
                            String total = NumberFormat.getIntegerInstance(Locale.US).format(totalRows);
                            flashFeedback("Copied " + rows + " displayed rows + headers (of " + total + ")");
                        } else {
                            flashFeedback("Copied " + rows + " rows + headers");
                        }
                    } else {
                        showFeedback("Nothing to copy");
                    }
                } finally {
                    exportButton.setDisable(table.getItems().isEmpty());
                }
            });
        });
    }

    private boolean copySelectedCell() {
        @SuppressWarnings("rawtypes")
        TablePosition pos = table.getFocusModel().getFocusedCell();
        if (pos == null || pos.getRow() < 0 || pos.getRow() >= table.getItems().size()) {
            return false;
        }
        AlignedSampleRow row = table.getItems().get(pos.getRow());
        TableColumn<AlignedSampleRow, ?> col = pos.getTableColumn();
        if (col == null) {
            return false;
        }
        Object value = col.getCellData(row);
        if (value == null) {
            return false;
        }
        return copyTextToClipboard(String.valueOf(value));
    }

    private boolean copySelectedTimestamp() {
        int rowIdx = table.getSelectionModel().getSelectedIndex();
        if (rowIdx < 0 || rowIdx >= table.getItems().size()) {
            @SuppressWarnings("rawtypes")
            TablePosition pos = table.getFocusModel().getFocusedCell();
            if (pos == null || pos.getRow() < 0 || pos.getRow() >= table.getItems().size()) {
                return false;
            }
            rowIdx = pos.getRow();
        }
        AlignedSampleRow row = table.getItems().get(rowIdx);
        return copyTextToClipboard(UiFormat.nanos(row.timestampNanos()));
    }

    private static boolean copyTextToClipboard(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }

    private void flashFeedback(String message) {
        showFeedback(message);
        feedbackPause.stop();
        feedbackPause.setOnFinished(e -> {
            if (!disposed && message.equals(navFeedback.getText())) {
                clearFeedback();
            }
        });
        feedbackPause.playFromStart();
    }

    private void addBrowserSelection() {
        addEntries(browserSelection.get());
    }

    private void clearAll() {
        if (model == null) {
            return;
        }
        model.clearWorkspace();
        reload(true);
        onWorkspaceChanged.run();
    }

    private void acceptDroppedKeys(List<SignalCatalogKey> keys) {
        addKeys(keys);
    }

    private void showEmpty(boolean empty) {
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        table.setVisible(!empty);
        table.setManaged(!empty);
        // Never force a hard minHeight that can overflow the parent and clip the table/footer.
        tableHost.setMinHeight(0);
        chipStrip.setVisible(!empty);
        chipStrip.setManaged(!empty);
        statusStrip.setVisible(!empty);
        statusStrip.setManaged(!empty);
        toolbar.setVisible(!empty);
        toolbar.setManaged(!empty);
        pagingStrip.setVisible(false);
        pagingStrip.setManaged(false);
        tableFooter.setVisible(!empty);
        tableFooter.setManaged(!empty);
        if (empty) {
            footerTimingRow.setVisible(false);
            footerTimingRow.setManaged(false);
            footerTimingWrapped = false;
        }
        if (empty) {
            refreshEmptyContext();
        } else {
            updateTableFooter();
        }
    }

    private void refreshEmptyContext() {
        String file = blankDash(analysisContext.sourceFile());
        String dbc = blankDash(analysisContext.dbcFile());
        String channels = blankDash(analysisContext.channelsSummary());
        emptyContextFile.setText("Source: " + file);
        emptyContextFile.setTooltip(new Tooltip(file));
        emptyContextDbc.setText("DBC: " + dbc);
        emptyContextDbc.setTooltip(new Tooltip(dbc));
        emptyContextChannels.setText("Channels: " + channels);
        emptyContextChannels.setTooltip(new Tooltip(channels));
        emptyContextCount.setText("Available Signals: "
                + NumberFormat.getIntegerInstance(Locale.US).format(availableSignalCount));
    }

    private void clearStatusStrip() {
        statusSelected.setText("0 signals");
        statusAlignment.setText("Timestamp aligned");
        statusRows.setText("\u2014 rows");
        statusStart.setText("Start \u2014");
        statusEnd.setText("End \u2014");
        statusChannels.setText("Channel \u2014");
    }

    private void clearSummaryBar() {
        clearStatusStrip();
    }

    private void refreshSummaryBar(AlignedWorkspaceSnapshot snap) {
        if (model == null) {
            clearStatusStrip();
            return;
        }
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        int selected = model.workspaceKeys().size();
        statusSelected.setText(nf.format(selected) + (selected == 1 ? " signal" : " signals"));
        statusAlignment.setText("Aligned timeline");
        if (selected > 1) {
            statusAlignment.setTooltip(new Tooltip(
                    "Chronological union of the selected signals. "
                            + "Aligned index is not one signal's sample index. "
                            + "A blank cell means that signal has no sample at that timestamp."));
        } else {
            statusAlignment.setTooltip(new Tooltip(
                    "One signal is selected, so the aligned index is that signal's sample index."));
        }

        if (snap != null && !snap.rows().isEmpty()) {
            int loaded = snap.rows().size();
            int total = snap.totalAlignedRows();
            if (total > 0) {
                statusRows.setText(nf.format(loaded) + " / " + nf.format(total) + " rows");
            } else {
                statusRows.setText(nf.format(loaded) + " rows");
            }
            statusRows.setTooltip(new Tooltip(
                    "Rows " + nf.format(snap.firstGlobalIndex())
                            + "\u2013" + nf.format(snap.lastGlobalIndex())
                            + (total > 0 ? " of " + nf.format(total) : "")));
            long startNanos = snap.rows().getFirst().timestampNanos();
            long endNanos = snap.rows().getLast().timestampNanos();
            statusStart.setText("Start " + UiFormat.nanos(startNanos) + " s");
            statusStart.setTooltip(new Tooltip(UiFormat.nanosExactTooltip(startNanos)));
            statusEnd.setText("End " + UiFormat.nanos(endNanos) + " s");
            statusEnd.setTooltip(new Tooltip(UiFormat.nanosExactTooltip(endNanos)));
        } else {
            statusRows.setText("\u2014 rows");
            statusStart.setText("Start \u2014");
            statusEnd.setText("End \u2014");
            Optional<Long> startTs = Optional.empty();
            Optional<Long> endTs = Optional.empty();
            for (SignalCatalogEntry entry : model.workspaceEntries()) {
                Optional<Long> first = entry.firstTimestampNanosOptional();
                Optional<Long> last = entry.lastTimestampNanosOptional();
                if (first.isPresent()) {
                    startTs = startTs.isEmpty()
                            ? first
                            : Optional.of(Math.min(startTs.get(), first.get()));
                }
                if (last.isPresent()) {
                    endTs = endTs.isEmpty()
                            ? last
                            : Optional.of(Math.max(endTs.get(), last.get()));
                }
            }
            if (startTs.isPresent()) {
                statusStart.setText("Start " + UiFormat.nanos(startTs.get()) + " s");
                statusStart.setTooltip(new Tooltip(UiFormat.nanosExactTooltip(startTs.get())));
            }
            if (endTs.isPresent()) {
                statusEnd.setText("End " + UiFormat.nanos(endTs.get()) + " s");
                statusEnd.setTooltip(new Tooltip(UiFormat.nanosExactTooltip(endTs.get())));
            }
        }

        String channels = blankDash(analysisContext.channelsSummary());
        statusChannels.setText(channels.startsWith("Channel") || channels.equals("\u2014")
                ? (channels.equals("\u2014") ? "Channel \u2014" : channels)
                : channels);
        statusChannels.setTooltip(new Tooltip(channels));
    }

    private void jumpToIndexValue(long globalIndex) {
        if (model == null) {
            return;
        }
        clearFeedback();
        setBusy(true);
        Optional<String> err = model.jumpToIndexAsync(globalIndex, snap -> Platform.runLater(() -> {
            applySnapshot(snap);
            setBusy(false);
        }));
        if (err.isPresent()) {
            setBusy(false);
            showFeedback(err.get());
        }
    }

    private void jumpToLastWindow() {
        int total = lastSnap.totalAlignedRows();
        if (total <= 0) {
            return;
        }
        jumpToIndexValue(total - 1L);
    }

    private void updateTableFooter() {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        if (lastSnap == null || lastSnap.rows().isEmpty()) {
            footerRangeLabel.setText("Showing 0 rows");
            footerStartLabel.setText("Start \u2014");
            footerEndLabel.setText("End \u2014");
            footerDurationLabel.setText("Duration \u2014");
            return;
        }
        int shown = lastSnap.rows().size();
        footerRangeLabel.setText("Showing " + nf.format(shown)
                + (shown == 1 ? " row" : " rows"));
        if (lastSnap.totalAlignedRows() > 0) {
            footerRangeLabel.setText(footerRangeLabel.getText()
                    + " (" + nf.format(lastSnap.firstGlobalIndex())
                    + "\u2013" + nf.format(lastSnap.lastGlobalIndex())
                    + " of " + nf.format(lastSnap.totalAlignedRows()) + ")");
        }
        long startNanos = lastSnap.rows().getFirst().timestampNanos();
        long endNanos = lastSnap.rows().getLast().timestampNanos();
        footerStartLabel.setText("Start " + UiFormat.nanos(startNanos) + " s");
        footerEndLabel.setText("End " + UiFormat.nanos(endNanos) + " s");
        long duration = Math.max(0L, endNanos - startNanos);
        footerDurationLabel.setText("Duration " + UiFormat.nanos(duration) + " s");
        footerStartLabel.setTooltip(new Tooltip(UiFormat.nanosExactTooltip(startNanos)));
        footerEndLabel.setTooltip(new Tooltip(UiFormat.nanosExactTooltip(endNanos)));
        if (displayMode == WorkspaceDisplayMode.RAW) {
            tableFooterLabel.setText("Raw decoded samples");
        } else {
            tableFooterLabel.setText("Physical values");
        }
    }

    private static String blankDash(String value) {
        return value == null || value.isBlank() || "—".equals(value) ? "—" : value;
    }

    private void setBusy(boolean busy) {
        busyIndicator.setVisible(busy);
        busyIndicator.setManaged(busy);
    }

    private void showFeedback(String message) {
        navFeedback.setText(message == null ? "" : message);
    }

    private void clearFeedback() {
        navFeedback.setText("");
    }

    /** Index and time share one toolbar control. The existing jump methods stay the only navigation logic. */
    private enum NavigateTarget {
        INDEX("Index", "e.g. 11459", "Jump to aligned row index"),
        TIME("Time (s)", "e.g. 596.79296", "Jump to timestamp");

        private final String label;
        private final String prompt;
        private final String tooltip;

        NavigateTarget(String label, String prompt, String tooltip) {
            this.label = label;
            this.prompt = prompt;
            this.tooltip = tooltip;
        }

        String label() {
            return label;
        }

        String prompt() {
            return prompt;
        }

        String tooltip() {
            return tooltip;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * One toolbar row when the controls fit. A second row when they do not,
     * so Display Mode, Precision, Rows, and Navigate stay readable.
     */
    private static final class SignalsToolbar extends HBox {
        @Override
        protected double computePrefHeight(double width) {
            if (width <= 0) {
                return super.computePrefHeight(width);
            }
            double inner = Math.max(0, width - snappedLeftInset() - snappedRightInset());
            return snappedTopInset() + snappedBottomInset() + contentHeight(inner);
        }

        @Override
        protected void layoutChildren() {
            double inner = getWidth() - snappedLeftInset() - snappedRightInset();
            if (inner <= 0 || rowCount(inner) <= 1) {
                super.layoutChildren();
                return;
            }
            double x = snappedLeftInset();
            double y = snappedTopInset();
            double rowHeight = 0;
            double limit = getWidth() - snappedRightInset();
            for (javafx.scene.Node child : getManagedChildren()) {
                double prefW = child.prefWidth(-1);
                double prefH = child.prefHeight(-1);
                if (x > snappedLeftInset() && x + prefW > limit + 0.5) {
                    x = snappedLeftInset();
                    y += rowHeight + getSpacing();
                    rowHeight = 0;
                }
                double width = Math.min(prefW, Math.max(0, limit - x));
                child.resizeRelocate(x, y, width, prefH);
                x += width + getSpacing();
                rowHeight = Math.max(rowHeight, prefH);
            }
        }

        private int rowCount(double innerWidth) {
            double x = 0;
            int rows = 1;
            boolean any = false;
            for (javafx.scene.Node child : getManagedChildren()) {
                double prefW = child.prefWidth(-1);
                if (any && x > 0 && x + prefW > innerWidth + 0.5) {
                    rows++;
                    x = 0;
                }
                x += prefW + getSpacing();
                any = true;
            }
            return rows;
        }

        private double contentHeight(double innerWidth) {
            double x = 0;
            double rowHeight = 0;
            double total = 0;
            boolean any = false;
            for (javafx.scene.Node child : getManagedChildren()) {
                double prefW = child.prefWidth(-1);
                double prefH = child.prefHeight(-1);
                if (any && x > 0 && x + prefW > innerWidth + 0.5) {
                    total += rowHeight + getSpacing();
                    x = 0;
                    rowHeight = 0;
                }
                x += prefW + getSpacing();
                rowHeight = Math.max(rowHeight, prefH);
                any = true;
            }
            return total + rowHeight;
        }
    }
}

package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.MeasurementCatalogScope;
import com.stellar.platform.desktop.presentation.SignalBrowserFilter;
import com.stellar.platform.desktop.presentation.SignalBrowserProjection;
import com.stellar.platform.desktop.presentation.SignalChannelLabels;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.presentation.SignalBrowserViewMode;
import com.stellar.platform.desktop.ui.StellarIcons;
import com.stellar.platform.desktop.ui.UiFormat;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.desktop.ui.DesktopSceneGraphRelease;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleButton;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Popup;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.text.NumberFormat;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Compact left-pane Signal Browser. Presentation/wiring only — catalog I/O stays in
 * {@link SignalsScreenModel}.
 */
public final class SignalBrowserPane extends VBox {

    public static final double PREF_WIDTH = SignalsLayoutMetrics.BROWSER_PREF_WIDTH;
    public static final double MIN_WIDTH = SignalsLayoutMetrics.BROWSER_MIN_WIDTH;
    public static final double MAX_WIDTH = SignalsLayoutMetrics.BROWSER_MAX_WIDTH;

    private final Label titleLabel = new Label("Signal Browser");
    private final Label countLabel = new Label("0 catalog entries");
    private final Label measurementLabel = new Label("Measurement");
    private final ComboBox<String> sourceCombo = new ComboBox<>();
    private final ComboBox<String> dbcCombo = new ComboBox<>();
    private final ComboBox<SignalBrowserFilter.SourceFilter> typeCombo = new ComboBox<>();
    private final HBox filterDropdownRow = new HBox(6);
    private final Popup filtersPopup = new Popup();
    private final Popup measurementPopup = new Popup();
    private final Label filtersBadge = new Label();
    private final Label signalSourceLabel = new Label("Signal Source");
    private final TextField searchField = new TextField();
    private final Button clearSearchButton = new Button("\u00D7");
    private final Button compactActionButton = new Button();
    private final ToggleButton filtersToggle = new ToggleButton("More Filters");
    private final ToggleButton hideDuplicatesToggle = new ToggleButton("Hide Duplicates");
    private final TextField messageFilter = new TextField();
    private final TextField canIdFilter = new TextField();
    private final TextField unitFilter = new TextField();
    private final ToggleButton favoritesToggle = new ToggleButton("Favorites");
    private final ToggleButton recentToggle = new ToggleButton("Recent");
    private final Button clearFiltersButton = new Button("Reset");
    private final Button collapseButton = new Button("\u2039");
    private final Button selectAllButton = new Button("Select All");
    private final Button deselectAllButton = new Button("Deselect All");
    /** Wraps instead of clipping: the browser column is only ~300 px wide. */
    private final FlowPane bulkSelectionRow = new FlowPane(6, 6);
    private final StackPane searchShell = new StackPane();
    private final Region searchIcon = (Region) StellarIcons.search();
    private final HBox advancedRow = new HBox(6);
    private final FlowPane secondaryControlsRow = new FlowPane(6, 6);
    private final VBox favoritesSection = new VBox(4);
    private final VBox favoritesList = new VBox(2);
    private final Label favoritesHeader = new Label("Favorites");
    private final VBox recentSection = new VBox(4);
    private final VBox recentList = new VBox(2);
    private final Label recentHeader = new Label("Recently Used");
    private final TableView<SignalCatalogEntry> catalogTable = new TableView<>();
    private final TableColumn<SignalCatalogEntry, Boolean> selectionCol = new TableColumn<>();
    private final CheckBox headerSelectionCheck = new CheckBox();
    private final TableColumn<SignalCatalogEntry, String> nameCol = new TableColumn<>("Signal");
    private final TableColumn<SignalCatalogEntry, String> channelCol = new TableColumn<>("Ch");
    private final TableColumn<SignalCatalogEntry, SignalSourceType> sourceCol = new TableColumn<>("Source");
    private final TableColumn<SignalCatalogEntry, String> messageCol = new TableColumn<>("Message");
    private final TableColumn<SignalCatalogEntry, String> canIdCol = new TableColumn<>("CAN ID");
    private final TableColumn<SignalCatalogEntry, String> unitCol = new TableColumn<>("Unit");
    private final TableColumn<SignalCatalogEntry, Number> samplesCol = new TableColumn<>("Samples");
    private final Label emptyTitle = new Label();
    private final Label emptyBody = new Label();
    private final Button emptyClearButton = new Button("Clear search & filters");
    private final VBox emptyState = new VBox(8, emptyTitle, emptyBody, emptyClearButton);
    private final StackPane tableHost = new StackPane();
    private final Label healthTitle = new Label("Project Health");
    private final Label healthValue = new Label("Healthy");
    private final Label healthCaption = new Label("Local workspace");
    private final ProgressBar healthStorageBar = new ProgressBar(0.0);
    private final Label healthStorageLabel = new Label("Catalog ready");
    private final VBox healthCard = new VBox(4);
    private final PauseTransition debounce = new PauseTransition(Duration.millis(280));

    private static final String ALL_DBCS = "All DBCs";
    /** Identity columns stay readable. Extra width goes to the signal name; a short pane scrolls. */
    private static final double BROWSER_SOURCE_W = 64;
    private static final double BROWSER_CHANNEL_W = 58;
    private static final double BROWSER_CAN_W = 68;
    private static final double BROWSER_NAME_MIN = 80;

    private SignalsScreenModel model;
    private Consumer<List<SignalCatalogEntry>> selectionListener = selected -> { };
    private Consumer<List<SignalCatalogEntry>> addToWorkspaceListener = selected -> { };
    private Consumer<List<SignalCatalogEntry>> removeFromWorkspaceListener = selected -> { };
    private Consumer<MeasurementCatalogScope.Ref> measurementScopeListener = ref -> { };
    private Runnable onDisplayedChanged = () -> { };
    private Runnable onCollapseRequested = () -> { };
    private Runnable onSelectAll = () -> { };
    private Runnable onDeselectAll = () -> { };
    private boolean suppressSelectionEvents;
    /** Suppresses toggle-driven filtering while source chips are rebuilt. */
    private boolean suppressFilterEvents;
    private boolean suppressViewModeEvents;
    private boolean filtersActive;
    private int filterRequestSerial;
    private Runnable favoritesListener;
    private Runnable recentsListener;
    private boolean favoriteRefreshQueued;
    private boolean disposed;
    private ContextMenu catalogRowMenu;
    private final ListChangeListener<SignalCatalogEntry> selectionChangeListener = c -> {
        if (!suppressSelectionEvents && !disposed) {
            selectionListener.accept(List.copyOf(catalogTable.getSelectionModel().getSelectedItems()));
        }
    };
    private final EventHandler<KeyEvent> catalogEnterFilter = e -> {
        if (e.getCode() == KeyCode.ENTER) {
            addFocusedToWorkspace();
            e.consume();
        }
    };
    private final EventHandler<KeyEvent> browserKeyFilter = e -> {
        if (e.isControlDown() && e.getCode() == KeyCode.F) {
            focusSearch();
            e.consume();
        } else if (e.getCode() == KeyCode.ESCAPE) {
            handleEscape();
            e.consume();
        }
    };
    /** O(1) duplicate marker lookup; rebuilt once per catalog replacement, never per table cell. */
    private Set<SignalCatalogKey> duplicateSignalKeys = Set.of();
    private Map<SignalCatalogKey, List<Integer>> duplicatePeerChannels = Map.of();

    public SignalBrowserPane() {
        getStyleClass().add("signal-browser-pane");
        setSpacing(6);
        setPadding(new Insets(SignalsLayoutMetrics.INNER_PADDING));
        setMinWidth(MIN_WIDTH);
        setPrefWidth(PREF_WIDTH);
        setMaxWidth(MAX_WIDTH);
        setFillWidth(true);
        setMinHeight(0);

        titleLabel.getStyleClass().add("section-header");
        countLabel.getStyleClass().addAll("muted", "signal-browser-count");
        countLabel.setMinWidth(0);
        countLabel.setMaxWidth(Double.MAX_VALUE);
        countLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        collapseButton.getStyleClass().addAll("secondary-button", "compact-button", "signal-panel-collapse-button");
        collapseButton.setFocusTraversable(false);
        collapseButton.setTooltip(new Tooltip("Collapse signal browser"));
        collapseButton.setOnAction(e -> onCollapseRequested.run());
        Region headerGrow = new Region();
        HBox.setHgrow(headerGrow, Priority.ALWAYS);
        HBox header = new HBox(8, titleLabel, headerGrow, collapseButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().addAll("signal-browser-header", "signal-panel-header");
        countLabel.setMaxWidth(Double.MAX_VALUE);

        searchField.getStyleClass().addAll("search-field", "search-field-inner");
        searchField.setPromptText("Search signals...");
        searchField.setTooltip(new Tooltip("Search by signal name, message, CAN ID or unit"));
        searchField.setMinWidth(0);
        searchField.setMaxWidth(Double.MAX_VALUE);
        searchIcon.setMouseTransparent(true);
        searchIcon.getStyleClass().add("search-icon");
        StackPane.setAlignment(searchIcon, Pos.CENTER_RIGHT);
        StackPane.setMargin(searchIcon, new Insets(0, 10, 0, 0));
        clearSearchButton.getStyleClass().addAll("compact-button", "search-clear-button");
        clearSearchButton.setTooltip(new Tooltip("Clear search"));
        clearSearchButton.setFocusTraversable(false);
        clearSearchButton.setVisible(false);
        clearSearchButton.setManaged(false);
        clearSearchButton.setOnAction(e -> {
            searchField.clear();
            runFilterNow();
        });
        StackPane.setAlignment(clearSearchButton, Pos.CENTER_RIGHT);
        StackPane.setMargin(clearSearchButton, new Insets(0, 28, 0, 0));
        searchShell.getStyleClass().add("search-field-shell");
        searchShell.getChildren().setAll(searchField, clearSearchButton, searchIcon);
        searchShell.setMinWidth(0);
        searchShell.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchShell, Priority.ALWAYS);
        compactActionButton.setGraphic(StellarIcons.filter());
        compactActionButton.getStyleClass().addAll("compact-button", "icon-button", "browser-compact-action");
        compactActionButton.setFocusTraversable(false);
        compactActionButton.setTooltip(new Tooltip("Filters"));
        compactActionButton.setOnAction(e -> toggleFilters(compactActionButton));
        filtersBadge.getStyleClass().add("browser-filter-badge");
        filtersBadge.setMouseTransparent(true);
        filtersBadge.setVisible(false);
        filtersBadge.setManaged(false);
        StackPane filterButtonHost = new StackPane(compactActionButton, filtersBadge);
        StackPane.setAlignment(filtersBadge, Pos.TOP_RIGHT);
        filterButtonHost.setMinWidth(28);
        filterButtonHost.setPrefWidth(28);
        filterButtonHost.setMaxWidth(28);
        HBox searchRow = new HBox(6, searchShell, filterButtonHost);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchRow.setMinWidth(0);
        searchRow.getStyleClass().add("search-row");

        configureFilterCombos();
        filterDropdownRow.getStyleClass().add("browser-filter-dropdown-row");
        filterDropdownRow.setAlignment(Pos.CENTER_LEFT);
        filterDropdownRow.setMinWidth(0);
        measurementLabel.setText("Measurement");
        measurementLabel.getStyleClass().add("browser-filters-label");
        signalSourceLabel.getStyleClass().add("browser-filters-label");
        VBox browserChrome = new VBox(8, searchRow, bulkSelectionRow);
        browserChrome.setFillWidth(true);
        browserChrome.setMinWidth(0);
        browserChrome.setMaxWidth(Double.MAX_VALUE);
        browserChrome.getStyleClass().add("browser-chrome");
        VBox.setVgrow(browserChrome, Priority.NEVER);

        configureActionToggle(filtersToggle, "Filters", "Optional signal filters");
        filtersToggle.setVisible(false);
        filtersToggle.setManaged(false);
        configureActionToggle(
                hideDuplicatesToggle,
                "Hide Duplicates",
                "View filter only: hide later rows with the exact same signal name. No decoded data is removed.");
        hideDuplicatesToggle.getStyleClass().add("browser-duplicates-toggle");
        favoritesToggle.getStyleClass().add("favorites-toggle");
        configureActionToggle(favoritesToggle, "Favorites", "Show favorites only", StellarIcons.starOutline());
        configureActionToggle(recentToggle, "Recent", "Recently used signals", StellarIcons.clock());
        favoritesToggle.setVisible(false);
        favoritesToggle.setManaged(false);
        recentToggle.setVisible(false);
        recentToggle.setManaged(false);
        clearFiltersButton.getStyleClass().addAll(
                "compact-button", "secondary-button", "browser-reset-button",
                "readable-action-button", "browser-compact-control");
        clearFiltersButton.setFocusTraversable(false);
        clearFiltersButton.setText("Reset Filters");
        clearFiltersButton.setTooltip(new Tooltip("Reset optional signal filters"));
        clearFiltersButton.setOnAction(e -> clearStructuredFilters());
        secondaryControlsRow.getChildren().setAll(hideDuplicatesToggle, clearFiltersButton);
        secondaryControlsRow.setHgap(6);
        secondaryControlsRow.setVgap(4);
        configureBulkButton(selectAllButton, "Select all currently filtered signals", () -> onSelectAll.run());
        configureBulkButton(deselectAllButton, "Remove currently filtered signals from the workspace", () -> onDeselectAll.run());
        bulkSelectionRow.getChildren().setAll(selectAllButton, deselectAllButton);
        bulkSelectionRow.setHgap(6);
        bulkSelectionRow.setVgap(4);
        bulkSelectionRow.getStyleClass().add("browser-bulk-actions");
        secondaryControlsRow.setAlignment(Pos.CENTER_LEFT);
        secondaryControlsRow.setMinWidth(0);
        secondaryControlsRow.getStyleClass().add("browser-secondary-controls");

        messageFilter.setPromptText("Message");
        canIdFilter.setPromptText("CAN ID");
        unitFilter.setPromptText("Unit");
        messageFilter.getStyleClass().add("search-field");
        canIdFilter.getStyleClass().add("search-field");
        unitFilter.getStyleClass().add("search-field");
        messageFilter.setMinWidth(0);
        canIdFilter.setMinWidth(0);
        unitFilter.setMinWidth(0);
        messageFilter.setPrefWidth(100);
        canIdFilter.setPrefWidth(72);
        unitFilter.setPrefWidth(64);
        HBox.setHgrow(messageFilter, Priority.ALWAYS);
        advancedRow.getChildren().setAll(messageFilter, canIdFilter, unitFilter);
        advancedRow.setAlignment(Pos.CENTER_LEFT);
        advancedRow.getStyleClass().add("filter-row");
        advancedRow.setVisible(true);
        advancedRow.setManaged(true);

        configureGroupedSections();
        configureTable();
        emptyTitle.getStyleClass().add("empty-state-title");
        emptyBody.getStyleClass().add("empty-state-body");
        emptyBody.setWrapText(true);
        emptyClearButton.getStyleClass().addAll("secondary-button", "compact-button");
        emptyClearButton.setFocusTraversable(false);
        emptyClearButton.setOnAction(e -> clearSearchAndFilters());
        emptyClearButton.setVisible(false);
        emptyClearButton.setManaged(false);
        emptyState.getStyleClass().add("empty-state");
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setMouseTransparent(false);
        tableHost.getChildren().addAll(catalogTable, emptyState);
        VBox.setVgrow(tableHost, Priority.ALWAYS);
        StackPane.setAlignment(emptyState, Pos.CENTER);

        healthTitle.getStyleClass().add("browser-health-title");
        healthValue.getStyleClass().add("browser-health-value");
        healthCaption.getStyleClass().add("browser-health-caption");
        healthStorageLabel.getStyleClass().add("muted");
        healthStorageBar.getStyleClass().add("browser-health-bar");
        healthStorageBar.setMaxWidth(Double.MAX_VALUE);
        healthStorageBar.setPrefHeight(6);
        healthCard.getStyleClass().add("browser-health-card");
        healthCard.getChildren().setAll(
                new HBox(8, healthTitle, healthValue),
                healthCaption,
                healthStorageBar,
                healthStorageLabel);

        recentSection.setVisible(false);
        recentSection.setManaged(false);
        buildContextPopups();
        getChildren().addAll(
                header,
                countLabel,
                browserChrome,
                favoritesSection,
                tableHost,
                healthCard);

        debounce.setOnFinished(e -> runFilterNow());
        searchField.focusedProperty().addListener((o, a, focused) -> {
            if (Boolean.TRUE.equals(focused)) {
                searchShell.getStyleClass().add("search-field-shell-focused");
            } else {
                searchShell.getStyleClass().remove("search-field-shell-focused");
            }
        });
        searchField.textProperty().addListener((o, a, b) -> {
            boolean hasQuery = b != null && !b.isBlank();
            clearSearchButton.setVisible(hasQuery);
            clearSearchButton.setManaged(hasQuery);
            debounce.playFromStart();
        });
        messageFilter.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        canIdFilter.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        unitFilter.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        favoritesToggle.selectedProperty().addListener((o, a, b) -> {
            favoritesToggle.setGraphic(Boolean.TRUE.equals(b) ? StellarIcons.starFilled() : StellarIcons.starOutline());
            if (!suppressFilterEvents) {
                runFilterNow();
            }
        });
        recentToggle.selectedProperty().addListener((o, a, b) -> {
            if (!suppressFilterEvents) {
                runFilterNow();
            }
        });
        hideDuplicatesToggle.selectedProperty().addListener((o, a, selected) -> {
            if (model == null || suppressViewModeEvents) {
                return;
            }
            model.setBrowserViewMode(Boolean.TRUE.equals(selected)
                    ? SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES
                    : SignalBrowserViewMode.ALL_INSTANCES);
            updateFiltersBadge();
            runFilterNow();
        });

        catalogTable.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                addFocusedToWorkspace();
                e.consume();
            }
        });
        catalogTable.addEventFilter(KeyEvent.KEY_PRESSED, catalogEnterFilter);
        addEventFilter(KeyEvent.KEY_PRESSED, browserKeyFilter);
        updateEmptyState(true, false, false);
        refreshGroupedSections();
        updateHealthCard(0);
    }

    public TableView<SignalCatalogEntry> catalogTable() {
        return catalogTable;
    }

    public VBox recentSectionForTest() {
        return recentSection;
    }

    public boolean tableHostGrowsForTest() {
        return VBox.getVgrow(tableHost) == Priority.ALWAYS;
    }

    public TextField searchField() {
        return searchField;
    }

    public void setModel(SignalsScreenModel model) {
        if (this.model != null) {
            this.model.removeFavoritesListener(favoritesListener);
            this.model.removeRecentsListener(recentsListener);
        }
        this.model = Objects.requireNonNull(model, "model");
        favoritesListener = this::scheduleFavoriteRefresh;
        recentsListener = this::scheduleFavoriteRefresh;
        this.model.addFavoritesListener(favoritesListener);
        this.model.addRecentsListener(recentsListener);
        syncViewModeToggle();
        rebuildFilterCombos(this.model.catalog());
        rebuildDuplicateIndex();
        refreshFromModel();
        updateFiltersBadge();
        refreshGroupedSections();
    }

    private void syncViewModeToggle() {
        suppressViewModeEvents = true;
        try {
            hideDuplicatesToggle.setSelected(
                    model != null && model.browserViewMode() == SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES);
        } finally {
            suppressViewModeEvents = false;
        }
    }

    public void setSelectionListener(Consumer<List<SignalCatalogEntry>> listener) {
        this.selectionListener = listener == null ? selected -> { } : listener;
    }

    public void setAddToWorkspaceListener(Consumer<List<SignalCatalogEntry>> listener) {
        this.addToWorkspaceListener = listener == null ? selected -> { } : listener;
    }

    public void setRemoveFromWorkspaceListener(Consumer<List<SignalCatalogEntry>> listener) {
        this.removeFromWorkspaceListener = listener == null ? selected -> { } : listener;
    }

    public void setMeasurementScopeListener(Consumer<MeasurementCatalogScope.Ref> listener) {
        this.measurementScopeListener = listener == null ? ref -> { } : listener;
    }

    public void setOnDisplayedChanged(Runnable listener) {
        this.onDisplayedChanged = listener == null ? () -> { } : listener;
    }

    public void setOnCollapseRequested(Runnable listener) {
        this.onCollapseRequested = listener == null ? () -> { } : listener;
    }

    public void setOnSelectAll(Runnable listener) {
        this.onSelectAll = listener == null ? () -> { } : listener;
    }

    public void setOnDeselectAll(Runnable listener) {
        this.onDeselectAll = listener == null ? () -> { } : listener;
    }

    /** Rows currently shown after measurement, search, and the other browser filters. */
    public List<SignalCatalogEntry> filteredEntries() {
        return List.copyOf(catalogTable.getItems());
    }

    public Button selectAllButtonForTest() {
        return selectAllButton;
    }

    public Button deselectAllButtonForTest() {
        return deselectAllButton;
    }

    public Button collapseButtonForTest() {
        return collapseButton;
    }

    public boolean collapseButtonInHeaderForTest() {
        return collapseButton.getParent() != null
                && collapseButton.getParent().getStyleClass().contains("signal-panel-header")
                && "Signal Browser".equals(titleLabel.getText());
    }

    public String moreFiltersTextForTest() {
        return filtersBadge.isVisible() && filtersBadge.getTooltip() != null
                ? filtersBadge.getTooltip().getText()
                : "Filters";
    }

    public String filtersBadgeTextForTest() {
        return filtersBadge.isVisible() ? filtersBadge.getText() : "";
    }

    public boolean measurementChooserInDefaultColumnForTest() {
        return shownInBrowser(sourceCombo);
    }

    public boolean dbcFilterInDefaultColumnForTest() {
        return shownInBrowser(dbcCombo);
    }

    public boolean typeFilterInDefaultColumnForTest() {
        return shownInBrowser(typeCombo);
    }

    public boolean hideDuplicatesInDefaultColumnForTest() {
        return shownInBrowser(hideDuplicatesToggle);
    }

    public String signalSourceLabelForTest(SignalBrowserFilter.SourceFilter source) {
        return typeCombo.getConverter().toString(source);
    }

    private boolean shownInBrowser(Node node) {
        if (node == null || !node.isVisible() || !node.isManaged()) {
            return false;
        }
        for (Node cursor = node; cursor != null; cursor = cursor.getParent()) {
            if (cursor == this) {
                return true;
            }
        }
        return false;
    }

    public String hideDuplicatesTextForTest() {
        return hideDuplicatesToggle.getText();
    }

    private static void configureBulkButton(Button button, String tooltip, Runnable action) {
        button.getStyleClass().addAll(
                "secondary-button", "compact-button", "readable-action-button", "browser-compact-control");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(e -> action.run());
    }

    public void onHide() {
        debounce.stop();
        filterRequestSerial++;
        closeFiltersPanel();
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        onHide();
        debounce.setOnFinished(null);
        catalogTable.getSelectionModel().getSelectedItems().removeListener(selectionChangeListener);
        catalogTable.removeEventFilter(KeyEvent.KEY_PRESSED, catalogEnterFilter);
        removeEventFilter(KeyEvent.KEY_PRESSED, browserKeyFilter);
        catalogTable.setOnMouseClicked(null);
        if (catalogRowMenu != null) {
            catalogRowMenu.hide();
            catalogRowMenu.getItems().clear();
            catalogRowMenu = null;
        }
        DesktopSceneGraphRelease.hideCombo(sourceCombo);
        DesktopSceneGraphRelease.hideCombo(dbcCombo);
        DesktopSceneGraphRelease.hideCombo(typeCombo);
        if (model != null) {
            if (favoritesListener != null) {
                model.removeFavoritesListener(favoritesListener);
            }
            if (recentsListener != null) {
                model.removeRecentsListener(recentsListener);
            }
        }
        favoritesListener = null;
        recentsListener = null;
        model = null;
        selectionListener = selected -> { };
        addToWorkspaceListener = selected -> { };
        removeFromWorkspaceListener = selected -> { };
        onDisplayedChanged = () -> { };
        DesktopSceneGraphRelease.disposeTableView(catalogTable);
        favoritesList.getChildren().clear();
        recentList.getChildren().clear();
        duplicateSignalKeys = Set.of();
        duplicatePeerChannels = Map.of();
    }

    boolean disposedForTest() {
        return disposed;
    }

    TableCell<SignalCatalogEntry, Boolean> favoriteCellForTest() {
        return selectionCol.getCellFactory().call(selectionCol);
    }

    public TableColumn<SignalCatalogEntry, Boolean> selectionColumnForTest() {
        return selectionCol;
    }

    public CheckBox headerSelectionCheckForTest() {
        return headerSelectionCheck;
    }

    TableCell<SignalCatalogEntry, String> nameCellForTest() {
        return nameCol.getCellFactory().call(nameCol);
    }

    public void focusSearch() {
        searchField.requestFocus();
        searchField.selectAll();
    }

    /** Refresh star column / favorites-only list without reparsing. */
    private void scheduleFavoriteRefresh() {
        if (favoriteRefreshQueued || disposed) {
            return;
        }
        favoriteRefreshQueued = true;
        Scene scene = getScene();
        if (scene == null || scene.getWindow() == null) {
            Platform.runLater(this::runFavoriteRefresh);
            return;
        }
        scene.addPostLayoutPulseListener(new Runnable() {
            @Override
            public void run() {
                scene.removePostLayoutPulseListener(this);
                Platform.runLater(SignalBrowserPane.this::runFavoriteRefresh);
            }
        });
    }

    private void runFavoriteRefresh() {
        favoriteRefreshQueued = false;
        refreshFavoritePresentation();
    }

    public void refreshFavoritePresentation() {
        if (disposed) {
            return;
        }
        catalogTable.refresh();
        syncHeaderSelectionCheck();
        refreshGroupedSections();
        if (favoritesToggle.isSelected() || recentToggle.isSelected()) {
            runFilterNow();
        }
    }

    public void closeFiltersPanel() {
        filtersToggle.setSelected(false);
        if (filtersPopup.isShowing()) {
            filtersPopup.hide();
        }
        if (measurementPopup.isShowing()) {
            measurementPopup.hide();
        }
    }

    private void handleEscape() {
        closeFiltersPanel();
    }

    private void clearStructuredFilters() {
        messageFilter.clear();
        canIdFilter.clear();
        unitFilter.clear();
        suppressFilterEvents = true;
        suppressViewModeEvents = true;
        try {
            dbcCombo.getSelectionModel().select(ALL_DBCS);
            typeCombo.getSelectionModel().select(SignalBrowserFilter.SourceFilter.ALL);
            favoritesToggle.setSelected(false);
            recentToggle.setSelected(false);
            hideDuplicatesToggle.setSelected(false);
        } finally {
            suppressFilterEvents = false;
            suppressViewModeEvents = false;
        }
        if (model != null) {
            model.setBrowserViewMode(SignalBrowserViewMode.ALL_INSTANCES);
        }
        updateFiltersBadge();
        runFilterNow();
    }

    private void updateFiltersBadge() {
        int n = currentFilterState().optionalFilterCount();
        if (hideDuplicatesToggle.isSelected()) {
            n++;
        }
        boolean active = n > 0;
        filtersBadge.setText(Integer.toString(n));
        filtersBadge.setVisible(active);
        filtersBadge.setManaged(active);
        compactActionButton.setTooltip(new Tooltip(active ? "Filters (" + n + ")" : "Filters"));
        filtersBadge.setTooltip(compactActionButton.getTooltip());
        if (active && !compactActionButton.getStyleClass().contains("filters-active")) {
            compactActionButton.getStyleClass().add("filters-active");
        } else if (!active) {
            compactActionButton.getStyleClass().remove("filters-active");
        }
    }

    public void refreshFromModel() {
        if (disposed || model == null) {
            return;
        }
        syncViewModeToggle();
        rebuildFilterCombos(model.catalog());
        restorePresentationFromModel();
        rebuildDuplicateIndex();
        filterRequestSerial++;
        // Apply filters synchronously against the current catalog — never leave the table on a
        // stale empty displayedCatalog while the header still reflects catalog.size().
        publishFilteredRows(applyFiltersSynchronously());
        refreshGroupedSections();
    }

    public void runFilterNow() {
        if (disposed || model == null || suppressFilterEvents) {
            return;
        }
        // Measurement scope must be visible before the next user action. An async-only
        // refresh left the previous logical rows in the table, so Analyze received
        // PROJECT keys the graph catalog could not resolve.
        publishFilteredRows(applyFiltersSynchronously());
    }

    private List<SignalCatalogEntry> applyFiltersSynchronously() {
        model.setSearchQuery(searchField.getText());
        model.setBrowserFilter(currentFilterState());
        filtersActive = hasActiveFilters();
        updateFiltersBadge();
        return model.applyBrowserFilterNow();
    }

    private void publishFilteredRows(List<SignalCatalogEntry> filtered) {
        List<SignalCatalogKey> previouslySelected = catalogTable.getSelectionModel()
                .getSelectedItems().stream()
                .map(SignalCatalogEntry::key)
                .toList();
        applyRows(filtered, previouslySelected);
        updateCount(scopedPhysicalCount(), filtered.size());
        updateEmptyState(model.catalogStore() == null, model.physicalSignalCount() == 0, filtered.isEmpty());
    }

    private static void configureActionToggle(ToggleButton button, String label, String tooltip) {
        configureActionToggle(button, label, tooltip, null);
    }

    private static void configureActionToggle(
            ToggleButton button, String label, String tooltip, javafx.scene.Node graphic) {
        button.setText(label);
        button.getStyleClass().addAll(
                "source-filter-toggle", "filter-button", "readable-action-button", "browser-compact-control");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setPrefWidth(Region.USE_COMPUTED_SIZE);
        button.setMaxWidth(Region.USE_PREF_SIZE);
        button.setWrapText(false);
        button.setTextOverrun(OverrunStyle.CLIP);
        button.setEllipsisString("");
        button.setTooltip(new Tooltip(tooltip));
        if (graphic != null) {
            button.setGraphic(graphic);
            button.setGraphicTextGap(6);
        }
    }

    private void configureTable() {
        catalogTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        catalogTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        catalogTable.setPlaceholder(new Label(""));
        catalogTable.setMinHeight(0);
        catalogTable.setFixedCellSize(32);
        catalogTable.setMinWidth(0);
        VBox.setVgrow(catalogTable, Priority.ALWAYS);

        selectionCol.setText(null);
        selectionCol.setMinWidth(36);
        selectionCol.setPrefWidth(36);
        selectionCol.setMaxWidth(38);
        selectionCol.setResizable(false);
        selectionCol.setSortable(false);
        headerSelectionCheck.getStyleClass().add("browser-header-check");
        headerSelectionCheck.setFocusTraversable(false);
        headerSelectionCheck.setAllowIndeterminate(true);
        headerSelectionCheck.setTooltip(new Tooltip("Select all filtered signals"));
        headerSelectionCheck.setOnAction(e -> toggleHeaderSelection());
        selectionCol.setGraphic(headerSelectionCheck);
        selectionCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                model != null && c.getValue() != null && model.workspaceKeys().contains(c.getValue().key())));
        selectionCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox box = new CheckBox();
            private boolean updating;

            {
                box.getStyleClass().add("browser-row-check");
                box.setFocusTraversable(false);
                box.setOnAction(e -> {
                    if (updating) {
                        return;
                    }
                    SignalCatalogEntry entry = getTableRow() == null ? null : getTableRow().getItem();
                    if (entry == null) {
                        return;
                    }
                    if (box.isSelected()) {
                        addToWorkspaceListener.accept(List.of(entry));
                    } else {
                        removeFromWorkspaceListener.accept(List.of(entry));
                    }
                });
            }

            @Override
            protected void updateItem(Boolean selected, boolean empty) {
                super.updateItem(selected, empty);
                if (empty) {
                    updating = true;
                    box.setSelected(false);
                    updating = false;
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                updating = true;
                box.setSelected(Boolean.TRUE.equals(selected));
                box.setTooltip(new Tooltip(box.isSelected()
                        ? "Remove from workspace"
                        : "Add to workspace"));
                updating = false;
                setAlignment(Pos.CENTER);
                setText(null);
                setGraphic(box);
            }
        });

        nameCol.setMinWidth(BROWSER_NAME_MIN);
        nameCol.setPrefWidth(140);
        nameCol.setMaxWidth(Double.MAX_VALUE);
        nameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue() == null ? "" : c.getValue().signalName()));
        nameCol.setCellFactory(col -> new TableCell<>() {
            private final Label name = new Label();
            private final Tooltip tip = new Tooltip();

            {
                name.getStyleClass().add("browser-signal-name");
                name.setTextOverrun(OverrunStyle.ELLIPSIS);
                name.setWrapText(false);
                name.setMaxWidth(Double.MAX_VALUE);
                tip.setWrapText(true);
                tip.setMaxWidth(420);
                widthProperty().addListener((obs, previous, width) ->
                        name.setMaxWidth(Math.max(24, width.doubleValue() - 8)));
            }

            @Override
            protected void updateItem(String signalName, boolean empty) {
                super.updateItem(signalName, empty);
                if (empty || signalName == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    name.setTooltip(null);
                    return;
                }
                SignalCatalogEntry entry = getTableRow() == null ? null : getTableRow().getItem();
                boolean active = entry != null
                        && model != null
                        && model.workspaceKeys().contains(entry.key());
                name.setText(signalName);
                name.getStyleClass().remove("browser-signal-active");
                if (active) {
                    name.getStyleClass().add("browser-signal-active");
                }
                String identity = SignalRowTooltip.text(entry);
                if (active && !identity.isBlank()) {
                    identity = identity + "\nActive in workspace";
                }
                tip.setText(identity.isBlank() ? signalName : identity);
                name.setTooltip(tip);
                setText(null);
                setGraphic(name);
                setTooltip(tip);
            }
        });

        channelCol.setMinWidth(BROWSER_CHANNEL_W);
        channelCol.setPrefWidth(BROWSER_CHANNEL_W);
        channelCol.setMaxWidth(80);
        channelCol.setResizable(false);
        channelCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(channelLabel(c.getValue())));
        channelCol.setComparator(Comparator.comparingInt(SignalBrowserPane::channelNumberFromLabel));
        channelCol.setSortType(TableColumn.SortType.ASCENDING);
        channelCol.setCellFactory(c -> new DuplicateAwareChannelCell());

        sourceCol.setMinWidth(BROWSER_SOURCE_W);
        sourceCol.setPrefWidth(BROWSER_SOURCE_W);
        sourceCol.setMaxWidth(84);
        sourceCol.setResizable(false);
        sourceCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().sourceType()));
        sourceCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(SignalSourceType type, boolean empty) {
                super.updateItem(type, empty);
                setGraphic(null);
                setAlignment(Pos.CENTER);
                getStyleClass().removeAll("source-badge", "source-badge-dbc", "source-badge-native", "source-badge-computed");
                if (empty || type == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                String sourceText = sourceColumnText(type);
                setText(sourceText);
                setTooltip(new Tooltip(sourceText));
                getStyleClass().addAll("source-badge", "badge", SignalBrowserFilter.sourceStyleClass(type));
                if (type == SignalSourceType.DBC_CAN) {
                    getStyleClass().addAll("badge-dbc", "dbc-badge");
                } else if (type == SignalSourceType.MF4_NATIVE) {
                    getStyleClass().addAll("badge-native", "native-badge");
                }
            }
        });

        messageCol.setMinWidth(70);
        messageCol.setPrefWidth(90);
        messageCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().messageName()));
        messageCol.setCellFactory(c -> tooltipCell());

        canIdCol.setMinWidth(BROWSER_CAN_W);
        canIdCol.setPrefWidth(BROWSER_CAN_W);
        canIdCol.setMaxWidth(96);
        canIdCol.setResizable(false);
        canIdCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().sourceType() == SignalSourceType.MF4_NATIVE
                        ? ""
                        : UiFormat.canId(c.getValue().canId())));
        canIdCol.setCellFactory(c -> tooltipCell());

        unitCol.setMinWidth(40);
        unitCol.setPrefWidth(52);
        unitCol.setMaxWidth(72);
        unitCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().unit()));

        samplesCol.setMinWidth(56);
        samplesCol.setPrefWidth(70);
        samplesCol.setMaxWidth(100);
        samplesCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().sampleCount()));

        // Safe default: one row per physical signal instance, with channel + CAN identity visible.
        // Message / Samples remain filterable/inspectable without widening the compact browser.
        catalogTable.getColumns().setAll(List.of(selectionCol, nameCol, sourceCol, channelCol, canIdCol));
        catalogTable.getSortOrder().setAll(channelCol);
        catalogTable.widthProperty().addListener((o, a, width) -> fitBrowserColumns(width.doubleValue()));
        Platform.runLater(() -> fitBrowserColumns(catalogTable.getWidth()));
        catalogRowMenu = SignalCatalogDragSupport.enableDragSource(catalogTable, this::buildBrowserMenuItems);
        catalogTable.getSelectionModel().getSelectedItems().addListener(selectionChangeListener);
    }

    private void configureFilterCombos() {
        sourceCombo.getStyleClass().addAll("browser-filter-combo");
        dbcCombo.getStyleClass().addAll("browser-filter-combo");
        typeCombo.getStyleClass().addAll("browser-filter-combo");
        sourceCombo.setPromptText("Measurement");
        dbcCombo.setPromptText("DBC");
        typeCombo.setPromptText("Signal Source");
        sourceCombo.setMaxWidth(Double.MAX_VALUE);
        dbcCombo.setMaxWidth(Double.MAX_VALUE);
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        sourceCombo.setMinWidth(0);
        dbcCombo.setMinWidth(0);
        typeCombo.setMinWidth(0);
        sourceCombo.setPrefWidth(260);
        dbcCombo.setPrefWidth(240);
        typeCombo.setPrefWidth(240);
        typeCombo.getItems().setAll(
                SignalBrowserFilter.SourceFilter.ALL,
                SignalBrowserFilter.SourceFilter.DBC,
                SignalBrowserFilter.SourceFilter.MF4_NATIVE,
                SignalBrowserFilter.SourceFilter.DERIVED);
        typeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(SignalBrowserFilter.SourceFilter object) {
                if (object == null || object == SignalBrowserFilter.SourceFilter.ALL) {
                    return "All";
                }
                if (object == SignalBrowserFilter.SourceFilter.MF4_NATIVE) {
                    return "Native";
                }
                return object.label();
            }

            @Override
            public SignalBrowserFilter.SourceFilter fromString(String string) {
                return SignalBrowserFilter.SourceFilter.ALL;
            }
        });
        typeCombo.getSelectionModel().select(SignalBrowserFilter.SourceFilter.ALL);
        installEllipsisCell(sourceCombo);
        installEllipsisCell(dbcCombo);
        installEllipsisCell(typeCombo);
        sourceCombo.getItems().setAll(allMeasurementsLabel());
        sourceCombo.getSelectionModel().select(allMeasurementsLabel());
        dbcCombo.getItems().setAll(ALL_DBCS);
        dbcCombo.getSelectionModel().select(ALL_DBCS);
        sourceCombo.valueProperty().addListener((o, a, b) -> {
            if (!suppressFilterEvents) {
                notifyMeasurementScope();
                runFilterNow();
                if (measurementPopup.isShowing()) {
                    measurementPopup.hide();
                }
            }
        });
        dbcCombo.valueProperty().addListener((o, a, b) -> {
            if (!suppressFilterEvents) {
                updateFiltersBadge();
                runFilterNow();
            }
        });
        typeCombo.valueProperty().addListener((o, a, b) -> {
            if (!suppressFilterEvents) {
                updateFiltersBadge();
                runFilterNow();
            }
        });
    }

    private void buildContextPopups() {
        measurementPopup.setAutoHide(true);
        measurementPopup.setHideOnEscape(true);
        VBox measurementBox = new VBox(6, measurementLabel, sourceCombo);
        measurementBox.setFillWidth(true);
        measurementBox.setPrefWidth(300);
        measurementBox.getStyleClass().add("browser-filters-popover");
        measurementPopup.getContent().setAll(measurementBox);

        Label title = new Label("Filters");
        title.getStyleClass().add("browser-filters-title");
        Label dbcLabel = new Label("DBC");
        dbcLabel.getStyleClass().add("browser-filters-label");
        clearFiltersButton.setMaxWidth(Double.MAX_VALUE);
        hideDuplicatesToggle.setMaxWidth(Double.MAX_VALUE);
        VBox filtersBox = new VBox(
                8,
                title,
                signalSourceLabel,
                typeCombo,
                dbcLabel,
                dbcCombo,
                hideDuplicatesToggle,
                advancedRow,
                clearFiltersButton);
        filtersBox.setFillWidth(true);
        filtersBox.setPrefWidth(280);
        filtersBox.getStyleClass().add("browser-filters-popover");
        filtersPopup.setAutoHide(true);
        filtersPopup.setHideOnEscape(true);
        filtersPopup.getContent().setAll(filtersBox);
    }

    private void toggleFilters(Node anchor) {
        if (filtersPopup.isShowing()) {
            filtersPopup.hide();
            return;
        }
        if (measurementPopup.isShowing()) {
            measurementPopup.hide();
        }
        showAnchoredPopup(filtersPopup, anchor);
    }

    public VBox measurementChooserRootForTest() {
        Node content = measurementPopup.getContent().isEmpty() ? null : measurementPopup.getContent().getFirst();
        return content instanceof VBox box ? box : null;
    }

    /** Opens measurement selection from the top context card. */
    public void showMeasurementChooser(Node anchor) {
        if (measurementPopup.isShowing()) {
            measurementPopup.hide();
            return;
        }
        if (filtersPopup.isShowing()) {
            filtersPopup.hide();
        }
        showAnchoredPopup(measurementPopup, anchor);
    }

    private static void showAnchoredPopup(Popup popup, Node anchor) {
        if (anchor == null || anchor.getScene() == null || anchor.getScene().getWindow() == null) {
            return;
        }
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        popup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 4);
        Scene popupScene = popup.getScene();
        Scene owner = anchor.getScene();
        if (popupScene != null && owner != null) {
            popupScene.getStylesheets().setAll(owner.getStylesheets());
        }
    }

    private <T> void installEllipsisCell(ComboBox<T> combo) {
        combo.setButtonCell(ellipsisCell(combo));
        combo.setCellFactory(list -> ellipsisCell(combo));
    }

    private <T> ListCell<T> ellipsisCell(ComboBox<T> combo) {
        return new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setTextOverrun(OverrunStyle.ELLIPSIS);
                setWrapText(false);
                setMinWidth(0);
                setMaxWidth(Double.MAX_VALUE);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                String label = combo.getConverter() == null
                        ? String.valueOf(item)
                        : combo.getConverter().toString(item);
                setText(label);
                setTooltip(label == null || label.isBlank() ? null : new Tooltip(label));
            }
        };
    }

    private void configureGroupedSections() {
        favoritesHeader.getStyleClass().add("browser-group-header");
        recentHeader.getStyleClass().add("browser-group-header");
        favoritesHeader.setMaxWidth(Double.MAX_VALUE);
        recentHeader.setMaxWidth(Double.MAX_VALUE);
        favoritesHeader.setOnMouseClicked(e -> {
            favoritesToggle.setSelected(!favoritesToggle.isSelected());
            refreshGroupedSections();
        });
        recentHeader.setOnMouseClicked(e -> {
            recentToggle.setSelected(!recentToggle.isSelected());
            refreshGroupedSections();
        });
        favoritesList.getStyleClass().add("browser-group-list");
        recentList.getStyleClass().add("browser-group-list");
        favoritesSection.getStyleClass().add("browser-group-section");
        recentSection.getStyleClass().add("browser-group-section");
        favoritesSection.getChildren().setAll(favoritesHeader, favoritesList);
        recentSection.getChildren().setAll(recentHeader, recentList);
    }

    private void refreshGroupedSections() {
        favoritesList.getChildren().clear();
        recentList.getChildren().clear();
        recentSection.setVisible(false);
        recentSection.setManaged(false);
        recentList.getChildren().clear();
        if (model == null) {
            favoritesHeader.setText("Favorites");
            favoritesSection.setVisible(false);
            favoritesSection.setManaged(false);
            return;
        }
        List<SignalCatalogEntry> favEntries = model.favorites().stream()
                .map(model::findEntry)
                .flatMap(Optional::stream)
                .limit(6)
                .toList();
        favoritesHeader.setText("Favorites (" + model.favorites().size() + ")");
        for (SignalCatalogEntry entry : favEntries) {
            favoritesList.getChildren().add(buildGroupRow(entry));
        }
        boolean showFav = !favEntries.isEmpty() || favoritesToggle.isSelected();
        favoritesSection.setVisible(showFav);
        favoritesSection.setManaged(showFav);
        if (favoritesToggle.isSelected()) {
            favoritesHeader.getStyleClass().add("browser-group-header-active");
        } else {
            favoritesHeader.getStyleClass().remove("browser-group-header-active");
        }
    }

    private HBox buildGroupRow(SignalCatalogEntry entry) {
        Label name = new Label(entry.signalName());
        name.getStyleClass().add("browser-group-row-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        Label badge = new Label(SignalBrowserFilter.sourceBadge(entry.sourceType()));
        badge.getStyleClass().addAll("source-badge", "badge", SignalBrowserFilter.sourceStyleClass(entry.sourceType()));
        if (entry.sourceType() == SignalSourceType.DBC_CAN) {
            badge.getStyleClass().addAll("badge-dbc", "dbc-badge");
        } else if (entry.sourceType() == SignalSourceType.MF4_NATIVE) {
            badge.getStyleClass().addAll("badge-native", "native-badge");
        }
        HBox row = new HBox(6, name, badge);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("browser-group-row");
        Tooltip groupTip = new Tooltip(SignalRowTooltip.text(entry));
        groupTip.setWrapText(true);
        groupTip.setMaxWidth(420);
        Tooltip.install(row, groupTip);
        name.setTooltip(groupTip);
        row.setOnMouseClicked(e -> {
            catalogTable.getSelectionModel().clearSelection();
            catalogTable.getSelectionModel().select(entry);
            catalogTable.scrollTo(entry);
            selectionListener.accept(List.of(entry));
            if (e.getClickCount() == 2) {
                addToWorkspaceListener.accept(List.of(entry));
            }
        });
        return row;
    }

    private void fitBrowserColumns(double tableWidth) {
        if (tableWidth <= 0) {
            return;
        }
        double insets = catalogTable.snappedLeftInset() + catalogTable.snappedRightInset();
        double usable = Math.max(0, tableWidth - insets - selectionCol.getWidth());
        if (selectionCol.getWidth() <= 1) {
            usable = Math.max(0, tableWidth - insets - 36);
        }
        double fixed = BROWSER_SOURCE_W + BROWSER_CHANNEL_W + BROWSER_CAN_W;
        double name = Math.max(BROWSER_NAME_MIN, usable - fixed);
        sourceCol.setResizable(false);
        sourceCol.setMinWidth(BROWSER_SOURCE_W);
        sourceCol.setPrefWidth(BROWSER_SOURCE_W);
        sourceCol.setMaxWidth(BROWSER_SOURCE_W);
        channelCol.setResizable(false);
        channelCol.setMinWidth(BROWSER_CHANNEL_W);
        channelCol.setPrefWidth(BROWSER_CHANNEL_W);
        channelCol.setMaxWidth(BROWSER_CHANNEL_W);
        canIdCol.setResizable(false);
        canIdCol.setMinWidth(BROWSER_CAN_W);
        canIdCol.setPrefWidth(BROWSER_CAN_W);
        canIdCol.setMaxWidth(BROWSER_CAN_W);
        nameCol.setResizable(true);
        nameCol.setMinWidth(BROWSER_NAME_MIN);
        nameCol.setPrefWidth(name);
        nameCol.setMaxWidth(Double.MAX_VALUE);
    }

    private void toggleHeaderSelection() {
        if (allVisibleInWorkspace()) {
            onDeselectAll.run();
        } else {
            onSelectAll.run();
        }
        syncHeaderSelectionCheck();
    }

    private boolean allVisibleInWorkspace() {
        if (model == null || catalogTable.getItems().isEmpty()) {
            return false;
        }
        for (SignalCatalogEntry entry : catalogTable.getItems()) {
            if (entry != null && !model.workspaceKeys().contains(entry.key())) {
                return false;
            }
        }
        return true;
    }

    private void syncHeaderSelectionCheck() {
        int visible = 0;
        int selected = 0;
        if (model != null) {
            for (SignalCatalogEntry entry : catalogTable.getItems()) {
                if (entry == null) {
                    continue;
                }
                visible++;
                if (model.workspaceKeys().contains(entry.key())) {
                    selected++;
                }
            }
        }
        if (visible == 0 || selected == 0) {
            headerSelectionCheck.setIndeterminate(false);
            headerSelectionCheck.setSelected(false);
        } else if (selected == visible) {
            headerSelectionCheck.setIndeterminate(false);
            headerSelectionCheck.setSelected(true);
        } else {
            headerSelectionCheck.setSelected(false);
            headerSelectionCheck.setIndeterminate(true);
        }
    }

    private static String sourceColumnText(SignalSourceType type) {
        if (type == SignalSourceType.DBC_CAN) {
            return "DBC";
        }
        if (type == SignalSourceType.MF4_NATIVE) {
            return "Native";
        }
        if (type == SignalSourceType.ANALYSIS_DERIVED) {
            return "Derived";
        }
        return type == null ? "" : type.name();
    }


    private void rebuildDuplicateIndex() {
        if (model == null) {
            duplicateSignalKeys = Set.of();
            duplicatePeerChannels = Map.of();
            return;
        }
        List<SignalCatalogEntry> entries = model.catalog().entries();
        duplicateSignalKeys = SignalBrowserProjection.duplicatePhysicalKeys(entries);
        duplicatePeerChannels = SignalBrowserProjection.duplicatePeerChannels(entries);
    }

    private final class DuplicateAwareChannelCell extends TableCell<SignalCatalogEntry, String> {
        private final HBox box = new HBox(3);
        private final Label channel = new Label();
        private final Label duplicateStar = new Label("*");
        private final Tooltip duplicateTip = new Tooltip();
        private final Tooltip channelTip = new Tooltip();

        private DuplicateAwareChannelCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            channel.getStyleClass().add("browser-channel-label");
            channel.setWrapText(false);
            channel.setTextOverrun(OverrunStyle.CLIP);
            channel.setEllipsisString("");
            channel.setMinWidth(Region.USE_COMPUTED_SIZE);
            duplicateStar.getStyleClass().add("browser-duplicate-star");
            duplicateStar.setTooltip(duplicateTip);
            box.getChildren().setAll(channel, duplicateStar);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null || item.isBlank()) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            SignalCatalogEntry entry = getTableRow() == null ? null : getTableRow().getItem();
            boolean duplicate = entry != null && duplicateSignalKeys.contains(entry.key());
            channel.setText(item);
            duplicateStar.setVisible(duplicate);
            duplicateStar.setManaged(duplicate);
            setText(null);
            setGraphic(box);
            if (duplicate) {
                List<Integer> peerChannels = duplicatePeerChannels.getOrDefault(entry.key(), List.of());
                String peerText = peerChannels.stream()
                        .map(ch -> "Ch" + ch)
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("");
                duplicateTip.setText(peerText.isBlank() ? "Duplicate" : "Duplicate: " + peerText);
                setTooltip(duplicateTip);
            } else {
                channelTip.setText(item);
                setTooltip(channelTip);
            }
        }
    }

    private static TableCell<SignalCatalogEntry, String> tooltipCell() {
        return new TableCell<>() {
            private final Tooltip tip = new Tooltip();

            {
                tip.setWrapText(true);
                tip.setMaxWidth(420);
                setTextOverrun(OverrunStyle.ELLIPSIS);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    setGraphic(null);
                    tip.setText(item);
                    setTooltip(tip);
                }
            }
        };
    }

    private void rebuildFilterCombos(SignalCatalog catalog) {
        suppressFilterEvents = true;
        try {
            String selectedSource = sourceCombo.getValue();
            String selectedDbc = dbcCombo.getValue();
            SignalBrowserFilter.SourceFilter selectedType = typeCombo.getValue();

            Set<String> dbcs = new LinkedHashSet<>();
            if (catalog != null) {
                for (SignalCatalogEntry entry : catalog.entries()) {
                    String dbc = entry.sourceDatabase();
                    if (dbc != null && !dbc.isBlank()) {
                        dbcs.add(dbc);
                    }
                }
            }
            List<MeasurementCatalogScope.Ref> measurements = catalog == null
                    ? List.of()
                    : MeasurementCatalogScope.measurements(catalog.entries(), model.catalogStore());
            String allLabel = MeasurementCatalogScope.allMeasurementsLabel(measurements.size());
            sourceCombo.getItems().setAll(allLabel);
            for (MeasurementCatalogScope.Ref ref : measurements) {
                sourceCombo.getItems().add(ref.comboLabel());
            }
            dbcCombo.getItems().setAll(ALL_DBCS);
            dbcCombo.getItems().addAll(dbcs);

            boolean forceAll = model != null && model.consumeAllMeasurementsNavigation();
            String pending = model == null ? null : model.peekMeasurementNavigation();
            String pendingLabel = labelForPendingMeasurement(pending, measurements);
            if (pendingLabel != null) {
                sourceCombo.getSelectionModel().select(pendingLabel);
                model.clearMeasurementNavigation();
                pinMeasurementScope(identityForLabel(pendingLabel, measurements));
            } else if (forceAll) {
                sourceCombo.getSelectionModel().select(allLabel);
                pinMeasurementScope("");
            } else if (selectedSource != null && sourceCombo.getItems().contains(selectedSource)) {
                sourceCombo.getSelectionModel().select(selectedSource);
            } else if (selectedSource != null) {
                String mapped = mapLegacySourceSelection(selectedSource, measurements, allLabel);
                sourceCombo.getSelectionModel().select(mapped);
            } else {
                sourceCombo.getSelectionModel().select(allLabel);
            }
            if (selectedDbc != null && dbcCombo.getItems().contains(selectedDbc)) {
                dbcCombo.getSelectionModel().select(selectedDbc);
            } else {
                dbcCombo.getSelectionModel().select(ALL_DBCS);
            }
            if (selectedType != null) {
                typeCombo.getSelectionModel().select(selectedType);
            } else {
                typeCombo.getSelectionModel().select(SignalBrowserFilter.SourceFilter.ALL);
            }
        } finally {
            suppressFilterEvents = false;
        }
    }

    /**
     * Puts the recreated browser controls back onto the session filter.
     * Reading empty controls into the model would wipe search and measurement scope.
     */
    private void restorePresentationFromModel() {
        if (model == null) {
            return;
        }
        suppressFilterEvents = true;
        try {
            String query = model.searchQuery() == null ? "" : model.searchQuery();
            if (!query.equals(searchField.getText())) {
                searchField.setText(query);
            }
            SignalBrowserFilter.State state = model.browserFilter();
            if (state == null) {
                state = SignalBrowserFilter.State.none();
            }
            if (typeCombo.getItems().contains(state.source())) {
                typeCombo.getSelectionModel().select(state.source());
            }
            messageFilter.setText(state.message());
            unitFilter.setText(state.unit());
            canIdFilter.setText(state.canId());
            favoritesToggle.setSelected(state.favoritesOnly());
            recentToggle.setSelected(state.recentOnly());
            if (state.dbcFile() == null || state.dbcFile().isBlank()) {
                dbcCombo.getSelectionModel().select(ALL_DBCS);
            } else if (dbcCombo.getItems().contains(state.dbcFile())) {
                dbcCombo.getSelectionModel().select(state.dbcFile());
            }
            selectMeasurementMatching(state.sourceFile());
        } finally {
            suppressFilterEvents = false;
        }
    }

    private void selectMeasurementMatching(String sourceFile) {
        if (sourceCombo.getItems().isEmpty()) {
            return;
        }
        if (sourceFile == null || sourceFile.isBlank()) {
            sourceCombo.getSelectionModel().select(0);
            return;
        }
        java.util.List<MeasurementCatalogScope.Ref> measurements = model == null
                ? java.util.List.of()
                : MeasurementCatalogScope.measurements(model.catalog().entries(), model.catalogStore());
        for (MeasurementCatalogScope.Ref ref : measurements) {
            if (sourceFile.equalsIgnoreCase(ref.identity())
                    || sourceFile.equalsIgnoreCase(ref.displayName())
                    || sourceFile.equalsIgnoreCase(ref.comboLabel())) {
                if (sourceCombo.getItems().contains(ref.comboLabel())) {
                    sourceCombo.getSelectionModel().select(ref.comboLabel());
                    return;
                }
            }
        }
    }

    private SignalBrowserFilter.State currentFilterState() {
        SignalBrowserFilter.SourceFilter source = typeCombo.getValue() == null
                ? SignalBrowserFilter.SourceFilter.ALL
                : typeCombo.getValue();
        MeasurementCatalogScope.Ref measurement = selectedMeasurementRef();
        String sourceFile = measurement.allMeasurements()
                ? ""
                : (measurement.identity().isBlank() ? measurement.displayName() : measurement.identity());
        String dbcFile = dbcCombo.getValue();
        if (dbcFile == null || ALL_DBCS.equals(dbcFile)) {
            dbcFile = "";
        }
        return new SignalBrowserFilter.State(
                source,
                messageFilter.getText(),
                unitFilter.getText(),
                canIdFilter.getText(),
                favoritesToggle.isSelected(),
                recentToggle.isSelected(),
                sourceFile,
                dbcFile
        );
    }

    private boolean hasActiveFilters() {
        SignalBrowserFilter.State state = currentFilterState();
        return state.structuredFilterCount() > 0
                || state.favoritesOnly()
                || state.recentOnly()
                || (searchField.getText() != null && !searchField.getText().isBlank());
    }

    private List<MenuItem> buildBrowserMenuItems(SignalCatalogEntry entry) {
        MenuItem add = new MenuItem("Add to Workspace");
        add.setOnAction(e -> {
            List<SignalCatalogEntry> selected =
                    List.copyOf(catalogTable.getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) {
                selected = List.of(entry);
            }
            addToWorkspaceListener.accept(selected);
        });
        boolean fav = model != null && model.isFavorite(entry.key());
        MenuItem favItem = new MenuItem(fav ? "Remove from Favorites" : "Add to Favorites");
        favItem.setOnAction(e -> {
            if (model != null) {
                model.toggleFavorite(entry.key());
            }
        });
        MenuItem copyName = new MenuItem("Copy Signal Name");
        copyName.setOnAction(e -> copyClipboard(entry.signalName()));
        List<MenuItem> items = new java.util.ArrayList<>();
        items.add(add);
        items.add(favItem);
        items.add(new SeparatorMenuItem());
        items.add(copyName);
        if (entry.sourceType() != SignalSourceType.MF4_NATIVE) {
            MenuItem copyCan = new MenuItem("Copy CAN ID");
            copyCan.setOnAction(ev -> copyClipboard(UiFormat.canId(entry.canId())));
            items.add(copyCan);
        }
        return items;
    }

    private static void copyClipboard(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void applyRows(List<SignalCatalogEntry> filtered, List<SignalCatalogKey> previouslySelected) {
        suppressSelectionEvents = true;
        try {
            catalogTable.getItems().setAll(filtered);
            // Re-apply the current TableView sort after filtering/catalog replacement. The default
            // sort is numeric channel ascending, while an explicit engineer sort remains respected.
            catalogTable.sort();
            catalogTable.getSelectionModel().clearSelection();
            if (!previouslySelected.isEmpty()) {
                Set<SignalCatalogKey> wanted = new HashSet<>(previouslySelected);
                for (SignalCatalogEntry entry : filtered) {
                    if (wanted.contains(entry.key())) {
                        catalogTable.getSelectionModel().select(entry);
                    }
                }
            }
        } finally {
            suppressSelectionEvents = false;
        }
        selectionListener.accept(List.copyOf(catalogTable.getSelectionModel().getSelectedItems()));
    }

    private void updateCount(int total, int shown) {
        MeasurementCatalogScope.Ref measurement = selectedMeasurementRef();
        int measurementCount = model == null
                ? 0
                : MeasurementCatalogScope.measurementCount(model.catalog().entries(), model.catalogStore());
        String text = MeasurementCatalogScope.countCaption(
                shown, total, measurementCount, measurement.allMeasurements());
        countLabel.setText(text);
        String tooltip = text;
        if (model != null && model.browserViewMode() == SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES) {
            tooltip += " · Hide Duplicates is view-only; physical catalog identity is unchanged.";
        }
        countLabel.setTooltip(new Tooltip(tooltip));
        updateHealthCard(total);
        onDisplayedChanged.run();
    }

    private String allMeasurementsLabel() {
        int n = model == null ? 0 : MeasurementCatalogScope.measurementCount(
                model.catalog().entries(), model.catalogStore());
        return MeasurementCatalogScope.allMeasurementsLabel(n);
    }

    private int scopedPhysicalCount() {
        if (model == null) {
            return 0;
        }
        MeasurementCatalogScope.Ref ref = selectedMeasurementRef();
        if (!ref.allMeasurements()
                && model.catalogStore() instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore merged) {
            return merged.physicalCatalog(ref.identity(), ref.displayName()).size();
        }
        return MeasurementCatalogScope.physicalCount(model.catalog().entries(), ref);
    }

    public String countTextForTest() {
        return countLabel.getText();
    }

    public List<String> measurementOptionsForTest() {
        return List.copyOf(sourceCombo.getItems());
    }

    public void selectMeasurementForTest(String label) {
        sourceCombo.getSelectionModel().select(label);
    }

    public MeasurementCatalogScope.Ref selectedMeasurementRef() {
        List<MeasurementCatalogScope.Ref> measurements = measurementRefs();
        String selected = sourceCombo.getValue();
        if (selected == null || selected.startsWith("All Measurements") || "All Sources".equals(selected)) {
            return MeasurementCatalogScope.Ref.all(measurements.size());
        }
        for (MeasurementCatalogScope.Ref ref : measurements) {
            if (selected.equals(ref.comboLabel())
                    || selected.equals(ref.displayName())
                    || selected.equals(ref.identity())) {
                return ref;
            }
        }
        return MeasurementCatalogScope.Ref.all(measurements.size());
    }

    private void notifyMeasurementScope() {
        measurementScopeListener.accept(selectedMeasurementRef());
    }

    private List<MeasurementCatalogScope.Ref> measurementRefs() {
        if (model == null) {
            return List.of();
        }
        return MeasurementCatalogScope.measurements(model.catalog().entries(), model.catalogStore());
    }

    /**
     * Records an explicit measurement choice before presentation restore.
     * An empty browser filter means "all measurements" and would otherwise
     * replace a per-file Explore selection on the same refresh.
     */
    private void pinMeasurementScope(String sourceFile) {
        if (model == null) {
            return;
        }
        SignalBrowserFilter.State state = model.browserFilter();
        if (state == null) {
            state = SignalBrowserFilter.State.none();
        }
        String next = sourceFile == null ? "" : sourceFile;
        if (next.equals(state.sourceFile())) {
            return;
        }
        model.setBrowserFilter(new SignalBrowserFilter.State(
                state.source(),
                state.message(),
                state.unit(),
                state.canId(),
                state.favoritesOnly(),
                state.recentOnly(),
                next,
                state.dbcFile()));
    }

    private static String identityForLabel(
            String label,
            List<MeasurementCatalogScope.Ref> measurements
    ) {
        if (label == null) {
            return "";
        }
        for (MeasurementCatalogScope.Ref ref : measurements) {
            if (label.equals(ref.comboLabel())
                    || label.equals(ref.displayName())
                    || label.equals(ref.identity())) {
                return ref.identity().isBlank() ? ref.displayName() : ref.identity();
            }
        }
        return label;
    }

    private static String labelForPendingMeasurement(
            String pending,
            List<MeasurementCatalogScope.Ref> measurements
    ) {
        if (pending == null || pending.isBlank() || measurements == null) {
            return null;
        }
        for (MeasurementCatalogScope.Ref ref : measurements) {
            if (pending.equals(ref.identity())
                    || pending.equals(ref.displayName())
                    || pending.equals(ref.comboLabel())) {
                return ref.comboLabel();
            }
        }
        return null;
    }

    private static String mapLegacySourceSelection(
            String selected,
            List<MeasurementCatalogScope.Ref> measurements,
            String allLabel
    ) {
        if (selected == null || selected.startsWith("All Measurements") || "All Sources".equals(selected)) {
            return allLabel;
        }
        for (MeasurementCatalogScope.Ref ref : measurements) {
            if (selected.equals(ref.comboLabel())
                    || selected.equals(ref.displayName())
                    || selected.equals(ref.identity())) {
                return ref.comboLabel();
            }
        }
        return allLabel;
    }

    private void updateHealthCard(int totalSignals) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        if (totalSignals <= 0) {
            healthValue.setText("Idle");
            healthCaption.setText("No catalog loaded");
            healthStorageBar.setProgress(0.0);
            healthStorageLabel.setText("Awaiting processed signals");
        } else {
            healthValue.setText("Healthy");
            healthCaption.setText("Local workspace");
            healthStorageBar.setProgress(Math.min(1.0, 0.18 + Math.min(0.72, totalSignals / 500.0)));
            healthStorageLabel.setText(nf.format(totalSignals) + " signals indexed");
        }
    }

    private static int channelNumberFromLabel(String label) {
        if (label == null || !label.startsWith("Ch")) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(label.substring(2).trim());
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private String channelLabel(SignalCatalogEntry entry) {
        return SignalChannelLabels.compact(entry);
    }

    private void updateEmptyState(boolean noStore, boolean catalogEmpty, boolean filteredEmpty) {
        boolean show;
        boolean showClear = false;
        if (noStore || catalogEmpty) {
            emptyTitle.setText("No signals");
            emptyBody.setText(noStore
                    ? "Parse a measurement file to browse signals."
                    : "No signals were discovered.");
            show = true;
        } else if (filteredEmpty) {
            emptyTitle.setText("No signals match your search/filter");
            emptyBody.setText("Try clearing search or filters to see all available signals.");
            show = true;
            showClear = true;
        } else {
            show = false;
        }
        emptyClearButton.setVisible(showClear);
        emptyClearButton.setManaged(showClear);
        emptyState.setVisible(show);
        emptyState.setManaged(show);
        if (show) {
            emptyState.toFront();
        } else {
            // Ensure a leftover overlay cannot cover rows after a successful catalog refresh.
            emptyState.toBack();
            catalogTable.toFront();
        }
    }

    private void clearSearchAndFilters() {
        searchField.clear();
        favoritesToggle.setSelected(false);
        recentToggle.setSelected(false);
        clearStructuredFilters();
    }

    private void addFocusedToWorkspace() {
        List<SignalCatalogEntry> selected = List.copyOf(catalogTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            SignalCatalogEntry focused = catalogTable.getFocusModel().getFocusedItem();
            if (focused != null) {
                selected = List.of(focused);
                catalogTable.getSelectionModel().select(focused);
            }
        }
        if (selected.isEmpty()) {
            return;
        }
        addToWorkspaceListener.accept(selected);
    }
}

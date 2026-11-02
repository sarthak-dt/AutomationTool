package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.CanMessageInventoryRow;
import com.stellar.platform.desktop.presentation.CanStudySignalDefinitionView;
import com.stellar.platform.desktop.presentation.DbcSignalDefinitionView;
import com.stellar.platform.desktop.presentation.InventoryEnrichmentMatchState;
import com.stellar.platform.desktop.presentation.InventoryStudySet;
import com.stellar.platform.desktop.presentation.StudyMessageEntry;
import com.stellar.platform.desktop.presentation.StudySetProjection;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.ui.StellarIcons;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Right-pane master-detail inspector for Source Inventory.
 * Two independent modes: Inspect (selectedCanRow) and Study Set (persistent studySelection).
 */
public final class CanInventoryDetailsInspector extends BorderPane {

    public enum Mode {
        INSPECT,
        STUDY
    }

    private final NumberFormat intFormat = NumberFormat.getIntegerInstance(Locale.US);

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleButton inspectModeButton = new ToggleButton("Inspect");
    private final ToggleButton studyModeButton = new ToggleButton("Study Set (0)");
    private final Button collapseButton = new Button();

    private final Label emptyHint = new Label("Select a CAN ID to inspect its DBC and signal definitions.");

    private final Label canIdValue = new Label("\u2014");
    private final Label matchValue = new Label("\u2014");
    private final Label canMetaLine = new Label("\u2014");
    private final Label dbcNameValue = new Label("\u2014");
    private final Label messageMetaLine = new Label("\u2014");
    private final Label mappingValue = new Label("\u2014");
    private final Label typeValue = new Label("\u2014");
    private final Label channelValue = new Label("\u2014");
    private final Label framesValue = new Label("\u2014");
    private final Label messageValue = new Label("\u2014");
    private final Label dlcValue = new Label("\u2014");
    private final Label signalCountValue = new Label("\u2014");

    private final Label unmatchedSignalsHint = new Label(
            "No signal definitions are available because this CAN ID is not defined in the current DBC.");

    private final TextField signalSearch = new TextField();
    private final TableView<DbcSignalDefinitionView> signalTable = new TableView<>();
    private final ObservableList<DbcSignalDefinitionView> allSignals = FXCollections.observableArrayList();
    private final ObservableList<DbcSignalDefinitionView> visibleSignals = FXCollections.observableArrayList();

    private final Label signalMetaName = new Label("");
    private final Label signalMetaBody = new Label("");
    private final VBox signalMetaBox = new VBox(2);

    private final VBox summaryBox = new VBox(6);
    private final VBox signalsSection = new VBox(4);
    private final VBox unmatchedSignalsSection = new VBox(4);
    private final HBox headerBar;
    private final VBox inspectTop = new VBox(4);

    private final Label studyTitle = new Label("Channel Study");
    private final Label studyStaleLabel = new Label(
            "Configuration changed. This Signal Preview is no longer the result you last reviewed.");
    private final Button studyStaleAcknowledgeButton = new Button("Study Channel Again");
    private final VBox studyStaleBar = new VBox(4);
    private final Label studySummaryLine = new Label("");
    private final Label studyMessagesMetric = new Label("0");
    private final Label studySignalsMetric = new Label("0");
    private final Label studyChannelsMetric = new Label("0");
    private final HBox studyMetricsRow = new HBox(8);
    private final TextField studySignalSearch = new TextField();
    private final Button studyClearButton = new Button("Clear Study");
    private final TableView<CanStudySignalDefinitionView> studyTable = new TableView<>();
    private final ObservableList<CanStudySignalDefinitionView> studyRows = FXCollections.observableArrayList();
    private final Label studyEmptyHint = new Label(
            "Study Set is empty\n\nSelect matched CAN messages from the inventory to add them here.");
    private final VBox studySummaryBox = new VBox(3);
    private final VBox studyContentSection = new VBox(4);
    private final VBox studyEmptySection = new VBox(8);
    private final VBox studyTop = new VBox(4);

    private InventoryStudySet studySet;
    private CanMessageInventoryRow inspectRow;
    private Mode mode = Mode.INSPECT;
    private Runnable onCollapseRequest;
    private Runnable onClearStudyRequest;
    private boolean showingContent;
    private boolean suppressModeCallback;

    public CanInventoryDetailsInspector() {
        getStyleClass().add("source-inventory-details");
        setMinWidth(310);
        setPrefWidth(330);
        setMaxWidth(360);
        setMinHeight(0);
        setMaxHeight(Double.MAX_VALUE);

        inspectModeButton.setToggleGroup(modeGroup);
        studyModeButton.setToggleGroup(modeGroup);
        inspectModeButton.setSelected(true);
        inspectModeButton.getStyleClass().addAll("inventory-mode-tab", "inventory-mode-tab-active");
        studyModeButton.getStyleClass().add("inventory-mode-tab");
        inspectModeButton.setFocusTraversable(false);
        studyModeButton.setFocusTraversable(false);
        modeGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (suppressModeCallback || b == null) {
                if (b == null) {
                    suppressModeCallback = true;
                    modeGroup.selectToggle(a);
                    suppressModeCallback = false;
                }
                return;
            }
            if (b == studyModeButton) {
                applyMode(Mode.STUDY);
            } else {
                applyMode(Mode.INSPECT);
            }
            refreshModeTabStyles();
        });

        collapseButton.setGraphic(StellarIcons.collapseLeft());
        collapseButton.getStyleClass().addAll("secondary-button", "compact-button", "inventory-details-collapse");
        collapseButton.setTooltip(new Tooltip("Collapse Details"));
        collapseButton.setOnAction(e -> {
            if (onCollapseRequest != null) {
                onCollapseRequest.run();
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        headerBar = new HBox(6, inspectModeButton, studyModeButton, spacer, collapseButton);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setPadding(new Insets(0, 0, 4, 0));
        headerBar.setMaxHeight(Region.USE_PREF_SIZE);

        emptyHint.getStyleClass().addAll("muted", "inventory-details-empty");
        emptyHint.setWrapText(true);

        buildCompactSummary();
        configureSignalTable();
        buildStudyUi();

        signalSearch.setPromptText("Search signals...");
        signalSearch.getStyleClass().addAll("search-field", "inventory-signal-search");
        signalSearch.setMinHeight(28);
        signalSearch.setPrefHeight(30);
        signalSearch.setMaxHeight(32);
        signalSearch.textProperty().addListener((o, a, b) -> applySignalFilter());

        signalMetaName.getStyleClass().add("inventory-signal-meta-name");
        signalMetaBody.getStyleClass().addAll("muted", "inventory-signal-meta-body");
        signalMetaBody.setWrapText(true);
        signalMetaBox.getChildren().setAll(signalMetaName, signalMetaBody);
        signalMetaBox.setVisible(false);
        signalMetaBox.setManaged(false);
        signalMetaBox.setMaxHeight(Region.USE_PREF_SIZE);

        Label signalsTitle = sectionTitle("SIGNAL DEFINITIONS");
        VBox.setVgrow(signalTable, Priority.ALWAYS);
        signalsSection.getStyleClass().add("inventory-details-section");
        signalsSection.setSpacing(3);
        signalsSection.setFillWidth(true);
        signalsSection.setMinHeight(0);
        signalsSection.setMaxHeight(Double.MAX_VALUE);
        signalsSection.getChildren().setAll(signalsTitle, signalSearch, signalTable, signalMetaBox);

        unmatchedSignalsHint.getStyleClass().addAll("muted", "inventory-unmatched-hint");
        unmatchedSignalsHint.setWrapText(true);
        unmatchedSignalsSection.getStyleClass().add("inventory-details-section");
        unmatchedSignalsSection.getChildren().setAll(sectionTitle("SIGNAL DEFINITIONS"), unmatchedSignalsHint);
        unmatchedSignalsSection.setVisible(false);
        unmatchedSignalsSection.setManaged(false);

        inspectTop.getChildren().setAll(headerBar, summaryBox);
        inspectTop.setFillWidth(true);
        inspectTop.setMaxHeight(Region.USE_PREF_SIZE);
        setTop(inspectTop);

        showEmpty();
    }

    private void buildStudyUi() {
        studyTitle.getStyleClass().addAll("inventory-details-section-title", "study-panel-title");
        studySummaryLine.getStyleClass().add("inventory-details-value");
        studySummaryLine.setTextOverrun(OverrunStyle.ELLIPSIS);
        studySummaryLine.setMaxWidth(Double.MAX_VALUE);
        // Keep the compact textual summary for accessibility/tests, while the visible product
        // chrome uses three scan-friendly metrics.
        studySummaryLine.setVisible(false);
        studySummaryLine.setManaged(false);

        configureStudyMetric(studyMessagesMetric);
        configureStudyMetric(studySignalsMetric);
        configureStudyMetric(studyChannelsMetric);
        studyMetricsRow.getStyleClass().add("study-metrics-row");
        studyMetricsRow.setAlignment(Pos.CENTER_LEFT);
        VBox messageMetric = studyMetricCard(studyMessagesMetric, "CAN Messages");
        VBox signalMetric = studyMetricCard(studySignalsMetric, "Signal Definitions");
        VBox channelMetric = studyMetricCard(studyChannelsMetric, "Channels");
        HBox.setHgrow(messageMetric, Priority.ALWAYS);
        HBox.setHgrow(signalMetric, Priority.ALWAYS);
        HBox.setHgrow(channelMetric, Priority.ALWAYS);
        studyMetricsRow.getChildren().setAll(messageMetric, signalMetric, channelMetric);

        studySignalSearch.setPromptText("Search signals...");
        studySignalSearch.getStyleClass().addAll("search-field", "inventory-signal-search");
        studySignalSearch.setMinHeight(28);
        studySignalSearch.setPrefHeight(30);
        studySignalSearch.setMaxHeight(32);
        studySignalSearch.textProperty().addListener((o, a, b) -> refreshStudyProjection());

        studyClearButton.getStyleClass().addAll("secondary-button", "compact-button");
        studyClearButton.setOnAction(e -> {
            if (onClearStudyRequest != null) {
                onClearStudyRequest.run();
            } else if (studySet != null) {
                studySet.clear();
            }
        });

        HBox studySearchRow = new HBox(6, studySignalSearch, studyClearButton);
        HBox.setHgrow(studySignalSearch, Priority.ALWAYS);
        studySearchRow.setAlignment(Pos.CENTER_LEFT);
        studySearchRow.setMaxHeight(Region.USE_PREF_SIZE);

        // Study is a read-only preview of what a channel/DBC configuration could expose. When that
        // configuration changes underneath the set, say so instead of presenting a stale preview.
        studyStaleLabel.getStyleClass().add("study-stale-message");
        studyStaleLabel.setWrapText(true);
        studyStaleAcknowledgeButton.getStyleClass().addAll("secondary-button", "compact-button",
                "study-stale-refresh");
        studyStaleAcknowledgeButton.setOnAction(e -> {
            if (studySet != null) {
                studySet.acknowledgeStale();
            }
        });
        studyStaleBar.getStyleClass().add("study-stale-bar");
        studyStaleBar.getChildren().setAll(studyStaleLabel, studyStaleAcknowledgeButton);
        studyStaleBar.setVisible(false);
        studyStaleBar.setManaged(false);
        studyStaleBar.setMaxHeight(Region.USE_PREF_SIZE);

        // Compact header only — selection management stays in the center inventory table.
        studySummaryBox.getChildren().setAll(
                studyTitle, studySummaryLine, studyStaleBar, studyMetricsRow, studySearchRow);
        studySummaryBox.setSpacing(7);
        studySummaryBox.setMaxHeight(Region.USE_PREF_SIZE);

        configureStudyTable();
        VBox.setVgrow(studyTable, Priority.ALWAYS);
        studyContentSection.getStyleClass().add("inventory-details-section");
        studyContentSection.setSpacing(0);
        studyContentSection.setFillWidth(true);
        studyContentSection.setMinHeight(0);
        studyContentSection.setMaxHeight(Double.MAX_VALUE);
        studyContentSection.getChildren().setAll(studyTable);

        studyEmptyHint.getStyleClass().addAll("muted", "inventory-details-empty");
        studyEmptyHint.setWrapText(true);
        studyEmptySection.getChildren().setAll(studyEmptyHint);
        studyEmptySection.setPadding(new Insets(8, 4, 4, 4));

        studyTop.getChildren().setAll(studySummaryBox);
        studyTop.setFillWidth(true);
        studyTop.setMaxHeight(Region.USE_PREF_SIZE);
    }

    private static void configureStudyMetric(Label value) {
        value.getStyleClass().add("study-metric-value");
        value.setMaxWidth(Double.MAX_VALUE);
    }

    private static VBox studyMetricCard(Label value, String captionText) {
        Label caption = new Label(captionText);
        caption.getStyleClass().add("study-metric-caption");
        VBox card = new VBox(1, value, caption);
        card.getStyleClass().add("study-metric-card");
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private void configureStudyTable() {
        studyTable.getStyleClass().addAll("inventory-signal-list", "inventory-study-signal-list");
        studyTable.setItems(studyRows);
        studyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        studyTable.setFixedCellSize(28);
        studyTable.setPlaceholder(new Label("No signal definitions in Study Set."));
        studyTable.setMinHeight(80);
        studyTable.setPrefHeight(Region.USE_COMPUTED_SIZE);
        studyTable.setMaxHeight(Double.MAX_VALUE);
        studyTable.setMinWidth(0);
        studyTable.setMaxWidth(Double.MAX_VALUE);

        TableColumn<CanStudySignalDefinitionView, String> chCol = new TableColumn<>("Ch");
        chCol.setMinWidth(36);
        chCol.setPrefWidth(44);
        chCol.setMaxWidth(56);
        chCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().channelLabel()));

        TableColumn<CanStudySignalDefinitionView, String> canCol = new TableColumn<>("CAN ID");
        canCol.setMinWidth(56);
        canCol.setPrefWidth(72);
        canCol.setMaxWidth(96);
        canCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().canIdHex()));
        canCol.setCellFactory(col -> studyEllipsisCell(true));

        TableColumn<CanStudySignalDefinitionView, String> nameCol = new TableColumn<>("Signal Name");
        nameCol.setMinWidth(80);
        nameCol.setPrefWidth(150);
        nameCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().signalName()));
        nameCol.setCellFactory(col -> studyEllipsisCell(true));

        TableColumn<CanStudySignalDefinitionView, String> startCol = new TableColumn<>("Start");
        startCol.setMinWidth(40);
        startCol.setPrefWidth(48);
        startCol.setMaxWidth(56);
        startCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().startDisplay()));

        TableColumn<CanStudySignalDefinitionView, String> lenCol = new TableColumn<>("Len");
        lenCol.setMinWidth(36);
        lenCol.setPrefWidth(42);
        lenCol.setMaxWidth(50);
        lenCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().lenDisplay()));

        TableColumn<CanStudySignalDefinitionView, String> unitCol = new TableColumn<>("Unit");
        unitCol.setMinWidth(40);
        unitCol.setPrefWidth(52);
        unitCol.setMaxWidth(68);
        unitCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().unitDisplay()));

        studyTable.getColumns().setAll(chCol, canCol, nameCol, startCol, lenCol, unitCol);
    }

    /** Tooltip carries DBC Message provenance without a dedicated Message column. */
    private TableCell<CanStudySignalDefinitionView, String> studyEllipsisCell(boolean includeMessageTooltip) {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isEmpty()) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item);
                setTextOverrun(OverrunStyle.ELLIPSIS);
                CanStudySignalDefinitionView row = getTableRow() == null ? null : getTableRow().getItem();
                if (includeMessageTooltip && row != null) {
                    setTooltip(new Tooltip(
                            row.channelLabel() + " \u00b7 " + row.canIdHex()
                                    + " \u00b7 " + row.dbcMessageDisplay()
                                    + " \u00b7 " + row.signalName()));
                } else {
                    setTooltip(new Tooltip(item));
                }
            }
        };
    }

    private void buildCompactSummary() {
        canIdValue.getStyleClass().addAll("inventory-details-value", "inventory-can-id");
        matchValue.getStyleClass().addAll("inventory-details-value", "inventory-match-badge");
        canMetaLine.getStyleClass().addAll("muted", "inventory-details-meta-line");
        canMetaLine.setTextOverrun(OverrunStyle.ELLIPSIS);
        dbcNameValue.getStyleClass().add("inventory-details-value");
        dbcNameValue.setTextOverrun(OverrunStyle.ELLIPSIS);
        messageMetaLine.getStyleClass().addAll("muted", "inventory-details-meta-line");
        messageMetaLine.setTextOverrun(OverrunStyle.ELLIPSIS);
        mappingValue.setManaged(false);
        mappingValue.setVisible(false);
        typeValue.setManaged(false);
        typeValue.setVisible(false);
        channelValue.setManaged(false);
        channelValue.setVisible(false);
        framesValue.setManaged(false);
        framesValue.setVisible(false);
        messageValue.setManaged(false);
        messageValue.setVisible(false);
        dlcValue.setManaged(false);
        dlcValue.setVisible(false);
        signalCountValue.setManaged(false);
        signalCountValue.setVisible(false);

        Region idSpacer = new Region();
        HBox.setHgrow(idSpacer, Priority.ALWAYS);
        HBox idRow = new HBox(8, canIdValue, idSpacer, matchValue);
        idRow.setAlignment(Pos.CENTER_LEFT);

        summaryBox.setSpacing(3);
        summaryBox.getChildren().setAll(idRow, canMetaLine, dbcNameValue, messageMetaLine);
        summaryBox.setMinHeight(56);
        summaryBox.setMaxHeight(Region.USE_PREF_SIZE);
        summaryBox.setVisible(false);
        summaryBox.setManaged(false);
    }

    public void bindStudySet(InventoryStudySet studySet) {
        this.studySet = Objects.requireNonNull(studySet, "studySet");
        studySet.addListener(this::onStudySetChanged);
        onStudySetChanged();
    }

    public void setOnCollapseRequest(Runnable onCollapseRequest) {
        this.onCollapseRequest = onCollapseRequest;
    }

    public void setModeChromeVisible(boolean visible) {
        headerBar.setVisible(visible);
        headerBar.setManaged(visible);
    }

    public void setOnClearStudyRequest(Runnable onClearStudyRequest) {
        this.onClearStudyRequest = onClearStudyRequest;
    }

    public void showStudyMode() {
        suppressModeCallback = true;
        studyModeButton.setSelected(true);
        suppressModeCallback = false;
        applyMode(Mode.STUDY);
        refreshModeTabStyles();
    }

    public boolean isStudyMode() {
        return mode == Mode.STUDY;
    }

    public void showInspectMode() {
        suppressModeCallback = true;
        inspectModeButton.setSelected(true);
        suppressModeCallback = false;
        applyMode(Mode.INSPECT);
        refreshModeTabStyles();
    }

    public Mode mode() {
        return mode;
    }

    public void setSelection(CanMessageInventoryRow row) {
        this.inspectRow = row;
        if (mode != Mode.INSPECT) {
            if (row == null) {
                clearInspectContent();
            } else {
                populateInspectContent(row);
            }
            return;
        }
        if (row == null) {
            clearSelection();
            return;
        }
        showInspectContent(row);
    }

    public void clearSelection() {
        inspectRow = null;
        clearInspectContent();
        if (mode == Mode.INSPECT) {
            showEmpty();
        }
    }

    private void showInspectContent(CanMessageInventoryRow row) {
        populateInspectContent(row);
        showingContent = true;
        summaryBox.setVisible(true);
        summaryBox.setManaged(true);
        setTop(inspectTop);
        if (!inspectTop.getChildren().contains(headerBar) || !inspectTop.getChildren().contains(summaryBox)) {
            inspectTop.getChildren().setAll(headerBar, summaryBox);
        }
        boolean matched = row.matchState() == InventoryEnrichmentMatchState.MATCHED;
        if (matched) {
            setCenter(signalsSection);
            BorderPane.setMargin(signalsSection, new Insets(4, 0, 0, 0));
            unmatchedSignalsSection.setVisible(false);
            unmatchedSignalsSection.setManaged(false);
        } else {
            setCenter(unmatchedSignalsSection);
            BorderPane.setMargin(unmatchedSignalsSection, new Insets(4, 0, 0, 0));
            unmatchedSignalsSection.setVisible(true);
            unmatchedSignalsSection.setManaged(true);
        }
    }

    private void populateInspectContent(CanMessageInventoryRow row) {
        canIdValue.setText(row.canIdHex());
        typeValue.setText(row.typeFull());
        channelValue.setText(row.channelLabel());
        framesValue.setText(intFormat.format(row.frameCount()));
        canMetaLine.setText(row.typeFull() + " \u00b7 " + row.channelLabel() + " \u00b7 "
                + intFormat.format(row.frameCount()) + " frames");

        String defName = row.definitionDisplayName();
        String map = mappingLabel(row);
        mappingValue.setText(map);
        if (defName == null || defName.isBlank()) {
            dbcNameValue.setText(map);
        } else if ("\u2014".equals(map)) {
            dbcNameValue.setText(defName);
        } else {
            dbcNameValue.setText(defName + " \u00b7 " + map);
        }
        matchValue.setText(row.matchBadge());
        messageValue.setText(row.dbcMessageDisplay());
        dlcValue.setText(row.dlc().map(String::valueOf).orElse("\u2014"));
        signalCountValue.setText(row.matchState() == InventoryEnrichmentMatchState.MATCHED
                ? Integer.toString(row.signalCount())
                : "\u2014");
        if (row.matchState() == InventoryEnrichmentMatchState.MATCHED) {
            messageMetaLine.setText(row.dbcMessageDisplay()
                    + " \u00b7 DLC " + row.dlc().map(String::valueOf).orElse("\u2014")
                    + " \u00b7 " + row.signalCount() + " signals");
        } else if (row.matchState() == InventoryEnrichmentMatchState.UNMATCHED) {
            messageMetaLine.setText("Not defined in the current DBC");
            unmatchedSignalsHint.setText(
                    "No signal definitions are available because this CAN ID is not defined in the current DBC.");
        } else if (row.matchState() == InventoryEnrichmentMatchState.NO_DBC) {
            messageMetaLine.setText("No DBC mapped/recommended for this source channel");
            unmatchedSignalsHint.setText("No DBC mapped/recommended for this source channel.");
        } else if (row.matchState() == InventoryEnrichmentMatchState.AMBIGUOUS) {
            messageMetaLine.setText("AMBIGUOUS DBC MAPPING \u2014 no single authoritative definition");
            unmatchedSignalsHint.setText("AMBIGUOUS DBC MAPPING \u2014 no single authoritative definition.");
        } else {
            messageMetaLine.setText(row.dbcMessageDisplay());
            unmatchedSignalsHint.setText("No signal definitions available for this CAN ID.");
        }

        boolean matched = row.matchState() == InventoryEnrichmentMatchState.MATCHED;
        allSignals.setAll(matched ? row.signalDefinitions() : java.util.List.of());
        if (!matched) {
            signalSearch.clear();
        }
        applySignalFilter();
        signalTable.getSelectionModel().clearSelection();
        clearSignalMeta();
    }

    private void clearInspectContent() {
        allSignals.clear();
        visibleSignals.clear();
        signalSearch.clear();
        signalTable.getSelectionModel().clearSelection();
        clearSignalMeta();
        summaryBox.setVisible(false);
        summaryBox.setManaged(false);
        showingContent = false;
    }

    private void onStudySetChanged() {
        int n = studySet == null ? 0 : studySet.size();
        studyModeButton.setText("Study Set (" + n + ")");
        studyClearButton.setDisable(n == 0);
        refreshStudyProjection();
        if (mode == Mode.STUDY) {
            renderStudyMode();
        }
    }

    private void applyMode(Mode next) {
        mode = next;
        if (next == Mode.STUDY) {
            renderStudyMode();
        } else if (inspectRow != null) {
            showInspectContent(inspectRow);
        } else {
            showEmpty();
        }
    }

    private void renderStudyMode() {
        // Reparent shared mode tabs into the Study top stack without discarding Study chrome.
        if (studyTop.getChildren().isEmpty() || studyTop.getChildren().get(0) != headerBar) {
            studyTop.getChildren().setAll(headerBar, studySummaryBox);
        }
        setTop(studyTop);
        if (studySet == null || studySet.isEmpty()) {
            studySummaryBox.setVisible(false);
            studySummaryBox.setManaged(false);
            setCenter(studyEmptySection);
            BorderPane.setMargin(studyEmptySection, new Insets(4, 0, 0, 0));
            return;
        }
        studySummaryBox.setVisible(true);
        studySummaryBox.setManaged(true);
        setCenter(studyContentSection);
        BorderPane.setMargin(studyContentSection, new Insets(4, 0, 0, 0));
    }

    private void refreshStudyProjection() {
        if (studySet == null) {
            studyRows.clear();
            studySummaryLine.setText("");
            studyMessagesMetric.setText("0");
            studySignalsMetric.setText("0");
            studyChannelsMetric.setText("0");
            applyStudyStaleState(0);
            return;
        }
        java.util.List<StudyMessageEntry> entries = studySet.entries();
        StudySetProjection.Summary summary = StudySetProjection.summarize(entries);
        studySummaryLine.setText(summary.compactLine());
        studyMessagesMetric.setText(intFormat.format(summary.messageCount()));
        studySignalsMetric.setText(intFormat.format(summary.signalCount()));
        studyChannelsMetric.setText(intFormat.format(summary.channelCount()));
        studyRows.setAll(StudySetProjection.buildFlat(entries, studySignalSearch.getText()));
        applyStudyStaleState(studySet.staleCount());
    }

    private void applyStudyStaleState(int staleCount) {
        boolean stale = staleCount > 0;
        if (stale) {
            String noun = staleCount == 1 ? "message" : "messages";
            studyStaleLabel.setText("Configuration changed for " + staleCount + " CAN " + noun
                    + ". This Signal Preview is no longer the result you last reviewed.");
        }
        studyStaleBar.setVisible(stale);
        studyStaleBar.setManaged(stale);
    }

    /** Test hook: stale-configuration notice shown above the Signal Preview. */
    public VBox studyStaleBarForTest() {
        return studyStaleBar;
    }

    /** Test hook: action that re-affirms the refreshed Signal Preview. */
    public Button studyStaleAcknowledgeButtonForTest() {
        return studyStaleAcknowledgeButton;
    }

    private void refreshModeTabStyles() {
        inspectModeButton.getStyleClass().remove("inventory-mode-tab-active");
        studyModeButton.getStyleClass().remove("inventory-mode-tab-active");
        if (mode == Mode.STUDY) {
            if (!studyModeButton.getStyleClass().contains("inventory-mode-tab-active")) {
                studyModeButton.getStyleClass().add("inventory-mode-tab-active");
            }
        } else if (!inspectModeButton.getStyleClass().contains("inventory-mode-tab-active")) {
            inspectModeButton.getStyleClass().add("inventory-mode-tab-active");
        }
    }

    public boolean isShowingContentForTest() {
        return mode == Mode.INSPECT && showingContent;
    }

    public String canIdForTest() {
        return canIdValue.getText();
    }

    public String matchForTest() {
        return matchValue.getText();
    }

    public String messageForTest() {
        return messageValue.getText();
    }

    public String mappingForTest() {
        return mappingValue.getText();
    }

    public String definitionForTest() {
        return dbcNameValue.getText();
    }

    public int visibleSignalCountForTest() {
        return visibleSignals.size();
    }

    public TableView<DbcSignalDefinitionView> signalTableForTest() {
        return signalTable;
    }

    public TableView<CanStudySignalDefinitionView> studyTableForTest() {
        return studyTable;
    }

    public String studySummaryForTest() {
        return studySummaryLine.getText();
    }

    /** Study Set no longer renders per-message chips; selection is center-table only. */
    public boolean hasStudyMessageChipsForTest() {
        return false;
    }

    public double studySummaryHeightForTest() {
        return studySummaryBox.isManaged() ? studySummaryBox.getHeight() : 0;
    }

    public double studyTableHeightForTest() {
        return studyTable.getHeight();
    }

    public Mode modeForTest() {
        return mode;
    }

    public ToggleButton studyModeButtonForTest() {
        return studyModeButton;
    }

    public ToggleButton inspectModeButtonForTest() {
        return inspectModeButton;
    }

    public TextField studySignalSearchForTest() {
        return studySignalSearch;
    }

    public CanMessageInventoryRow inspectRowForTest() {
        return inspectRow;
    }

    public VBox summaryBoxForTest() {
        return summaryBox;
    }

    public boolean isShowingSignalTableForTest() {
        return mode == Mode.INSPECT && getCenter() == signalsSection;
    }

    public boolean isShowingUnmatchedSignalsHintForTest() {
        return mode == Mode.INSPECT && getCenter() == unmatchedSignalsSection;
    }

    public boolean isShowingStudyTableForTest() {
        return mode == Mode.STUDY && getCenter() == studyContentSection;
    }

    public boolean isShowingStudyEmptyForTest() {
        return mode == Mode.STUDY && getCenter() == studyEmptySection;
    }

    public double signalTableHeightForTest() {
        return signalTable.getHeight();
    }

    public double summaryHeightForTest() {
        return summaryBox.getHeight();
    }

    public void filterSignalsForTest(String query) {
        signalSearch.setText(query == null ? "" : query);
    }

    private void showEmpty() {
        showingContent = false;
        // Keep summaryBox in the top stack (managed=false) so layout metrics stay stable when
        // Inspect content returns — avoid re-inserting which can leave height ~0 until a later pulse.
        if (!inspectTop.getChildren().contains(headerBar) || !inspectTop.getChildren().contains(summaryBox)) {
            inspectTop.getChildren().setAll(headerBar, summaryBox);
        }
        setTop(inspectTop);
        setCenter(emptyHint);
        BorderPane.setAlignment(emptyHint, Pos.TOP_LEFT);
        BorderPane.setMargin(emptyHint, new Insets(8, 4, 4, 4));
        summaryBox.setVisible(false);
        summaryBox.setManaged(false);
    }

    private void configureSignalTable() {
        signalTable.getStyleClass().add("inventory-signal-list");
        signalTable.setItems(visibleSignals);
        signalTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        signalTable.setFixedCellSize(28);
        signalTable.setPlaceholder(new Label("No signal definitions."));
        signalTable.setMinHeight(80);
        signalTable.setPrefHeight(Region.USE_COMPUTED_SIZE);
        signalTable.setMaxHeight(Double.MAX_VALUE);
        signalTable.setMinWidth(0);
        signalTable.setMaxWidth(Double.MAX_VALUE);

        TableColumn<DbcSignalDefinitionView, String> nameCol = new TableColumn<>("Name");
        nameCol.setMinWidth(100);
        nameCol.setPrefWidth(220);
        nameCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().name()));
        nameCol.setCellFactory(col -> new TableCell<>() {
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
                setTooltip(new Tooltip(item));
            }
        });

        TableColumn<DbcSignalDefinitionView, String> startCol = new TableColumn<>("Start");
        startCol.setMinWidth(40);
        startCol.setPrefWidth(48);
        startCol.setMaxWidth(56);
        startCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(Integer.toString(c.getValue().startBit())));

        TableColumn<DbcSignalDefinitionView, String> lenCol = new TableColumn<>("Len");
        lenCol.setMinWidth(36);
        lenCol.setPrefWidth(42);
        lenCol.setMaxWidth(50);
        lenCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(Integer.toString(c.getValue().bitLength())));

        TableColumn<DbcSignalDefinitionView, String> unitCol = new TableColumn<>("Unit");
        unitCol.setMinWidth(40);
        unitCol.setPrefWidth(52);
        unitCol.setMaxWidth(68);
        unitCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().unitDisplay()));

        signalTable.getColumns().setAll(nameCol, startCol, lenCol, unitCol);

        signalTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b == null) {
                clearSignalMeta();
                return;
            }
            signalMetaName.setText(b.name());
            signalMetaBody.setText(
                    b.byteOrderLabel() + " \u00b7 " + b.signednessLabel()
                            + "\nFactor " + b.factor() + " \u00b7 Offset " + b.offset()
                            + "\nDefined Min " + b.definedMinimum()
                            + " \u00b7 Defined Max " + b.definedMaximum()
                            + "\nMux " + b.multiplexLabel()
                            + (b.receiversDisplay().equals("\u2014") ? "" : "\nReceivers " + b.receiversDisplay())
                            + "\n" + b.conversionHint()
            );
            signalMetaBox.setVisible(true);
            signalMetaBox.setManaged(true);
        });
    }

    private void applySignalFilter() {
        String q = signalSearch.getText() == null ? "" : signalSearch.getText().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            visibleSignals.setAll(allSignals);
            return;
        }
        java.util.ArrayList<DbcSignalDefinitionView> next = new java.util.ArrayList<>();
        for (DbcSignalDefinitionView s : allSignals) {
            if (s.name().toLowerCase(Locale.ROOT).contains(q)) {
                next.add(s);
            }
        }
        visibleSignals.setAll(next);
    }

    private void clearSignalMeta() {
        signalMetaName.setText("");
        signalMetaBody.setText("");
        signalMetaBox.setVisible(false);
        signalMetaBox.setManaged(false);
    }

    private static String mappingLabel(CanMessageInventoryRow row) {
        if (row.mappingStatus().isEmpty()) {
            return "\u2014";
        }
        ChannelMappingStatus status = row.mappingStatus().get();
        if (status == ChannelMappingStatus.RECOMMENDED) {
            return "RECOMMENDED \u00b7 not confirmed";
        }
        return status.badgeLabel();
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("inventory-details-section-title");
        return label;
    }

}

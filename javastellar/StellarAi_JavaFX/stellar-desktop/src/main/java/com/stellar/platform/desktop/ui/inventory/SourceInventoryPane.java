package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.desktop.presentation.DbcSignalDefinitionFormatter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.CanTypeFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.ContentFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.MatchFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterEngine;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterResult;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterResult.FilteredChannel;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterResult.FilteredMessage;
import com.stellar.platform.desktop.presentation.SourceInventoryMatchState;
import com.stellar.platform.desktop.presentation.SourceInventoryModel;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanDefinitionMatchStatus;
import com.stellar.platform.domain.inventory.CanDefinitionMatchSummary;
import com.stellar.platform.domain.inventory.CanInventoryDiagnostic;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageMatch;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.text.NumberFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Legacy single-source Source Inventory UI (DBC hierarchy / match filters).
 * Retained for regression tests of the filter engine presentation helpers.
 * Product UI uses {@link SourceInventoryWorkspace} (Phase 1 multi-source).
 *
 * @deprecated Use {@link SourceInventoryWorkspace}.
 */
@Deprecated
public final class SourceInventoryPane extends VBox {

    private static final String CHANNEL_ALL = "All";

    private final Label titleLabel = new Label("Source Inventory");
    private final Label fileLabel = new Label("File: \u2014");
    private final Label formatLabel = new Label("Format: \u2014");
    private final Label channelSummaryLabel = new Label("Discovering CAN channels...");
    private final Label observedUniqueLabel = new Label("Unique CAN IDs: \u2014");
    private final Label statusLabel = new Label("");

    private final VBox matchingCard = new VBox(4);
    private final Label matchingTitle = new Label("DBC Matching");
    private final Label matchingStatusLabel = new Label("No DBC selected");
    private final Label matchingDbcLabel = new Label("");
    private final Label matchingCountsLabel = new Label("");
    private final Label matchingPercentLabel = new Label("");

    private final TextField searchField = new TextField();
    private final ComboBox<ChannelOption> channelCombo = new ComboBox<>();
    private final ComboBox<MatchFilter> matchCombo = new ComboBox<>();
    private final ComboBox<CanTypeFilter> canTypeCombo = new ComboBox<>();
    private final ComboBox<ContentFilter> contentCombo = new ComboBox<>();
    private final CheckBox autoExpandCheck = new CheckBox("Auto-expand matches");
    private final Button clearFiltersButton = new Button("Clear Filters");
    private final Label filterSummaryLabel = new Label("");

    private final TreeTableView<InventoryRow> tree = new TreeTableView<>();
    private final TreeItem<InventoryRow> root = new TreeItem<>(InventoryRow.section("root"));

    private final SourceInventoryFilterEngine filterEngine = new SourceInventoryFilterEngine();
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(200));

    private SourceInventoryModel model;
    private Runnable modelListener;
    private CanSourceInventory current;
    private SourceInventoryFilterCriteria criteria = SourceInventoryFilterCriteria.cleared();
    private boolean updatingFilterControls;

    public SourceInventoryPane() {
        getStyleClass().addAll("source-inventory-pane", "content-surface");
        setSpacing(8);
        setPadding(new Insets(10, 12, 10, 12));
        setMinHeight(Region.USE_PREF_SIZE);
        setPrefHeight(Region.USE_COMPUTED_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);
        setFillWidth(true);

        titleLabel.getStyleClass().add("section-header");
        fileLabel.getStyleClass().add("muted");
        formatLabel.getStyleClass().add("muted");
        channelSummaryLabel.getStyleClass().add("section-subtitle");
        observedUniqueLabel.getStyleClass().add("section-subtitle");
        statusLabel.getStyleClass().add("muted");
        statusLabel.setWrapText(true);

        matchingCard.getStyleClass().add("source-inventory-match-card");
        matchingTitle.getStyleClass().add("section-subtitle");
        matchingStatusLabel.getStyleClass().add("muted");
        matchingStatusLabel.setWrapText(true);
        matchingDbcLabel.getStyleClass().add("muted");
        matchingCountsLabel.getStyleClass().add("muted");
        matchingPercentLabel.getStyleClass().add("section-subtitle");
        matchingCard.getChildren().setAll(
                matchingTitle,
                matchingStatusLabel,
                matchingDbcLabel,
                matchingCountsLabel,
                matchingPercentLabel
        );

        configureFilterBar();
        configureTree();

        HBox meta = new HBox(16, fileLabel, formatLabel);
        meta.setAlignment(Pos.CENTER_LEFT);

        VBox filterBlock = buildFilterBlock();

        getChildren().addAll(
                titleLabel,
                meta,
                channelSummaryLabel,
                observedUniqueLabel,
                statusLabel,
                matchingCard,
                filterBlock,
                tree
        );
        showEmpty("Add a measurement file to discover CAN channels and Message IDs.");
    }

    private void configureFilterBar() {
        searchField.setPromptText("Search CAN ID, Message or Signal...");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setMaxWidth(Double.MAX_VALUE);

        channelCombo.setMaxWidth(140);
        matchCombo.setMaxWidth(130);
        canTypeCombo.setMaxWidth(120);
        contentCombo.setMaxWidth(170);

        channelCombo.getItems().setAll(ChannelOption.all());
        channelCombo.setValue(ChannelOption.all());
        channelCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ChannelOption opt) {
                return opt == null ? CHANNEL_ALL : opt.label();
            }

            @Override
            public ChannelOption fromString(String string) {
                return ChannelOption.all();
            }
        });

        matchCombo.getItems().setAll(MatchFilter.values());
        matchCombo.setValue(MatchFilter.ALL);
        matchCombo.setConverter(enumConverter(MatchFilter.class, m -> switch (m) {
            case ALL -> "DBC Match: All";
            case MATCHED -> "DBC Match: Matched";
            case UNMATCHED -> "DBC Match: Unmatched";
            case NO_DBC -> "DBC Match: No DBC";
            case AMBIGUOUS -> "DBC Match: Ambiguous";
        }));

        canTypeCombo.getItems().setAll(CanTypeFilter.values());
        canTypeCombo.setValue(CanTypeFilter.ALL);
        canTypeCombo.setConverter(enumConverter(CanTypeFilter.class, t -> switch (t) {
            case ALL -> "CAN Type: All";
            case STANDARD -> "CAN Type: Standard";
            case EXTENDED -> "CAN Type: Extended";
            case CAN_FD -> "CAN Type: CAN FD";
        }));

        contentCombo.getItems().setAll(ContentFilter.values());
        contentCombo.setValue(ContentFilter.ALL);
        contentCombo.setConverter(enumConverter(ContentFilter.class, c -> switch (c) {
            case ALL -> "Content: All";
            case MESSAGES_ONLY -> "Content: Messages Only";
            case MATCHED_MESSAGES -> "Content: Matched Messages";
            case MESSAGES_WITH_SIGNALS -> "Content: Messages With Signals";
            case SIGNALS_ONLY -> "Content: Signals Only";
            case HAS_SIGNALS -> "Content: Has Signals";
            case NO_SIGNALS -> "Content: No Signals";
        }));

        autoExpandCheck.setSelected(false);
        clearFiltersButton.getStyleClass().add("secondary-button");
        filterSummaryLabel.getStyleClass().add("muted");

        searchDebounce.setOnFinished(e -> applyCriteriaFromControls(true));
        searchField.textProperty().addListener((o, a, b) -> {
            if (!updatingFilterControls) {
                searchDebounce.playFromStart();
            }
        });
        channelCombo.valueProperty().addListener((o, a, b) -> {
            if (!updatingFilterControls) {
                applyCriteriaFromControls(false);
            }
        });
        matchCombo.valueProperty().addListener((o, a, b) -> {
            if (!updatingFilterControls) {
                applyCriteriaFromControls(false);
            }
        });
        canTypeCombo.valueProperty().addListener((o, a, b) -> {
            if (!updatingFilterControls) {
                applyCriteriaFromControls(false);
            }
        });
        contentCombo.valueProperty().addListener((o, a, b) -> {
            if (!updatingFilterControls) {
                applyCriteriaFromControls(false);
            }
        });
        autoExpandCheck.selectedProperty().addListener((o, a, b) -> {
            if (!updatingFilterControls) {
                applyCriteriaFromControls(false);
            }
        });
        clearFiltersButton.setOnAction(e -> clearFilters());
    }

    private VBox buildFilterBlock() {
        HBox searchRow = new HBox(8, searchField);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        HBox comboRow = new HBox(8,
                channelCombo,
                matchCombo,
                canTypeCombo,
                contentCombo,
                clearFiltersButton
        );
        comboRow.setAlignment(Pos.CENTER_LEFT);

        HBox optionsRow = new HBox(12, autoExpandCheck, filterSummaryLabel);
        optionsRow.setAlignment(Pos.CENTER_LEFT);

        VBox block = new VBox(6, searchRow, comboRow, optionsRow);
        block.getStyleClass().add("source-inventory-filter-bar");
        return block;
    }

    private void configureTree() {
        TreeTableColumn<InventoryRow, String> colPrimary = new TreeTableColumn<>("Channel / CAN ID / Signal");
        colPrimary.setPrefWidth(170);
        colPrimary.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().getValue().primary()));

        TreeTableColumn<InventoryRow, String> colType = new TreeTableColumn<>("Type");
        colType.setPrefWidth(110);
        colType.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().getValue().type()));

        TreeTableColumn<InventoryRow, String> colObserved = new TreeTableColumn<>("Observed / Frames / Bits");
        colObserved.setPrefWidth(130);
        colObserved.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().getValue().observedOrFrames()));

        TreeTableColumn<InventoryRow, String> colMatched = new TreeTableColumn<>("Matched / Mux");
        colMatched.setPrefWidth(110);
        colMatched.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().getValue().matchedOrStatus()));

        TreeTableColumn<InventoryRow, String> colPctOrName = new TreeTableColumn<>("DBC Message / Conversion");
        colPctOrName.setPrefWidth(180);
        colPctOrName.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(
                c.getValue().getValue().percentOrMessage()));

        tree.getColumns().setAll(colPrimary, colType, colObserved, colMatched, colPctOrName);
        tree.setShowRoot(false);
        tree.setRoot(root);
        tree.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tree.setRowFactory(tv -> new TreeTableRow<>() {
            @Override
            protected void updateItem(InventoryRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("source-inventory-signal-row");
                if (!empty && item != null && item.kind() == RowKind.SIGNAL) {
                    getStyleClass().add("source-inventory-signal-row");
                }
            }
        });
        tree.setMinHeight(140);
        tree.setPrefHeight(220);
        tree.setMaxHeight(280);
        VBox.setVgrow(tree, Priority.NEVER);
    }

    public void setModel(SourceInventoryModel model) {
        if (this.model != null && modelListener != null) {
            this.model.removeListener(modelListener);
        }
        this.model = Objects.requireNonNull(model, "model");
        modelListener = () -> javafx.application.Platform.runLater(this::refreshFromModel);
        this.model.addListener(modelListener);
        refreshFromModel();
    }

    public void refreshFromModel() {
        if (model == null) {
            showEmpty("Add a measurement file to discover CAN channels and Message IDs.");
            return;
        }
        CanSourceInventory inventory = model.focusedInventory().orElse(null);
        render(inventory, model.matchState());
    }

    private SourceInventoryMatchState matchState() {
        return model == null ? SourceInventoryMatchState.noDbcSelected() : model.matchState();
    }

    private void clearFilters() {
        updatingFilterControls = true;
        try {
            searchDebounce.stop();
            searchField.clear();
            channelCombo.setValue(ChannelOption.all());
            matchCombo.setValue(MatchFilter.ALL);
            canTypeCombo.setValue(CanTypeFilter.ALL);
            contentCombo.setValue(ContentFilter.ALL);
            autoExpandCheck.setSelected(false);
            criteria = SourceInventoryFilterCriteria.cleared();
        } finally {
            updatingFilterControls = false;
        }
        render(current, matchState());
    }

    private void applyCriteriaFromControls(boolean fromSearchDebounce) {
        if (!fromSearchDebounce) {
            searchDebounce.stop();
        }
        ChannelOption channel = channelCombo.getValue();
        OptionalInt channelId = channel == null || channel.isAll()
                ? OptionalInt.empty()
                : OptionalInt.of(channel.id());
        criteria = SourceInventoryFilterCriteria.cleared()
                .withSearchText(searchField.getText())
                .withChannelId(channelId)
                .withMatchFilter(matchCombo.getValue() == null ? MatchFilter.ALL : matchCombo.getValue())
                .withCanTypeFilter(canTypeCombo.getValue() == null ? CanTypeFilter.ALL : canTypeCombo.getValue())
                .withContentFilter(contentCombo.getValue() == null ? ContentFilter.ALL : contentCombo.getValue())
                .withAutoExpandMatches(autoExpandCheck.isSelected());
        render(current, matchState());
    }

    private void render(CanSourceInventory inventory, SourceInventoryMatchState match) {
        this.current = inventory;
        if (inventory == null) {
            showEmpty("Add a measurement file to discover CAN channels and Message IDs.");
            renderMatchingCard(null, match == null ? SourceInventoryMatchState.noDbcSelected() : match);
            return;
        }
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        fileLabel.setText("File: " + inventory.sourceFileName());
        formatLabel.setText("Format: " + inventory.format().displayName());
        statusLabel.setText(statusText(inventory));

        CanInventoryStatus status = inventory.status();
        if (!inventory.channelDiscoveryComplete()
                && inventory.totalCanChannels() == 0
                && (status == CanInventoryStatus.FILE_ACCEPTED
                || status == CanInventoryStatus.DISCOVERING_CHANNELS
                || status == CanInventoryStatus.INDEXING_MESSAGE_IDS)) {
            channelSummaryLabel.setText("Discovering CAN channels...");
        } else if (inventory.totalCanChannels() == 0 && inventory.channelDiscoveryComplete()) {
            channelSummaryLabel.setText("CAN Channels: 0");
        } else {
            channelSummaryLabel.setText("CAN Channels: " + nf.format(inventory.totalCanChannels()));
        }

        long globalUnique = SourceInventoryMatchState.globalObservedUniqueCount(inventory, match);
        if (globalUnique > 0 || inventory.channelDiscoveryComplete()) {
            observedUniqueLabel.setText("Unique CAN IDs: " + nf.format(globalUnique));
        } else {
            observedUniqueLabel.setText("Unique CAN IDs: \u2014");
        }

        SourceInventoryMatchState safeMatch = match == null
                ? SourceInventoryMatchState.noDbcSelected()
                : match;
        renderMatchingCard(inventory, safeMatch);
        refreshChannelOptions(inventory);
        updateMatchFilterAvailability(safeMatch);

        Set<String> expanded = captureExpandedKeys();
        SourceInventoryFilterResult filtered = filterEngine.filter(inventory, safeMatch, criteria);
        root.getChildren().clear();
        for (FilteredChannel channel : filtered.channels()) {
            TreeItem<InventoryRow> channelItem = new TreeItem<>(
                    InventoryRow.channel(channel.channel(), channel.channelMatch(), nf));
            boolean expandChannel = channel.expand()
                    || expanded.contains(channelKey(channel.channel().channelId()));
            for (FilteredMessage message : channel.messages()) {
                TreeItem<InventoryRow> messageItem = messageTreeItem(message, nf);
                if (message.expand()
                        || expanded.contains(messageKey(
                        channel.channel().channelId(),
                        message.message().canId(),
                        message.message().extended()))) {
                    messageItem.setExpanded(true);
                }
                channelItem.getChildren().add(messageItem);
            }
            channelItem.setExpanded(expandChannel);
            root.getChildren().add(channelItem);
        }
        long globalMatched = safeMatch.summary()
                .map(CanDefinitionMatchSummary::globalMatchedUniqueCount)
                .orElse(0L);
        filterSummaryLabel.setText(filtered.summaryText(
                criteria.hasActiveBrowseFilters(),
                globalUnique,
                globalMatched));
        tree.setVisible(true);
        tree.setManaged(true);
    }

    private void refreshChannelOptions(CanSourceInventory inventory) {
        updatingFilterControls = true;
        try {
            ChannelOption selected = channelCombo.getValue();
            List<ChannelOption> options = new java.util.ArrayList<>();
            options.add(ChannelOption.all());
            for (CanChannelInventory ch : inventory.channels()) {
                options.add(new ChannelOption(ch.channelId(), "Channel: " + ch.displayName()));
            }
            channelCombo.getItems().setAll(options);
            if (selected != null && !selected.isAll()) {
                ChannelOption match = options.stream()
                        .filter(o -> !o.isAll() && Objects.equals(o.id(), selected.id()))
                        .findFirst()
                        .orElse(ChannelOption.all());
                channelCombo.setValue(match);
                if (match.isAll()) {
                    criteria = criteria.withChannelId(OptionalInt.empty());
                }
            } else {
                channelCombo.setValue(ChannelOption.all());
            }
        } finally {
            updatingFilterControls = false;
        }
    }

    private void updateMatchFilterAvailability(SourceInventoryMatchState match) {
        boolean ready = SourceInventoryFilterEngine.isDbcMatchReady(match);
        matchCombo.setDisable(!ready);
        if (!ready && matchCombo.getValue() != MatchFilter.ALL) {
            updatingFilterControls = true;
            try {
                matchCombo.setValue(MatchFilter.ALL);
                criteria = criteria.withMatchFilter(MatchFilter.ALL);
            } finally {
                updatingFilterControls = false;
            }
        }
    }

    private TreeItem<InventoryRow> messageTreeItem(FilteredMessage filtered, NumberFormat nf) {
        InventoryRow row = InventoryRow.message(filtered.message(), filtered.messageMatch(), nf);
        TreeItem<InventoryRow> item = new TreeItem<>(row);
        if (!filtered.showSignalChildren() || filtered.visibleSignals().isEmpty()) {
            return item;
        }
        List<DbcSignal> signals = filtered.visibleSignals();
        if (filtered.eagerlyLoadSignals()) {
            for (DbcSignal signal : signals) {
                item.getChildren().add(new TreeItem<>(InventoryRow.signal(signal)));
            }
            return item;
        }
        TreeItem<InventoryRow> placeholder = new TreeItem<>(InventoryRow.signalPlaceholder());
        item.getChildren().add(placeholder);
        ChangeListener<Boolean> lazyLoad = (obs, wasExpanded, expanded) -> {
            if (!expanded || item.getChildren().isEmpty()
                    || item.getChildren().getFirst().getValue().kind() != RowKind.SIGNAL_PLACEHOLDER) {
                return;
            }
            item.getChildren().clear();
            for (DbcSignal signal : signals) {
                item.getChildren().add(new TreeItem<>(InventoryRow.signal(signal)));
            }
        };
        item.expandedProperty().addListener(lazyLoad);
        return item;
    }

    private Set<String> captureExpandedKeys() {
        Set<String> keys = new HashSet<>();
        for (TreeItem<InventoryRow> channelItem : root.getChildren()) {
            InventoryRow ch = channelItem.getValue();
            if (ch != null && ch.kind() == RowKind.CHANNEL && channelItem.isExpanded()) {
                keys.add(channelKey(ch.channelId()));
            }
            for (TreeItem<InventoryRow> messageItem : channelItem.getChildren()) {
                InventoryRow msg = messageItem.getValue();
                if (msg != null && msg.kind() == RowKind.MESSAGE && messageItem.isExpanded()) {
                    keys.add(messageKey(msg.channelId(), msg.canId(), msg.extended()));
                }
            }
        }
        return keys;
    }

    private static String channelKey(int channelId) {
        return "c:" + channelId;
    }

    private static String messageKey(int channelId, long canId, boolean extended) {
        return "m:" + channelId + ":" + canId + ":" + (extended ? "E" : "S");
    }

    private void renderMatchingCard(CanSourceInventory inventory, SourceInventoryMatchState match) {
        CanDefinitionMatchStatus status = match.status();
        matchingDbcLabel.setManaged(false);
        matchingDbcLabel.setVisible(false);
        matchingCountsLabel.setManaged(false);
        matchingCountsLabel.setVisible(false);
        matchingPercentLabel.setManaged(false);
        matchingPercentLabel.setVisible(false);

        switch (status) {
            case NO_DBC_SELECTED -> matchingStatusLabel.setText("No DBC selected");
            case WAITING_FOR_SOURCE_INVENTORY -> {
                matchingStatusLabel.setText("Waiting for source inventory...");
                match.dbcFileName().ifPresent(name -> {
                    matchingDbcLabel.setText("DBC: " + name);
                    matchingDbcLabel.setManaged(true);
                    matchingDbcLabel.setVisible(true);
                });
            }
            case MATCHING -> {
                matchingStatusLabel.setText("Matching...");
                match.dbcFileName().ifPresent(name -> {
                    matchingDbcLabel.setText("DBC: " + name);
                    matchingDbcLabel.setManaged(true);
                    matchingDbcLabel.setVisible(true);
                });
            }
            case FAILED -> {
                matchingStatusLabel.setText("Failed"
                        + match.statusDetail().map(d -> " \u00B7 " + d).orElse(""));
                match.dbcFileName().ifPresent(name -> {
                    matchingDbcLabel.setText("DBC: " + name);
                    matchingDbcLabel.setManaged(true);
                    matchingDbcLabel.setVisible(true);
                });
            }
            case NO_MATCHING_MESSAGE_IDS, READY -> {
                CanDefinitionMatchSummary summary = match.summary().orElse(null);
                if (summary == null) {
                    matchingStatusLabel.setText(status == CanDefinitionMatchStatus.NO_MATCHING_MESSAGE_IDS
                            ? "No matching Message IDs"
                            : "Ready");
                    break;
                }
                NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
                matchingStatusLabel.setText(
                        status == CanDefinitionMatchStatus.NO_MATCHING_MESSAGE_IDS
                                ? "No matching Message IDs"
                                : "Ready");
                matchingDbcLabel.setText("DBC: " + match.dbcFileName().orElse("\u2014"));
                matchingDbcLabel.setManaged(true);
                matchingDbcLabel.setVisible(true);
                matchingCountsLabel.setText(
                        "DBC Messages: " + nf.format(summary.dbcMessageCount())
                                + "    Matching IDs: "
                                + nf.format(summary.globalMatchedUniqueCount())
                                + " / "
                                + nf.format(summary.globalObservedUniqueCount()));
                matchingCountsLabel.setManaged(true);
                matchingCountsLabel.setVisible(true);
                matchingPercentLabel.setText(
                        "Total Matching: " + formatPercent(summary.overallMatchPercentage()));
                matchingPercentLabel.setManaged(true);
                matchingPercentLabel.setVisible(true);
            }
            default -> matchingStatusLabel.setText(status.name().replace('_', ' '));
        }

        if (inventory == null && status == CanDefinitionMatchStatus.NO_DBC_SELECTED) {
            matchingStatusLabel.setText("No DBC selected");
        }
    }

    private void showEmpty(String message) {
        fileLabel.setText("File: \u2014");
        formatLabel.setText("Format: \u2014");
        channelSummaryLabel.setText(message);
        observedUniqueLabel.setText("Unique CAN IDs: \u2014");
        statusLabel.setText("");
        filterSummaryLabel.setText("");
        root.getChildren().clear();
        renderMatchingCard(null, matchState());
    }

    private static String statusText(CanSourceInventory inventory) {
        StringBuilder sb = new StringBuilder(inventory.status().name().replace('_', ' '));
        if (inventory.totalCanFrames() > 0) {
            sb.append(" \u00B7 ").append(NumberFormat.getIntegerInstance(Locale.US)
                    .format(inventory.totalCanFrames())).append(" frames");
        }
        for (CanInventoryDiagnostic d : inventory.diagnostics()) {
            sb.append(" \u00B7 ").append(d.message());
        }
        return sb.toString();
    }

    static String formatPercent(double value) {
        return String.format(Locale.US, "%.2f%%", value);
    }

    private static <T extends Enum<T>> StringConverter<T> enumConverter(
            Class<T> type,
            java.util.function.Function<T, String> labeler
    ) {
        return new StringConverter<>() {
            @Override
            public String toString(T object) {
                return object == null ? "" : labeler.apply(object);
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        };
    }

    record ChannelOption(Integer id, String label) {
        static ChannelOption all() {
            return new ChannelOption(null, "Channel: All");
        }

        boolean isAll() {
            return id == null;
        }
    }

    enum RowKind {
        SECTION,
        CHANNEL,
        MESSAGE,
        SIGNAL,
        SIGNAL_PLACEHOLDER
    }

    record InventoryRow(
            RowKind kind,
            String primary,
            String type,
            String observedOrFrames,
            String matchedOrStatus,
            String percentOrMessage,
            int channelId,
            long canId,
            boolean extended
    ) {
        static InventoryRow section(String name) {
            return new InventoryRow(RowKind.SECTION, name, "", "", "", "", -1, -1L, false);
        }

        static InventoryRow channel(
                CanChannelInventory channel,
                CanChannelMatchSummary match,
                NumberFormat nf
        ) {
            String observed = nf.format(channel.uniqueMessageCount()) + " Observed IDs";
            if (match == null) {
                return new InventoryRow(
                        RowKind.CHANNEL, channel.displayName(), "", observed, "\u2014", "\u2014",
                        channel.channelId(), -1L, false);
            }
            return new InventoryRow(
                    RowKind.CHANNEL,
                    channel.displayName(),
                    "",
                    observed,
                    nf.format(match.matchedCount()) + " Matched IDs",
                    formatPercent(match.matchPercentage()),
                    channel.channelId(),
                    -1L,
                    false
            );
        }

        static InventoryRow message(
                CanMessageInventory message,
                CanMessageMatch match,
                NumberFormat nf
        ) {
            String type = message.canFdObserved()
                    ? message.idTypeLabel() + " / CAN FD"
                    : message.idTypeLabel();
            if (match == null) {
                return new InventoryRow(
                        RowKind.MESSAGE,
                        String.format(Locale.ROOT, "0x%X", message.canId()),
                        type,
                        nf.format(message.frameCount()),
                        "\u2014",
                        "\u2014",
                        message.channel(),
                        message.canId(),
                        message.extended()
                );
            }
            return new InventoryRow(
                    RowKind.MESSAGE,
                    String.format(Locale.ROOT, "0x%X", message.canId()),
                    type,
                    nf.format(message.frameCount()),
                    match.matchLabel(),
                    match.matched() ? match.dbcMessageName().orElse("\u2014") : "\u2014",
                    message.channel(),
                    message.canId(),
                    message.extended()
            );
        }

        static InventoryRow signal(DbcSignal signal) {
            return new InventoryRow(
                    RowKind.SIGNAL,
                    DbcSignalDefinitionFormatter.name(signal),
                    DbcSignalDefinitionFormatter.typeAndOrder(signal),
                    DbcSignalDefinitionFormatter.bitPosition(signal),
                    DbcSignalDefinitionFormatter.multiplexLabel(signal),
                    DbcSignalDefinitionFormatter.conversionAndUnit(signal),
                    -1,
                    -1L,
                    false
            );
        }

        static InventoryRow signalPlaceholder() {
            return new InventoryRow(RowKind.SIGNAL_PLACEHOLDER, "\u2014", "", "", "", "", -1, -1L, false);
        }
    }
}

package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.CanMessageInventoryRow;
import com.stellar.platform.desktop.presentation.CoverageClassificationPolicy;
import com.stellar.platform.desktop.presentation.CoverageClassificationPolicy.CoverageQuality;
import com.stellar.platform.desktop.presentation.DbcSignalDefinitionView;
import com.stellar.platform.desktop.presentation.InventoryEnrichmentMatchState;
import com.stellar.platform.desktop.presentation.SignalChannelLabels;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Right-pane signal explorer. Reads existing catalog + inventory enrichment only.
 * Never reparses measurement or DBC files.
 */
public final class ChannelSignalExplorerPane extends VBox {

    enum Scope { ALL, NATIVE, DBC }

    public record SignalRow(
            String name,
            String sourceType,
            String channel,
            String unit,
            boolean nativeSignal,
            boolean dbcSignal,
            String canId,
            String message
    ) {
    }

    public record ExplorerContext(
            String title,
            List<SignalCatalogEntry> catalogEntries,
            List<CanMessageInventoryRow> inventoryRows,
            SourceChannelMappingEntry mapping,
            String dbcName,
            boolean sourceLevel,
            boolean hasActiveMappingChannel
    ) {
        public ExplorerContext {
            title = title == null ? "Signal Explorer" : title;
            catalogEntries = catalogEntries == null ? List.of() : List.copyOf(catalogEntries);
            inventoryRows = inventoryRows == null ? List.of() : List.copyOf(inventoryRows);
            dbcName = dbcName == null ? "" : dbcName;
        }
    }

    private final Label title = new Label("Signal Explorer");
    private final TextField search = new TextField();
    private final ToggleButton allChip = new ToggleButton("All");
    private final ToggleButton nativeChip = new ToggleButton("Native");
    private final ToggleButton dbcChip = new ToggleButton("DBC");
    private final HBox chips = new HBox(6);
    private final TableView<SignalRow> table = new TableView<>();
    private final ObservableList<SignalRow> allRows = FXCollections.observableArrayList();
    private final ObservableList<SignalRow> visible = FXCollections.observableArrayList();
    private final Label emptyHint = new Label("");
    private final Label previewLine = new Label("");
    private final CoverageClassificationPolicy coveragePolicy = CoverageClassificationPolicy.defaults();
    private final NumberFormat percentFormat = NumberFormat.getNumberInstance(Locale.US);

    private Scope scope = Scope.ALL;

    public ChannelSignalExplorerPane() {
        getStyleClass().add("channel-signal-explorer");
        setSpacing(6);
        setPadding(new Insets(8, 8, 8, 8));
        setMinWidth(300);
        setPrefWidth(320);
        setMaxWidth(340);
        setMinHeight(0);
        setMaxHeight(Double.MAX_VALUE);
        percentFormat.setMaximumFractionDigits(2);
        percentFormat.setMinimumFractionDigits(0);

        title.getStyleClass().add("decision-section-title");
        search.setPromptText("Search signals...");
        search.getStyleClass().addAll("search-field", "inventory-signal-search");
        search.setMinHeight(28);
        search.setPrefHeight(30);
        search.textProperty().addListener((o, a, b) -> applyFilter());

        ToggleGroup group = new ToggleGroup();
        styleChip(allChip, group, Scope.ALL);
        styleChip(nativeChip, group, Scope.NATIVE);
        styleChip(dbcChip, group, Scope.DBC);
        allChip.setSelected(true);
        chips.setAlignment(Pos.CENTER_LEFT);
        chips.getChildren().setAll(allChip, nativeChip, dbcChip);

        configureTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        emptyHint.getStyleClass().addAll("muted", "decision-signal-empty-hint");
        emptyHint.setWrapText(true);
        emptyHint.setVisible(false);
        emptyHint.setManaged(false);

        previewLine.getStyleClass().addAll("muted", "decision-mapping-preview-line");
        previewLine.setWrapText(true);
        previewLine.setMaxHeight(Region.USE_PREF_SIZE);

        getChildren().setAll(title, search, chips, table, emptyHint, previewLine);
        clear();
    }

    public void clear() {
        allRows.clear();
        visible.clear();
        search.clear();
        title.setText("Signal Explorer");
        previewLine.setText("");
        emptyHint.setText("");
        emptyHint.setVisible(false);
        emptyHint.setManaged(false);
        table.setPlaceholder(new Label("No signals available for this context."));
        updateChipLabels(0, 0, 0);
        showSupportedScopes(false, false);
    }

    public void show(
            List<CanMessageInventoryRow> rows,
            SourceChannelMappingEntry mapping,
            String dbcName
    ) {
        show(new ExplorerContext("Signal Explorer", List.of(), rows, mapping, dbcName, false, true));
    }

    public void show(ExplorerContext context) {
        ExplorerContext ctx = context == null
                ? new ExplorerContext("Signal Explorer", List.of(), List.of(), null, "", false, false)
                : context;
        title.setText(ctx.title());
        List<SignalRow> next = mergeRows(ctx.catalogEntries(), ctx.inventoryRows());
        allRows.setAll(next);
        applyFilter();

        int nativeCount = 0;
        int dbcCount = 0;
        for (SignalRow row : next) {
            if (row.nativeSignal()) {
                nativeCount++;
            }
            if (row.dbcSignal()) {
                dbcCount++;
            }
        }
        updateChipLabels(next.size(), nativeCount, dbcCount);
        showSupportedScopes(nativeCount > 0, dbcCount > 0);

        if (next.isEmpty()) {
            String placeholder = ctx.sourceLevel() && !ctx.hasActiveMappingChannel()
                    ? "Select a channel to view decoded DBC signals."
                    : "No signals available for this context.";
            table.setPlaceholder(new Label(placeholder));
            emptyHint.setText(ctx.sourceLevel() && ctx.hasActiveMappingChannel() && nativeCount == 0
                    ? "Select a channel to view decoded DBC signals."
                    : "");
            emptyHint.setVisible(!emptyHint.getText().isBlank());
            emptyHint.setManaged(emptyHint.isVisible());
        } else if (ctx.sourceLevel() && dbcCount == 0) {
            emptyHint.setText("Select a channel to view decoded DBC signals.");
            emptyHint.setVisible(true);
            emptyHint.setManaged(true);
        } else {
            emptyHint.setText("");
            emptyHint.setVisible(false);
            emptyHint.setManaged(false);
        }

        previewLine.setText(previewText(ctx, nativeCount, dbcCount));
    }

    public TableView<SignalRow> tableForTest() {
        return table;
    }

    public int visibleCountForTest() {
        return visible.size();
    }

    public int nativeCountForTest() {
        int n = 0;
        for (SignalRow row : allRows) {
            if (row.nativeSignal()) {
                n++;
            }
        }
        return n;
    }

    public int dbcCountForTest() {
        int n = 0;
        for (SignalRow row : allRows) {
            if (row.dbcSignal()) {
                n++;
            }
        }
        return n;
    }

    public String titleForTest() {
        return title.getText();
    }

    public boolean nativeScopeVisibleForTest() {
        return nativeChip.isVisible();
    }

    public boolean dbcScopeVisibleForTest() {
        return dbcChip.isVisible();
    }

    public List<String> visibleNamesForTest() {
        List<String> names = new ArrayList<>();
        for (SignalRow row : visible) {
            names.add(row.name());
        }
        return names;
    }

    private List<SignalRow> mergeRows(
            List<SignalCatalogEntry> catalogEntries,
            List<CanMessageInventoryRow> inventoryRows
    ) {
        List<SignalRow> next = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SignalCatalogEntry entry : catalogEntries == null ? List.<SignalCatalogEntry>of() : catalogEntries) {
            boolean nativeSignal = entry.sourceType() == SignalSourceType.MF4_NATIVE;
            boolean dbcSignal = entry.sourceType() == SignalSourceType.DBC_CAN;
            String key = dedupeKey(entry.signalName(), entry.canId(), entry.channel(), nativeSignal);
            if (!seen.add(key)) {
                continue;
            }
            next.add(new SignalRow(
                    entry.signalName(),
                    nativeSignal ? "MF4" : "DBC",
                    nativeSignal ? SignalChannelLabels.compact(entry) : SignalChannelLabels.compact(entry),
                    entry.unit() == null || entry.unit().isBlank() ? "—" : entry.unit(),
                    nativeSignal,
                    dbcSignal,
                    entry.canId() > 0 ? "0x" + Long.toHexString(entry.canId()).toUpperCase(Locale.ROOT) : "—",
                    entry.messageName()
            ));
        }
        for (CanMessageInventoryRow row : inventoryRows == null ? List.<CanMessageInventoryRow>of() : inventoryRows) {
            if (row.matchState() != InventoryEnrichmentMatchState.MATCHED || row.signalDefinitions().isEmpty()) {
                continue;
            }
            for (DbcSignalDefinitionView signal : row.signalDefinitions()) {
                String key = dedupeKey(signal.name(), row.canId(), row.channelId(), false);
                if (!seen.add(key)) {
                    continue;
                }
                next.add(new SignalRow(
                        signal.name(),
                        "DBC",
                        row.channelLabel(),
                        signal.unitDisplay(),
                        false,
                        true,
                        row.canIdHex(),
                        row.dbcMessageDisplay()
                ));
            }
        }
        return next;
    }

    private static String dedupeKey(String name, long canId, int channel, boolean nativeSignal) {
        return (nativeSignal ? "N" : "D") + '|' + name + '|' + canId + '|' + channel;
    }

    private String previewText(ExplorerContext ctx, int nativeCount, int dbcCount) {
        if (ctx.mapping() != null) {
            CoverageQuality quality = coveragePolicy.classify(ctx.mapping().coverageRatio());
            String dbc = ctx.dbcName().isBlank() ? "No DBC" : ctx.dbcName();
            return dbc + "  ·  " + percentFormat.format(ctx.mapping().coveragePercent())
                    + "%  ·  " + quality.badgeLabel()
                    + "  ·  " + nativeCount + " native / " + dbcCount + " DBC";
        }
        if (nativeCount > 0 || dbcCount > 0) {
            return nativeCount + " native  ·  " + dbcCount + " DBC";
        }
        return "";
    }

    private void configureTable() {
        table.setItems(visible);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getStyleClass().addAll("inventory-signal-list", "decision-signal-table");
        table.setPlaceholder(new Label("No signals available for this context."));
        table.setMinHeight(120);

        TableColumn<SignalRow, String> name = new TableColumn<>("Signal Name");
        name.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().name()));
        name.setMinWidth(80);
        name.setPrefWidth(120);

        TableColumn<SignalRow, String> source = new TableColumn<>("Source");
        source.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().sourceType()));
        source.setMinWidth(44);
        source.setPrefWidth(56);
        source.setMaxWidth(72);
        source.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("signal-source-mf4", "signal-source-dbc");
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item);
                getStyleClass().add("MF4".equals(item) ? "signal-source-mf4" : "signal-source-dbc");
            }
        });

        TableColumn<SignalRow, String> channel = new TableColumn<>("Ch");
        channel.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().channel()));
        channel.setMinWidth(36);
        channel.setPrefWidth(48);
        channel.setMaxWidth(64);

        TableColumn<SignalRow, String> unit = new TableColumn<>("Unit");
        unit.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().unit()));
        unit.setMinWidth(40);
        unit.setPrefWidth(54);

        table.getColumns().setAll(name, source, channel, unit);
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(SignalRow item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("signal-row-native", "signal-row-dbc");
                if (empty || item == null) {
                    setTooltip(null);
                    return;
                }
                getStyleClass().add(item.nativeSignal() ? "signal-row-native" : "signal-row-dbc");
                setTooltip(new Tooltip(item.name() + " · " + item.sourceType()
                        + " · " + item.channel() + " · " + item.unit()));
            }
        });
    }

    private void styleChip(ToggleButton chip, ToggleGroup group, Scope value) {
        chip.setToggleGroup(group);
        chip.getStyleClass().add("inventory-quick-filter");
        chip.setOnAction(e -> {
            scope = value;
            chip.setSelected(true);
            applyFilter();
        });
    }

    private void applyFilter() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<SignalRow> next = new ArrayList<>();
        for (SignalRow row : allRows) {
            boolean scopeOk = switch (scope) {
                case ALL -> true;
                case NATIVE -> row.nativeSignal();
                case DBC -> row.dbcSignal();
            };
            if (!scopeOk) {
                continue;
            }
            if (!q.isEmpty()
                    && !row.name().toLowerCase(Locale.ROOT).contains(q)
                    && !row.sourceType().toLowerCase(Locale.ROOT).contains(q)
                    && !row.channel().toLowerCase(Locale.ROOT).contains(q)
                    && !row.canId().toLowerCase(Locale.ROOT).contains(q)
                    && !row.message().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            next.add(row);
        }
        visible.setAll(next);
    }

    private void updateChipLabels(int all, int nativeCount, int dbcCount) {
        allChip.setText("All " + all);
        nativeChip.setText("Native " + nativeCount);
        dbcChip.setText("DBC " + dbcCount);
    }

    private void showSupportedScopes(boolean hasNative, boolean hasDbc) {
        nativeChip.setVisible(hasNative);
        nativeChip.setManaged(hasNative);
        dbcChip.setVisible(hasDbc);
        dbcChip.setManaged(hasDbc);
        if (scope == Scope.NATIVE && !hasNative || scope == Scope.DBC && !hasDbc) {
            scope = Scope.ALL;
            allChip.setSelected(true);
            applyFilter();
        }
    }
}

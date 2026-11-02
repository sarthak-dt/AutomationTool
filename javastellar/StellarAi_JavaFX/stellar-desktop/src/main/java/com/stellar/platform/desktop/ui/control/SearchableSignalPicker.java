package com.stellar.platform.desktop.ui.control;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.map.GpsSignalCandidate;

import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polyline;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Searchable signal selector shared by Configuration and any other screen that has to let an
 * engineer choose one catalog signal.
 *
 * <p>Collapsed it shows only the chosen signal, so a screen with several roles stays readable.
 * Opening it reveals a search box over the candidate list: suggested resolver candidates appear
 * first, the remaining catalog stays in its original order and is never re-sorted by name. The
 * value shown is always the measurement's own signal name, so provenance is never replaced by a
 * semantic role name.
 *
 * <p>A selection is only reported when the user commits one (click or Enter); walking the list with
 * the arrow keys does not change the mapping.
 */
public final class SearchableSignalPicker extends VBox {

    private static final String SELECT_PROMPT = "Select a signal";
    private static final String CLEARABLE_PROMPT = "Not mapped";
    private static final String SEARCH_PROMPT = "Search signal...";

    private final Label value = new Label();
    private final HBox field = new HBox(8, value, chevron());
    private final TextField search = new TextField();
    private final ListView<Row> list = new ListView<>();
    private final VBox options = new VBox(6, search, list);
    private final Popup popup = new Popup();

    private List<Row> rows = List.of();
    private List<SignalCatalogEntry> catalog = List.of();
    private List<GpsSignalCandidate> suggested = List.of();
    private SignalCatalogKey selected;
    private boolean clearable;
    private String emptyPrompt;
    private Consumer<SignalCatalogEntry> selectionHandler = e -> { };
    private Runnable clearHandler = () -> { };

    public SearchableSignalPicker() {
        getStyleClass().add("gps-signal-picker");

        field.getStyleClass().add("gps-signal-picker-field");
        field.setMinWidth(0);
        field.setMaxWidth(Double.MAX_VALUE);
        field.setAlignment(Pos.CENTER_LEFT);
        setMinWidth(0);
        field.setFocusTraversable(true);
        field.setOnMouseClicked(e -> {
            field.requestFocus();
            toggleOptions();
        });
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.DOWN) {
                toggleOptions();
                e.consume();
            }
        });
        value.getStyleClass().add("gps-signal-picker-value");
        value.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        value.setText(promptText());
        value.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(value, Priority.ALWAYS);

        options.getStyleClass().add("gps-signal-picker-options");
        search.setPromptText(SEARCH_PROMPT);
        search.getStyleClass().add("gps-signal-picker-search");
        search.textProperty().addListener((o, a, b) -> rebuildRows());
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                focusList();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                commit(firstSelectableRow());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hideOptions();
                e.consume();
            }
        });

        list.setPrefHeight(200);
        list.setMaxHeight(240);
        list.getStyleClass().add("gps-signal-picker-list");
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("gps-signal-picker-header", "gps-signal-picker-clear");
                if (empty || item == null) {
                    setText(null);
                    setDisable(false);
                    return;
                }
                setText(item.label());
                setDisable(item.header());
                if (item.header()) {
                    getStyleClass().add("gps-signal-picker-header");
                } else if (item.clear()) {
                    getStyleClass().add("gps-signal-picker-clear");
                }
            }
        });
        list.setOnMouseClicked(e -> commit(list.getSelectionModel().getSelectedItem()));
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                commit(list.getSelectionModel().getSelectedItem());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hideOptions();
                e.consume();
            }
        });

        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.setConsumeAutoHidingEvents(false);
        popup.getContent().add(options);

        getChildren().add(field);
    }

    public void setCatalog(List<SignalCatalogEntry> catalog, List<GpsSignalCandidate> suggested) {
        this.catalog = catalog == null ? List.of() : List.copyOf(catalog);
        this.suggested = suggested == null ? List.of() : List.copyOf(suggested);
        rebuildRows();
    }

    /** Adds an explicit "not mapped" row so an optional role can be unset again. */
    public void setClearable(boolean clearable) {
        if (this.clearable == clearable) {
            return;
        }
        this.clearable = clearable;
        if (selected == null) {
            value.setText(promptText());
        }
        rebuildRows();
    }

    /** Overrides the collapsed empty-state label (for example "Select signal (optional)"). */
    public void setEmptyPrompt(String prompt) {
        this.emptyPrompt = prompt == null || prompt.isBlank() ? null : prompt.trim();
        if (selected == null) {
            value.setText(promptText());
        }
    }

    public void setSelected(SignalCatalogKey key) {
        this.selected = key;
        value.setText(key == null ? promptText() : key.signalName());
        list.getSelectionModel().clearSelection();
    }

    public SignalCatalogKey selected() {
        return selected;
    }

    public void setOnPicked(Consumer<SignalCatalogEntry> handler) {
        this.selectionHandler = handler == null ? e -> { } : handler;
    }

    public void setOnCleared(Runnable handler) {
        this.clearHandler = handler == null ? () -> { } : handler;
    }

    public void clearSearch() {
        search.clear();
    }

    /** Closes the candidate list; called when the owning page is hidden or disposed. */
    public void hideOptions() {
        if (popup.isShowing()) {
            popup.hide();
        }
    }

    private void toggleOptions() {
        if (popup.isShowing()) {
            hideOptions();
            return;
        }
        Scene scene = getScene();
        if (scene == null || scene.getWindow() == null) {
            return;
        }
        search.clear();
        rebuildRows();
        selectRowFor(selected);
        options.setMinWidth(field.getWidth());
        options.setPrefWidth(field.getWidth());
        Bounds anchor = field.localToScreen(field.getBoundsInLocal());
        popup.show(field, anchor.getMinX(), anchor.getMaxY() + 2);
        if (popup.getScene() != null) {
            popup.getScene().getStylesheets().setAll(scene.getStylesheets());
        }
        search.requestFocus();
    }

    private void focusList() {
        Row row = list.getSelectionModel().getSelectedItem();
        if (row == null || row.header()) {
            list.getSelectionModel().select(firstSelectableIndex());
        }
        list.requestFocus();
    }

    private void commit(Row row) {
        if (row == null || row.header()) {
            return;
        }
        if (row.clear()) {
            selected = null;
            value.setText(promptText());
            hideOptions();
            clearHandler.run();
            return;
        }
        if (row.entry() == null) {
            return;
        }
        selected = row.entry().key();
        value.setText(row.entry().key().signalName());
        hideOptions();
        selectionHandler.accept(row.entry());
    }

    private String promptText() {
        if (emptyPrompt != null) {
            return emptyPrompt;
        }
        return clearable ? CLEARABLE_PROMPT : SELECT_PROMPT;
    }

    private void selectRowFor(SignalCatalogKey key) {
        if (key == null) {
            list.getSelectionModel().clearSelection();
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            SignalCatalogEntry entry = rows.get(i).entry();
            if (entry != null && entry.key().equals(key)) {
                list.getSelectionModel().select(i);
                list.scrollTo(Math.max(0, i - 2));
                return;
            }
        }
        list.getSelectionModel().clearSelection();
    }

    private Row firstSelectableRow() {
        int index = firstSelectableIndex();
        return index < 0 ? null : rows.get(index);
    }

    private int firstSelectableIndex() {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).header() && !rows.get(i).clear()) {
                return i;
            }
        }
        return -1;
    }

    private static Region chevron() {
        Polyline mark = new Polyline(0, 0, 4.5, 4.5, 9, 0);
        mark.getStyleClass().add("gps-signal-picker-chevron");
        VBox host = new VBox(mark);
        host.setMouseTransparent(true);
        return host;
    }

    private void rebuildRows() {
        String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Row> next = new ArrayList<>();
        if (clearable) {
            next.add(Row.clearRow());
        }
        Set<SignalCatalogKey> suggestedKeys = new LinkedHashSet<>();
        List<Row> suggestionRows = new ArrayList<>();
        for (GpsSignalCandidate candidate : suggested) {
            SignalCatalogEntry entry = find(candidate.key());
            if (entry == null || !matches(entry, query)) {
                continue;
            }
            if (suggestedKeys.add(entry.key())) {
                suggestionRows.add(Row.entryRow(entry));
            }
        }
        if (!suggestionRows.isEmpty()) {
            next.add(Row.header(true));
            next.addAll(suggestionRows);
        }
        List<Row> allRows = new ArrayList<>();
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null || suggestedKeys.contains(entry.key()) || !matches(entry, query)) {
                continue;
            }
            allRows.add(Row.entryRow(entry));
        }
        if (!allRows.isEmpty() || suggestionRows.isEmpty()) {
            next.add(Row.header(false));
            next.addAll(allRows);
        }
        // Clear the selection before the items change and hand the ListView a fresh backing list:
        // selecting into a list that was emptied under the selection model throws out of
        // ListViewBehavior on the next click (JDK-8187145).
        list.getSelectionModel().clearSelection();
        rows = List.copyOf(next);
        list.setItems(FXCollections.observableArrayList(rows));
    }

    private SignalCatalogEntry find(SignalCatalogKey key) {
        for (SignalCatalogEntry entry : catalog) {
            if (entry.key().equals(key)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean matches(SignalCatalogEntry entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return entry.key().searchBlob().contains(query);
    }

    private record Row(boolean header, boolean suggested, boolean clear, SignalCatalogEntry entry) {

        static Row header(boolean suggested) {
            return new Row(true, suggested, false, null);
        }

        static Row entryRow(SignalCatalogEntry entry) {
            return new Row(false, false, false, entry);
        }

        static Row clearRow() {
            return new Row(false, false, true, null);
        }

        String label() {
            if (header) {
                return suggested ? "Suggested Signals" : "All Signals";
            }
            if (clear) {
                return "— not mapped —";
            }
            return entry == null ? "" : entry.key().signalName();
        }
    }

    int rowCountForTest() {
        return rows.size();
    }

    String rowLabelForTest(int index) {
        return index < 0 || index >= rows.size() ? "" : rows.get(index).label();
    }

    String displayedValueForTest() {
        return value.getText();
    }

    void pickForTest(int index) {
        commit(index < 0 || index >= rows.size() ? null : rows.get(index));
    }

    void searchForTest(String query) {
        search.setText(query);
    }

    /** Moves the highlight without committing, the way the arrow keys do. */
    void highlightForTest(int index) {
        list.getSelectionModel().select(index);
    }

    int highlightedIndexForTest() {
        return list.getSelectionModel().getSelectedIndex();
    }
}

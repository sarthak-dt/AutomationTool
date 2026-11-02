package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Drag-source and drop-target wiring for catalog keys. */
public final class SignalCatalogDragSupport {

    public static final DataFormat SIGNAL_KEYS = new DataFormat("application/x-stellar-signal-keys");

    private SignalCatalogDragSupport() {
    }

    public static ContextMenu enableDragSource(TableView<SignalCatalogEntry> catalog) {
        return enableDragSource(catalog, null);
    }

    /**
     * Enables multi-select drag. If the pressed row is part of the selection, all selected
     * keys are dragged; otherwise only the pressed row is dragged.
     */
    public static ContextMenu enableDragSource(
            TableView<SignalCatalogEntry> catalog,
            Function<SignalCatalogEntry, List<MenuItem>> contextMenuItems
    ) {
        ContextMenu menu = contextMenuItems == null ? null : new ContextMenu();
        catalog.setRowFactory(tv -> {
            TableRow<SignalCatalogEntry> row = new TableRow<>();
            row.setOnDragDetected(e -> {
                if (row.isEmpty() || row.getItem() == null) {
                    return;
                }
                List<SignalCatalogEntry> selected =
                        List.copyOf(catalog.getSelectionModel().getSelectedItems());
                List<SignalCatalogEntry> payload;
                if (!selected.isEmpty() && selected.contains(row.getItem())) {
                    payload = selected;
                } else {
                    payload = List.of(row.getItem());
                }
                Dragboard db = row.startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();
                content.put(SIGNAL_KEYS, encode(payload.stream().map(SignalCatalogEntry::key).toList()));
                content.putString(payload.getFirst().signalName());
                db.setContent(content);
                e.consume();
            });
            if (menu != null) {
                row.setOnContextMenuRequested(e -> {
                    if (row.isEmpty() || row.getItem() == null) {
                        menu.hide();
                        return;
                    }
                    SignalCatalogEntry entry = row.getItem();
                    if (!catalog.getSelectionModel().getSelectedItems().contains(entry)) {
                        catalog.getSelectionModel().clearAndSelect(row.getIndex());
                    }
                    menu.getItems().setAll(contextMenuItems.apply(entry));
                    menu.show(row, e.getScreenX(), e.getScreenY());
                    e.consume();
                });
            }
            return row;
        });
        return menu;
    }

    public static void clearDropTarget(Node target) {
        if (target != null) {
            target.setOnDragOver(null);
            target.setOnDragExited(null);
            target.setOnDragDropped(null);
        }
    }

    public static void enableDropTarget(Node target, Consumer<List<SignalCatalogKey>> onDrop) {
        target.setOnDragOver(e -> {
            if (e.getGestureSource() != target && e.getDragboard().hasContent(SIGNAL_KEYS)) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!target.getStyleClass().contains("drop-target-active")) {
                    target.getStyleClass().add("drop-target-active");
                }
                e.consume();
            }
        });
        target.setOnDragExited(e -> {
            target.getStyleClass().remove("drop-target-active");
            e.consume();
        });
        target.setOnDragDropped(e -> {
            target.getStyleClass().remove("drop-target-active");
            Object raw = e.getDragboard().getContent(SIGNAL_KEYS);
            List<SignalCatalogKey> keys = decode(raw == null ? "" : raw.toString());
            boolean ok = !keys.isEmpty();
            if (ok && onDrop != null) {
                onDrop.accept(keys);
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    public static String encode(List<SignalCatalogKey> keys) {
        StringBuilder sb = new StringBuilder();
        for (SignalCatalogKey k : keys) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(k.signalName()).append('\u001f')
                    .append(k.sourceType().name()).append('\u001f')
                    .append(k.messageName()).append('\u001f')
                    .append(k.canId()).append('\u001f')
                    .append(k.sourceFile()).append('\u001f')
                    .append(k.sourceDatabase()).append('\u001f')
                    .append(k.channel());
        }
        return sb.toString();
    }

    public static List<SignalCatalogKey> decode(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        List<SignalCatalogKey> keys = new ArrayList<>();
        for (String line : payload.split("\n")) {
            String[] p = line.split("\u001f", -1);
            if (p.length < 4) {
                continue;
            }
            try {
                int channel = 0;
                if (p.length > 6 && !p[6].isBlank()) {
                    channel = Integer.parseInt(p[6]);
                }
                keys.add(new SignalCatalogKey(
                        p[0],
                        SignalSourceType.valueOf(p[1]),
                        p[2],
                        Long.parseLong(p[3]),
                        channel,
                        p.length > 4 ? p[4] : "",
                        p.length > 5 ? p[5] : ""
                ));
            } catch (RuntimeException ignored) {
                // Skip malformed drag payload.
            }
        }
        return List.copyOf(keys);
    }
}

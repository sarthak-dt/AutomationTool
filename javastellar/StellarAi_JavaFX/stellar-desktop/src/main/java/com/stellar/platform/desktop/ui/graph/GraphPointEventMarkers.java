package com.stellar.platform.desktop.ui.graph;

import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clickable plus markers for point events. Each marker toggles its own note.
 * The glyphs are unmanaged so they sit on the waveform instead of in the layout flow.
 */
final class GraphPointEventMarkers {

    record Marker(String id, double x, double y, String title, String time, String value, String note) {
        Marker {
            id = id == null ? "" : id;
            title = title == null || title.isBlank() ? "Event" : title;
            time = time == null ? "" : time;
            value = value == null ? "" : value;
            note = note == null ? "" : note;
        }
    }

    interface Host {
        void add(Node node);
        void remove(Node node);
        boolean contains(Node node);
        double width();
        double height();
        void aboveWaveform();
        /** Marker position in the note layer. The note layer is the graph, not the scrolled tile. */
        Point2D notePoint(double x, double y);
        double noteWidth();
        double noteHeight();
    }

    private final Host host;
    private final Map<String, Button> buttons = new LinkedHashMap<>();
    private final Map<String, Label> notes = new LinkedHashMap<>();
    private final Set<String> expanded = new LinkedHashSet<>();
    private List<Marker> current = List.of();

    GraphPointEventMarkers(Host host) {
        this.host = host;
    }

    void sync(List<Marker> markers) {
        current = markers == null ? List.of() : List.copyOf(markers);
        Set<String> live = new LinkedHashSet<>();
        for (Marker marker : current) {
            if (marker.id().isBlank() || !Double.isFinite(marker.x()) || !Double.isFinite(marker.y())) {
                continue;
            }
            live.add(marker.id());
            Button button = buttons.computeIfAbsent(marker.id(), id -> create(marker));
            button.setLayoutX(marker.x() - 9);
            button.setLayoutY(marker.y() - 9);
            button.setTooltip(tip(marker));
            button.setAccessibleText(accessible(marker));
            if (!host.contains(button)) {
                host.add(button);
            }
            Label note = notes.get(marker.id());
            if (expanded.contains(marker.id())) {
                if (note == null) {
                    note = createNote(marker);
                    notes.put(marker.id(), note);
                }
                note.setText(body(marker));
                if (!host.contains(note)) {
                    host.add(note);
                }
                placeNote(marker, note);
                note.toFront();
            } else if (note != null) {
                host.remove(note);
                notes.remove(marker.id());
            }
            button.toFront();
        }
        host.aboveWaveform();
        List<String> stale = new ArrayList<>();
        for (String id : buttons.keySet()) {
            if (!live.contains(id)) {
                stale.add(id);
            }
        }
        for (String id : stale) {
            host.remove(buttons.remove(id));
            Label note = notes.remove(id);
            if (note != null) {
                host.remove(note);
            }
            expanded.remove(id);
        }
    }

    List<Node> nodesForTest() {
        List<Node> nodes = new ArrayList<>();
        nodes.addAll(buttons.values());
        nodes.addAll(notes.values());
        return nodes;
    }

    boolean expandedForTest(String id) {
        return expanded.contains(id);
    }

    /** True when the pointer is on a plus. Does not open the note. */
    boolean hits(double x, double y) {
        return nearest(x, y) != null;
    }

    /** Close every open note. Returns false when nothing was open. */
    boolean collapseFromEscape() {
        if (expanded.isEmpty()) {
            return false;
        }
        expanded.clear();
        sync(current);
        return true;
    }

    /** Toggle the marker under a waveform click. The cursor is not moved. */
    boolean toggleAt(double x, double y) {
        String id = nearest(x, y);
        if (id == null) {
            return false;
        }
        toggle(id);
        return true;
    }

    private String nearest(double x, double y) {
        String id = null;
        double best = 14;
        for (Marker marker : current) {
            if (!Double.isFinite(marker.x()) || !Double.isFinite(marker.y())) {
                continue;
            }
            double distance = Math.hypot(marker.x() - x, marker.y() - y);
            if (distance <= best) {
                best = distance;
                id = marker.id();
            }
        }
        return id;
    }

    private Button create(Marker marker) {
        Button button = new Button("+");
        button.setManaged(false);
        button.setFocusTraversable(true);
        button.setMnemonicParsing(false);
        button.setMouseTransparent(false);
        button.setViewOrder(-1);
        button.getStyleClass().add("graph-event-marker");
        button.setStyle("-fx-background-color: #0E7490; -fx-text-fill: white; -fx-font-weight: bold;"
                + "-fx-font-size: 14px; -fx-background-radius: 9; -fx-padding: 0; -fx-alignment: center;");
        button.setMinSize(18, 18);
        button.setPrefSize(18, 18);
        button.setMaxSize(18, 18);
        button.setOnAction(event -> toggle(marker.id()));
        button.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && expanded.remove(marker.id())) {
                event.consume();
                sync(current);
            }
        });
        return button;
    }

    private void toggle(String id) {
        if (!expanded.add(id)) {
            expanded.remove(id);
        }
        sync(current);
    }

    private static Label createNote(Marker marker) {
        Label note = new Label(body(marker));
        note.setManaged(false);
        note.setViewOrder(-1);
        note.setWrapText(true);
        note.setMinWidth(160);
        note.setPrefWidth(220);
        note.setMaxWidth(240);
        note.getStyleClass().add("graph-event-note");
        note.setStyle("-fx-background-color: white; -fx-text-fill: #163044; -fx-border-color: #0E7490;"
                + "-fx-border-width: 1.5; -fx-background-radius: 4; -fx-border-radius: 4; -fx-padding: 8;"
                + "-fx-font-size: 12px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.28), 10, 0.2, 0, 2);");
        note.setFocusTraversable(true);
        note.setVisible(true);
        note.setAccessibleText(body(marker));
        return note;
    }

    private void placeNote(Marker marker, Label note) {
        size(note);
        Point2D at = host.notePoint(marker.x(), marker.y());
        double spaceW = host.noteWidth() > 1 ? host.noteWidth() : 640;
        double spaceH = host.noteHeight() > 1 ? host.noteHeight() : 480;
        double noteW = Math.max(160, note.getWidth() > 1 ? note.getWidth() : 240);
        double noteH = Math.max(36, note.getHeight() > 1 ? note.getHeight() : note.prefHeight(-1));
        double x = at.getX() + 16;
        if (x + noteW > spaceW - 8) {
            x = at.getX() - noteW - 16;
        }
        double y = at.getY() + 14;
        if (y + noteH > spaceH - 8) {
            y = at.getY() - noteH - 12;
        }
        x = Math.max(4, Math.min(x, Math.max(4, spaceW - noteW - 4)));
        y = Math.max(4, Math.min(y, Math.max(4, spaceH - noteH - 4)));
        note.setLayoutX(x);
        note.setLayoutY(y);
        note.setVisible(true);
    }

    private static void size(Label note) {
        note.applyCss();
        note.autosize();
        double height = Math.max(36, note.prefHeight(240));
        note.setMinHeight(height);
        note.setPrefHeight(height);
        note.resize(Math.min(240, Math.max(160, note.prefWidth(-1))), height);
    }

    private static String body(Marker marker) {
        StringBuilder text = new StringBuilder();
        text.append(marker.title());
        if (!marker.time().isBlank()) {
            text.append('\n').append(marker.time());
        }
        if (!marker.value().isBlank()) {
            text.append('\n').append(marker.value());
        }
        if (!marker.note().isBlank()) {
            text.append('\n').append(marker.note());
        }
        return text.toString();
    }

    private static String accessible(Marker marker) {
        return "Event " + marker.title() + " at " + marker.time() + ". Activate to show or hide the note.";
    }

    private static Tooltip tip(Marker marker) {
        Tooltip tooltip = new Tooltip(body(marker));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(260);
        return tooltip;
    }
}

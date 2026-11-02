package com.stellar.platform.desktop.ui.graph;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lists every measurement under one waveform pixel. The colour and track stay unchanged;
 * the user names which recording the cursor should follow.
 */
public final class OverlapMeasurementChooser {

    private Popup popup;
    private final List<Button> buttons = new ArrayList<>();
    private int generation;

    public OverlapMeasurementChooser() {
    }

    /**
     * Popup construction initializes {@code javafx.stage.Screen}, which JavaFX allows
     * only on the application thread. The chooser is a field of the graph controller,
     * so the window is created on first show rather than during controller construction.
     */
    private Popup popup() {
        if (popup == null) {
            Popup created = new Popup();
            created.setAutoHide(true);
            created.setHideOnEscape(true);
            created.setOnAutoHide(event -> generation++);
            popup = created;
        }
        return popup;
    }

    public void show(
            Node owner,
            double screenX,
            double screenY,
            List<AutomotiveSignalCanvas.MeasurementChoice> choices,
            Consumer<AutomotiveSignalCanvas.MeasurementChoice> onSelect
    ) {
        generation++;
        int shown = generation;
        buttons.clear();
        VBox box = new VBox(6);
        box.setPadding(new Insets(8));
        box.setFillWidth(true);
        box.setStyle("-fx-background-color: white; -fx-border-color: #8aa0b4; -fx-background-radius: 6; -fx-border-radius: 6;");
        Label title = new Label("Select measurement");
        title.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(title);
        VBox rows = new VBox(4);
        if (choices != null) {
            for (AutomotiveSignalCanvas.MeasurementChoice choice : choices) {
                rows.getChildren().add(choiceButton(choice, shown, onSelect));
            }
        }
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(320);
        scroll.setPrefViewportHeight(Math.min(240, Math.max(48, (choices == null ? 1 : choices.size()) * 52)));
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        box.getChildren().add(scroll);
        Popup window = popup();
        window.getContent().setAll(box);
        if (window.isShowing()) {
            window.hide();
        }
        if (owner != null && owner.getScene() != null && owner.getScene().getWindow() != null) {
            window.show(owner, screenX, screenY);
        }
    }

    public void dismiss() {
        generation++;
        if (popup != null && popup.isShowing()) {
            popup.hide();
        }
    }

    public boolean showing() {
        return popup != null && popup.isShowing();
    }

    public List<String> labels() {
        List<String> labels = new ArrayList<>();
        for (Button button : buttons) {
            if (button.getGraphic() instanceof Label label) {
                labels.add(label.getText());
            }
        }
        return List.copyOf(labels);
    }

    public void choose(String measurementId) {
        if (measurementId == null) {
            return;
        }
        for (Button button : buttons) {
            if (measurementId.equals(button.getUserData())) {
                button.fire();
                return;
            }
        }
    }

    private Button choiceButton(
            AutomotiveSignalCanvas.MeasurementChoice choice,
            int shown,
            Consumer<AutomotiveSignalCanvas.MeasurementChoice> onSelect
    ) {
        String name = choice.segmentLabel() == null || choice.segmentLabel().isBlank()
                ? choice.hit().measurementId()
                : choice.segmentLabel();
        String value = Double.isFinite(choice.physicalValue())
                ? String.format("%.3f %s", choice.physicalValue(), choice.unit() == null ? "" : choice.unit()).trim()
                : "—";
        Label label = new Label(name + "\n" + value);
        label.setWrapText(true);
        label.setMaxWidth(280);
        label.setAlignment(Pos.CENTER_LEFT);
        Button button = new Button();
        button.setGraphic(label);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setUserData(choice.hit().measurementId());
        button.setOnAction(event -> {
            if (shown != generation || onSelect == null) {
                return;
            }
            generation++;
            if (popup != null) {
                popup.hide();
            }
            onSelect.accept(choice);
        });
        buttons.add(button);
        return button;
    }
}

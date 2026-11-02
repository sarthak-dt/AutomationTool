package com.stellar.platform.desktop.ui.home;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Compact informational KPI card. Not a button.
 */
public final class HomeMetricCard {

    private final HBox card;
    private final Label value;

    public HomeMetricCard(HBox card, String label, Node icon, String iconClass) {
        this.card = card;
        this.value = new Label("0");
        value.getStyleClass().add("metric-value");

        StackPane iconHost = new StackPane(icon);
        iconHost.getStyleClass().addAll("metric-icon", iconClass);
        iconHost.setMinSize(32, 32);
        iconHost.setPrefSize(32, 32);
        iconHost.setMaxSize(32, 32);

        Label caption = new Label(label);
        caption.getStyleClass().add("metric-label");
        caption.setWrapText(false);
        caption.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        caption.setMinWidth(0);
        caption.setMaxWidth(Double.MAX_VALUE);
        value.setMinWidth(0);

        VBox copy = new VBox(2, caption, value);
        copy.getStyleClass().add("metric-copy");
        copy.setMinWidth(0);
        copy.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(copy, Priority.ALWAYS);

        HBox body = new HBox(12, iconHost, copy);
        body.setAlignment(Pos.CENTER_LEFT);
        body.getStyleClass().add("metric-card-body");
        body.setMinWidth(0);
        body.setMaxWidth(Double.MAX_VALUE);
        card.getChildren().setAll(body);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
    }

    public void setValue(String text) {
        value.setText(text == null || text.isBlank() ? "0" : text);
    }

    public void setVisible(boolean visible) {
        if (card == null) {
            return;
        }
        card.setVisible(visible);
        card.setManaged(visible);
    }
}

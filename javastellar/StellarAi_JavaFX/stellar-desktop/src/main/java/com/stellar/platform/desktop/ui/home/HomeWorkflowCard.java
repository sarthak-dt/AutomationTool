package com.stellar.platform.desktop.ui.home;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Clickable Get Started shortcut into an existing workspace.
 */
public final class HomeWorkflowCard {

    private final Button card;

    public HomeWorkflowCard(Button card, String title, String copy, Node icon) {
        this.card = card;
        StackPane iconHost = new StackPane(icon);
        iconHost.getStyleClass().add("workflow-icon");
        iconHost.setMinSize(32, 32);
        iconHost.setPrefSize(32, 32);
        iconHost.setMaxSize(32, 32);

        Label heading = new Label(title);
        heading.getStyleClass().add("workflow-title");
        heading.setWrapText(true);
        heading.setMaxWidth(Double.MAX_VALUE);

        Label caption = new Label(copy);
        caption.getStyleClass().add("workflow-copy");
        caption.setWrapText(true);
        caption.setMaxWidth(Double.MAX_VALUE);

        VBox body = new VBox(8, iconHost, heading, caption);
        body.setAlignment(Pos.TOP_LEFT);
        body.getStyleClass().add("workflow-card-body");
        body.setMaxWidth(Double.MAX_VALUE);
        card.setGraphic(body);
        card.setMaxWidth(Double.MAX_VALUE);
    }

    public void setDisable(boolean disable) {
        card.setDisable(disable);
        card.getStyleClass().remove("workflow-card-disabled");
        if (disable) {
            card.getStyleClass().add("workflow-card-disabled");
        }
    }

    public void setDisabledTooltip(String text) {
        if (text == null || text.isBlank()) {
            card.setTooltip(null);
            return;
        }
        card.setTooltip(new Tooltip(text));
    }
}

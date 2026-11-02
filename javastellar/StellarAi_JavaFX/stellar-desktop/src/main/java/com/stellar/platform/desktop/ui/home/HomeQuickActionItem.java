package com.stellar.platform.desktop.ui.home;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * One row in the Home Quick Actions list.
 */
public final class HomeQuickActionItem {

    private final Button row;
    private final Runnable action;

    public HomeQuickActionItem(String label, Node icon, Runnable action) {
        this.action = action;
        row = new Button();
        row.getStyleClass().addAll("quick-action", "quick-action-item", "home-action-row");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setMinWidth(Region.USE_PREF_SIZE);
        row.setMnemonicParsing(false);

        StackPane iconHost = new StackPane(icon);
        iconHost.getStyleClass().addAll("quick-action-icon", "home-action-icon");
        iconHost.setMinSize(28, 28);
        iconHost.setPrefSize(28, 28);
        iconHost.setMaxSize(28, 28);

        Label text = new Label(label);
        text.getStyleClass().addAll("quick-action-label", "home-action-label");
        text.setWrapText(false);
        text.setMinWidth(Region.USE_PREF_SIZE);
        text.setTextOverrun(OverrunStyle.CLIP);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label chevron = new Label("›");
        chevron.getStyleClass().addAll("quick-action-chevron", "home-action-chevron");

        HBox body = new HBox(10, iconHost, text, chevron);
        body.setAlignment(Pos.CENTER_LEFT);
        body.setMaxWidth(Double.MAX_VALUE);
        row.setGraphic(body);
        row.setOnAction(e -> this.action.run());
    }

    public Button node() {
        return row;
    }

    public void setDisable(boolean disable) {
        row.setDisable(disable);
        row.getStyleClass().remove("quick-action-disabled");
        if (disable) {
            row.getStyleClass().add("quick-action-disabled");
        }
    }

    public void setDisabledTooltip(String text) {
        if (text == null || text.isBlank()) {
            row.setTooltip(null);
            return;
        }
        row.setTooltip(new Tooltip(text));
    }
}

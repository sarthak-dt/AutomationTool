package com.stellar.platform.desktop.ui.inventory;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/**
 * One-line workspace counts under the source/channel tree. Presentation only.
 */
public final class DecisionWorkspaceSummary extends VBox {

    private final Label line = new Label("0 Channels \u00b7 0 High Coverage \u00b7 0 Attention");
    private final Label channels = line;

    public DecisionWorkspaceSummary() {
        getStyleClass().add("decision-workspace-summary");
        setSpacing(0);
        setPadding(new Insets(6, 8, 6, 8));
        setMaxHeight(USE_PREF_SIZE);
        line.getStyleClass().add("decision-summary-metric");
        line.setAlignment(Pos.CENTER_LEFT);
        line.setWrapText(true);
        line.setMaxWidth(Double.MAX_VALUE);
        getChildren().setAll(line);
    }

    public void refresh(int channelCount, int highCoverage, int needsAttention, int unmappedCount) {
        line.setText(channelCount + " Channels \u00b7 " + highCoverage + " High Coverage \u00b7 "
                + needsAttention + " Attention");
        line.setTooltip(new Tooltip("Unmapped: " + unmappedCount));
    }

    public String channelsTextForTest() {
        return numberFrom(channels.getText());
    }

    private static String numberFrom(String text) {
        if (text == null || text.isBlank()) {
            return "0";
        }
        int space = text.lastIndexOf(' ');
        return space < 0 ? text : text.substring(space + 1);
    }
}

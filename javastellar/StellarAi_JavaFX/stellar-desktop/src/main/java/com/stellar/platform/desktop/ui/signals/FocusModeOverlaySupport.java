package com.stellar.platform.desktop.ui.signals;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Temporary Focus Mode drawers that host the existing browser / inspector panes
 * without duplicating catalog or metadata logic.
 * Overlay nodes must remain unmanaged when closed so they never reserve width.
 */
public final class FocusModeOverlaySupport {

    private FocusModeOverlaySupport() {
    }

    public static BorderPane createLeftDrawer(String title, Runnable onClose) {
        return createDrawer(title, onClose, "focus-palette-drawer", SignalsLayoutMetrics.FOCUS_PALETTE_WIDTH);
    }

    public static BorderPane createRightDrawer(String title, Runnable onClose) {
        return createDrawer(title, onClose, "focus-details-drawer", SignalsLayoutMetrics.FOCUS_DETAILS_WIDTH);
    }

    private static BorderPane createDrawer(String title, Runnable onClose, String styleClass, double width) {
        BorderPane drawer = new BorderPane();
        drawer.getStyleClass().addAll("focus-mode-drawer", styleClass);
        drawer.setPrefWidth(width);
        drawer.setMinWidth(width);
        drawer.setMaxWidth(width);
        drawer.setMaxHeight(Double.MAX_VALUE);
        drawer.setManaged(false);
        drawer.setVisible(false);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("focus-drawer-title");
        Button close = new Button("\u00D7");
        close.getStyleClass().addAll("compact-button", "icon-button", "focus-drawer-close");
        close.setTooltip(new Tooltip("Close"));
        close.setOnAction(e -> {
            if (onClose != null) {
                onClose.run();
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, titleLabel, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("focus-drawer-header");
        header.setPadding(new Insets(8, 10, 8, 10));
        drawer.setTop(header);
        return drawer;
    }

    public static void setDrawerContent(BorderPane drawer, Node content) {
        if (content != null) {
            VBox.setVgrow(content, Priority.ALWAYS);
            if (content instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
                region.setMaxHeight(Double.MAX_VALUE);
                region.setMinWidth(0);
            }
        }
        drawer.setCenter(content);
    }

    public static void showDrawer(BorderPane drawer, boolean show) {
        // When closed: unmanaged so overlays never shift the base workspace.
        drawer.setManaged(show);
        drawer.setVisible(show);
        if (show) {
            drawer.toFront();
        }
    }

    public static Button createEdgeButton(String text, String tooltip, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("focus-edge-button", styleClass);
        button.setTooltip(new Tooltip(tooltip));
        button.setMinWidth(SignalsLayoutMetrics.FOCUS_EDGE_WIDTH);
        button.setPrefWidth(SignalsLayoutMetrics.FOCUS_EDGE_WIDTH);
        button.setMaxWidth(SignalsLayoutMetrics.FOCUS_EDGE_WIDTH);
        button.setMinHeight(SignalsLayoutMetrics.FOCUS_EDGE_WIDTH);
        button.setMaxHeight(Double.MAX_VALUE);
        button.setManaged(false);
        button.setVisible(false);
        return button;
    }

    public static void alignOverlay(StackPane host, Node node, Pos position) {
        StackPane.setAlignment(node, position);
        if (!host.getChildren().contains(node)) {
            host.getChildren().add(node);
        }
    }
}

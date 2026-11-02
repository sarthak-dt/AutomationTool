package com.stellar.platform.desktop.ui.control;

import javafx.beans.InvalidationListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Mounts a modal overlay on the application Scene root so it is centered against the
 * full window, not a nested Graphs / SplitPane / inspector pane.
 *
 * <p>Normal window mode and Graphs Focus Mode use this same host. Only Scene size changes.
 */
public final class ApplicationOverlayLayer {

    public static final String STYLE_CLASS = "application-overlay-layer";

    private ApplicationOverlayLayer() {
    }

    /**
     * Attaches {@code overlay} to the Scene that owns {@code owner}. Safe to call repeatedly;
     * an already-attached overlay is only brought to front.
     */
    public static void attach(Region overlay, Node owner) {
        if (overlay == null || owner == null) {
            return;
        }
        Scene scene = owner.getScene() != null ? owner.getScene() : overlay.getScene();
        if (scene == null) {
            if (!Boolean.TRUE.equals(overlay.getProperties().get("stellar.overlay.pending-scene"))) {
                overlay.getProperties().put("stellar.overlay.pending-scene", Boolean.TRUE);
                owner.sceneProperty().addListener((o, a, next) -> {
                    if (next != null) {
                        overlay.getProperties().remove("stellar.overlay.pending-scene");
                        attach(overlay, owner);
                    }
                });
            }
            return;
        }
        Parent root = scene.getRoot();
        if (root instanceof StackPane stack) {
            attachToStack(overlay, stack);
            return;
        }
        if (root instanceof Pane pane) {
            attachToPane(overlay, pane);
        }
    }

    public static void detach(Region overlay) {
        if (overlay == null || overlay.getParent() == null) {
            return;
        }
        if (overlay.getParent() instanceof Pane pane) {
            pane.getChildren().remove(overlay);
        }
    }

    private static void attachToStack(Region overlay, StackPane stack) {
        if (overlay.getParent() != stack) {
            if (overlay.getParent() instanceof Pane previous) {
                previous.getChildren().remove(overlay);
            }
            overlay.setManaged(true);
            overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            StackPane.setAlignment(overlay, Pos.CENTER);
            stack.getChildren().add(overlay);
        }
        overlay.toFront();
    }

    private static void attachToPane(Region overlay, Pane pane) {
        if (overlay.getParent() != pane) {
            if (overlay.getParent() instanceof Pane previous) {
                previous.getChildren().remove(overlay);
            }
            if (!overlay.getStyleClass().contains(STYLE_CLASS)) {
                overlay.getStyleClass().add(STYLE_CLASS);
            }
            overlay.setManaged(false);
            pane.getChildren().add(overlay);
            if (!Boolean.TRUE.equals(overlay.getProperties().get("stellar.overlay.bound"))) {
                overlay.getProperties().put("stellar.overlay.bound", Boolean.TRUE);
                InvalidationListener relayout = obs -> layoutOver(overlay, pane);
                pane.widthProperty().addListener(relayout);
                pane.heightProperty().addListener(relayout);
            }
        }
        layoutOver(overlay, pane);
        overlay.toFront();
    }

    private static void layoutOver(Region overlay, Pane pane) {
        overlay.resizeRelocate(0, 0, pane.getWidth(), pane.getHeight());
    }
}

package com.stellar.platform.desktop.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TableView;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

import java.util.List;

/**
 * Detaches page-owned JavaFX resources while the subtree is still in a Scene.
 * Used only during analysis-page eviction — not a UI redesign.
 */
public final class DesktopSceneGraphRelease {

    private static final System.Logger LOG = System.getLogger("stellar.desktop.lifecycle");

    private DesktopSceneGraphRelease() {
    }

    public static void log(String event, Object owner, Node root) {
        LOG.log(System.Logger.Level.INFO,
                "{0} owner={1} root={2} scene={3}",
                event,
                identity(owner),
                identity(root),
                root == null ? "null" : identity(root.getScene()));
    }

    public static String identity(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + "@" + System.identityHashCode(value);
    }

    public static void releaseFocusFrom(Node subtree) {
        if (subtree == null) {
            return;
        }
        Scene scene = subtree.getScene();
        if (scene == null) {
            return;
        }
        Node focus = scene.getFocusOwner();
        if (focus == null || !isDescendant(subtree, focus)) {
            return;
        }
        Node parent = subtree.getParent();
        if (parent != null && parent.getScene() == scene) {
            parent.requestFocus();
        }
        if (scene.getFocusOwner() != null && isDescendant(subtree, scene.getFocusOwner()) && scene.getRoot() != null) {
            scene.getRoot().requestFocus();
        }
    }

    /**
     * Tooltips, combo popups and context menus are separate Windows. Their ownerNode
     * keeps the evicted table/cell tree reachable after the page leaves {@code contentHost}.
     */
    public static void hideOwnedPopups(Node subtree) {
        if (subtree == null) {
            return;
        }
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window instanceof PopupWindow popup) {
                Node owner = popup.getOwnerNode();
                if (owner != null && isDescendant(subtree, owner)) {
                    popup.hide();
                }
            }
        }
    }

    public static boolean isDescendant(Node root, Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == root) {
                return true;
            }
        }
        return false;
    }

    public static void disposeTableView(TableView<?> table) {
        if (table == null) {
            return;
        }
        table.getSelectionModel().clearSelection();
        table.getItems().clear();
        table.getColumns().clear();
        table.setRowFactory(null);
        table.setPlaceholder(null);
        ContextMenu menu = table.getContextMenu();
        if (menu != null) {
            menu.hide();
        }
        table.setContextMenu(null);
        table.setOnMouseClicked(null);
        table.setOnKeyPressed(null);
    }

    public static void hideCombo(ComboBox<?> combo) {
        if (combo != null) {
            combo.hide();
        }
    }

    public static void clearTooltip(javafx.scene.control.Control control) {
        if (control != null) {
            control.setTooltip(null);
        }
    }
}

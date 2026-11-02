package com.stellar.platform.desktop.ui.graph;

import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves secondary Graphs toolbar nodes into a More menu when the bar cannot fit them
 * without clipping labels.
 */
public final class GraphToolbarOverflow {

    private GraphToolbarOverflow() {
    }

    /** Widths at or below this always overflow — 1366 laptops and 1600×1000 @125%. */
    static final double COMPACT_WIDTH = 1680;

    public static boolean shouldOverflow(double toolbarWidth, double primaryWidth, double secondaryWidth) {
        if (toolbarWidth <= 0 || toolbarWidth < COMPACT_WIDTH) {
            return true;
        }
        return primaryWidth + secondaryWidth + 96 > toolbarWidth;
    }

    public static void apply(HBox toolbar, HBox secondary, MenuButton more, List<OverflowAction> actions) {
        if (toolbar == null || more == null) {
            return;
        }
        // A showing MenuButton hides itself when its item list is replaced.
        // Graph layout pulses must not rebuild or retoggle the open menu.
        if (more.isShowing()) {
            return;
        }
        double primary = 0;
        for (Node child : toolbar.getChildren()) {
            if (child == secondary || child == more || !child.isManaged()) {
                continue;
            }
            primary += prefWidth(child) + toolbar.getSpacing();
        }
        double extra = secondary == null ? 0 : prefWidth(secondary);
        boolean overflow = shouldOverflow(toolbar.getWidth(), primary, extra);
        if (secondary != null) {
            boolean showSecondary = !overflow;
            if (secondary.isVisible() != showSecondary) {
                secondary.setVisible(showSecondary);
            }
            if (secondary.isManaged() != showSecondary) {
                secondary.setManaged(showSecondary);
            }
        }
        List<MenuItem> next = menuItems(actions);
        if (!sameItems(more.getItems(), next)) {
            more.getItems().setAll(next);
        }
        if (more.isVisible() != overflow) {
            more.setVisible(overflow);
        }
        if (more.isManaged() != overflow) {
            more.setManaged(overflow);
        }
        boolean empty = actions == null || actions.isEmpty();
        if (more.isDisable() != empty) {
            more.setDisable(empty);
        }
    }

    private static double prefWidth(Node node) {
        if (node == null) {
            return 0;
        }
        double width = node.prefWidth(-1);
        return Double.isNaN(width) ? 0 : Math.max(0, width);
    }

    private static List<MenuItem> menuItems(List<OverflowAction> actions) {
        List<MenuItem> items = new ArrayList<>();
        if (actions == null) {
            return items;
        }
        String lastGroup = null;
        for (OverflowAction action : actions) {
            if (action == null || action.label() == null || action.label().isBlank()) {
                continue;
            }
            if (lastGroup != null && action.group() != null && !action.group().equals(lastGroup)) {
                items.add(new SeparatorMenuItem());
            }
            MenuItem item = new MenuItem(action.label());
            item.setOnAction(e -> {
                if (action.handler() != null) {
                    action.handler().run();
                }
                if (item.getParentPopup() != null) {
                    item.getParentPopup().hide();
                }
            });
            item.setDisable(action.disabled());
            items.add(item);
            lastGroup = action.group();
        }
        return items;
    }

    private static boolean sameItems(List<MenuItem> current, List<MenuItem> next) {
        if (current.size() != next.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            MenuItem left = current.get(i);
            MenuItem right = next.get(i);
            boolean leftSep = left instanceof SeparatorMenuItem;
            boolean rightSep = right instanceof SeparatorMenuItem;
            if (leftSep || rightSep) {
                if (leftSep != rightSep) {
                    return false;
                }
                continue;
            }
            if (!java.util.Objects.equals(left.getText(), right.getText()) || left.isDisable() != right.isDisable()) {
                return false;
            }
        }
        return true;
    }

    public record OverflowAction(String group, String label, Runnable handler, boolean disabled) {
        public OverflowAction(String group, String label, Runnable handler) {
            this(group, label, handler, false);
        }
    }
}

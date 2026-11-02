package com.stellar.platform.desktop.ui.graph;

import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps Inspector event actions at their full label width.
 * Actions that do not fit the row move into a labelled More menu.
 */
public final class GraphEventActions {

    private GraphEventActions() {
    }

    public static void layout(double width, FlowPane row, MenuButton more, List<Button> buttons) {
        if (row == null || more == null || buttons == null) {
            return;
        }
        double available = Math.max(72, width - 16);
        row.setPrefWrapLength(available);
        List<Button> shown = new ArrayList<>();
        List<Button> overflow = new ArrayList<>();
        double used = 0;
        for (Button button : buttons) {
            if (button == null) {
                continue;
            }
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setPrefWidth(Region.USE_COMPUTED_SIZE);
            button.setMaxWidth(Region.USE_PREF_SIZE);
            button.setTextOverrun(OverrunStyle.CLIP);
            if (button.getTooltip() == null || button.getTooltip().getText() == null
                    || button.getTooltip().getText().isBlank()) {
                button.setTooltip(new Tooltip(button.getText()));
            }
            button.getTooltip().setWrapText(true);
            double next = Math.max(button.prefWidth(-1), textWidth(button));
            double withGap = shown.isEmpty() ? next : used + row.getHgap() + next;
            if (shown.isEmpty() || withGap <= available) {
                shown.add(button);
                used = withGap;
            } else {
                overflow.add(button);
            }
        }
        if (!sameButtons(row.getChildren(), shown)) {
            row.getChildren().setAll(shown);
        }
        List<MenuItem> items = new ArrayList<>();
        for (Button button : overflow) {
            MenuItem item = new MenuItem(button.getText());
            item.setDisable(button.isDisable());
            item.setOnAction(event -> button.fire());
            items.add(item);
        }
        if (!sameItems(more.getItems(), items)) {
            more.getItems().setAll(items);
        } else {
            for (int i = 0; i < items.size(); i++) {
                more.getItems().get(i).setDisable(items.get(i).isDisable());
            }
        }
        boolean showMore = !overflow.isEmpty();
        more.setVisible(showMore);
        more.setManaged(showMore);
        more.setDisable(false);
    }

    private static double textWidth(Button button) {
        String text = button.getText() == null ? "" : button.getText();
        return text.length() * 7.5 + 24;
    }

    private static boolean sameButtons(List<javafx.scene.Node> current, List<Button> next) {
        if (current.size() != next.size()) {
            return false;
        }
        for (int i = 0; i < next.size(); i++) {
            if (current.get(i) != next.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameItems(List<MenuItem> current, List<MenuItem> next) {
        if (current.size() != next.size()) {
            return false;
        }
        for (int i = 0; i < next.size(); i++) {
            if (!java.util.Objects.equals(current.get(i).getText(), next.get(i).getText())) {
                return false;
            }
        }
        return true;
    }
}

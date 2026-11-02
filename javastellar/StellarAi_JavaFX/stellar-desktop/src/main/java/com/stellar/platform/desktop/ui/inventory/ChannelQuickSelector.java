package com.stellar.platform.desktop.ui.inventory;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Dedicated channel-chip row. Horizontal scroll when width is insufficient —
 * chips keep their natural width so labels never overlap DBC status.
 */
public final class ChannelQuickSelector extends VBox {

    public record ChannelChip(
            int channelId,
            String label,
            String idsText,
            String bestDbc,
            String coverage
    ) {
    }

    private final HBox chipRow = new HBox(6);
    private final ScrollPane scroll = new ScrollPane(chipRow);
    private IntConsumer onSelect = id -> { };
    private Integer activeChannelId;
    private List<ChannelChip> chips = List.of();

    public ChannelQuickSelector() {
        getStyleClass().add("channel-quick-selector");
        setSpacing(0);
        setPadding(new Insets(2, 0, 2, 0));
        setFillWidth(true);
        setMaxHeight(Region.USE_PREF_SIZE);
        setMinHeight(0);

        chipRow.setAlignment(Pos.CENTER_LEFT);
        chipRow.getStyleClass().add("channel-quick-selector-row");
        chipRow.setMinHeight(Region.USE_PREF_SIZE);
        chipRow.setMaxHeight(Region.USE_PREF_SIZE);

        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("channel-quick-selector-scroll");
        scroll.setMinHeight(36);
        scroll.setPrefHeight(Region.USE_COMPUTED_SIZE);
        scroll.setMaxHeight(Region.USE_PREF_SIZE);
        scroll.setMinWidth(0);
        VBox.setVgrow(scroll, Priority.NEVER);

        getChildren().setAll(scroll);
        clear();
    }

    public void setOnSelect(IntConsumer onSelect) {
        this.onSelect = onSelect == null ? id -> { } : onSelect;
    }

    public void clear() {
        chips = List.of();
        activeChannelId = null;
        chipRow.getChildren().clear();
        setVisible(false);
        setManaged(false);
    }

    public void show(List<ChannelChip> next, Integer activeChannelId) {
        this.chips = next == null ? List.of() : List.copyOf(next);
        this.activeChannelId = activeChannelId;
        if (chips.isEmpty()) {
            clear();
            return;
        }
        setVisible(true);
        setManaged(true);
        List<Button> buttons = new ArrayList<>();
        for (ChannelChip chip : chips) {
            buttons.add(buildChip(chip, activeChannelId != null && activeChannelId == chip.channelId()));
        }
        chipRow.getChildren().setAll(buttons);
    }

    public int chipCountForTest() {
        return chips.size();
    }

    public Integer activeChannelForTest() {
        return activeChannelId;
    }

    public HBox chipRowForTest() {
        return chipRow;
    }

    private Button buildChip(ChannelChip chip, boolean active) {
        Label name = new Label(chip.label() == null || chip.label().isBlank()
                ? "Ch" + chip.channelId() : chip.label());
        name.getStyleClass().add("channel-quick-chip-name");
        name.setMinWidth(Region.USE_PREF_SIZE);

        Label ids = new Label(chip.idsText() == null ? "" : chip.idsText());
        ids.getStyleClass().add("channel-quick-chip-ids");
        ids.setMinWidth(Region.USE_PREF_SIZE);

        String dbc = chip.bestDbc() == null || chip.bestDbc().isBlank() ? "" : chip.bestDbc();
        String cov = chip.coverage() == null || chip.coverage().isBlank() ? "" : chip.coverage();
        Label mapping = new Label("Best DBC: " + dbc + (cov.isBlank() ? "" : " \u00b7 " + cov));
        mapping.getStyleClass().add("channel-quick-chip-dbc");
        mapping.setMinWidth(Region.USE_PREF_SIZE);

        HBox body = new HBox(8, name, ids, mapping);
        body.setAlignment(Pos.CENTER_LEFT);
        Button button = new Button();
        button.setGraphic(body);
        button.getStyleClass().add("channel-quick-chip");
        if (active) {
            button.getStyleClass().add("channel-quick-chip-active");
        }
        button.setMinHeight(30);
        button.setPrefHeight(32);
        button.setMaxHeight(34);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> onSelect.accept(chip.channelId()));
        button.setTooltip(new Tooltip(
                (chip.label() == null ? "Ch" + chip.channelId() : chip.label())
                        + (chip.idsText() == null ? "" : " \u00b7 " + chip.idsText())
                        + " \u00b7 Best DBC: " + dbc
                        + (cov.isBlank() ? "" : " \u00b7 " + cov)));
        return button;
    }
}

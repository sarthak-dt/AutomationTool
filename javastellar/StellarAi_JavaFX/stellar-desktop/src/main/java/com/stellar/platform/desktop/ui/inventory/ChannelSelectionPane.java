package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.ChannelSelectionModel;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterEngine;
import com.stellar.platform.desktop.presentation.ChannelSelectionModel.SelectionSummary;
import com.stellar.platform.desktop.presentation.SourceInventoryMatchState;
import com.stellar.platform.desktop.presentation.SourceInventoryModel;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanDefinitionMatchStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Compact CAN channel selection for LOCAL processing. Metadata-only; no reparse.
 */
public final class ChannelSelectionPane extends VBox {

    private final Label titleLabel = new Label("Signal Processing Selection");
    private final Label subtitleLabel = new Label("Choose which CAN channels should generate DBC-decoded signals.");
    private final Label stateLabel = new Label("");
    private final FlowPane cardsPane = new FlowPane();
    private final Label summaryLabel = new Label("Selected Channels: 0");
    private final Label staleLabel = new Label("");
    private final Button selectAllButton = new Button("Select All");
    private final Button selectMatchedButton = new Button("Select All Matched");
    private final Button clearButton = new Button("Clear Selection");

    private SourceInventoryModel inventoryModel;
    private ChannelSelectionModel selectionModel;
    private Runnable inventoryListener;
    private Runnable selectionListener;
    private Runnable onSelectionChanged;

    public ChannelSelectionPane() {
        // Do not add content-surface: that style is for page hosts and would fight card chrome.
        getStyleClass().add("channel-selection-pane");
        setSpacing(8);
        setPadding(new Insets(10, 12, 10, 12));
        setFillWidth(true);
        setMinHeight(Region.USE_PREF_SIZE);
        setPrefHeight(Region.USE_COMPUTED_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);
        // Decorative/container must not steal hits from child controls or the page ScrollPane.
        setPickOnBounds(false);

        titleLabel.getStyleClass().add("section-header");
        subtitleLabel.getStyleClass().add("muted");
        subtitleLabel.setWrapText(true);
        stateLabel.getStyleClass().add("muted");
        stateLabel.setWrapText(true);
        summaryLabel.getStyleClass().add("section-subtitle");
        staleLabel.getStyleClass().add("status-line");
        staleLabel.setWrapText(true);
        staleLabel.setManaged(false);
        staleLabel.setVisible(false);

        cardsPane.getStyleClass().add("channel-selection-cards");
        cardsPane.setHgap(10);
        cardsPane.setVgap(10);
        cardsPane.setPrefWrapLength(980);

        selectAllButton.getStyleClass().add("secondary-button");
        selectMatchedButton.getStyleClass().add("secondary-button");
        clearButton.getStyleClass().add("secondary-button");
        selectAllButton.setOnAction(e -> {
            if (selectionModel != null && inventoryModel != null) {
                selectionModel.selectAll(inventoryModel.focusedInventory().orElse(null));
            }
        });
        selectMatchedButton.setOnAction(e -> {
            if (selectionModel != null && inventoryModel != null) {
                selectionModel.selectAllMatched(
                        inventoryModel.focusedInventory().orElse(null),
                        inventoryModel.matchState());
            }
        });
        clearButton.setOnAction(e -> {
            if (selectionModel != null) {
                selectionModel.clearSelection();
            }
        });

        HBox actions = new HBox(8, selectAllButton, selectMatchedButton, clearButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(
                titleLabel,
                subtitleLabel,
                stateLabel,
                actions,
                cardsPane,
                summaryLabel,
                staleLabel
        );
        showIdle("Add a measurement file to discover CAN channels.");
    }

    public void setModels(SourceInventoryModel inventoryModel, ChannelSelectionModel selectionModel) {
        if (this.inventoryModel != null && inventoryListener != null) {
            this.inventoryModel.removeListener(inventoryListener);
        }
        if (this.selectionModel != null && selectionListener != null) {
            this.selectionModel.removeListener(selectionListener);
        }
        this.inventoryModel = Objects.requireNonNull(inventoryModel, "inventoryModel");
        this.selectionModel = Objects.requireNonNull(selectionModel, "selectionModel");
        inventoryListener = () -> javafx.application.Platform.runLater(this::refresh);
        selectionListener = () -> javafx.application.Platform.runLater(() -> {
            if (!isDisabled()) {
                refresh();
            }
            Runnable callback = onSelectionChanged;
            if (callback != null) {
                callback.run();
            }
        });
        this.inventoryModel.addListener(inventoryListener);
        this.selectionModel.addListener(selectionListener);
        refresh();
    }

    public void setOnSelectionChanged(Runnable onSelectionChanged) {
        this.onSelectionChanged = onSelectionChanged;
    }

    public void refresh() {
        if (inventoryModel == null || selectionModel == null) {
            showIdle("Add a measurement file to discover CAN channels.");
            return;
        }
        CanSourceInventory inventory = inventoryModel.focusedInventory().orElse(null);
        SourceInventoryMatchState match = inventoryModel.matchState();
        if (inventory == null || inventory.channels().isEmpty()) {
            showIdle("Waiting for Source Inventory CAN channel discovery...");
            return;
        }

        boolean dbcReady = SourceInventoryFilterEngine.isDbcMatchReady(match);
        if (!dbcReady) {
            stateLabel.setText("Select a DBC before generating DBC-decoded signals. Raw channel inventory remains available above.");
            selectMatchedButton.setDisable(true);
        } else {
            stateLabel.setText("Select one or more channels, then Process Selected.");
            selectMatchedButton.setDisable(false);
        }

        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        List<CanChannelInventory> channels = new ArrayList<>(inventory.channels());
        channels.sort(Comparator.comparingInt(CanChannelInventory::channelId));

        double bestCoverage = bestCoverage(channels, match);
        cardsPane.getChildren().clear();
        for (CanChannelInventory channel : channels) {
            Optional<CanChannelMatchSummary> channelMatch = match.channelMatch(channel.channelId());
            long matched = channelMatch.map(CanChannelMatchSummary::matchedCount).orElse(0L);
            long observed = channel.uniqueMessageCount();
            long unmatched = Math.max(0L, observed - matched);
            double coverage = channelMatch.map(CanChannelMatchSummary::matchPercentage).orElse(0.0);
            boolean selected = selectionModel.isSelected(channel.channelId());
            boolean best = dbcReady && observed > 0 && coverage >= bestCoverage - 0.0001 && coverage > 0;

            VBox card = buildCard(channel, observed, matched, unmatched, coverage, selected, best, dbcReady, nf);
            cardsPane.getChildren().add(card);
        }

        SelectionSummary summary = selectionModel.summarize(inventory, match);
        summaryLabel.setText(
                "Selected Channels: " + summary.selectedChannels()
                        + "    Selected Observed IDs: " + nf.format(summary.selectedObservedIds())
                        + "    Selected DBC-Matched IDs: " + nf.format(summary.selectedMatchedIds())
        );
        if (summary.resultStale()) {
            staleLabel.setText("Selection changed. Run processing to generate signals for the new channel selection.");
            staleLabel.setManaged(true);
            staleLabel.setVisible(true);
        } else {
            staleLabel.setText("");
            staleLabel.setManaged(false);
            staleLabel.setVisible(false);
        }
        selectAllButton.setDisable(false);
        clearButton.setDisable(!selectionModel.hasSelection());
    }

    private VBox buildCard(
            CanChannelInventory channel,
            long observed,
            long matched,
            long unmatched,
            double coverage,
            boolean selected,
            boolean best,
            boolean dbcReady,
            NumberFormat nf
    ) {
        VBox card = new VBox(6);
        card.getStyleClass().add("channel-selection-card");
        if (selected) {
            card.getStyleClass().add("channel-selection-card-selected");
        }
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setPrefWidth(300);
        card.setMinWidth(260);
        card.setMaxWidth(360);

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(channel.displayName());
        name.getStyleClass().add("section-subtitle");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(name, spacer);
        if (best) {
            Label badge = new Label("BEST COVERAGE");
            badge.getStyleClass().addAll("badge", "badge-ready");
            header.getChildren().add(badge);
        }

        Label stats = new Label(
                nf.format(observed) + " Observed IDs\n"
                        + nf.format(matched) + " DBC Matched\n"
                        + nf.format(unmatched) + " Unmatched\n"
                        + String.format(Locale.US, "%.2f%% Coverage\n", coverage)
                        + nf.format(channel.frameCount()) + " Frames"
        );
        stats.getStyleClass().add("muted");
        stats.setWrapText(true);

        ProgressBar bar = new ProgressBar(Math.max(0.0, Math.min(1.0, coverage / 100.0)));
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("channel-coverage-bar");

        Label hint = new Label("");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        if (!dbcReady) {
            hint.setText("DBC required for signal generation.");
        } else if (matched == 0) {
            hint.setText("0 DBC matches — this channel will produce no DBC-decoded signals with the selected DBC.");
        }

        CheckBox select = new CheckBox(selected ? "Selected" : "Select");
        select.setSelected(selected);
        select.setDisable(!dbcReady);
        select.selectedProperty().addListener((o, a, b) -> {
            if (selectionModel != null) {
                selectionModel.select(channel.channelId(), b);
            }
        });

        card.getChildren().addAll(header, stats, bar, hint, select);
        return card;
    }

    private static double bestCoverage(List<CanChannelInventory> channels, SourceInventoryMatchState match) {
        double best = -1.0;
        int bestCount = 0;
        for (CanChannelInventory ch : channels) {
            double coverage = match.channelMatch(ch.channelId())
                    .map(CanChannelMatchSummary::matchPercentage)
                    .orElse(0.0);
            if (coverage > best + 0.0001) {
                best = coverage;
                bestCount = 1;
            } else if (Math.abs(coverage - best) <= 0.0001) {
                bestCount++;
            }
        }
        // Only highlight when a unique highest coverage exists.
        return bestCount == 1 ? best : Double.POSITIVE_INFINITY;
    }

    private void showIdle(String message) {
        cardsPane.getChildren().clear();
        stateLabel.setText(message);
        summaryLabel.setText("Selected Channels: 0");
        staleLabel.setManaged(false);
        staleLabel.setVisible(false);
        selectAllButton.setDisable(true);
        selectMatchedButton.setDisable(true);
        clearButton.setDisable(true);
    }

}

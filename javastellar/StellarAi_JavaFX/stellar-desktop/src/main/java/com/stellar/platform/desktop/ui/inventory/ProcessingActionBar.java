package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.ChannelSelectionModel;
import com.stellar.platform.desktop.presentation.ChannelSelectionModel.SelectionSummary;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterEngine;
import com.stellar.platform.desktop.presentation.SourceInventoryMatchState;
import com.stellar.platform.desktop.presentation.SourceInventoryModel;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Sticky Project Files processing action bar — lives outside the page ScrollPane.
 */
public final class ProcessingActionBar extends VBox {

    private final Label titleLabel = new Label("Selected Channels: 0");
    private final Label channelsLabel = new Label("—");
    private final Label matchLabel = new Label("");
    private final Label hintLabel = new Label("");
    private final ProgressBar compactProgress = new ProgressBar(0);
    private final Label progressLabel = new Label("");
    private final Button clearButton = new Button("Clear");
    private final Button processButton = new Button("Process Selected");
    private final Button cancelButton = new Button("Cancel");
    private final Button detailsButton = new Button("View Details");
    private final HBox progressRow = new HBox(8);
    private final HBox actionsRow = new HBox(8);

    private Runnable onProcess = () -> { };
    private Runnable onClear = () -> { };
    private Runnable onCancel = () -> { };
    private Runnable onDetails = () -> { };

    public ProcessingActionBar() {
        getStyleClass().add("processing-action-bar");
        setSpacing(6);
        setPadding(new Insets(10, 14, 10, 14));
        setFillWidth(true);
        setPickOnBounds(true);

        titleLabel.getStyleClass().add("section-subtitle");
        channelsLabel.getStyleClass().add("processing-action-channels");
        matchLabel.getStyleClass().add("muted");
        hintLabel.getStyleClass().add("status-line");
        hintLabel.setWrapText(true);

        compactProgress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(compactProgress, Priority.ALWAYS);
        progressLabel.getStyleClass().add("muted");
        progressRow.setAlignment(Pos.CENTER_LEFT);
        progressRow.getChildren().setAll(progressLabel, compactProgress);
        progressRow.setVisible(false);
        progressRow.setManaged(false);

        clearButton.getStyleClass().addAll("secondary-button", "compact-button");
        processButton.getStyleClass().addAll("primary-button", "compact-button");
        cancelButton.getStyleClass().addAll("secondary-button", "compact-button");
        detailsButton.getStyleClass().addAll("secondary-button", "compact-button");
        clearButton.setOnAction(e -> onClear.run());
        processButton.setOnAction(e -> onProcess.run());
        cancelButton.setOnAction(e -> onCancel.run());
        detailsButton.setOnAction(e -> onDetails.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actionsRow.setAlignment(Pos.CENTER_LEFT);
        actionsRow.getChildren().setAll(clearButton, spacer, cancelButton, detailsButton, processButton);

        getChildren().addAll(titleLabel, channelsLabel, matchLabel, hintLabel, progressRow, actionsRow);
        showIdle("Add a measurement and DBC to enable processing.");
    }

    public void setOnProcess(Runnable onProcess) {
        this.onProcess = onProcess == null ? () -> { } : onProcess;
    }

    public void setOnClear(Runnable onClear) {
        this.onClear = onClear == null ? () -> { } : onClear;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel == null ? () -> { } : onCancel;
    }

    public void setOnDetails(Runnable onDetails) {
        this.onDetails = onDetails == null ? () -> { } : onDetails;
    }

    public Button processButton() {
        return processButton;
    }

    public void refresh(
            SourceInventoryModel inventoryModel,
            ChannelSelectionModel selectionModel,
            String processingHint,
            boolean canProcess,
            boolean running,
            double progressFraction,
            String progressChannels
    ) {
        Objects.requireNonNull(inventoryModel, "inventoryModel");
        Objects.requireNonNull(selectionModel, "selectionModel");
        CanSourceInventory inventory = inventoryModel.focusedInventory().orElse(null);
        SourceInventoryMatchState match = inventoryModel.matchState();
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);

        if (inventory == null || inventory.channels().isEmpty()) {
            if (canProcess && processingHint != null && processingHint.startsWith("Direct processing")) {
                showDirect(processingHint, running);
                applyRunningState(running, progressFraction, progressChannels);
                return;
            }
            showIdle("Waiting for Source Inventory…");
            applyRunningState(running, progressFraction, progressChannels);
            return;
        }

        boolean dbcReady = SourceInventoryFilterEngine.isDbcMatchReady(match);
        SelectionSummary summary = selectionModel.summarize(inventory, match);
        titleLabel.setText("Selected Channels: " + summary.selectedChannels());
        channelsLabel.setText(summary.selectedChannels() == 0 ? "None" : summary.channelLabel());
        if (!dbcReady) {
            matchLabel.setText("Select a DBC to generate decoded signals.");
            hintLabel.setText(processingHint == null ? "" : processingHint);
            processButton.setDisable(true);
        } else {
            String coverage = "";
            if (summary.selectedObservedIds() > 0) {
                double pct = 100.0 * summary.selectedMatchedIds() / summary.selectedObservedIds();
                coverage = " · " + String.format(Locale.US, "%.2f%% coverage", pct);
            }
            matchLabel.setText(nf.format(summary.selectedMatchedIds()) + " selected matched IDs" + coverage);
            hintLabel.setText(processingHint == null ? "" : processingHint);
            processButton.setDisable(!canProcess || running);
        }
        clearButton.setDisable(!selectionModel.hasSelection() || running);
        applyRunningState(running, progressFraction, progressChannels);
    }

    private void applyRunningState(boolean running, double progressFraction, String progressChannels) {
        progressRow.setVisible(running);
        progressRow.setManaged(running);
        cancelButton.setVisible(running);
        cancelButton.setManaged(running);
        detailsButton.setDisable(false);
        if (running) {
            progressLabel.setText("Processing " + (progressChannels == null || progressChannels.isBlank()
                    ? "…"
                    : progressChannels));
            double p = Math.max(0.0, Math.min(1.0, progressFraction));
            compactProgress.setProgress(p <= 0.0 ? ProgressBar.INDETERMINATE_PROGRESS : p);
            processButton.setText("Processing…");
        } else {
            compactProgress.setProgress(0);
            processButton.setText("Process Selected");
        }
    }

    private void showDirect(String hint, boolean running) {
        titleLabel.setText("Direct Processing");
        channelsLabel.setText("DBC not required");
        matchLabel.setText(hint == null ? "" : hint);
        hintLabel.setText("");
        processButton.setDisable(running);
        clearButton.setDisable(true);
    }

    private void showIdle(String message) {
        titleLabel.setText("Selected Channels: 0");
        channelsLabel.setText("—");
        matchLabel.setText(message);
        hintLabel.setText("");
        processButton.setDisable(true);
        clearButton.setDisable(true);
    }
}

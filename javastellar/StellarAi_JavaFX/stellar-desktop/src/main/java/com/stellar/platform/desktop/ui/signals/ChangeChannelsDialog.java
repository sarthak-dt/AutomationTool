package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.ChannelSelectionModel;
import com.stellar.platform.desktop.presentation.SignalsProcessingContext;
import com.stellar.platform.desktop.presentation.SourceInventoryModel;
import com.stellar.platform.desktop.ui.inventory.ChannelSelectionPane;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Lightweight Change Channels dialog. Uses a draft {@link ChannelSelectionModel}
 * so the completed Signals result is not mutated until Apply & Reprocess.
 */
public final class ChangeChannelsDialog {

    private ChangeChannelsDialog() {
    }

    /**
     * @return selected channel ids when Apply confirmed; empty when cancelled
     */
    public static Optional<Set<Integer>> show(
            Window owner,
            SourceInventoryModel inventoryModel,
            ChannelSelectionModel liveSelection,
            Path measurementPath,
            String measurementFileId,
            SignalsProcessingContext currentResult,
            String sourceFileName,
            String dbcFileName
    ) {
        Objects.requireNonNull(inventoryModel, "inventoryModel");
        Objects.requireNonNull(liveSelection, "liveSelection");

        ChannelSelectionModel draft = new ChannelSelectionModel();
        CanSourceInventory inventory = inventoryModel.focusedInventory().orElse(null);
        draft.bindSource(measurementFileId, measurementPath, inventory);
        draft.replaceSelection(liveSelection.selectedChannelIds());

        Label sourceLabel = new Label("Source: " + nullToDash(sourceFileName));
        Label dbcLabel = new Label("DBC: " + nullToDash(dbcFileName));
        Label currentLabel = new Label("Current Result: "
                + (currentResult == null || !currentResult.hasResult()
                ? "\u2014"
                : currentResult.channelsSummary()));
        Label pendingLabel = new Label("New Selection: " + draft.toProcessingSelection().summaryLabel());
        sourceLabel.getStyleClass().add("muted");
        dbcLabel.getStyleClass().add("muted");
        currentLabel.getStyleClass().add("section-subtitle");
        pendingLabel.getStyleClass().add("section-subtitle");

        ChannelSelectionPane pane = new ChannelSelectionPane();
        pane.setModels(inventoryModel, draft);
        pane.setOnSelectionChanged(() ->
                pendingLabel.setText("New Selection: " + draft.toProcessingSelection().summaryLabel()));

        ScrollPane scroll = new ScrollPane(pane);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(360);
        scroll.setMaxHeight(420);

        VBox body = new VBox(8, sourceLabel, dbcLabel, currentLabel, pendingLabel, scroll);
        body.setPadding(new Insets(8, 4, 4, 4));
        body.setFillWidth(true);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Change Processing Channels");
        dialog.setHeaderText("Choose channels for the next LOCAL processing run.");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getStyleClass().add("change-channels-dialog");
        dialogPane.setContent(body);
        ButtonType applyType = new ButtonType("Apply & Reprocess", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().setAll(cancelType, applyType);

        Node applyButton = dialogPane.lookupButton(applyType);
        applyButton.setDisable(!draft.hasSelection());
        draft.addListener(() -> applyButton.setDisable(!draft.hasSelection()));

        dialogPane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                dialog.setResult(cancelType);
                dialog.close();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && draft.hasSelection()) {
                dialog.setResult(applyType);
                dialog.close();
                e.consume();
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == applyType && draft.hasSelection()) {
            return Optional.of(draft.selectedChannelIds());
        }
        return Optional.empty();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "\u2014" : value;
    }
}

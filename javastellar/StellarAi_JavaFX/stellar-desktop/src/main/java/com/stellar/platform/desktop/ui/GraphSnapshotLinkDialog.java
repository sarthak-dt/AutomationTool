package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphSnapshotLinks;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;

/**
 * Graph event dialog for choosing, previewing, and linking a snapshot that already exists.
 */
final class GraphSnapshotLinkDialog {

    private final DesktopAppService appService;
    private final String bookmarkId;
    private final java.util.function.Predicate<String> captureAction;
    private final ListView<GraphSnapshotLinks.Choice> choices = new ListView<>();
    private final ImageView preview = new ImageView();
    private final Label detail = new Label("Select a snapshot.");
    private final TextField captureName = new TextField();
    private Button linkButton;
    private Button unlinkButton;

    GraphSnapshotLinkDialog(
            DesktopAppService appService, String bookmarkId, java.util.function.Predicate<String> captureAction
    ) {
        this.appService = appService;
        this.bookmarkId = bookmarkId == null ? "" : bookmarkId;
        this.captureAction = captureAction;
    }

    Dialog<ButtonType> build() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Link Snapshot");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        preview.setFitWidth(320);
        preview.setFitHeight(180);
        preview.setPreserveRatio(true);
        detail.setWrapText(true);
        choices.setPrefHeight(140);
        choices.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(GraphSnapshotLinks.Choice item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText((item.linked() ? "Linked  ·  " : "") + item.caption());
            }
        });
        choices.getSelectionModel().selectedItemProperty().addListener((obs, previous, value) -> show(value));
        captureName.setPromptText("Name for a new snapshot");
        Button capture = new Button("Capture");
        capture.setOnAction(event -> capture());
        linkButton = new Button("Link");
        linkButton.setOnAction(event -> apply(true));
        unlinkButton = new Button("Unlink");
        unlinkButton.setOnAction(event -> apply(false));
        Button exclude = new Button("Exclude");
        exclude.setOnAction(event -> evidence(false));
        Button include = new Button("Include");
        include.setOnAction(event -> evidence(true));
        HBox actions = new HBox(8, capture, linkButton, unlinkButton, include, exclude);
        VBox body = new VBox(8,
                new Label("Existing snapshots"),
                choices,
                preview,
                detail,
                new Label("Capture a new snapshot from the current graph"),
                captureName,
                actions);
        body.setPadding(new Insets(4));
        VBox.setVgrow(choices, Priority.ALWAYS);
        dialog.getDialogPane().setContent(body);
        dialog.setOnHidden(event -> preview.setImage(null));
        reload();
        return dialog;
    }

    private void reload() {
        String keep = selectedId();
        List<GraphSnapshotLinks.Choice> items = appService == null
                ? List.of() : appService.graphSnapshotChoices(bookmarkId);
        choices.getItems().setAll(items);
        if (keep != null) {
            for (GraphSnapshotLinks.Choice item : items) {
                if (keep.equals(item.snapshotId())) {
                    choices.getSelectionModel().select(item);
                    return;
                }
            }
        }
        if (!items.isEmpty()) {
            choices.getSelectionModel().selectFirst();
        } else {
            show(null);
        }
    }

    private void show(GraphSnapshotLinks.Choice choice) {
        preview.setImage(null);
        if (choice == null) {
            detail.setText("Select a snapshot.");
            if (linkButton != null) {
                linkButton.setDisable(true);
            }
            if (unlinkButton != null) {
                unlinkButton.setDisable(true);
            }
            return;
        }
        detail.setText(choice.detail().isBlank() ? choice.caption() : choice.caption() + ". " + choice.detail());
        if (linkButton != null) {
            linkButton.setDisable(!choice.linkAllowed() || choice.linked());
        }
        if (unlinkButton != null) {
            unlinkButton.setDisable(!choice.linked());
        }
        Path png = choice.png();
        if (png != null) {
            preview.setImage(new Image(png.toUri().toString(), 320, 180, true, true, false));
        }
    }

    private void capture() {
        String name = captureName.getText() == null ? "" : captureName.getText().trim();
        if (name.isEmpty() || appService == null || captureAction == null) {
            detail.setText("Enter a snapshot name.");
            return;
        }
        if (!captureAction.test(name)) {
            detail.setText("The snapshot was not captured.");
            return;
        }
        captureName.clear();
        reload();
        for (GraphSnapshotLinks.Choice item : choices.getItems()) {
            if (name.equals(item.caption())) {
                choices.getSelectionModel().select(item);
            }
        }
    }

    private void apply(boolean link) {
        GraphSnapshotLinks.Choice choice = choices.getSelectionModel().getSelectedItem();
        if (choice == null || appService == null) {
            return;
        }
        String message = link
                ? appService.linkGraphEventSnapshot(bookmarkId, choice.snapshotId())
                : appService.unlinkGraphEventSnapshot(bookmarkId, choice.snapshotId());
        reload();
        if (!message.isBlank()) {
            detail.setText(message);
        }
    }

    private void evidence(boolean include) {
        GraphSnapshotLinks.Choice choice = choices.getSelectionModel().getSelectedItem();
        if (choice == null || appService == null) {
            return;
        }
        String message = include
                ? appService.includeGraphSnapshot(choice.snapshotId())
                : appService.excludeGraphSnapshot(choice.snapshotId());
        reload();
        if (!message.isBlank()) {
            detail.setText(message);
        }
    }

    void selectForTest(String snapshotId) {
        for (GraphSnapshotLinks.Choice item : choices.getItems()) {
            if (snapshotId.equals(item.snapshotId())) {
                choices.getSelectionModel().select(item);
                return;
            }
        }
    }

    boolean previewReadyForTest() {
        return preview.getImage() != null && preview.getImage().getWidth() > 0;
    }

    String detailForTest() {
        return detail.getText() == null ? "" : detail.getText();
    }

    boolean linkDisabledForTest() {
        return linkButton == null || linkButton.isDisabled();
    }

    String commitLinkForTest() {
        apply(true);
        return detailForTest();
    }

    String commitUnlinkForTest() {
        apply(false);
        return detailForTest();
    }

    String commitExcludeForTest() {
        evidence(false);
        return detailForTest();
    }

    String commitIncludeForTest() {
        evidence(true);
        return detailForTest();
    }

    void setCaptureNameForTest(String name) {
        captureName.setText(name);
    }

    boolean commitCaptureForTest() {
        int before = choices.getItems().size();
        capture();
        return choices.getItems().size() == before + 1;
    }

    private String selectedId() {
        GraphSnapshotLinks.Choice choice = choices.getSelectionModel().getSelectedItem();
        return choice == null ? null : choice.snapshotId();
    }
}

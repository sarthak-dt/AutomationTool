package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.ProcessingWarningReport;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Map;

/** Compact right-side drawer listing persisted processing warnings. */
public final class ProcessingWarningDrawer extends VBox {

    private final VBox groups = new VBox(10);
    private final Label emptyLabel = new Label("No processing warnings are recorded for this project.");

    public ProcessingWarningDrawer(Runnable onClose) {
        getStyleClass().add("processing-warning-drawer");
        setFillWidth(true);
        setMaxWidth(440);
        setPrefWidth(420);
        setMinWidth(320);
        setPadding(new Insets(16));
        setSpacing(12);

        Label title = new Label("Processing warnings");
        title.getStyleClass().add("subsection-title");
        Button close = new Button("Close");
        close.getStyleClass().addAll("secondary-button", "compact-button");
        close.setOnAction(e -> {
            if (onClose != null) {
                onClose.run();
            }
        });
        HBox header = new HBox(8, title, new Region(), close);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);

        Label hint = new Label("These warnings were recorded during processing and remain part of the project evidence.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        emptyLabel.getStyleClass().add("muted");
        emptyLabel.setWrapText(true);

        groups.setFillWidth(true);
        ScrollPane scroll = new ScrollPane(groups);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("processing-warning-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(header, hint, scroll);
        setVisible(false);
        setManaged(false);
    }

    public void show(ProcessingWarningReport report) {
        groups.getChildren().clear();
        ProcessingWarningReport effective = report == null ? ProcessingWarningReport.empty() : report;
        if (effective.isEmpty()) {
            groups.getChildren().add(emptyLabel);
            return;
        }
        for (Map.Entry<ProcessingWarningReport.Category, java.util.List<ProcessingWarningReport.Item>> entry
                : effective.grouped().entrySet()) {
            VBox body = new VBox(8);
            for (ProcessingWarningReport.Item item : entry.getValue()) {
                body.getChildren().add(card(item));
            }
            TitledPane pane = new TitledPane(entry.getKey().title() + " (" + entry.getValue().size() + ")", body);
            pane.setExpanded(true);
            pane.getStyleClass().add("processing-warning-group");
            groups.getChildren().add(pane);
        }
    }

    private static VBox card(ProcessingWarningReport.Item item) {
        Label summary = new Label(item.summary());
        summary.setWrapText(true);
        VBox card = new VBox(4, summary);
        card.getStyleClass().add("processing-warning-card");
        if (!item.source().isBlank()) {
            Label source = new Label("Measurement: " + item.source());
            source.getStyleClass().add("muted");
            source.setWrapText(true);
            card.getChildren().add(source);
        }
        if (!item.signalOrMessage().isBlank()) {
            Label signal = new Label("Channel: " + item.signalOrMessage());
            signal.getStyleClass().add("muted");
            signal.setWrapText(true);
            card.getChildren().add(signal);
        }
        if (item.count() > 0) {
            Label count = new Label("Count: " + item.count());
            count.getStyleClass().add("muted");
            card.getChildren().add(count);
        }
        Label continueLabel = new Label(item.analysisCanContinue()
                ? "Analysis can continue"
                : "Review before relying on this analysis");
        continueLabel.getStyleClass().add("muted");
        card.getChildren().add(continueLabel);
        if (!item.technicalDetail().isBlank()) {
            TitledPane technical = new TitledPane("Technical details", technicalLabel(item.technicalDetail()));
            technical.setExpanded(false);
            technical.getStyleClass().add("processing-warning-technical");
            card.getChildren().add(technical);
        }
        return card;
    }

    private static Label technicalLabel(String detail) {
        Label label = new Label(detail);
        label.setWrapText(true);
        label.getStyleClass().add("processing-warning-technical-text");
        return label;
    }
}

package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.ProcessingDetailsPresentation;
import com.stellar.platform.desktop.presentation.ProcessingDetailsPresentation.DiagnosticGroup;
import com.stellar.platform.desktop.presentation.ProcessingDetailsPresentation.Model;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/**
 * Processing Complete details. Structured summary and a virtualized diagnostic list;
 * the original raw text remains on the Raw Log tab.
 */
public final class ProcessingDetailsDialog {

    private ProcessingDetailsDialog() {
    }

    public static Stage show(Window owner, Model model) {
        Model data = model == null
                ? new Model("", "", "", "", "", "", "", "", "", List.of(), "")
                : model;
        Stage stage = new Stage();
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Processing Details");
        stage.setResizable(true);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("processing-details-dialog");
        root.setPadding(new Insets(WorkstationLayoutMetrics.PAGE_PADDING_V,
                WorkstationLayoutMetrics.PAGE_PADDING_H,
                WorkstationLayoutMetrics.PAGE_PADDING_V,
                WorkstationLayoutMetrics.PAGE_PADDING_H));

        VBox header = header(data);
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("processing-details-tabs");
        Tab summary = new Tab("Summary", summaryPane(data));
        Tab warnings = new Tab("Warnings / Diagnostics", warningsPane(data));
        Tab raw = new Tab("Raw Log", rawPane(data));
        summary.setClosable(false);
        warnings.setClosable(false);
        raw.setClosable(false);
        tabs.getTabs().setAll(summary, warnings, raw);
        tabs.getSelectionModel().select(data.groups().isEmpty() ? summary : warnings);

        HBox actions = new HBox(WorkstationLayoutMetrics.CONTROL_GAP);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("processing-details-actions");
        Button copy = new Button("Copy Details");
        copy.getStyleClass().addAll("secondary-button", "workstation-secondary-button");
        copy.setOnAction(e -> copyDetails(data));
        Button close = new Button("Close");
        close.getStyleClass().addAll("primary-button", "workstation-primary-button");
        close.setDefaultButton(true);
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());
        actions.getChildren().setAll(copy, close);

        VBox center = new VBox(WorkstationLayoutMetrics.SECTION_GAP, header, tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        root.setCenter(center);
        root.setBottom(actions);

        Scene scene = new Scene(root);
        var css = ProcessingDetailsDialog.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
                e.consume();
            }
        });
        stage.setScene(scene);
        applyBounds(stage, owner);
        stage.show();
        close.requestFocus();
        return stage;
    }

    private static VBox header(Model data) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("processing-details-header");
        grid.setHgap(WorkstationLayoutMetrics.CONTROL_GAP);
        grid.setVgap(4);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(110);
        labels.setPrefWidth(130);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, values);
        addField(grid, 0, "Source", data.sourceName());
        addField(grid, 1, "Result", data.result());
        addField(grid, 2, "Channels", data.channels());
        addField(grid, 3, "DBC Used", data.dbcUsed().isBlank() ? "—" : data.dbcUsed());
        addField(grid, 4, "Elapsed", data.elapsed());
        addField(grid, 5, "Warnings", Integer.toString(data.warningCount()));
        return new VBox(grid);
    }

    private static void addField(GridPane grid, int row, String label, String value) {
        Label name = new Label(label);
        name.getStyleClass().add("processing-details-field-label");
        Label body = new Label(value == null || value.isBlank() ? "—" : value);
        body.getStyleClass().add("processing-details-field-value");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        grid.add(name, 0, row);
        grid.add(body, 1, row);
    }

    private static GridPane summaryPane(Model data) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("processing-details-summary");
        grid.setHgap(WorkstationLayoutMetrics.CONTROL_GAP);
        grid.setVgap(6);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(140);
        labels.setPrefWidth(160);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, values);
        addField(grid, 0, "Source", data.sourceName());
        addField(grid, 1, "Channels", data.channels());
        addField(grid, 2, "DBC Used", data.dbcUsed().isBlank() ? "—" : data.dbcUsed());
        addField(grid, 3, "Selected Frames", data.selectedFrames());
        addField(grid, 4, "DBC Matched", data.dbcMatched());
        addField(grid, 5, "Decoded Signals", data.decodedSignals());
        addField(grid, 6, "Elapsed", data.elapsed());
        addField(grid, 7, "Result", data.result());
        return grid;
    }

    private static VBox warningsPane(Model data) {
        TableView<DiagnosticGroup> table = new TableView<>();
        table.getStyleClass().add("processing-details-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No warnings or diagnostics."));
        TableColumn<DiagnosticGroup, String> severity = new TableColumn<>("Severity / Type");
        severity.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().severity()));
        severity.setMinWidth(110);
        severity.setPrefWidth(130);
        TableColumn<DiagnosticGroup, String> code = new TableColumn<>("Code");
        code.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().code()));
        code.setMinWidth(160);
        code.setPrefWidth(220);
        TableColumn<DiagnosticGroup, String> message = new TableColumn<>("Message");
        message.setCellValueFactory(c -> {
            DiagnosticGroup group = c.getValue();
            String text = group.message();
            if (group.occurrences() > 1) {
                text = text + "  ·  Occurrences: " + group.occurrences();
            }
            return new javafx.beans.property.ReadOnlyStringWrapper(text);
        });
        message.setMinWidth(240);
        message.setCellFactory(col -> wrappingCell());
        table.getColumns().setAll(severity, code, message);
        table.getItems().setAll(data.groups());

        TextArea originals = new TextArea();
        originals.getStyleClass().add("processing-details-originals");
        originals.setEditable(false);
        originals.setWrapText(true);
        originals.setPrefRowCount(4);
        originals.setPromptText("Select a grouped diagnostic to inspect every original entry.");
        table.getSelectionModel().selectedItemProperty().addListener((obs, prev, next) -> {
            if (next == null || next.originals().isEmpty()) {
                originals.clear();
                return;
            }
            originals.setText(String.join("\n", next.originals()));
        });
        if (!data.groups().isEmpty()) {
            table.getSelectionModel().selectFirst();
        }
        VBox box = new VBox(WorkstationLayoutMetrics.CONTROL_GAP, table, originals);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    private static TableCell<DiagnosticGroup, String> wrappingCell() {
        return new TableCell<>() {
            private final Label label = new Label();

            {
                label.setWrapText(true);
                label.getStyleClass().add("processing-details-message");
                setGraphic(label);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    label.setText("");
                    return;
                }
                label.setText(item);
                label.setMaxWidth(getTableColumn() == null ? 400 : Math.max(200, getTableColumn().getWidth() - 12));
            }
        };
    }

    private static TextArea rawPane(Model data) {
        TextArea raw = new TextArea(data.rawText().isBlank() ? "No raw diagnostic text." : data.rawText());
        raw.getStyleClass().add("processing-details-raw");
        raw.setEditable(false);
        raw.setWrapText(true);
        return raw;
    }

    private static void applyBounds(Stage stage, Window owner) {
        Rectangle2D screen = owner != null && owner.getX() >= 0
                ? new Rectangle2D(owner.getX(), owner.getY(),
                Math.max(1, owner.getWidth()), Math.max(1, owner.getHeight()))
                : Screen.getPrimary().getVisualBounds();
        if (owner == null) {
            screen = Screen.getPrimary().getVisualBounds();
        } else {
            try {
                screen = Screen.getScreensForRectangle(owner.getX(), owner.getY(),
                        Math.max(1, owner.getWidth()), Math.max(1, owner.getHeight()))
                        .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
            } catch (RuntimeException ignored) {
                screen = Screen.getPrimary().getVisualBounds();
            }
        }
        double maxW = Math.max(WorkstationLayoutMetrics.DETAILS_DIALOG_MIN_WIDTH, screen.getWidth() - 48);
        double maxH = Math.max(WorkstationLayoutMetrics.DETAILS_DIALOG_MIN_HEIGHT, screen.getHeight() - 48);
        double width = Math.min(WorkstationLayoutMetrics.DETAILS_DIALOG_WIDTH, maxW);
        double height = Math.min(WorkstationLayoutMetrics.DETAILS_DIALOG_HEIGHT, maxH);
        width = Math.max(Math.min(width, maxW), Math.min(WorkstationLayoutMetrics.DETAILS_DIALOG_MIN_WIDTH, maxW));
        height = Math.max(Math.min(height, maxH), Math.min(WorkstationLayoutMetrics.DETAILS_DIALOG_MIN_HEIGHT, maxH));
        if (screen.getWidth() <= WorkstationLayoutMetrics.BREAKPOINT_NARROW + 1) {
            width = Math.min(width, screen.getWidth() - 32);
            height = Math.min(height, screen.getHeight() - 32);
        }
        stage.setMinWidth(Math.min(WorkstationLayoutMetrics.DETAILS_DIALOG_MIN_WIDTH, width));
        stage.setMinHeight(Math.min(WorkstationLayoutMetrics.DETAILS_DIALOG_MIN_HEIGHT, height));
        stage.setMaxWidth(maxW);
        stage.setMaxHeight(maxH);
        stage.setWidth(width);
        stage.setHeight(height);
        if (owner != null) {
            stage.setX(owner.getX() + Math.max(0, (owner.getWidth() - width) / 2));
            stage.setY(owner.getY() + Math.max(0, (owner.getHeight() - height) / 2));
        }
    }

    private static void copyDetails(Model data) {
        ClipboardContent content = new ClipboardContent();
        content.putString(ProcessingDetailsPresentation.copyText(data));
        Clipboard.getSystemClipboard().setContent(content);
    }
}

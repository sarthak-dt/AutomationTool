package com.stellar.platform.desktop.ui.control;

import com.stellar.platform.desktop.ui.StellarIcons;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.map.GpsDetectionConfidence;
import com.stellar.platform.processing.map.GpsMappingDraft;
import com.stellar.platform.processing.map.GpsSemanticResolver;
import com.stellar.platform.processing.map.GpsSignalCandidate;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSignalValidator;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteModel;
import javafx.beans.InvalidationListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Guided GPS mapping dialog for Map Route.
 *
 * <p>Resolution, validation and persistence stay in {@link GpsMappingDraft}. This control is the
 * default engineer-facing flow: pick a measurement, assign the required coordinates, confirm the
 * preview, then save. Manual typed names and product defaults remain behind Advanced Mapping.
 */
public final class GpsSignalMappingModal extends StackPane {

    private static final String OPTIONAL_PROMPT = "Select signal (optional)";
    private static final String MEASUREMENT_PROMPT = "Select measurement";
    private static final long MIN_RENDERABLE_SAMPLES = 2L;
    private static final double STACK_BODY_WIDTH = 900;
    private static final double LABEL_COL = 126;
    private static final double BADGE_COL = 96;
    private static final double PILL_COL = 58;

    private final Map<GpsSignalRole, RoleRow> rows = new EnumMap<>(GpsSignalRole.class);
    private final ComboBox<MeasurementOption> measurementField = new ComboBox<>();
    private final Button applyAllButton = new Button("Apply All");
    private final Button closeButton = new Button();
    private final Button cancelButton = new Button("Cancel");
    private final Button advancedButton = new Button("Advanced Mapping");
    private final Button saveButton = new Button("Save Mapping");
    private final Label saveError = new Label();
    private final Label bannerIconHost = new Label();
    private final Label bannerTitle = new Label();
    private final Label bannerDetail = new Label();
    private final VBox banner = new VBox(4);
    private final Label summaryMeasurement = new Label();
    private final Label summaryLatitude = new Label();
    private final Label summaryLongitude = new Label();
    private final Label summaryFormat = new Label();
    private final Label summarySamples = new Label();
    private final Label summaryStatus = new Label();
    private final Label routeChip = new Label();
    private final Label noteText = new Label();
    private final RoutePreviewPane preview = new RoutePreviewPane();
    private final BorderPane dialog = new BorderPane();
    private final GridPane bodyGrid = new GridPane();
    private final VBox leftForm = new VBox(16);
    private final VBox rightColumn = new VBox(14);
    private final StackPane mapHost = new StackPane();
    private boolean stackedBody;

    private GpsMappingDraft draft;
    private MapRoute previewRoute = MapRoute.emptyRoute();
    private boolean applying;
    private boolean saveBusy;
    private boolean userAttemptedSave;
    private Runnable saveHandler = () -> { };
    private Runnable cancelHandler = () -> { };
    private Runnable recommendedHandler = () -> { };
    private Runnable fullConfigHandler = () -> { };
    private Consumer<GpsMappingDraft> changeHandler = d -> { };
    private Consumer<String> measurementChangeHandler = id -> { };

    public GpsSignalMappingModal() {
        getStyleClass().add("gps-mapping-overlay");
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setMinSize(0, 0);
        setFocusTraversable(true);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onOverlayKey);

        Region scrim = new Region();
        scrim.getStyleClass().add("gps-mapping-scrim");
        scrim.setOnMouseClicked(e -> cancelHandler.run());

        dialog.getStyleClass().add("gps-mapping-dialog");
        dialog.setOnMouseClicked(e -> e.consume());
        dialog.setMinWidth(0);
        dialog.setMinHeight(0);
        bindDialogSize();

        VBox top = new VBox(buildHeader(), buildSteps());
        top.setMinWidth(0);
        dialog.setTop(top);
        dialog.setCenter(buildScrollableBody());
        dialog.setBottom(buildFooter());

        StackPane.setAlignment(dialog, Pos.CENTER);
        StackPane.setMargin(dialog, new Insets(16, 24, 16, 24));
        getChildren().addAll(scrim, dialog);
        setVisible(false);
        setManaged(false);
        setMouseTransparent(true);
    }

    /**
     * Shows this overlay on the application Scene root. Focus Mode only changes Scene size;
     * the same instance stays attached.
     */
    public void showOnApplicationOverlay(Node owner) {
        ApplicationOverlayLayer.attach(this, owner != null ? owner : this);
        setMouseTransparent(false);
        setVisible(true);
        setManaged(getParent() instanceof StackPane);
        toFront();
        requestFocus();
        layoutBody(dialog.getWidth());
    }

    public void hideOverlay() {
        hidePickers();
        setVisible(false);
        setManaged(false);
        setMouseTransparent(true);
    }

    public void detachOverlay() {
        hideOverlay();
        ApplicationOverlayLayer.detach(this);
    }

    private void bindDialogSize() {
        InvalidationListener refresh = obs -> applyDialogSize();
        widthProperty().addListener(refresh);
        heightProperty().addListener(refresh);
        sceneProperty().addListener((o, old, scene) -> {
            if (old != null) {
                old.widthProperty().removeListener(refresh);
                old.heightProperty().removeListener(refresh);
            }
            if (scene != null) {
                scene.widthProperty().addListener(refresh);
                scene.heightProperty().addListener(refresh);
            }
            applyDialogSize();
        });
        dialog.widthProperty().addListener((o, a, width) -> {
            layoutBody(width == null ? 0 : width.doubleValue());
            preview.setResponsiveHeight(previewHeight(dialog.getHeight()));
        });
        dialog.heightProperty().addListener((o, a, height) ->
                preview.setResponsiveHeight(previewHeight(height == null ? 0 : height.doubleValue())));
        applyDialogSize();
    }

    private void applyDialogSize() {
        double width = dialogWidth(viewportWidth());
        double height = dialogHeight(viewportHeight());
        dialog.setPrefWidth(width);
        dialog.setMaxWidth(width);
        dialog.setPrefHeight(height);
        dialog.setMaxHeight(height);
        preview.setResponsiveHeight(previewHeight(height));
    }

    private double viewportWidth() {
        if (getWidth() > 1) {
            return getWidth();
        }
        return getScene() == null ? 1280 : getScene().getWidth();
    }

    private double viewportHeight() {
        if (getHeight() > 1) {
            return getHeight();
        }
        return getScene() == null ? 800 : getScene().getHeight();
    }

    static double dialogWidth(double sceneWidth) {
        double available = Math.max(0, sceneWidth - 48);
        double preferred = sceneWidth * 0.78;
        double width = Math.max(980, Math.min(preferred, 1280));
        width = Math.min(width, available);
        if (available < 980) {
            width = available >= 860 ? Math.max(860, Math.min(available, 980)) : available;
        }
        return Math.max(0, Math.min(width, available));
    }

    static double dialogHeight(double sceneHeight) {
        double available = Math.max(0, sceneHeight - 32);
        return Math.min(Math.min(sceneHeight * 0.86, 860), available);
    }

    private static double previewHeight(double dialogHeight) {
        if (dialogHeight < 700) {
            return Math.max(160, Math.min(190, dialogHeight * 0.22));
        }
        if (dialogHeight < 800) {
            return 210;
        }
        return Math.min(300, Math.max(240, dialogHeight * 0.28));
    }

    private ScrollPane buildScrollableBody() {
        leftForm.getStyleClass().add("gps-mapping-form");
        leftForm.setMinWidth(0);
        leftForm.setFillWidth(true);
        leftForm.getChildren().addAll(
                buildMeasurementSection(),
                buildRecommendedHelper(),
                buildRequiredSection(),
                divider(),
                buildOptionalSection());

        rightColumn.getStyleClass().add("gps-mapping-right");
        rightColumn.setMinWidth(0);
        rightColumn.getChildren().setAll(buildValidationCard(), buildRouteCard());
        VBox.setVgrow(rightColumn.getChildren().get(1), Priority.SOMETIMES);

        bodyGrid.getStyleClass().add("gps-mapping-body");
        bodyGrid.setHgap(28);
        bodyGrid.setVgap(16);
        bodyGrid.setMinWidth(0);
        layoutBody(1200);

        ScrollPane scroll = new ScrollPane(bodyGrid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPannable(false);
        scroll.getStyleClass().add("gps-mapping-form-scroll");
        scroll.setMinWidth(0);
        scroll.setMinHeight(0);
        BorderPane.setAlignment(scroll, Pos.TOP_CENTER);
        return scroll;
    }

    private void layoutBody(double dialogWidth) {
        boolean stack = dialogWidth > 1 && dialogWidth < STACK_BODY_WIDTH;
        if (stack == stackedBody && !bodyGrid.getChildren().isEmpty()) {
            return;
        }
        stackedBody = stack;
        bodyGrid.getChildren().removeAll(leftForm, rightColumn);
        bodyGrid.getColumnConstraints().clear();
        if (stack) {
            ColumnConstraints only = percentColumn(100);
            bodyGrid.getColumnConstraints().add(only);
            bodyGrid.add(leftForm, 0, 0);
            bodyGrid.add(rightColumn, 0, 1);
        } else {
            bodyGrid.getColumnConstraints().addAll(percentColumn(58), percentColumn(42));
            bodyGrid.add(leftForm, 0, 0);
            bodyGrid.add(rightColumn, 1, 0);
        }
        GridPane.setHgrow(leftForm, Priority.ALWAYS);
        GridPane.setHgrow(rightColumn, Priority.ALWAYS);
        GridPane.setFillWidth(leftForm, true);
        GridPane.setFillWidth(rightColumn, true);
        GridPane.setValignment(leftForm, VPos.TOP);
        GridPane.setValignment(rightColumn, VPos.TOP);
    }

    private static ColumnConstraints percentColumn(double percent) {
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(percent);
        column.setHgrow(Priority.ALWAYS);
        column.setMinWidth(0);
        column.setFillWidth(true);
        return column;
    }

    private VBox buildHeader() {
        Label title = new Label("Configure GPS Signal Mapping");
        title.getStyleClass().add("gps-mapping-title");
        Label subtitle = new Label("Map the required signals to enable route visualization.");
        subtitle.getStyleClass().add("gps-mapping-subtitle");
        VBox copy = new VBox(4, title, subtitle);

        closeButton.getStyleClass().add("gps-mapping-close");
        closeButton.setGraphic(StellarIcons.close());
        closeButton.setTooltip(new Tooltip("Close without saving"));
        closeButton.setAccessibleText("Close");
        closeButton.setOnAction(e -> cancelHandler.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, copy, spacer, closeButton);
        row.setAlignment(Pos.TOP_LEFT);
        VBox header = new VBox(row);
        header.getStyleClass().add("gps-mapping-header");
        return header;
    }

    private GridPane buildSteps() {
        GridPane steps = new GridPane();
        steps.getStyleClass().add("gps-mapping-steps");
        steps.setMinWidth(0);
        steps.setHgap(10);
        steps.getColumnConstraints().addAll(percentColumn(33.333), percentColumn(33.333), percentColumn(33.334));
        steps.add(step(1, "Select Measurement", "Choose data source", StepState.COMPLETE, true, true), 0, 0);
        steps.add(step(2, "Map GPS Signals", "Assign latitude, longitude and optional signals", StepState.ACTIVE, false, true), 1, 0);
        steps.add(step(3, "Confirm & Preview", "Validate and save", StepState.UPCOMING, false, false), 2, 0);
        return steps;
    }

    private static Node step(int number, String title, String subtitle, StepState state, boolean completeLine, boolean showLine) {
        Label marker = new Label(state == StepState.COMPLETE ? "" : String.valueOf(number));
        marker.getStyleClass().addAll("gps-mapping-step-marker", switch (state) {
            case COMPLETE -> "gps-mapping-step-complete";
            case ACTIVE -> "gps-mapping-step-active";
            case UPCOMING -> "gps-mapping-step-upcoming";
        });
        if (state == StepState.COMPLETE) {
            marker.setGraphic(StellarIcons.check());
        }
        Label name = new Label(title);
        name.getStyleClass().addAll("gps-mapping-step-title",
                state == StepState.UPCOMING ? "gps-mapping-step-muted" : "gps-mapping-step-current");
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);
        Label hint = new Label(subtitle);
        hint.getStyleClass().add("gps-mapping-step-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);
        VBox copy = new VBox(2, name, hint);
        copy.setMinWidth(0);
        HBox.setHgrow(copy, Priority.ALWAYS);
        HBox row = new HBox(8, marker, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
        row.setMaxWidth(Double.MAX_VALUE);
        if (showLine) {
            Region line = connector(completeLine);
            HBox.setHgrow(line, Priority.ALWAYS);
            row.getChildren().add(line);
        }
        GridPane.setHgrow(row, Priority.ALWAYS);
        GridPane.setFillWidth(row, true);
        GridPane.setValignment(row, VPos.CENTER);
        return row;
    }

    private static Region connector(boolean complete) {
        Region line = new Region();
        line.getStyleClass().addAll("gps-mapping-step-line",
                complete ? "gps-mapping-step-line-done" : "gps-mapping-step-line-todo");
        line.setMinHeight(2);
        line.setPrefHeight(2);
        line.setMaxHeight(2);
        line.setMinWidth(16);
        line.setMaxWidth(Double.MAX_VALUE);
        return line;
    }

    private VBox buildMeasurementSection() {
        Label label = new Label("Measurement");
        label.getStyleClass().add("gps-mapping-field-label");
        label.setLabelFor(measurementField);
        measurementField.getStyleClass().add("gps-mapping-combo");
        measurementField.setMinWidth(0);
        measurementField.setPrefWidth(160);
        measurementField.setMaxWidth(Double.MAX_VALUE);
        measurementField.setVisibleRowCount(10);
        measurementField.setButtonCell(ellipsisCell());
        measurementField.setCellFactory(list -> ellipsisCell());
        measurementField.valueProperty().addListener((o, previous, value) -> {
            if (value == null || value.displayName().isBlank()) {
                measurementField.setTooltip(null);
            } else {
                measurementField.setTooltip(new Tooltip(value.displayName()));
            }
            if (applying || value == previous) {
                return;
            }
            measurementChangeHandler.accept(value.sourceId());
        });
        VBox section = new VBox(6, label, measurementField);
        section.setMinWidth(0);
        section.getStyleClass().add("gps-mapping-section");
        return section;
    }

    private ListCell<MeasurementOption> ellipsisCell() {
        return new ListCell<>() {
            {
                setTextOverrun(OverrunStyle.ELLIPSIS);
                setMaxWidth(Double.MAX_VALUE);
                prefWidthProperty().bind(measurementField.widthProperty().subtract(28));
            }

            @Override
            protected void updateItem(MeasurementOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        };
    }

    private HBox buildRecommendedHelper() {
        Node icon = StellarIcons.autoDecode();
        Label title = new Label("Use Recommended Mapping");
        title.getStyleClass().add("gps-mapping-helper-title");
        Label detail = new Label("Apply the best matching signals for GPS data from this measurement.");
        detail.getStyleClass().add("gps-mapping-helper-text");
        detail.setWrapText(true);
        VBox copy = new VBox(2, title, detail);
        copy.setMinWidth(0);
        HBox.setHgrow(copy, Priority.ALWAYS);

        applyAllButton.getStyleClass().addAll("secondary-button", "gps-mapping-apply-all");
        applyAllButton.setOnAction(e -> recommendedHandler.run());

        HBox helper = new HBox(12, icon, copy, applyAllButton);
        helper.setAlignment(Pos.CENTER_LEFT);
        helper.setMinWidth(0);
        helper.getStyleClass().add("gps-mapping-helper");
        return helper;
    }

    private VBox buildRequiredSection() {
        Label title = new Label("Required GPS signals");
        title.getStyleClass().add("gps-mapping-section-title");
        Label hint = new Label("These signals are required to plot the route.");
        hint.getStyleClass().add("gps-mapping-section-hint");
        RoleRow latitude = new RoleRow(GpsSignalRole.LATITUDE, "Latitude",
                "Geographic latitude. Required to plot the vehicle route.");
        RoleRow longitude = new RoleRow(GpsSignalRole.LONGITUDE, "Longitude",
                "Geographic longitude. Required to plot the vehicle route.");
        rows.put(GpsSignalRole.LATITUDE, latitude);
        rows.put(GpsSignalRole.LONGITUDE, longitude);
        GridPane grid = roleGrid();
        latitude.addTo(grid, 0);
        longitude.addTo(grid, 1);
        VBox section = new VBox(12, title, hint, grid);
        section.setMinWidth(0);
        section.getStyleClass().add("gps-mapping-section");
        return section;
    }

    private VBox buildOptionalSection() {
        Label title = new Label("Optional signals");
        title.getStyleClass().add("gps-mapping-section-title");
        Label hint = new Label("These signals are optional and enhance the route visualization.");
        hint.getStyleClass().add("gps-mapping-section-hint");
        RoleRow altitude = new RoleRow(GpsSignalRole.ALTITUDE, "Altitude",
                "Optional elevation along the route.");
        RoleRow speed = new RoleRow(GpsSignalRole.GPS_SPEED, "Speed",
                "Optional ground speed along the route.");
        RoleRow heading = new RoleRow(GpsSignalRole.COURSE, "Heading",
                "Optional course or heading along the route.");
        rows.put(GpsSignalRole.ALTITUDE, altitude);
        rows.put(GpsSignalRole.GPS_SPEED, speed);
        rows.put(GpsSignalRole.COURSE, heading);
        GridPane grid = roleGrid();
        altitude.addTo(grid, 0);
        speed.addTo(grid, 1);
        heading.addTo(grid, 2);
        VBox section = new VBox(12, title, hint, grid);
        section.setMinWidth(0);
        section.getStyleClass().add("gps-mapping-section");
        return section;
    }

    private static GridPane roleGrid() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("gps-mapping-role-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setMinWidth(0);
        ColumnConstraints labels = new ColumnConstraints(LABEL_COL, LABEL_COL, LABEL_COL);
        labels.setHalignment(HPos.LEFT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setMinWidth(0);
        fields.setFillWidth(true);
        ColumnConstraints badges = new ColumnConstraints(BADGE_COL, BADGE_COL, BADGE_COL);
        ColumnConstraints pills = new ColumnConstraints(PILL_COL, PILL_COL, PILL_COL);
        grid.getColumnConstraints().addAll(labels, fields, badges, pills);
        return grid;
    }

    private static Region divider() {
        Region line = new Region();
        line.getStyleClass().add("gps-mapping-divider");
        line.setMinHeight(1);
        line.setPrefHeight(1);
        line.setMaxHeight(1);
        return line;
    }

    private VBox buildValidationCard() {
        Label title = new Label("Mapping Preview & Validation");
        title.getStyleClass().add("gps-mapping-card-title");

        bannerIconHost.getStyleClass().add("gps-mapping-banner-icon");
        bannerTitle.getStyleClass().add("gps-mapping-banner-title");
        bannerDetail.getStyleClass().add("gps-mapping-banner-detail");
        bannerDetail.setWrapText(true);
        VBox copy = new VBox(2, bannerTitle, bannerDetail);
        HBox.setHgrow(copy, Priority.ALWAYS);
        HBox bannerRow = new HBox(10, bannerIconHost, copy);
        bannerRow.setAlignment(Pos.CENTER_LEFT);
        banner.getChildren().setAll(bannerRow);
        banner.getStyleClass().add("gps-mapping-banner");

        VBox summary = new VBox(8,
                summaryRow("Measurement", summaryMeasurement),
                summaryRow("Latitude signal", summaryLatitude),
                summaryRow("Longitude signal", summaryLongitude),
                summaryRow("Coordinate format", summaryFormat),
                summaryRow("Samples (lat/lon)", summarySamples),
                summaryRow("Status", summaryStatus));
        summary.getStyleClass().add("gps-mapping-summary");

        VBox card = new VBox(12, title, banner, summary);
        card.setMinWidth(0);
        card.getStyleClass().add("gps-mapping-card");
        return card;
    }

    private static HBox summaryRow(String caption, Label value) {
        Label name = new Label(caption);
        name.getStyleClass().add("gps-mapping-summary-key");
        name.setMinWidth(132);
        value.getStyleClass().add("gps-mapping-summary-value");
        value.setWrapText(true);
        value.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(value, Priority.ALWAYS);
        HBox row = new HBox(10, name, value);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private VBox buildRouteCard() {
        Label title = new Label("Route Preview");
        title.getStyleClass().add("gps-mapping-card-title");

        routeChip.getStyleClass().add("gps-mapping-route-chip");
        StackPane.setAlignment(routeChip, Pos.TOP_LEFT);
        StackPane.setMargin(routeChip, new Insets(10, 0, 0, 10));
        preview.setMinWidth(0);
        preview.setMaxWidth(Double.MAX_VALUE);
        mapHost.getChildren().setAll(preview, routeChip);
        mapHost.getStyleClass().add("gps-mapping-map-host");
        mapHost.setMinSize(0, 0);
        mapHost.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(mapHost, Priority.SOMETIMES);

        Node info = StellarIcons.info();
        noteText.getStyleClass().add("gps-mapping-note-text");
        noteText.setWrapText(true);
        HBox note = new HBox(8, info, noteText);
        note.setAlignment(Pos.TOP_LEFT);
        note.getStyleClass().add("gps-mapping-note");
        HBox.setHgrow(noteText, Priority.ALWAYS);

        VBox card = new VBox(10, title, mapHost, note);
        card.setMinWidth(0);
        card.getStyleClass().addAll("gps-mapping-card", "gps-mapping-route-card");
        VBox.setVgrow(card, Priority.SOMETIMES);
        return card;
    }

    private HBox buildFooter() {
        cancelButton.getStyleClass().addAll("secondary-button", "gps-mapping-cancel");
        cancelButton.setOnAction(e -> cancelHandler.run());
        cancelButton.setCancelButton(true);

        advancedButton.getStyleClass().addAll("secondary-button", "gps-mapping-advanced");
        advancedButton.setOnAction(e -> fullConfigHandler.run());

        saveButton.getStyleClass().addAll("primary-button", "gps-mapping-save");
        saveButton.setDefaultButton(true);
        saveButton.setOnAction(e -> {
            userAttemptedSave = true;
            if (!saveBusy) {
                if (draft == null || !draft.requiredRolesConfigured()) {
                    applyDraft();
                    return;
                }
                saveHandler.run();
            }
        });

        saveError.getStyleClass().add("gps-mapping-save-error");
        saveError.setWrapText(true);
        saveError.setVisible(false);
        saveError.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(10, cancelButton, spacer, advancedButton, saveButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox block = new VBox(6, saveError, actions);
        HBox footer = new HBox(block);
        HBox.setHgrow(block, Priority.ALWAYS);
        footer.setMinHeight(60);
        footer.getStyleClass().add("gps-mapping-footer");
        return footer;
    }

    public void setDraft(GpsMappingDraft next) {
        this.draft = next;
        userAttemptedSave = false;
        if (!saveBusy) {
            saveError.setVisible(false);
            saveError.setManaged(false);
        }
        applyDraft();
    }

    public GpsMappingDraft draft() {
        return draft;
    }

    public void setMeasurementLabel(String label) {
        String value = label == null || label.isBlank() ? "Active measurement" : label.trim();
        setMeasurements(List.of(new MeasurementOption(value, value)), value, false);
    }

    public void setMeasurements(List<MeasurementOption> options, String selectedSourceId, boolean requireChoice) {
        applying = true;
        try {
            List<MeasurementOption> items = new ArrayList<>();
            if (requireChoice) {
                items.add(MeasurementOption.prompt());
            }
            if (options != null) {
                items.addAll(options);
            }
            measurementField.getItems().setAll(items);
            MeasurementOption selected = null;
            if (selectedSourceId != null && !selectedSourceId.isBlank()) {
                for (MeasurementOption option : items) {
                    if (selectedSourceId.equals(option.sourceId())) {
                        selected = option;
                        break;
                    }
                }
            }
            if (selected == null && requireChoice) {
                selected = MeasurementOption.prompt();
            } else if (selected == null && !items.isEmpty()) {
                selected = items.getFirst();
            }
            measurementField.getSelectionModel().select(selected);
        } finally {
            applying = false;
        }
    }

    public void setMeasurementChangeHandler(Consumer<String> handler) {
        this.measurementChangeHandler = handler == null ? id -> { } : handler;
    }

    public void setPreviewRoute(MapRoute route) {
        this.previewRoute = route == null ? MapRoute.emptyRoute() : route;
        refreshPreview();
    }

    public void setSaveHandler(Runnable handler) {
        this.saveHandler = handler == null ? () -> { } : handler;
    }

    public void setCancelHandler(Runnable handler) {
        this.cancelHandler = handler == null ? () -> { } : handler;
    }

    public void setRecommendedHandler(Runnable handler) {
        this.recommendedHandler = handler == null ? () -> { } : handler;
    }

    public void setFullConfigHandler(Runnable handler) {
        this.fullConfigHandler = handler == null ? () -> { } : handler;
    }

    public void setChangeHandler(Consumer<GpsMappingDraft> handler) {
        this.changeHandler = handler == null ? d -> { } : handler;
    }

    public void hidePickers() {
        rows.values().forEach(row -> row.picker.hideOptions());
    }

    public void setBusy(boolean busy) {
        this.saveBusy = busy;
        applyAllButton.setDisable(busy);
        advancedButton.setDisable(busy);
        cancelButton.setDisable(busy);
        closeButton.setDisable(busy);
        measurementField.setDisable(busy);
        saveButton.setText(busy ? "Saving…" : "Save Mapping");
        rows.values().forEach(row -> row.picker.setDisable(busy));
        refreshSaveEnabled();
    }

    public void setStatus(String text) {
        String message = text == null ? "" : text;
        saveError.setText(message);
        saveError.setVisible(!message.isBlank());
        saveError.setManaged(!message.isBlank());
    }

    public void applyDraft() {
        if (draft == null) {
            return;
        }
        applying = true;
        try {
            for (RoleRow row : rows.values()) {
                row.syncFrom(draft);
            }
            refreshValidation();
            refreshSaveEnabled();
        } finally {
            applying = false;
        }
    }

    private void refreshSaveEnabled() {
        boolean blocked = saveBusy || draft == null || !draft.requiredRolesConfigured();
        saveButton.setDisable(blocked);
    }

    private void onPicked(GpsSignalRole role, SignalCatalogEntry entry) {
        if (applying || draft == null || entry == null) {
            return;
        }
        draft.assign(role, entry.key());
        applyDraft();
        changeHandler.accept(draft);
    }

    private void onCleared(GpsSignalRole role) {
        if (applying || draft == null) {
            return;
        }
        draft.clear(role);
        applyDraft();
        changeHandler.accept(draft);
    }

    private void onOverlayKey(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            hidePickers();
            cancelHandler.run();
            event.consume();
        }
    }

    private void refreshValidation() {
        MappingState state = evaluate();
        banner.getStyleClass().removeAll(
                "gps-mapping-banner-success", "gps-mapping-banner-warning", "gps-mapping-banner-error");
        banner.getStyleClass().add(switch (state.verdict) {
            case VALID -> "gps-mapping-banner-success";
            case WARNING -> "gps-mapping-banner-warning";
            case BLOCKED -> "gps-mapping-banner-error";
        });
        bannerIconHost.setGraphic(switch (state.verdict) {
            case VALID -> StellarIcons.tinted(StellarIcons.checkSecondary(), Color.web("#047857"));
            case WARNING -> StellarIcons.warning();
            case BLOCKED -> StellarIcons.warning();
        });
        bannerTitle.setText(state.title);
        bannerDetail.setText(state.detail);
        summaryMeasurement.setText(ellipsis(measurementValue(), 46));
        summaryLatitude.setText(signalName(GpsSignalRole.LATITUDE));
        summaryLongitude.setText(signalName(GpsSignalRole.LONGITUDE));
        summaryFormat.setText(state.format);
        summarySamples.setText(state.samplesLabel);
        summaryStatus.setText(state.status);
        routeChip.setText(state.chip);
        routeChip.setVisible(state.verdict != Verdict.BLOCKED);
        noteText.setText(state.note);
        refreshPreview();
    }

    private void refreshPreview() {
        MappingState state = evaluate();
        preview.setState(state, previewRoute, draft);
    }

    private MappingState evaluate() {
        if (draft == null) {
            return MappingState.blocked("Route cannot be rendered",
                    "Open a processed measurement before mapping GPS signals.",
                    "—", "—", "No processed signals", "Route (0 points)",
                    "Select a measurement and map latitude and longitude.");
        }
        boolean latMissing = !mapped(GpsSignalRole.LATITUDE);
        boolean lonMissing = !mapped(GpsSignalRole.LONGITUDE);
        if (latMissing || lonMissing) {
            List<String> missing = new ArrayList<>();
            if (latMissing) {
                missing.add("latitude");
            }
            if (lonMissing) {
                missing.add("longitude");
            }
            return MappingState.blocked("Route cannot be rendered",
                    "Missing " + String.join(" and ", missing) + ". Both are required to plot the route.",
                    "Not detected", "—", draft.status().label(), "Route (0 points)",
                    "Map the required signals, then save to enable route visualization.");
        }
        GpsSignalValidator.Result pair = draft.coordinateAdvisory();
        String swap = swappedAdvisory();
        long samples = sampleCount();
        String format = coordinateFormat(pair);
        String samplesLabel = samples > 0 ? formatCount(samples) : "—";
        String status = draft.status().label();
        if (pair.message().equals(GpsSignalValidator.ZERO_ZERO_ADVISORY)) {
            return MappingState.blocked("Route cannot be rendered",
                    pair.message(),
                    declaresDegrees(draft.entry(draft.assigned(GpsSignalRole.LATITUDE)))
                            || declaresDegrees(draft.entry(draft.assigned(GpsSignalRole.LONGITUDE)))
                            ? "Degrees detected" : format,
                    formatCount(samples), status, "Route (0 points)",
                    "Every decoded GPS fix is (0,0). The measurement has no usable location track.");
        }
        if (samples > 0 && samples < MIN_RENDERABLE_SAMPLES) {
            return MappingState.blocked("Route cannot be rendered",
                    "Insufficient samples detected (" + samplesLabel + "). At least two GPS points are required.",
                    format, samplesLabel, status, "Route (0 points)",
                    "Check that the selected signals contain enough valid GPS samples.");
        }
        if (!swap.isEmpty()) {
            return MappingState.warning("Route may be incorrect",
                    swap, format, samplesLabel, status, "Route (" + formatCount(samples) + " points)",
                    "Latitude and longitude look swapped. Recheck the mapping or open Advanced Mapping.");
        }
        if (pair.severity() == GpsSignalValidator.Severity.SUSPICIOUS) {
            return MappingState.warning("Route may be incorrect",
                    pair.message(), format, samplesLabel, status,
                    "Route (" + formatCount(samples) + " points)",
                    "If the route looks incorrect, check signal units or try Advanced Mapping.");
        }
        GpsSignalValidator.Result lat = draft.advisory(GpsSignalRole.LATITUDE);
        GpsSignalValidator.Result lon = draft.advisory(GpsSignalRole.LONGITUDE);
        if (lat.severity() != GpsSignalValidator.Severity.OK || lon.severity() != GpsSignalValidator.Severity.OK) {
            String message = !lat.message().isBlank() ? lat.message() : lon.message();
            return MappingState.warning("Route may be incorrect",
                    message.isBlank() ? "Selected GPS signals look unusual for this measurement." : message,
                    format, samplesLabel, status, "Route (" + formatCount(samples) + " points)",
                    "If the route looks incorrect, check signal units or try Advanced Mapping.");
        }
        if (draft.manualNameUnresolved(GpsSignalRole.LATITUDE)
                || draft.manualNameUnresolved(GpsSignalRole.LONGITUDE)) {
            return MappingState.blocked("Route cannot be rendered",
                    "Mapped signal names are not present in this measurement.",
                    format, samplesLabel, status, "Route (0 points)",
                    "Choose signals that exist in the selected measurement.");
        }
        return MappingState.valid(
                "Route can be rendered",
                "Valid GPS signals detected, " + formatCount(Math.max(samples, 0)) + " samples found.",
                format, samplesLabel, status,
                "Route (" + formatCount(samples) + " points)",
                "Coordinates appear to be in decimal degrees (WGS84).\n"
                        + "If the route looks incorrect, check signal units or try Advanced Mapping.");
    }

    private boolean mapped(GpsSignalRole role) {
        return draft != null && (draft.assigned(role) != null || !draft.manualName(role).isEmpty());
    }

    private String signalName(GpsSignalRole role) {
        if (draft == null) {
            return "—";
        }
        if (draft.assigned(role) != null) {
            return draft.assigned(role).signalName();
        }
        String manual = draft.manualName(role);
        return manual.isBlank() ? "—" : manual;
    }

    private String measurementValue() {
        MeasurementOption selected = measurementField.getValue();
        if (selected != null && !selected.displayName().isBlank() && !selected.sourceId().isBlank()) {
            return selected.displayName();
        }
        return draft == null || draft.sourceId().isBlank() ? "Active measurement" : draft.sourceId();
    }

    private long sampleCount() {
        if (draft == null) {
            return 0;
        }
        SignalCatalogEntry lat = draft.entry(draft.assigned(GpsSignalRole.LATITUDE));
        SignalCatalogEntry lon = draft.entry(draft.assigned(GpsSignalRole.LONGITUDE));
        if (lat == null || lon == null) {
            return previewRoute == null || previewRoute.isEmpty() ? 0 : previewRoute.model().size();
        }
        long catalog = Math.min(lat.sampleCount(), lon.sampleCount());
        if (!previewRoute.isEmpty()) {
            return Math.max(catalog, previewRoute.model().size());
        }
        return catalog;
    }

    private String coordinateFormat(GpsSignalValidator.Result pair) {
        if (draft == null) {
            return "Not detected";
        }
        SignalCatalogEntry lat = draft.entry(draft.assigned(GpsSignalRole.LATITUDE));
        SignalCatalogEntry lon = draft.entry(draft.assigned(GpsSignalRole.LONGITUDE));
        if (lat == null || lon == null) {
            return "Not detected";
        }
        if (pair.severity() == GpsSignalValidator.Severity.SUSPICIOUS
                && pair.message().toLowerCase(Locale.ROOT).contains("radian")) {
            return "Radians suspected";
        }
        if (declaresDegrees(lat) || declaresDegrees(lon)) {
            return "Degrees detected";
        }
        if (looksLikeDegrees(lat, lon)) {
            return "Degrees detected";
        }
        if (pair.severity() != GpsSignalValidator.Severity.OK) {
            return "Unsupported coordinate format";
        }
        return "Degrees detected";
    }

    private String swappedAdvisory() {
        if (draft == null) {
            return "";
        }
        SignalCatalogEntry lat = draft.entry(draft.assigned(GpsSignalRole.LATITUDE));
        SignalCatalogEntry lon = draft.entry(draft.assigned(GpsSignalRole.LONGITUDE));
        if (lat == null || lon == null || lat.minPhysical() == null || lat.maxPhysical() == null
                || lon.minPhysical() == null || lon.maxPhysical() == null) {
            return "";
        }
        boolean latOutside = lat.minPhysical() < -90 || lat.maxPhysical() > 90;
        boolean lonInsideLat = lon.minPhysical() >= -90 && lon.maxPhysical() <= 90;
        boolean latLooksLikeLongitude = Math.abs(midpoint(lat)) > 40 && within(lat, -180, 180);
        boolean lonLooksLikeLatitude = Math.abs(midpoint(lon)) <= 40 && within(lon, -90, 90);
        if (latLooksLikeLongitude && lonLooksLikeLatitude) {
            return "Latitude and longitude appear to be swapped. The latitude range is "
                    + formatRange(lat) + " and the longitude range is " + formatRange(lon) + ".";
        }
        if (latOutside && lonInsideLat) {
            return "Latitude values fall outside ±90°. The selected signals may be swapped or malformed.";
        }
        return "";
    }

    private static boolean declaresDegrees(SignalCatalogEntry entry) {
        if (entry == null) {
            return false;
        }
        String unit = entry.unit() == null ? "" : entry.unit().trim().toLowerCase(Locale.ROOT);
        if (unit.contains("decimal degree") || unit.equals("degree") || unit.equals("degrees") || unit.equals("°")) {
            return true;
        }
        return unit.startsWith("deg") && !unit.startsWith("degc") && !unit.startsWith("degf");
    }

    private static boolean looksLikeDegrees(SignalCatalogEntry lat, SignalCatalogEntry lon) {
        return within(lat, -90, 90) && within(lon, -180, 180);
    }

    private static boolean within(SignalCatalogEntry entry, double lo, double hi) {
        if (entry.minPhysical() == null || entry.maxPhysical() == null) {
            return false;
        }
        return entry.minPhysical() >= lo && entry.maxPhysical() <= hi;
    }

    private static double midpoint(SignalCatalogEntry entry) {
        return (entry.minPhysical() + entry.maxPhysical()) / 2.0;
    }

    private static String formatRange(SignalCatalogEntry entry) {
        return String.format(Locale.ROOT, "%.2f – %.2f", entry.minPhysical(), entry.maxPhysical());
    }

    private static String formatCount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String ellipsis(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "—" : text;
        }
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

    public boolean saveDisabledForTest() {
        return saveButton.isDisabled();
    }

    public void pickRoleForTest(GpsSignalRole role, String signalName) {
        RoleRow row = rows.get(role);
        if (row == null || draft == null) {
            return;
        }
        draft.catalog().stream()
                .filter(e -> e.key().signalName().equals(signalName))
                .findFirst()
                .ifPresent(e -> onPicked(role, e));
    }

    public void typeRoleForTest(GpsSignalRole role, String signalName) {
        if (draft == null) {
            return;
        }
        draft.assignManual(role, signalName);
        applyDraft();
        changeHandler.accept(draft);
    }

    public boolean manualVisibleForTest(GpsSignalRole role) {
        return false;
    }

    public boolean requiredRolesRenderedForTest() {
        return rows.containsKey(GpsSignalRole.LATITUDE) && rows.containsKey(GpsSignalRole.LONGITUDE);
    }

    public void saveForTest() {
        userAttemptedSave = true;
        if (!saveBusy) {
            if (draft == null || !draft.requiredRolesConfigured()) {
                applyDraft();
                return;
            }
            saveHandler.run();
        }
    }

    public void cancelForTest() {
        cancelHandler.run();
    }

    public void useRecommendedForTest() {
        recommendedHandler.run();
    }

    public String bannerTitleForTest() {
        return bannerTitle.getText();
    }

    public String bannerDetailForTest() {
        return bannerDetail.getText();
    }

    public String fieldPillForTest(GpsSignalRole role) {
        RoleRow row = rows.get(role);
        return row == null ? "" : row.pill.getText();
    }

    public String coordinateFormatForTest() {
        return summaryFormat.getText();
    }

    public boolean saveEnabledForTest() {
        return !saveButton.isDisabled();
    }

    public void escapeForTest() {
        cancelHandler.run();
    }

    public BorderPane dialogForTest() {
        return dialog;
    }

    public boolean footerOutsideScrollForTest() {
        return dialog.getBottom() != null && dialog.getCenter() instanceof ScrollPane;
    }

    public boolean bodyVerticalScrollOnlyForTest() {
        return dialog.getCenter() instanceof ScrollPane scroll
                && scroll.isFitToWidth()
                && scroll.getHbarPolicy() == ScrollPane.ScrollBarPolicy.NEVER
                && !scroll.isPannable();
    }

    public String assignedSignalForTest(GpsSignalRole role) {
        if (draft == null || draft.assigned(role) == null) {
            return "";
        }
        return draft.assigned(role).signalName();
    }

    public List<String> measurementSourceIdsForTest() {
        return measurementField.getItems().stream()
                .filter(option -> option != null && !option.sourceId().isBlank())
                .map(MeasurementOption::sourceId)
                .toList();
    }

    public String selectedMeasurementSourceIdForTest() {
        MeasurementOption value = measurementField.getValue();
        return value == null ? "" : value.sourceId();
    }

    public String draftSourceIdForTest() {
        return draft == null ? "" : draft.sourceId();
    }

    public List<String> catalogSignalNamesForTest() {
        if (draft == null) {
            return List.of();
        }
        return draft.catalog().stream().map(SignalCatalogEntry::signalName).toList();
    }

    public List<String> catalogSourceIdsForTest() {
        if (draft == null) {
            return List.of();
        }
        return draft.catalog().stream()
                .map(entry -> entry.key() == null || entry.key().sourceFile() == null
                        ? ""
                        : entry.key().sourceFile())
                .toList();
    }

    public void selectMeasurementForTest(String sourceId) {
        for (MeasurementOption option : measurementField.getItems()) {
            if (option != null && option.sourceId().equals(sourceId)) {
                measurementField.getSelectionModel().select(option);
                return;
            }
        }
    }

    public boolean stackedBodyForTest() {
        return stackedBody;
    }

    private enum StepState {
        COMPLETE,
        ACTIVE,
        UPCOMING
    }

    enum Verdict {
        VALID,
        WARNING,
        BLOCKED
    }

    private record MappingState(
            Verdict verdict,
            String title,
            String detail,
            String format,
            String samplesLabel,
            String status,
            String chip,
            String note
    ) {
        static MappingState valid(String title, String detail, String format, String samples,
                String status, String chip, String note) {
            return new MappingState(Verdict.VALID, title, detail, format, samples, status, chip, note);
        }

        static MappingState warning(String title, String detail, String format, String samples,
                String status, String chip, String note) {
            return new MappingState(Verdict.WARNING, title, detail, format, samples, status, chip, note);
        }

        static MappingState blocked(String title, String detail, String format, String samples,
                String status, String chip, String note) {
            return new MappingState(Verdict.BLOCKED, title, detail, format, samples, status, chip, note);
        }
    }

    final class RoleRow {
        final GpsSignalRole role;
        final SearchableSignalPicker picker = new SearchableSignalPicker();
        final Label recommended = new Label("Recommended");
        final Label pill = new Label();
        private final HBox caption = new HBox(4);
        private final VBox field = new VBox(4);
        private final Label error = new Label();

        RoleRow(GpsSignalRole role, String labelText, String help) {
            this.role = role;
            Label name = new Label(labelText);
            name.getStyleClass().add("gps-mapping-role-name");
            caption.setAlignment(Pos.CENTER_LEFT);
            caption.setMinWidth(0);
            caption.getChildren().add(name);
            if (role.required()) {
                Label star = new Label("*");
                star.getStyleClass().add("gps-mapping-required");
                caption.getChildren().add(star);
            }
            Button info = new Button();
            info.getStyleClass().add("gps-mapping-info");
            info.setGraphic(StellarIcons.info());
            info.setTooltip(new Tooltip(help));
            info.setAccessibleText(labelText + " help");
            caption.getChildren().add(info);
            name.setLabelFor(picker);

            picker.setMinWidth(0);
            picker.setMaxWidth(Double.MAX_VALUE);
            picker.setClearable(!role.required());
            if (!role.required()) {
                picker.setEmptyPrompt(OPTIONAL_PROMPT);
            }
            picker.setOnPicked(entry -> onPicked(role, entry));
            picker.setOnCleared(() -> onCleared(role));

            recommended.getStyleClass().add("gps-mapping-recommended");
            recommended.setMinWidth(BADGE_COL);
            recommended.setPrefWidth(BADGE_COL);
            recommended.setMaxWidth(BADGE_COL);
            recommended.setAlignment(Pos.CENTER);
            recommended.setVisible(false);
            recommended.setManaged(true);
            pill.getStyleClass().add("gps-mapping-pill");
            pill.setMinWidth(PILL_COL);
            pill.setAlignment(Pos.CENTER);
            pill.setVisible(false);
            pill.setManaged(true);
            error.getStyleClass().add("gps-mapping-inline-error");
            error.setVisible(false);
            error.setManaged(false);

            field.setMinWidth(0);
            field.getChildren().addAll(picker, error);
            VBox.setVgrow(picker, Priority.NEVER);
        }

        void addTo(GridPane grid, int row) {
            grid.add(caption, 0, row);
            grid.add(field, 1, row);
            grid.add(recommended, 2, row);
            grid.add(pill, 3, row);
            GridPane.setValignment(caption, VPos.CENTER);
            GridPane.setValignment(field, VPos.CENTER);
            GridPane.setValignment(recommended, VPos.CENTER);
            GridPane.setValignment(pill, VPos.CENTER);
            GridPane.setHgrow(field, Priority.ALWAYS);
            GridPane.setFillWidth(field, true);
        }

        void syncFrom(GpsMappingDraft current) {
            picker.setCatalog(current.catalog(), current.suggestions(role));
            picker.setSelected(current.assigned(role));
            boolean hasValue = current.assigned(role) != null || !current.manualName(role).isEmpty();
            GpsSignalValidator.Result advisory = current.advisory(role);
            boolean recommendedMatch = isRecommended(current);
            recommended.setVisible(recommendedMatch);

            pill.getStyleClass().removeAll(
                    "gps-mapping-pill-valid", "gps-mapping-pill-warning", "gps-mapping-pill-error");
            if (hasValue && advisory.severity() == GpsSignalValidator.Severity.OK
                    && !current.manualNameUnresolved(role)) {
                showPill("Valid", "gps-mapping-pill-valid");
                hideError();
            } else if (hasValue) {
                showPill("Warning", "gps-mapping-pill-warning");
                hideError();
            } else if (role.required() && userAttemptedSave) {
                showPill("Required", "gps-mapping-pill-error");
                error.setText(role.displayName() + " is required.");
                error.setVisible(true);
                error.setManaged(true);
            } else {
                pill.setVisible(false);
                hideError();
            }
        }

        private boolean isRecommended(GpsMappingDraft current) {
            List<GpsSignalCandidate> suggestions = current.suggestions(role);
            if (suggestions.isEmpty()) {
                return false;
            }
            GpsSignalCandidate top = suggestions.getFirst();
            GpsDetectionConfidence confidence = GpsSemanticResolver.confidenceForScore(top.score());
            if (!confidence.isReliable()) {
                return false;
            }
            return current.assigned(role) != null && current.assigned(role).equals(top.key());
        }

        private void showPill(String text, String style) {
            pill.setText(text);
            pill.getStyleClass().add(style);
            pill.setVisible(true);
        }

        private void hideError() {
            error.setVisible(false);
            error.setManaged(false);
        }
    }

    /**
     * Static schematic preview. Uses the already-prepared route when one exists; otherwise a
     * bounded path from catalog latitude/longitude ranges. Never reads measurement files.
     */
    static final class RoutePreviewPane extends StackPane {
        private final Canvas canvas = new Canvas();
        private final Button zoomIn = iconButton(StellarIcons.plusMuted(), "Zoom in");
        private final Button zoomOut = iconButton(StellarIcons.minusMuted(), "Zoom out");
        private final Label scale = new Label("500 m");
        private MappingState state;
        private MapRoute route = MapRoute.emptyRoute();
        private GpsMappingDraft draft;
        private double zoom = 1.0;

        RoutePreviewPane() {
            getStyleClass().add("gps-mapping-preview");
            setMinWidth(0);
            setMaxWidth(Double.MAX_VALUE);
            widthProperty().addListener((o, a, b) -> draw());
            heightProperty().addListener((o, a, b) -> draw());
            sceneProperty().addListener((obs, was, now) -> {
                if (now != null) {
                    draw();
                }
            });
            setMinHeight(0);
            setPrefHeight(190);

            zoomIn.setOnAction(e -> {
                zoom = Math.min(2.4, zoom + 0.25);
                draw();
            });
            zoomOut.setOnAction(e -> {
                zoom = Math.max(0.75, zoom - 0.25);
                draw();
            });
            VBox zooms = new VBox(4, zoomIn, zoomOut);
            zooms.getStyleClass().add("gps-mapping-zoom");
            StackPane.setAlignment(zooms, Pos.CENTER_RIGHT);
            StackPane.setMargin(zooms, new Insets(12, 10, 0, 0));

            scale.getStyleClass().add("gps-mapping-scale");
            StackPane.setAlignment(scale, Pos.BOTTOM_LEFT);
            StackPane.setMargin(scale, new Insets(0, 0, 10, 10));

            getChildren().addAll(canvas, zooms, scale);
        }

        void setResponsiveHeight(double height) {
            double clamped = Math.max(140, height);
            setMinHeight(Math.min(160, clamped));
            setPrefHeight(clamped);
            setMaxHeight(clamped);
        }

        void setState(MappingState next, MapRoute previewRoute, GpsMappingDraft mapping) {
            this.state = next;
            this.route = previewRoute == null ? MapRoute.emptyRoute() : previewRoute;
            this.draft = mapping;
            draw();
        }

        private void draw() {
            CanvasRenderSupport.place(canvas, 0, 0, getWidth(), getHeight());
            if (!CanvasRenderSupport.canPaint(canvas)) {
                return;
            }
            GraphicsContext g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth();
            double h = canvas.getHeight();
            g.clearRect(0, 0, w, h);
            paintBase(g, w, h);
            double[][] path = previewPath();
            if (state == null || state.verdict == Verdict.BLOCKED || path.length < 2) {
                scale.setText("");
                return;
            }
            paintRoute(g, path, w, h);
            scale.setText(scaleLabel(path));
        }

        private void paintBase(GraphicsContext g, double w, double h) {
            g.setFill(Color.web("#F4F7FA"));
            g.fillRect(0, 0, w, h);
            g.setStroke(Color.web("#E2E8F0"));
            g.setLineWidth(1);
            for (int i = 1; i < 6; i++) {
                double y = h * i / 6.0;
                g.strokeLine(0, y, w, y);
            }
            for (int i = 1; i < 7; i++) {
                double x = w * i / 7.0;
                g.strokeLine(x, 0, x, h);
            }
            g.setStroke(Color.web("#D5DEE8"));
            g.strokeRoundRect(0.5, 0.5, w - 1, h - 1, 8, 8);
        }

        private void paintRoute(GraphicsContext g, double[][] path, double w, double h) {
            double minLat = path[0][0];
            double maxLat = path[0][0];
            double minLon = path[0][1];
            double maxLon = path[0][1];
            for (double[] point : path) {
                minLat = Math.min(minLat, point[0]);
                maxLat = Math.max(maxLat, point[0]);
                minLon = Math.min(minLon, point[1]);
                maxLon = Math.max(maxLon, point[1]);
            }
            double padLat = Math.max(0.0008, (maxLat - minLat) * 0.18);
            double padLon = Math.max(0.0008, (maxLon - minLon) * 0.18);
            minLat -= padLat;
            maxLat += padLat;
            minLon -= padLon;
            maxLon += padLon;
            double latSpan = Math.max(1e-6, maxLat - minLat);
            double lonSpan = Math.max(1e-6, maxLon - minLon);

            g.setStroke(Color.web("#0F6CBD"));
            g.setLineWidth(2.6);
            g.setLineCap(StrokeLineCap.ROUND);
            g.setLineJoin(StrokeLineJoin.ROUND);
            g.beginPath();
            for (int i = 0; i < path.length; i++) {
                double x = projectX(path[i][1], minLon, lonSpan, w);
                double y = projectY(path[i][0], minLat, latSpan, h);
                if (i == 0) {
                    g.moveTo(x, y);
                } else {
                    g.lineTo(x, y);
                }
            }
            g.stroke();

            double startX = projectX(path[0][1], minLon, lonSpan, w);
            double startY = projectY(path[0][0], minLat, latSpan, h);
            double endX = projectX(path[path.length - 1][1], minLon, lonSpan, w);
            double endY = projectY(path[path.length - 1][0], minLat, latSpan, h);
            g.setFill(Color.web("#059669"));
            g.fillOval(startX - 5, startY - 5, 10, 10);
            g.setFill(Color.web("#0F172A"));
            g.fillOval(endX - 5, endY - 5, 10, 10);
        }

        private double projectX(double lon, double minLon, double lonSpan, double w) {
            double inset = 18;
            double usable = Math.max(1, (w - inset * 2) * zoom);
            double origin = (w - usable) / 2.0;
            return origin + ((lon - minLon) / lonSpan) * usable;
        }

        private double projectY(double lat, double minLat, double latSpan, double h) {
            double inset = 18;
            double usable = Math.max(1, (h - inset * 2) * zoom);
            double origin = (h - usable) / 2.0;
            return origin + (1.0 - ((lat - minLat) / latSpan)) * usable;
        }

        private double[][] previewPath() {
            if (route != null && !route.isEmpty()) {
                return downsample(route.model());
            }
            if (draft == null) {
                return new double[0][];
            }
            SignalCatalogEntry lat = draft.entry(draft.assigned(GpsSignalRole.LATITUDE));
            SignalCatalogEntry lon = draft.entry(draft.assigned(GpsSignalRole.LONGITUDE));
            if (lat == null || lon == null || lat.minPhysical() == null || lat.maxPhysical() == null
                    || lon.minPhysical() == null || lon.maxPhysical() == null) {
                return schematicBengaluru();
            }
            return schematicInBounds(
                    lat.minPhysical(), lat.maxPhysical(), lon.minPhysical(), lon.maxPhysical());
        }

        private static double[][] downsample(MapRouteModel model) {
            int size = model.size();
            int target = Math.min(120, size);
            if (target < 2) {
                return new double[0][];
            }
            double[][] out = new double[target][2];
            for (int i = 0; i < target; i++) {
                int index = (int) Math.round(i * (size - 1.0) / (target - 1.0));
                out[i][0] = model.latitude(index);
                out[i][1] = model.longitude(index);
            }
            return out;
        }

        private static double[][] schematicBengaluru() {
            return schematicInBounds(12.91, 13.06, 77.55, 77.72);
        }

        private static double[][] schematicInBounds(double minLat, double maxLat, double minLon, double maxLon) {
            double[][] knots = {
                    {0.18, 0.22}, {0.28, 0.34}, {0.42, 0.30}, {0.55, 0.48},
                    {0.63, 0.62}, {0.74, 0.58}, {0.86, 0.76}
            };
            double[][] path = new double[knots.length][2];
            for (int i = 0; i < knots.length; i++) {
                path[i][0] = minLat + (maxLat - minLat) * knots[i][0];
                path[i][1] = minLon + (maxLon - minLon) * knots[i][1];
            }
            return path;
        }

        private static String scaleLabel(double[][] path) {
            if (path.length < 2) {
                return "";
            }
            double minLat = path[0][0];
            double maxLat = path[0][0];
            for (double[] point : path) {
                minLat = Math.min(minLat, point[0]);
                maxLat = Math.max(maxLat, point[0]);
            }
            double meters = Math.max(50, (maxLat - minLat) * 111_000 / 4.0);
            if (meters >= 1000) {
                return String.format(Locale.ROOT, "%.1f km", meters / 1000.0);
            }
            return String.format(Locale.ROOT, "%.0f m", meters);
        }

        private static Button iconButton(Node graphic, String accessible) {
            Button button = new Button();
            button.getStyleClass().add("gps-mapping-zoom-button");
            button.setGraphic(graphic);
            button.setAccessibleText(accessible);
            button.setTooltip(new Tooltip(accessible));
            return button;
        }
    }

    /** Combo identity is {@code sourceId}; {@code displayName} is presentation only. */
    public static final class MeasurementOption {
        private final String sourceId;
        private final String displayName;

        public MeasurementOption(String sourceId, String displayName) {
            this.sourceId = sourceId == null ? "" : sourceId;
            this.displayName = displayName == null || displayName.isBlank() ? this.sourceId : displayName;
        }

        public static MeasurementOption prompt() {
            return new MeasurementOption("", MEASUREMENT_PROMPT);
        }

        public String sourceId() {
            return sourceId;
        }

        public String displayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MeasurementOption option)) {
                return false;
            }
            return sourceId.equals(option.sourceId) && displayName.equals(option.displayName);
        }

        @Override
        public int hashCode() {
            return sourceId.hashCode() * 31 + displayName.hashCode();
        }
    }

}

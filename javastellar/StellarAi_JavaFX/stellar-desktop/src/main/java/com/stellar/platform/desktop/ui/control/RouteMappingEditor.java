package com.stellar.platform.desktop.ui.control;

import com.stellar.platform.processing.map.GpsMappingDraft;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSignalValidator;
import com.stellar.platform.processing.map.RouteMappingMode;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Compact editor for the five GPS roles used by Map Route.
 *
 * <p>Resolution, validation and persistence live in {@link GpsMappingDraft}. This control only
 * binds that draft to pickers and typed-name fields so Map Route and Configuration share one
 * mapping model rather than inventing a second one.
 */
public final class RouteMappingEditor extends VBox {

    private final Map<GpsSignalRole, RoleRow> rows = new EnumMap<>(GpsSignalRole.class);
    private final Label message = new Label();
    private final Button saveButton = new Button("Save Mapping");
    private final Button cancelButton = new Button("Cancel");
    private final Button recommendedButton = new Button("Use Recommended Mapping");
    private final Button fullConfigButton = new Button("Open Full Configuration");

    private GpsMappingDraft draft;
    private boolean applying;
    private boolean saveBusy;
    private Runnable saveHandler = () -> { };
    private Runnable cancelHandler = () -> { };
    private Runnable recommendedHandler = () -> { };
    private Runnable fullConfigHandler = () -> { };
    private Consumer<GpsMappingDraft> changeHandler = d -> { };

    public RouteMappingEditor() {
        getStyleClass().add("route-mapping-editor");
        setFillWidth(true);

        Label title = new Label("Route signals");
        title.getStyleClass().add("route-mapping-editor-title");
        Label hint = new Label("Latitude and Longitude are required. Optional roles can stay empty.");
        hint.getStyleClass().add("route-mapping-editor-hint");
        hint.setWrapText(true);

        VBox roles = new VBox(8);
        for (GpsSignalRole role : GpsMappingDraft.EDITABLE_ROLES) {
            RoleRow row = new RoleRow(role);
            rows.put(role, row);
            roles.getChildren().add(row.root);
        }
        ScrollPane roleScroll = new ScrollPane(roles);
        roleScroll.setFitToWidth(true);
        roleScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        roleScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        roleScroll.setMinHeight(80);
        roleScroll.getStyleClass().add("route-mapping-editor-scroll");
        VBox.setVgrow(roleScroll, Priority.ALWAYS);

        recommendedButton.getStyleClass().addAll("secondary-button", "compact-button");
        recommendedButton.setOnAction(e -> recommendedHandler.run());
        cancelButton.getStyleClass().addAll("secondary-button", "compact-button");
        cancelButton.setOnAction(e -> cancelHandler.run());
        saveButton.getStyleClass().addAll("primary-button", "compact-button");
        saveButton.setOnAction(e -> {
            if (!saveBusy) {
                saveHandler.run();
            }
        });
        fullConfigButton.getStyleClass().addAll("secondary-button", "compact-button");
        fullConfigButton.setOnAction(e -> fullConfigHandler.run());
        fullConfigButton.setMaxWidth(Double.MAX_VALUE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, recommendedButton, spacer, cancelButton, saveButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("route-mapping-editor-actions");

        message.getStyleClass().add("route-mapping-editor-message");
        message.setWrapText(true);

        setMaxHeight(Double.MAX_VALUE);
        setMinHeight(0);
        getChildren().addAll(title, hint, roleScroll, message, actions, fullConfigButton);
    }

    public void setDraft(GpsMappingDraft next) {
        this.draft = next;
        applyDraft();
    }

    public GpsMappingDraft draft() {
        return draft;
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

    public void setFullConfigVisible(boolean visible) {
        fullConfigButton.setVisible(visible);
        fullConfigButton.setManaged(visible);
    }

    public void hidePickers() {
        rows.values().forEach(row -> row.picker.hideOptions());
    }

    public void setBusy(boolean busy) {
        this.saveBusy = busy;
        recommendedButton.setDisable(busy);
        fullConfigButton.setDisable(busy);
        cancelButton.setDisable(busy);
        saveButton.setText(busy ? "Saving…" : "Save Mapping");
        refreshSaveEnabled();
    }

    public void setStatus(String text) {
        message.setText(text == null ? "" : text);
    }

    private void refreshSaveEnabled() {
        boolean blocked = saveBusy || draft == null || !draft.requiredRolesConfigured();
        saveButton.setDisable(blocked);
    }

    public void applyDraft() {
        if (draft == null) {
            return;
        }
        applying = true;
        try {
            for (Map.Entry<GpsSignalRole, RoleRow> entry : rows.entrySet()) {
                entry.getValue().syncFrom(draft);
            }
            refreshSaveEnabled();
            if (!saveBusy) {
                message.setText(draft.statusDetail());
            }
        } finally {
            applying = false;
        }
    }

    private void onPicked(GpsSignalRole role, com.stellar.platform.domain.catalog.SignalCatalogEntry entry) {
        if (applying || draft == null || entry == null) {
            return;
        }
        GpsSignalValidator.Result result = draft.assign(role, entry.key());
        applyDraft();
        if (result.severity() != GpsSignalValidator.Severity.OK) {
            message.setText(result.message());
        }
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

    private void onManual(GpsSignalRole role, String text) {
        if (applying || draft == null) {
            return;
        }
        GpsSignalValidator.Result result = draft.assignManual(role, text);
        applyDraft();
        if (result.severity() != GpsSignalValidator.Severity.OK) {
            message.setText(result.message());
        }
        changeHandler.accept(draft);
    }

    public boolean saveDisabledForTest() {
        return saveButton.isDisabled();
    }

    public void pickRoleForTest(GpsSignalRole role, String signalName) {
        RoleRow row = rows.get(role);
        if (row != null) {
            row.pickForTest(signalName);
        }
    }

    public void typeRoleForTest(GpsSignalRole role, String signalName) {
        RoleRow row = rows.get(role);
        if (row != null) {
            row.typeForTest(signalName);
        }
    }

    public boolean manualVisibleForTest(GpsSignalRole role) {
        RoleRow row = rows.get(role);
        return row != null && row.manualVisibleForTest();
    }

    public boolean requiredRolesRenderedForTest() {
        return rows.containsKey(GpsSignalRole.LATITUDE) && rows.containsKey(GpsSignalRole.LONGITUDE);
    }

    public void saveForTest() {
        if (!saveBusy) {
            saveHandler.run();
        }
    }

    public void cancelForTest() {
        cancelHandler.run();
    }

    public void useRecommendedForTest() {
        recommendedHandler.run();
    }

    final class RoleRow {
        final GpsSignalRole role;
        final VBox root = new VBox(4);
        final SearchableSignalPicker picker = new SearchableSignalPicker();
        final TextField manual = new TextField();
        final ToggleButton selectMode = new ToggleButton("Select Existing");
        final ToggleButton manualMode = new ToggleButton("Enter Manually");
        final Label status = new Label();
        private boolean switching;

        RoleRow(GpsSignalRole role) {
            this.role = role;
            root.getStyleClass().add("route-mapping-role");

            Label name = new Label(role.displayName());
            name.getStyleClass().add("route-mapping-role-name");
            Label marker = new Label(role.required() ? "*" : "(optional)");
            marker.getStyleClass().add(role.required() ? "config-gps-role-required" : "config-gps-role-optional");
            HBox caption = new HBox(6, name, marker);
            caption.setAlignment(Pos.CENTER_LEFT);

            ToggleGroup group = new ToggleGroup();
            selectMode.setToggleGroup(group);
            manualMode.setToggleGroup(group);
            selectMode.getStyleClass().addAll("route-mapping-mode", "route-mapping-mode-left");
            manualMode.getStyleClass().addAll("route-mapping-mode", "route-mapping-mode-right");
            selectMode.setSelected(true);
            selectMode.setOnAction(e -> showManual(false));
            manualMode.setOnAction(e -> showManual(true));
            HBox modes = new HBox(0, selectMode, manualMode);
            modes.getStyleClass().add("route-mapping-modes");

            picker.setClearable(!role.required());
            picker.setOnPicked(entry -> onPicked(role, entry));
            picker.setOnCleared(() -> onCleared(role));

            manual.setPromptText("Signal name");
            manual.getStyleClass().add("route-mapping-manual");
            manual.setOnAction(e -> onManual(role, manual.getText()));
            manual.focusedProperty().addListener((o, a, focused) -> {
                if (!focused) {
                    onManual(role, manual.getText());
                }
            });

            status.getStyleClass().add("config-gps-role-status");
            status.setWrapText(true);

            showManual(false);
            root.getChildren().addAll(caption, modes, picker, manual, status);
        }

        void syncFrom(GpsMappingDraft current) {
            picker.setCatalog(current.catalog(), current.suggestions(role));
            picker.setSelected(current.assigned(role));
            String typed = current.manualName(role);
            if (!typed.equals(manual.getText())) {
                manual.setText(typed);
            }
            boolean manualSelected = current.mode(role) == RouteMappingMode.MANUAL_TEXT;
            switching = true;
            try {
                selectMode.setSelected(!manualSelected);
                manualMode.setSelected(manualSelected);
                showManual(manualSelected);
            } finally {
                switching = false;
            }
            GpsSignalValidator.Result advisory = current.advisory(role);
            String origin = current.mode(role) == RouteMappingMode.MANUAL_TEXT
                    ? "Manual mapping"
                    : current.roleOrigin(role).label();
            status.getStyleClass().removeAll(
                    "config-gps-role-status-warning", "config-gps-role-status-missing");
            if (current.manualNameUnresolved(role)) {
                status.setText(origin + " · not in this measurement");
                status.getStyleClass().add("config-gps-role-status-warning");
            } else if (advisory.severity() != GpsSignalValidator.Severity.OK) {
                status.setText(origin + " · " + advisory.message());
                status.getStyleClass().add(advisory.blocking()
                        ? "config-gps-role-status-missing"
                        : "config-gps-role-status-warning");
            } else {
                status.setText(origin);
            }
        }

        private void showManual(boolean manualSelected) {
            picker.setVisible(!manualSelected);
            picker.setManaged(!manualSelected);
            manual.setVisible(manualSelected);
            manual.setManaged(manualSelected);
            if (manualSelected) {
                picker.hideOptions();
            }
        }

        void pickForTest(String signalName) {
            picker.setSelected(draft == null ? null : draft.assigned(role));
            if (draft == null) {
                return;
            }
            draft.catalog().stream()
                    .filter(e -> e.key().signalName().equals(signalName))
                    .findFirst()
                    .ifPresent(e -> onPicked(role, e));
        }

        void typeForTest(String signalName) {
            switching = true;
            try {
                selectMode.setSelected(false);
                manualMode.setSelected(true);
                showManual(true);
            } finally {
                switching = false;
            }
            manual.setText(signalName);
            onManual(role, signalName);
        }

        boolean manualVisibleForTest() {
            return manual.isVisible();
        }

        String displayedValueForTest() {
            return picker.displayedValueForTest();
        }
    }
}

package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.ui.graph.map.MapBasemapSettings;
import com.stellar.platform.desktop.ui.graph.map.MapBasemapSettingsStore;
import com.stellar.platform.desktop.ui.graph.map.MapBasemapUserMessages;
import com.stellar.platform.desktop.ui.graph.map.MapBoundaryProfile;
import com.stellar.platform.desktop.ui.graph.map.MapLanguage;
import com.stellar.platform.desktop.ui.graph.map.MapProviderIds;
import com.stellar.platform.desktop.ui.graph.map.MapSecretStore;
import com.stellar.platform.desktop.ui.graph.map.MapSourceHealthCheck;
import com.stellar.platform.desktop.ui.graph.map.OfflineMapPackRegistry;
import com.stellar.platform.desktop.presentation.AnalysisMeasurementContext;
import com.stellar.platform.desktop.presentation.AnalysisStorageService;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.ui.control.SearchableSignalPicker;
import com.stellar.platform.processing.map.GpsMappingDraft;
import com.stellar.platform.processing.map.GpsMappingScope;
import com.stellar.platform.processing.map.GpsSignalMapping;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSignalValidator;
import com.stellar.platform.processing.map.RouteMappingMode;
import com.stellar.platform.processing.map.RouteMappingScope;
import com.stellar.platform.storage.analysis.AnalysisStorageValidator;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application Configuration page: Analysis Storage, GPS &amp; Map, About / Support.
 *
 * <p>The GPS section is the canonical mapping editor. All resolution, validation and status
 * reasoning is delegated to {@link GpsMappingDraft} in processing-core; this controller only binds
 * that state to controls and forwards user gestures.
 */
public final class ConfigurationController implements AppServiceAware, Refreshable, DesktopPageLifecycle {

    @FXML private ScrollPane configurationScroll;
    @FXML private VBox configurationRoot;
    @FXML private VBox analysisStorageCard;
    @FXML private TextField storagePathField;
    @FXML private Button browseButton;
    @FXML private Button testAccessButton;
    @FXML private Button saveButton;
    @FXML private Label statusBadge;
    @FXML private Label readAccessLabel;
    @FXML private Label writeAccessLabel;
    @FXML private Label freeSpaceLabel;
    @FXML private Label messageLabel;
    @FXML private Label aboutVersionLabel;
    @FXML private Label supportMessageLabel;

    @FXML private Button gpsSectionChip;
    @FXML private VBox gpsMappingCard;
    @FXML private VBox gpsEditor;
    @FXML private Label gpsNoProjectLabel;
    @FXML private HBox gpsSourceRow;
    @FXML private ComboBox<String> gpsSourceSelector;
    @FXML private Label gpsSourceReuseLabel;
    @FXML private VBox gpsSummary;
    @FXML private Label gpsSummaryLatitude;
    @FXML private Label gpsSummaryLongitude;
    @FXML private Label gpsSummaryAdvanced;
    @FXML private Button gpsPreviewButton;
    @FXML private Button gpsChangeButton;
    @FXML private Button gpsUseAutoButton;
    @FXML private VBox gpsSetupRequired;
    @FXML private VBox gpsPickerEditor;
    @FXML private Button gpsCancelButton;
    @FXML private SearchableSignalPicker gpsLatitudePicker;
    @FXML private SearchableSignalPicker gpsLongitudePicker;
    @FXML private SearchableSignalPicker gpsAltitudePicker;
    @FXML private SearchableSignalPicker gpsSpeedPicker;
    @FXML private SearchableSignalPicker gpsCoursePicker;
    @FXML private StackPane gpsLatitudeIcon;
    @FXML private StackPane gpsLongitudeIcon;
    @FXML private StackPane gpsAltitudeIcon;
    @FXML private StackPane gpsSpeedIcon;
    @FXML private StackPane gpsCourseIcon;
    @FXML private Label gpsLatitudeStatus;
    @FXML private Label gpsLongitudeStatus;
    @FXML private Label gpsAltitudeStatus;
    @FXML private Label gpsSpeedStatus;
    @FXML private Label gpsCourseStatus;
    @FXML private Label gpsStatusBadge;
    @FXML private Label gpsScopeBadge;
    @FXML private Label gpsMessageLabel;
    @FXML private Button gpsAutoDetectButton;
    @FXML private Button gpsSaveButton;
    @FXML private Button gpsOverrideButton;
    @FXML private Button gpsRevertDefaultButton;
    @FXML private Button gpsSaveDefaultButton;
    @FXML private Button mapSectionChip;
    @FXML private VBox mapSettingsCard;
    @FXML private ComboBox<MapLanguage> mapLanguageSelector;
    @FXML private ComboBox<MapBoundaryProfile> mapBoundarySelector;
    @FXML private ComboBox<String> mapProviderSelector;
    @FXML private CheckBox mapAllowStandardFallbackCheck;
    @FXML private TextField mapEnglishMapIdField;
    @FXML private TextField mapEnglishTileUrlField;
    @FXML private TextField mapAttributionField;
    @FXML private TextField mapMaxZoomField;
    @FXML private VBox mapTilerFields;
    @FXML private Label mapKeyStatusLabel;
    @FXML private Label mapBoundaryMessage;
    @FXML private Label mapSettingsMessage;
    @FXML private Button mapSettingsSaveButton;
    @FXML private Button mapTestSourceButton;

    private final Map<GpsSignalRole, SearchableSignalPicker> gpsPickers = new EnumMap<>(GpsSignalRole.class);
    private final Map<GpsSignalRole, Label> gpsRoleStatus = new EnumMap<>(GpsSignalRole.class);
    private final Map<GpsSignalRole, TextField> gpsManualFields = new EnumMap<>(GpsSignalRole.class);
    private final Map<GpsSignalRole, ToggleButton> gpsSelectModes = new EnumMap<>(GpsSignalRole.class);
    private final Map<GpsSignalRole, ToggleButton> gpsManualModes = new EnumMap<>(GpsSignalRole.class);
    private GpsMappingDraft gpsDraft;
    private String gpsSourceId = "";
    private boolean applyingGpsDraft;
    /** True while the signal pickers are on screen; the compact summary is the default. */
    private boolean gpsEditing;
    /** Set by any picker gesture and cleared on save, cancel or reload. Guards source switching. */
    private boolean gpsUnsavedEdits;
    private boolean applyingMapSettings;

    private DesktopAppService appService;
    private MainShellController shell;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stellar-analysis-storage");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void setAppService(DesktopAppService appService, MainShellController shell) {
        this.appService = appService;
        this.shell = shell;
    }

    @FXML
    private void initialize() {
        if (storagePathField != null) {
            storagePathField.setPromptText("Select a folder for reusable analysis artifacts");
        }
        bindGpsRole(GpsSignalRole.LATITUDE, gpsLatitudePicker, gpsLatitudeStatus, false);
        bindGpsRole(GpsSignalRole.LONGITUDE, gpsLongitudePicker, gpsLongitudeStatus, false);
        bindGpsRole(GpsSignalRole.ALTITUDE, gpsAltitudePicker, gpsAltitudeStatus, true);
        bindGpsRole(GpsSignalRole.GPS_SPEED, gpsSpeedPicker, gpsSpeedStatus, true);
        bindGpsRole(GpsSignalRole.COURSE, gpsCoursePicker, gpsCourseStatus, true);
        setRoleIcon(gpsLatitudeIcon, StellarIcons.mapPin());
        setRoleIcon(gpsLongitudeIcon, StellarIcons.mapPin());
        setRoleIcon(gpsAltitudeIcon, StellarIcons.mountain());
        setRoleIcon(gpsSpeedIcon, StellarIcons.speedometer());
        setRoleIcon(gpsCourseIcon, StellarIcons.compass());
        if (gpsAutoDetectButton != null) {
            gpsAutoDetectButton.setGraphic(StellarIcons.sparkle());
        }
        if (gpsSourceSelector != null) {
            gpsSourceSelector.valueProperty().addListener((o, a, source) -> {
                if (applyingGpsDraft || source == null || source.equals(gpsSourceId)) {
                    return;
                }
                if (gpsUnsavedEdits) {
                    // Unsaved work is never destroyed by a selection change; the previous
                    // measurement stays selected until the edit is saved or cancelled.
                    revertGpsSourceSelection();
                    setGpsMessage("Save or discard your GPS changes first.");
                    return;
                }
                loadGpsDraft(source);
            });
        }
        initializeMapSettingsControls();
        loadMapSettings();
    }

    private static void setRoleIcon(StackPane host, Node icon) {
        if (host != null) {
            host.getChildren().setAll(icon);
        }
    }

    private void bindGpsRole(GpsSignalRole role, SearchableSignalPicker picker, Label status, boolean optional) {
        if (picker == null) {
            return;
        }
        gpsPickers.put(role, picker);
        if (status != null) {
            gpsRoleStatus.put(role, status);
        }
        picker.setClearable(optional);
        picker.setOnPicked(entry -> onGpsRolePicked(role, entry));
        picker.setOnCleared(() -> onGpsRoleCleared(role));
        installManualMode(role, picker);
    }

    /**
     * Adds the Select Existing / Enter Manually switch beside the picker without rewriting the FXML
     * grid. The typed name is what a product configuration uses when the measurement's channels
     * are named differently.
     */
    private void installManualMode(GpsSignalRole role, SearchableSignalPicker picker) {
        if (!(picker.getParent() instanceof VBox host)) {
            return;
        }
        ToggleButton select = new ToggleButton("Select Existing");
        ToggleButton manual = new ToggleButton("Enter Manually");
        ToggleGroup group = new ToggleGroup();
        select.setToggleGroup(group);
        manual.setToggleGroup(group);
        select.getStyleClass().addAll("route-mapping-mode", "route-mapping-mode-left");
        manual.getStyleClass().addAll("route-mapping-mode", "route-mapping-mode-right");
        select.setSelected(true);
        HBox modes = new HBox(0, select, manual);
        modes.getStyleClass().add("route-mapping-modes");
        TextField field = new TextField();
        field.setPromptText("Signal name");
        field.getStyleClass().add("route-mapping-manual");
        field.setVisible(false);
        field.setManaged(false);
        select.setOnAction(e -> showManualInput(role, false));
        manual.setOnAction(e -> showManualInput(role, true));
        field.setOnAction(e -> onGpsManualEntered(role, field.getText()));
        field.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) {
                onGpsManualEntered(role, field.getText());
            }
        });
        gpsSelectModes.put(role, select);
        gpsManualModes.put(role, manual);
        gpsManualFields.put(role, field);
        int pickerIndex = host.getChildren().indexOf(picker);
        host.getChildren().add(pickerIndex, modes);
        host.getChildren().add(pickerIndex + 2, field);
    }

    private void showManualInput(GpsSignalRole role, boolean manual) {
        SearchableSignalPicker picker = gpsPickers.get(role);
        TextField field = gpsManualFields.get(role);
        ToggleButton select = gpsSelectModes.get(role);
        ToggleButton typed = gpsManualModes.get(role);
        if (picker != null) {
            picker.setVisible(!manual);
            picker.setManaged(!manual);
            if (manual) {
                picker.hideOptions();
            }
        }
        if (field != null) {
            field.setVisible(manual);
            field.setManaged(manual);
        }
        if (select != null) {
            select.setSelected(!manual);
        }
        if (typed != null) {
            typed.setSelected(manual);
        }
        if (manual && field != null) {
            field.requestFocus();
        }
    }

    @Override
    public void refresh() {
        loadMapSettings();
        if (appService == null) {
            return;
        }
        refreshGpsSection();
        AnalysisStorageService storage = appService.analysisStorage();
        storage.userSelectedRoot().ifPresentOrElse(
                path -> storagePathField.setText(path.toString()),
                () -> {
                    if (storagePathField.getText() == null || storagePathField.getText().isBlank()) {
                        storagePathField.setText("");
                    }
                });
        applyStatus(storage.currentStatus());
        if (aboutVersionLabel != null) {
            aboutVersionLabel.setText("StellarAI " + DesktopVersion.displayVersion()
                    + "  ·  schema v" + SqliteProjectMetadataStore.currentSchemaVersion()
                    + "  ·  local desktop");
        }
    }

    @FXML
    private void onBrowse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose Analysis Storage folder");
        Path current = parseFieldPath().orElse(null);
        if (current != null && java.nio.file.Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        var window = configurationRoot == null || configurationRoot.getScene() == null
                ? null : configurationRoot.getScene().getWindow();
        var chosen = chooser.showDialog(window);
        if (chosen != null) {
            storagePathField.setText(chosen.toPath().toAbsolutePath().normalize().toString());
            messageLabel.setText("Folder selected. Click Test Access or Save to validate.");
        }
    }

    @FXML
    private void onTestAccess() {
        Path path = parseFieldPath().orElse(null);
        if (path == null) {
            messageLabel.setText("Choose a folder first.");
            return;
        }
        setBusy(true);
        worker.submit(() -> {
            AnalysisStorageValidator.Result result = appService.analysisStorage().testAccess(path);
            Platform.runLater(() -> {
                applyStatus(result);
                setBusy(false);
            });
        });
    }

    @FXML
    private void onSave() {
        Path path = parseFieldPath().orElse(null);
        if (path == null) {
            messageLabel.setText("Choose a folder before saving.");
            return;
        }
        setBusy(true);
        worker.submit(() -> {
            AnalysisStorageValidator.Result result = appService.saveAnalysisStorage(path);
            Platform.runLater(() -> {
                applyStatus(result);
                if (result.valid()) {
                    messageLabel.setText("Analysis Storage saved. You can create projects.");
                }
                setBusy(false);
            });
        });
    }

    private void applyStatus(AnalysisStorageValidator.Result result) {
        if (result == null) {
            return;
        }
        statusBadge.getStyleClass().removeAll("config-status-valid", "config-status-invalid", "config-status-unset");
        switch (result.status()) {
            case VALID -> {
                statusBadge.setText("Valid");
                statusBadge.getStyleClass().add("config-status-valid");
            }
            case INVALID -> {
                statusBadge.setText("Invalid");
                statusBadge.getStyleClass().add("config-status-invalid");
            }
            case NOT_CONFIGURED -> {
                statusBadge.setText("Not configured");
                statusBadge.getStyleClass().add("config-status-unset");
            }
        }
        readAccessLabel.setText(result.readable() ? "Read access      ✓" : "Read access      ✗");
        writeAccessLabel.setText(result.writable() ? "Write access     ✓" : "Write access     ✗");
        if (result.freeSpaceBytes() == null) {
            freeSpaceLabel.setText("Available space  —");
        } else {
            freeSpaceLabel.setText("Available space  " + humanBytes(result.freeSpaceBytes()));
        }
        messageLabel.setText(result.message());
    }

    private void setBusy(boolean busy) {
        if (browseButton != null) browseButton.setDisable(busy);
        if (testAccessButton != null) testAccessButton.setDisable(busy);
        if (saveButton != null) saveButton.setDisable(busy);
    }

    private java.util.Optional<Path> parseFieldPath() {
        if (storagePathField == null) {
            return java.util.Optional.empty();
        }
        String text = storagePathField.getText();
        if (text == null || text.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Path.of(text.trim()).toAbsolutePath().normalize());
        } catch (RuntimeException ex) {
            return java.util.Optional.empty();
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 0) return "—";
        long abs = bytes;
        if (abs < 1024) return abs + " B";
        CharacterIterator ci = new StringCharacterIterator("KMGTPE");
        while (abs >= 1024 * 1024 && ci.next() != CharacterIterator.DONE) {
            abs /= 1024;
        }
        abs /= 1024;
        return String.format(java.util.Locale.ROOT, "%.0f %cB", (double) abs, ci.current());
    }

    @FXML
    private void onAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About StellarAI");
        alert.setHeaderText(DesktopVersion.windowTitle());
        alert.setContentText("Version " + DesktopVersion.displayVersion()
                + "\nProject schema v" + SqliteProjectMetadataStore.currentSchemaVersion()
                + "\nLocal Windows desktop. Core processing does not require internet, PostgreSQL, or stellar-server."
                + "\nProduction formats: MF4 + DBC. ASC is present. BLF is experimental.");
        alert.showAndWait();
    }

    @FXML
    private void onSupportBundle() {
        if (appService == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Diagnostics Support Bundle");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP archive", "*.zip"));
        chooser.setInitialFileName("StellarAI-support-" + DesktopVersion.displayVersion() + ".zip");
        var window = configurationRoot == null || configurationRoot.getScene() == null
                ? null : configurationRoot.getScene().getWindow();
        var file = chooser.showSaveDialog(window);
        if (file == null) {
            return;
        }
        setBusy(true);
        worker.submit(() -> {
            try {
                Path written = appService.exportDiagnosticsSupportBundle(file.toPath());
                Platform.runLater(() -> {
                    if (supportMessageLabel != null) {
                        supportMessageLabel.setText("Support bundle written: " + written.getFileName());
                    }
                    setBusy(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (supportMessageLabel != null) {
                        supportMessageLabel.setText("Support bundle failed: "
                                + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                    }
                    setBusy(false);
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // GPS & Map
    // ------------------------------------------------------------------

    /**
     * Configuration is reachable without an open project, so the GPS section degrades to an
     * informational state instead of offering pickers that cannot resolve anything.
     */
    private void refreshGpsSection() {
        if (gpsMappingCard == null || appService == null) {
            return;
        }
        boolean available = appService.hasProcessedSignalCatalog();
        setShown(gpsEditor, available);
        setShown(gpsNoProjectLabel, !available);
        if (!available) {
            gpsDraft = null;
            gpsSourceId = "";
            gpsEditing = false;
            gpsUnsavedEdits = false;
            applyGpsStatusBadge(GpsMappingDraft.Status.NO_CATALOG);
            setGpsMessage("");
            return;
        }
        if (gpsUnsavedEdits) {
            // Re-entering the page must not throw away edits the engineer has not saved.
            applyGpsDraft();
            return;
        }
        List<String> sources = appService.gpsMappingSourceIds();
        List<String> options = sources.isEmpty()
                ? List.of(DesktopAppService.GPS_MAPPING_MERGED_SOURCE)
                : sources;
        String desired = preferredGpsSource(options);
        applyingGpsDraft = true;
        try {
            gpsSourceSelector.getItems().setAll(options);
            gpsSourceSelector.setValue(desired);
        } finally {
            applyingGpsDraft = false;
        }
        setShown(gpsSourceRow, options.size() > 1);
        loadGpsDraft(desired);
    }

    /** Builds the draft from the already-processed catalog. No measurement file is opened. */
    private void loadGpsDraft(String sourceId) {
        if (appService == null) {
            return;
        }
        gpsSourceId = sourceId == null ? "" : sourceId;
        gpsDraft = GpsMappingDraft.resolve(
                appService.currentProjectId(),
                gpsSourceId,
                appService.gpsMappingCatalog(gpsSourceId),
                persistedMappingsForDraft(gpsSourceId),
                appService.loadRouteConfigDefaults());
        gpsUnsavedEdits = false;
        // Neither state opens on five dropdowns: a detected route shows the compact summary, and an
        // undetected one shows the short setup prompt until the user asks to configure.
        gpsEditing = false;
        applyGpsDraft();
    }

    /**
     * Renders the draft into whichever of the three states the section is in: the compact summary,
     * the setup prompt, or the signal pickers.
     */
    private void applyGpsDraft() {
        if (gpsDraft == null) {
            return;
        }
        applyingGpsDraft = true;
        try {
            for (Map.Entry<GpsSignalRole, SearchableSignalPicker> entry : gpsPickers.entrySet()) {
                GpsSignalRole role = entry.getKey();
                SearchableSignalPicker picker = entry.getValue();
                picker.setCatalog(gpsDraft.catalog(), gpsDraft.suggestions(role));
                picker.setSelected(gpsDraft.assigned(role));
                TextField manual = gpsManualFields.get(role);
                boolean typed = gpsDraft.mode(role) == RouteMappingMode.MANUAL_TEXT;
                if (manual != null && !manual.getText().equals(gpsDraft.manualName(role))) {
                    manual.setText(gpsDraft.manualName(role));
                }
                showManualInput(role, typed);
                applyRoleStatus(gpsRoleStatus.get(role), role);
            }
        } finally {
            applyingGpsDraft = false;
        }
        applyGpsStatusBadge(gpsDraft.status());
        applyGpsScopeBadge();
        setGpsMessage(gpsDraft.statusDetail());
        applyGpsSectionState();
    }

    private void applyGpsSectionState() {
        boolean routable = gpsDraft.hasRoute();
        setShown(gpsPickerEditor, gpsEditing);
        setShown(gpsSummary, !gpsEditing && routable);
        setShown(gpsSetupRequired, !gpsEditing && !routable);
        if (gpsSummaryLatitude != null) {
            gpsSummaryLatitude.setText(assignedName(GpsSignalRole.LATITUDE));
            gpsSummaryLongitude.setText(assignedName(GpsSignalRole.LONGITUDE));
            gpsSummaryAdvanced.setText(advancedSummary());
        }
        if (gpsPreviewButton != null) {
            // Nothing to look at without a shell to navigate with, or without coordinates.
            gpsPreviewButton.setDisable(shell == null || !routable);
        }
        if (gpsUseAutoButton != null) {
            boolean stored = gpsDraft.status() == GpsMappingDraft.Status.USER_CONFIGURED
                    || gpsDraft.status() == GpsMappingDraft.Status.SCHEMA_REUSED;
            setShown(gpsUseAutoButton, stored);
        }
        if (gpsSaveButton != null) {
            gpsSaveButton.setDisable(!gpsDraft.requiredRolesConfigured());
        }
        if (gpsSaveDefaultButton != null) {
            gpsSaveDefaultButton.setDisable(!gpsDraft.requiredRolesConfigured());
        }
        if (gpsOverrideButton != null) {
            setShown(gpsOverrideButton, gpsDraft.inheritedFromDefault());
        }
        if (gpsRevertDefaultButton != null) {
            setShown(gpsRevertDefaultButton,
                    gpsDraft.hasProductDefault() && gpsDraft.status() == GpsMappingDraft.Status.USER_CONFIGURED);
        }
        if (gpsSourceSelector != null) {
            // Preferred over silently discarding: the selector locks until the edit is resolved.
            gpsSourceSelector.setDisable(gpsUnsavedEdits || gpsSourceSelector.getItems().size() < 2);
        }
        if (gpsSourceReuseLabel != null) {
            gpsSourceReuseLabel.setText(sourceScopeNote());
        }
    }

    private String assignedName(GpsSignalRole role) {
        if (gpsDraft == null) {
            return "Not mapped";
        }
        String manual = gpsDraft.manualName(role);
        if (!manual.isEmpty()) {
            return manual + " (manual)";
        }
        var key = gpsDraft.assigned(role);
        return key == null ? "Not mapped" : key.signalName();
    }

    /** One line about the optional roles, so the summary does not need three more rows. */
    private String advancedSummary() {
        List<String> mapped = new java.util.ArrayList<>();
        for (GpsSignalRole role : GpsMappingDraft.OPTIONAL_ROLES) {
            if (gpsDraft.assigned(role) != null) {
                mapped.add(role.displayName());
            }
        }
        if (mapped.isEmpty()) {
            return "No altitude, speed or heading signal in use.";
        }
        return "Also in use: " + String.join(", ", mapped) + ".";
    }

    /**
     * Explains when one mapping already covers several identical recordings, so nobody configures
     * the same schema file by file.
     */
    private String sourceScopeNote() {
        if (appService == null || gpsUnsavedEdits) {
            return gpsUnsavedEdits ? "Save or discard your GPS changes first." : "";
        }
        int compatible = appService.gpsMappingCompatibleSourceCount(gpsSourceId);
        return compatible > 1 ? "Used by " + compatible + " compatible measurement files" : "";
    }

    private void setGpsMessage(String text) {
        if (gpsMessageLabel != null) {
            gpsMessageLabel.setText(text == null ? "" : text);
        }
    }

    /** Shows why the role holds its current signal, plus any advisory range warning. */
    private void applyRoleStatus(Label status, GpsSignalRole role) {
        if (status == null) {
            return;
        }
        GpsSignalValidator.Result advisory = gpsDraft.advisory(role);
        String origin = gpsDraft.roleOrigin(role).label();
        status.getStyleClass().removeAll(
                "config-gps-role-status-warning", "config-gps-role-status-missing");
        if (advisory.severity() == GpsSignalValidator.Severity.OK) {
            status.setText(origin);
            return;
        }
        status.setText(origin + " · " + advisory.message());
        status.getStyleClass().add(advisory.blocking()
                ? "config-gps-role-status-missing"
                : "config-gps-role-status-warning");
    }

    private void applyGpsStatusBadge(GpsMappingDraft.Status status) {
        if (gpsStatusBadge == null) {
            return;
        }
        gpsStatusBadge.getStyleClass().removeAll(
                "config-status-valid", "config-status-invalid", "config-status-unset");
        gpsStatusBadge.setText(status.label());
        gpsStatusBadge.getStyleClass().add(switch (status) {
            case USER_CONFIGURED, AUTO_DETECTED, SCHEMA_REUSED, PRODUCT_DEFAULT -> "config-status-valid";
            case MAPPING_SIGNAL_MISSING -> "config-status-invalid";
            case MAPPING_REQUIRED, NO_CATALOG -> "config-status-unset";
        });
    }

    private void applyGpsScopeBadge() {
        if (gpsScopeBadge == null || gpsDraft == null) {
            return;
        }
        RouteMappingScope scope = gpsDraft.resolvedScope();
        if (scope == RouteMappingScope.PROJECT) {
            gpsScopeBadge.setText("Project configuration");
            setShown(gpsScopeBadge, true);
        } else if (scope == RouteMappingScope.DEFAULT) {
            gpsScopeBadge.setText("Product default");
            setShown(gpsScopeBadge, true);
        } else {
            gpsScopeBadge.setText("");
            setShown(gpsScopeBadge, false);
        }
    }

    /**
     * Applies a picked signal.
     *
     * <p>Validation is advisory: a suspicious range is applied and flagged inline rather than
     * blocked, because unusual engineering data is not necessarily wrong. Keeping the warning
     * visible next to the role — instead of a modal the user dismisses and forgets — is what makes
     * the override explicit. Only a physically impossible selection is refused.
     */
    private void onGpsRolePicked(GpsSignalRole role, com.stellar.platform.domain.catalog.SignalCatalogEntry entry) {
        if (applyingGpsDraft || gpsDraft == null || entry == null) {
            return;
        }
        GpsSignalValidator.Result result = gpsDraft.assign(role, entry.key());
        gpsUnsavedEdits = true;
        applyGpsDraft();
        if (result.severity() != GpsSignalValidator.Severity.OK) {
            setGpsMessage(result.message());
        }
    }

    private void onGpsManualEntered(GpsSignalRole role, String text) {
        if (applyingGpsDraft || gpsDraft == null) {
            return;
        }
        GpsSignalValidator.Result result = gpsDraft.assignManual(role, text);
        gpsUnsavedEdits = true;
        applyGpsDraft();
        if (result.severity() != GpsSignalValidator.Severity.OK) {
            setGpsMessage(result.message());
        }
    }

    private void onGpsRoleCleared(GpsSignalRole role) {
        if (applyingGpsDraft || gpsDraft == null) {
            return;
        }
        gpsDraft.clear(role);
        gpsUnsavedEdits = true;
        applyGpsDraft();
    }

    /** Takes the user to Map Route so the mapping can be judged by the route it draws. */
    @FXML
    private void onGpsPreviewRoute() {
        if (shell != null) {
            shell.openMapRoute();
        }
    }

    /** Reveals the pickers. Nothing is persisted until Save Mapping. */
    @FXML
    private void onGpsChangeMapping() {
        if (gpsDraft == null) {
            return;
        }
        gpsEditing = true;
        applyGpsDraft();
    }

    /** Leaves the pickers and drops unsaved edits by rebuilding the draft from stored state. */
    @FXML
    private void onGpsCancelEditing() {
        if (gpsDraft == null) {
            return;
        }
        gpsUnsavedEdits = false;
        loadGpsDraft(gpsSourceId);
        if (gpsDraft.hasRoute()) {
            gpsEditing = false;
            applyGpsSectionState();
        }
        setGpsMessage("");
    }

    @FXML
    private void onGpsAutoDetect() {
        if (gpsDraft == null) {
            return;
        }
        gpsDraft.autoDetect();
        gpsUnsavedEdits = true;
        applyGpsDraft();
    }

    /**
     * Hands resolution back to automatic/schema logic for this measurement.
     *
     * <p>A mapping inherited from a compatible measurement is not this source's to delete: removing
     * rows here would report success and then revert on reopen. In that case the owning measurement
     * is selected instead, which is where the mapping can actually be changed.
     */
    @FXML
    private void onGpsUseAutomaticDetection() {
        if (gpsDraft == null || appService == null) {
            return;
        }
        String owner = gpsSelectorIdentity(gpsDraft.reusedFromSourceId());
        if (!owner.isBlank()) {
            selectGpsSource(owner);
            setGpsMessage("This mapping is reused from a compatible measurement (" + owner
                    + "). Switched to that measurement, where it can be changed.");
            return;
        }
        gpsDraft.resetToAuto();
        appService.persistGpsSignalMappings(
                gpsDraft.sourceId(), gpsDraft.schemaFingerprint(), List.of());
        gpsUnsavedEdits = false;
        gpsEditing = false;
        applyGpsDraft();
        setGpsMessage("Automatic detection is in charge again for this measurement.");
    }

    @FXML
    private void onGpsSaveMapping() {
        if (gpsDraft == null || appService == null) {
            return;
        }
        if (!gpsDraft.requiredRolesConfigured()) {
            setGpsMessage("Latitude and Longitude are required to draw the route.");
            return;
        }
        appService.persistGpsSignalMappings(
                gpsDraft.sourceId(), gpsDraft.schemaFingerprint(),
                gpsDraft.toMappings(Instant.now(), RouteMappingScope.PROJECT));
        boolean stored = !gpsDraft.toMappings(null).isEmpty();
        gpsUnsavedEdits = false;
        loadGpsDraft(gpsSourceId);
        setGpsMessage(stored
                ? "Project GPS mapping saved. Map Route uses it immediately."
                : "Automatic GPS detection is in use for this measurement.");
    }

    @FXML
    private void onGpsSaveAsDefault() {
        if (gpsDraft == null || appService == null) {
            return;
        }
        if (!gpsDraft.requiredRolesConfigured()) {
            setGpsMessage("Latitude and Longitude are required before a product default can be saved.");
            return;
        }
        appService.persistRouteConfigDefaults(gpsDraft.toMappings(Instant.now(), RouteMappingScope.DEFAULT));
        gpsUnsavedEdits = false;
        loadGpsDraft(gpsSourceId);
        setGpsMessage("Saved as the product default. Projects without their own mapping inherit it.");
    }

    @FXML
    private void onGpsUseProjectOverride() {
        if (gpsDraft == null || appService == null) {
            return;
        }
        for (GpsSignalRole role : GpsMappingDraft.EDITABLE_ROLES) {
            String typed = gpsDraft.manualName(role);
            if (!typed.isEmpty()) {
                gpsDraft.assignManual(role, typed);
            } else if (gpsDraft.assigned(role) != null) {
                gpsDraft.assign(role, gpsDraft.assigned(role));
            }
        }
        gpsEditing = true;
        gpsUnsavedEdits = true;
        applyGpsDraft();
        setGpsMessage("This will become a project mapping when you Save Mapping.");
    }

    @FXML
    private void onGpsRevertToDefault() {
        if (gpsDraft == null || appService == null) {
            return;
        }
        appService.persistGpsSignalMappings(gpsDraft.sourceId(), gpsDraft.schemaFingerprint(), List.of());
        gpsUnsavedEdits = false;
        gpsEditing = false;
        loadGpsDraft(gpsSourceId);
        setGpsMessage("Project override removed. The product default is in use again.");
    }

    private void revertGpsSourceSelection() {
        applyingGpsDraft = true;
        try {
            gpsSourceSelector.setValue(gpsSourceId);
        } finally {
            applyingGpsDraft = false;
        }
    }

    private void selectGpsSource(String sourceId) {
        String selector = gpsSelectorIdentity(sourceId);
        if (gpsSourceSelector == null || selector == null
                || !gpsSourceSelector.getItems().contains(selector)) {
            return;
        }
        applyingGpsDraft = true;
        try {
            gpsSourceSelector.setValue(selector);
        } finally {
            applyingGpsDraft = false;
        }
        loadGpsDraft(selector);
    }

    /**
     * Configuration's source list is display filenames; persisted rows and reuse owners may use
     * canonical workspace ids. Resolve to the selector value without inventing a third identity.
     */
    private String gpsSelectorIdentity(String identityOrName) {
        if (identityOrName == null || identityOrName.isBlank()) {
            return "";
        }
        List<String> options = gpsSourceSelector == null ? List.of() : gpsSourceSelector.getItems();
        if (options.contains(identityOrName)) {
            return identityOrName;
        }
        AnalysisMeasurementContext ctx = appService == null
                ? AnalysisMeasurementContext.none()
                : appService.resolveAnalysisMeasurement(identityOrName);
        if (ctx.isPresent()) {
            for (String option : options) {
                if (ctx.matches(option)) {
                    return option;
                }
            }
        }
        return identityOrName;
    }

    private List<GpsSignalMapping> persistedMappingsForDraft(String selectorSourceId) {
        List<GpsSignalMapping> all = appService.loadAllGpsSignalMappings();
        AnalysisMeasurementContext ctx = appService.resolveAnalysisMeasurement(selectorSourceId);
        if (!ctx.isPresent()) {
            return all;
        }
        return GpsMappingScope.aliasMappingsForLookup(
                selectorSourceId, ctx.sourceId(), ctx.fileName(), all);
    }

    /**
     * Entry point used when Map Route asks the user to configure GPS signals.
     *
     * <p>Arriving here is already an explicit request to configure, so an undetected route opens the
     * pickers directly rather than asking a second time.
     */
    public void focusGpsMappingSection() {
        focusGpsMappingSection(appService == null ? null : appService.activeAnalysisMeasurement());
    }

    public void focusGpsMappingSection(AnalysisMeasurementContext measurement) {
        if (measurement != null && measurement.isPresent()) {
            gpsSourceId = measurement.fileName().isBlank() ? measurement.sourceId() : measurement.fileName();
        }
        refreshGpsSection();
        if (gpsDraft != null && !gpsDraft.hasRoute() && !gpsEditing) {
            onGpsChangeMapping();
        }
        scrollIntoView(gpsMappingCard);
    }

    private String preferredGpsSource(List<String> options) {
        if (options == null || options.isEmpty()) {
            return "";
        }
        AnalysisMeasurementContext active = appService == null
                ? AnalysisMeasurementContext.none()
                : appService.activeAnalysisMeasurement();
        if (active.isPresent()) {
            for (String option : options) {
                if (active.matches(option)) {
                    return option;
                }
            }
            AnalysisMeasurementContext resolved = appService.resolveAnalysisMeasurement(gpsSourceId);
            if (resolved.isPresent() && resolved.sourceId().equals(active.sourceId())) {
                for (String option : options) {
                    if (resolved.matches(option)) {
                        return option;
                    }
                }
            }
        }
        if (gpsSourceId != null && options.contains(gpsSourceId)) {
            return gpsSourceId;
        }
        return options.getFirst();
    }

    @FXML
    private void onFocusGpsMapping() {
        scrollIntoView(gpsMappingCard);
    }

    @FXML
    private void onFocusMapSettings() {
        scrollIntoView(mapSettingsCard);
    }

    @FXML
    private void onFocusAnalysisStorage() {
        scrollIntoView(analysisStorageCard);
    }

    private void initializeMapSettingsControls() {
        if (mapLanguageSelector != null) {
            mapLanguageSelector.getItems().setAll(MapLanguage.ENGLISH);
            mapLanguageSelector.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(MapLanguage language) {
                    return language == null ? "" : language.displayLabel();
                }

                @Override
                public MapLanguage fromString(String string) {
                    return MapLanguage.fromPersisted(string);
                }
            });
        }
        if (mapBoundarySelector != null) {
            mapBoundarySelector.getItems().setAll(
                    MapBoundaryProfile.PROVIDER_DEFAULT,
                    MapBoundaryProfile.GLOBAL_NEUTRAL,
                    MapBoundaryProfile.INDIA_OFFICIAL);
            mapBoundarySelector.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(MapBoundaryProfile profile) {
                    return profile == null ? "" : profile.displayLabel();
                }

                @Override
                public MapBoundaryProfile fromString(String string) {
                    return MapBoundaryProfile.fromPersisted(string);
                }
            });
            mapBoundarySelector.valueProperty().addListener((o, a, profile) -> {
                if (applyingMapSettings || profile == null) {
                    return;
                }
                if (profile == MapBoundaryProfile.INDIA_OFFICIAL
                        && !MapBoundaryProfile.indiaOfficialSourceConfigured()) {
                    if (mapBoundaryMessage != null) {
                        mapBoundaryMessage.setText(MapBasemapUserMessages.INDIA_OFFICIAL_UNCONFIGURED);
                    }
                    applyingMapSettings = true;
                    try {
                        mapBoundarySelector.setValue(MapBoundaryProfile.PROVIDER_DEFAULT);
                    } finally {
                        applyingMapSettings = false;
                    }
                }
            });
        }
        if (mapProviderSelector != null) {
            boolean maptilerConfigured = MapSecretStore.production().configured();
            if (maptilerConfigured) {
                mapProviderSelector.getItems().setAll(
                        MapProviderIds.AUTO,
                        MapProviderIds.SELFHOSTED_ENGLISH_RASTER,
                        MapProviderIds.MAPTILER_ENGLISH_RASTER);
            } else {
                mapProviderSelector.getItems().setAll(
                        MapProviderIds.AUTO,
                        MapProviderIds.SELFHOSTED_ENGLISH_RASTER);
            }
            mapProviderSelector.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(String id) {
                    return MapProviderIds.mapSourceLabel(id);
                }

                @Override
                public String fromString(String string) {
                    return string;
                }
            });
        }
        setShown(mapTilerFields, MapSecretStore.production().configured());
    }

    private void loadMapSettings() {
        MapBasemapSettings settings = MapBasemapSettingsStore.production().load();
        applyingMapSettings = true;
        try {
            if (mapLanguageSelector != null) {
                mapLanguageSelector.setValue(settings.language());
            }
            if (mapBoundarySelector != null) {
                MapBoundaryProfile profile = settings.boundaryProfile();
                if (profile == MapBoundaryProfile.INDIA_OFFICIAL
                        && !MapBoundaryProfile.indiaOfficialSourceConfigured()) {
                    profile = MapBoundaryProfile.PROVIDER_DEFAULT;
                    if (mapBoundaryMessage != null) {
                        mapBoundaryMessage.setText(MapBasemapUserMessages.INDIA_OFFICIAL_UNCONFIGURED);
                    }
                }
                mapBoundarySelector.setValue(profile);
            }
            if (mapProviderSelector != null) {
                String preference = settings.providerPreference();
                if (!mapProviderSelector.getItems().contains(preference)) {
                    preference = MapProviderIds.AUTO;
                }
                mapProviderSelector.setValue(preference);
            }
            if (mapAllowStandardFallbackCheck != null) {
                mapAllowStandardFallbackCheck.setSelected(settings.allowStandardFallback());
            }
            if (mapEnglishMapIdField != null) {
                mapEnglishMapIdField.setText(settings.englishMapId());
            }
            if (mapEnglishTileUrlField != null) {
                mapEnglishTileUrlField.setText(settings.selfHostedUrl());
            }
            if (mapAttributionField != null) {
                mapAttributionField.setText(settings.selfHostedAttribution());
            }
            if (mapMaxZoomField != null) {
                mapMaxZoomField.setText(Integer.toString(settings.selfHostedMaxZoom()));
            }
        } finally {
            applyingMapSettings = false;
        }
        boolean key = MapSecretStore.production().configured();
        setShown(mapTilerFields, key);
        if (mapKeyStatusLabel != null) {
            mapKeyStatusLabel.setText("API key: " + MapSecretStore.maskConfigured(key)
                    + "  (STELLARAI_MAP_API_KEY or config/map-secrets.properties)");
        }
    }

    @FXML
    private void onSaveMapSettings() {
        MapBasemapSettings current = currentMapSettingsFromForm();
        try {
            MapBasemapSettingsStore.production().save(current);
            loadMapSettings();
            if (mapSettingsMessage != null) {
                mapSettingsMessage.setText("Map settings saved for this workstation.");
            }
        } catch (Exception ex) {
            if (mapSettingsMessage != null) {
                mapSettingsMessage.setText("Could not save map settings.");
            }
        }
    }

    @FXML
    private void onTestMapSource() {
        if (mapSettingsMessage != null) {
            mapSettingsMessage.setText("Checking map source…");
        }
        MapBasemapSettings current = currentMapSettingsFromForm();
        worker.submit(() -> {
            MapSourceHealthCheck.Result result = MapSourceHealthCheck.check(
                    current,
                    MapSecretStore.production(),
                    new OfflineMapPackRegistry());
            Platform.runLater(() -> {
                if (mapSettingsMessage != null) {
                    mapSettingsMessage.setText(result.userMessage());
                }
            });
        });
    }

    private MapBasemapSettings currentMapSettingsFromForm() {
        int maxZoom = MapBasemapSettings.DEFAULT_MAX_ZOOM;
        if (mapMaxZoomField != null && mapMaxZoomField.getText() != null && !mapMaxZoomField.getText().isBlank()) {
            try {
                maxZoom = Integer.parseInt(mapMaxZoomField.getText().trim());
            } catch (NumberFormatException ignored) {
                maxZoom = MapBasemapSettings.DEFAULT_MAX_ZOOM;
            }
        }
        return new MapBasemapSettings(
                mapLanguageSelector == null || mapLanguageSelector.getValue() == null
                        ? MapLanguage.ENGLISH
                        : mapLanguageSelector.getValue(),
                mapBoundarySelector == null || mapBoundarySelector.getValue() == null
                        ? MapBoundaryProfile.PROVIDER_DEFAULT
                        : mapBoundarySelector.getValue(),
                mapProviderSelector == null || mapProviderSelector.getValue() == null
                        ? MapProviderIds.AUTO
                        : mapProviderSelector.getValue(),
                mapEnglishMapIdField == null ? "" : mapEnglishMapIdField.getText(),
                mapEnglishTileUrlField == null ? "" : mapEnglishTileUrlField.getText(),
                mapAttributionField == null ? "" : mapAttributionField.getText(),
                maxZoom,
                mapAllowStandardFallbackCheck == null || mapAllowStandardFallbackCheck.isSelected());
    }

    private void scrollIntoView(Node target) {
        if (configurationScroll == null || configurationRoot == null || target == null) {
            return;
        }
        Platform.runLater(() -> {
            configurationScroll.applyCss();
            configurationScroll.layout();
            double contentHeight = configurationRoot.getBoundsInLocal().getHeight();
            double viewportHeight = configurationScroll.getViewportBounds().getHeight();
            double scrollable = contentHeight - viewportHeight;
            if (scrollable <= 0) {
                configurationScroll.setVvalue(0);
                return;
            }
            double top = target.getBoundsInParent().getMinY();
            configurationScroll.setVvalue(Math.max(0, Math.min(1, top / scrollable)));
        });
    }

    private static void setShown(Node node, boolean shown) {
        if (node == null) {
            return;
        }
        node.setVisible(shown);
        node.setManaged(shown);
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    public void onHide() {
        closeGpsPickerPopups();
    }

    @Override
    public void dispose() {
        closeGpsPickerPopups();
        worker.shutdownNow();
    }

    /** A shown popup is a separate Window and would keep this page reachable after it is evicted. */
    private void closeGpsPickerPopups() {
        gpsPickers.values().forEach(SearchableSignalPicker::hideOptions);
    }

    // --- structural probes for tests ---

    GpsMappingDraft gpsDraftForTest() {
        return gpsDraft;
    }

    boolean gpsEditorShownForTest() {
        return gpsEditor != null && gpsEditor.isVisible();
    }

    boolean analysisStorageShownForTest() {
        return analysisStorageCard != null && analysisStorageCard.isVisible();
    }

    boolean gpsRoleStatusWarnedForTest(GpsSignalRole role) {
        Label label = gpsRoleStatus.get(role);
        return label != null && (label.getStyleClass().contains("config-gps-role-status-warning")
                || label.getStyleClass().contains("config-gps-role-status-missing"));
    }

    String gpsStatusBadgeForTest() {
        return gpsStatusBadge == null ? "" : gpsStatusBadge.getText();
    }

    String gpsMessageForTest() {
        return gpsMessageLabel == null ? "" : gpsMessageLabel.getText();
    }

    String gpsRoleStatusForTest(GpsSignalRole role) {
        Label label = gpsRoleStatus.get(role);
        return label == null ? "" : label.getText();
    }

    List<String> gpsSourcesForTest() {
        return gpsSourceSelector == null ? List.of() : List.copyOf(gpsSourceSelector.getItems());
    }

    /** Drives the same path as choosing a measurement in the selector, including the dirty guard. */
    void selectGpsSourceForTest(String sourceId) {
        if (gpsUnsavedEdits) {
            setGpsMessage("Save or discard your GPS changes first.");
            return;
        }
        applyingGpsDraft = true;
        try {
            if (gpsSourceSelector != null && gpsSourceSelector.getItems().contains(sourceId)) {
                gpsSourceSelector.setValue(sourceId);
            }
        } finally {
            applyingGpsDraft = false;
        }
        loadGpsDraft(sourceId);
    }

    boolean gpsPickersShownForTest() {
        return gpsPickerEditor != null && gpsPickerEditor.isVisible();
    }

    boolean gpsSummaryShownForTest() {
        return gpsSummary != null && gpsSummary.isVisible();
    }

    boolean gpsSetupRequiredShownForTest() {
        return gpsSetupRequired != null && gpsSetupRequired.isVisible();
    }

    /** True when an optional role's selector is on screen. */
    boolean gpsOptionalRoleShownForTest(GpsSignalRole role) {
        SearchableSignalPicker picker = gpsPickers.get(role);
        return picker != null && picker.isVisible() && gpsPickersShownForTest();
    }

    boolean gpsSourceSelectorDisabledForTest() {
        return gpsSourceSelector != null && gpsSourceSelector.isDisabled();
    }

    String gpsSourceNoteForTest() {
        return gpsSourceReuseLabel == null ? "" : gpsSourceReuseLabel.getText();
    }

    String gpsSummaryLatitudeForTest() {
        return gpsSummaryLatitude == null ? "" : gpsSummaryLatitude.getText();
    }

    void changeGpsMappingForTest() {
        onGpsChangeMapping();
    }

    void cancelGpsEditingForTest() {
        onGpsCancelEditing();
    }

    void useAutomaticGpsDetectionForTest() {
        onGpsUseAutomaticDetection();
    }

    /**
     * Drives the same path as picking the row labelled {@code signalName}, bypassing only the mouse
     * gesture.
     */
    void pickGpsRoleForTest(GpsSignalRole role, String signalName) {
        if (gpsDraft == null) {
            return;
        }
        onGpsRolePicked(role, gpsDraft.catalog().stream()
                .filter(e -> e.key().signalName().equals(signalName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no catalog signal named " + signalName)));
    }

    void clearGpsRoleForTest(GpsSignalRole role) {
        onGpsRoleCleared(role);
    }

    void autoDetectGpsForTest() {
        onGpsAutoDetect();
    }

    void resetGpsToAutoForTest() {
        onGpsUseAutomaticDetection();
    }

    void saveGpsMappingForTest() {
        onGpsSaveMapping();
    }

    void typeGpsRoleForTest(GpsSignalRole role, String signalName) {
        showManualInput(role, true);
        onGpsManualEntered(role, signalName);
    }

    String gpsScopeBadgeForTest() {
        return gpsScopeBadge == null ? "" : gpsScopeBadge.getText();
    }

    void saveGpsAsDefaultForTest() {
        onGpsSaveAsDefault();
    }

    void revertGpsToDefaultForTest() {
        onGpsRevertToDefault();
    }

    void useProjectOverrideForTest() {
        onGpsUseProjectOverride();
    }

    boolean gpsSaveDisabledForTest() {
        return gpsSaveButton == null || gpsSaveButton.isDisabled();
    }

    boolean mapSettingsCardShownForTest() {
        return mapSettingsCard != null && mapSettingsCard.isVisible();
    }

    boolean mapTestSourceShownForTest() {
        return mapTestSourceButton != null && mapTestSourceButton.isVisible();
    }

    String mapSettingsMessageForTest() {
        return mapSettingsMessage == null ? "" : mapSettingsMessage.getText();
    }

    List<String> mapSourceLabelsForTest() {
        if (mapProviderSelector == null) {
            return List.of();
        }
        return mapProviderSelector.getItems().stream().map(MapProviderIds::mapSourceLabel).toList();
    }
}

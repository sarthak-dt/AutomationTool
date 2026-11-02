package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.MeasurementAddConfirmation;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.ProjectProcessingContext;
import com.stellar.platform.desktop.presentation.SourceStatusPresenter;
import com.stellar.platform.desktop.presentation.SourceWorkspaceHeaderSummary;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.MeasurementSourceCapability;
import com.stellar.platform.desktop.project.ProjectFilesAddResult;
import com.stellar.platform.desktop.presentation.ProjectDefinitionMatchingModel;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.ui.inventory.ChannelSelectionPane;
import com.stellar.platform.desktop.ui.inventory.DbcMappingTablePane;
import com.stellar.platform.desktop.ui.inventory.MeasurementSourceInventoryList;
import com.stellar.platform.desktop.ui.inventory.ProcessingActionBar;
import com.stellar.platform.desktop.ui.inventory.SourceInventoryWorkspace;
import com.stellar.platform.desktop.validation.InputValidationResult;
import com.stellar.platform.desktop.ui.StellarIcons;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ProjectFilesController implements AppServiceAware, Refreshable, DesktopPageLifecycle {

    @FXML
    private MeasurementSourceInventoryList measurementSourceList;
    @FXML
    private TableView<WorkspaceFile> definitionTable;
    @FXML
    private TableColumn<WorkspaceFile, String> defNameCol;
    @FXML
    private TableColumn<WorkspaceFile, String> defTypeCol;
    @FXML
    private TableColumn<WorkspaceFile, String> defPathCol;
    @FXML
    private TableColumn<WorkspaceFile, String> defSizeCol;
    @FXML
    private TableColumn<WorkspaceFile, String> defStatusCol;
    @FXML
    private TableColumn<WorkspaceFile, Void> defRemoveCol;
    @FXML
    private Label validationLabel;
    @FXML
    private Label shellSummaryLabel;
    @FXML
    private Label workspaceTitleLabel;
    @FXML
    private Label measurementSummaryLabel;
    @FXML
    private Label definitionSummaryLabel;
    @FXML
    private Label inventorySummaryLabel;
    @FXML
    private Label mappingSummaryLabel;
    @FXML
    private DbcMappingTablePane dbcMappingTablePane;
    @FXML
    private Label measurementCountBadge;
    @FXML
    private Label definitionCountBadge;
    @FXML
    private Label measurementEmptyLabel;
    @FXML
    private Label definitionEmptyLabel;
    @FXML
    private Label addResultLabel;
    @FXML
    private SourceInventoryWorkspace sourceInventoryWorkspace;
    @FXML
    private ChannelSelectionPane channelSelectionPane;
    @FXML
    private ProcessingActionBar processingActionBar;
    @FXML
    private ScrollPane sourcesWorkspace;
    @FXML
    private StackPane workspaceHost;
    @FXML
    private HBox workspaceNavBar;
    @FXML
    private HBox sourceDecodeModeStrip;
    @FXML
    private Label decodeReadinessLabel;
    @FXML
    private VBox sourcesEmptyState;
    @FXML
    private HBox sourcesToolbar;
    @FXML
    private HBox sourcesWorkbench;
    @FXML
    private HBox sourcesActionBar;
    @FXML
    private Label sourcesReadinessLabel;
    @FXML
    private Button continueToDecodeButton;
    @FXML
    private Button sourcesEmptyBrowseButton;
    @FXML
    private VBox sourceDetailsInspector;
    @FXML
    private Label sourceDetailsTitle;
    @FXML
    private Label sourceDetailsFacts;
    @FXML
    private Label sourceDetailsLocation;
    @FXML
    private ToggleGroup projectFilesWorkspaceGroup;
    @FXML
    private ToggleButton sourcesWorkspaceTab;
    @FXML
    private ToggleButton sourceInventoryWorkspaceTab;
    @FXML
    private ToggleButton sourceAutoDecodeButton;
    @FXML
    private ToggleButton sourceCustomDecodeButton;
    @FXML
    private TextField measurementSearchField;
    @FXML
    private TextField definitionSearchField;
    @FXML
    private ToggleButton dbcMappingWorkspaceTab;
    @FXML
    private Label processingHintLabel;
    @FXML
    private Label workflowLabel;

    private DesktopAppService appService;
    private MainShellController shell;
    private boolean refreshing;
    private BooleanSupplier confirmMultiFileAddForTest;
    private Function<FileChooser, List<File>> openDialogForTest;
    private Runnable inventoryModelListener;
    private Runnable matchingModelListener;
    private Runnable sourceAvailabilityListener;
    private ProjectFilesWorkspaceHost workspaceHostSwitcher;
    private boolean syncingWorkspaceTabs;
    private ProjectFilesWorkspaceKind activeWorkspace = ProjectFilesWorkspaceKind.SOURCES;
    /** Quick Decode becomes the default only when the Decision Workspace is first opened. */
    private boolean decisionWorkspaceModeInitialized;
    private List<WorkspaceFile> definitionSnapshot = List.of();

    @Override
    public void dispose() {
        if (appService == null) {
            return;
        }
        if (inventoryModelListener != null) {
            appService.sourceInventoryModel().removeListener(inventoryModelListener);
            inventoryModelListener = null;
        }
        if (matchingModelListener != null) {
            appService.definitionMatchingModel().removeListener(matchingModelListener);
            matchingModelListener = null;
        }
        if (sourceAvailabilityListener != null) {
            appService.sourceAvailabilityModel().removeListener(sourceAvailabilityListener);
            sourceAvailabilityListener = null;
        }
    }

    @Override
    public void setAppService(DesktopAppService appService, MainShellController shell) {
        this.appService = appService;
        this.shell = shell;
        wireDefinitionColumns();
        if (measurementSourceList != null) {
            measurementSourceList.bind(appService, appService.sourceInventoryModel());
            measurementSourceList.setOnRemove(file -> {
                // Project-scoped removal only: the measurement file on disk is never touched.
                appService.removeFile(file.projectSourceId().value());
                refresh();
            });
            measurementSourceList.setOnLocate(this::onLocateSource);
            measurementSourceList.setOnRetry(this::onRetrySource);
            measurementSourceList.setOnSelectionCommitted(this::refresh);
            measurementSourceList.getSelectionModel().selectedItemProperty()
                    .addListener((obs, oldValue, newValue) -> updateSourceDetailsInspector(newValue));
            if (measurementSearchField != null) {
                measurementSearchField.textProperty().addListener((obs, oldValue, newValue) -> {
                    measurementSourceList.setFilterText(newValue);
                    updateMeasurementCountBadge();
                });
            }
        }
        if (definitionSearchField != null) {
            definitionSearchField.textProperty().addListener((obs, oldValue, newValue) ->
                    applyDefinitionFilter(currentSelectedDefinitionId()));
        }
        if (sourceInventoryWorkspace != null) {
            sourceInventoryWorkspace.bind(appService, this::onAddMeasurement);
            // Do not eagerly construct/skin Quick Decode during Project Files startup. The first
            // Decision Workspace entry selects it as the product default (see showWorkspace).
            decisionWorkspaceModeInitialized = false;
            sourceInventoryWorkspace.setOnProceedToProcessing(this::onProceedToProcessing);
            sourceInventoryWorkspace.setOnDecisionModeChanged(mode -> {
                syncDecisionWorkspaceKindToMode(mode);
                updateDecodeModePresentation(mode);
                updateWorkspaceBreadcrumb(activeWorkspace);
            });
            sourceInventoryWorkspace.setMinHeight(0);
            sourceInventoryWorkspace.setMaxHeight(Double.MAX_VALUE);
        }
        if (dbcMappingTablePane != null) {
            dbcMappingTablePane.setMinHeight(0);
            dbcMappingTablePane.setMaxHeight(Double.MAX_VALUE);
            VBox.setVgrow(dbcMappingTablePane, Priority.ALWAYS);
        }
        if (sourcesWorkspace != null) {
            sourcesWorkspace.setMinHeight(0);
            sourcesWorkspace.setMaxHeight(Double.MAX_VALUE);
            installMeasurementDropTarget(sourcesWorkspace);
        }
        if (sourceAvailabilityListener != null) {
            appService.sourceAvailabilityModel().removeListener(sourceAvailabilityListener);
        }
        sourceAvailabilityListener = () -> runOnFxThread(this::refreshInventoryPresentation);
        appService.sourceAvailabilityModel().addListener(sourceAvailabilityListener);
        if (workspaceHost != null) {
            VBox.setVgrow(workspaceHost, Priority.ALWAYS);
            BorderPane.setAlignment(workspaceHost, javafx.geometry.Pos.CENTER);
        }
        retirePrematureProcessingUi();
        configureWorkspaceNavigation();
        updateDecodeModePresentation(DecisionWorkspaceMode.QUICK_DECODE);
        if (channelSelectionPane != null) {
            // Keep models wired for future Processing Selection workspace; UI retired for now.
            channelSelectionPane.setModels(
                    appService.sourceInventoryModel(),
                    appService.channelSelectionModel());
            channelSelectionPane.setOnSelectionChanged(this::applyProcessingControlState);
            channelSelectionPane.setVisible(false);
            channelSelectionPane.setManaged(false);
        }
        if (processingActionBar != null) {
            processingActionBar.setOnProcess(this::onStartProcessing);
            processingActionBar.setOnClear(() -> {
                appService.channelSelectionModel().clearSelection();
                applyProcessingControlState();
            });
            processingActionBar.setOnCancel(() -> appService.cancelProcessing());
            processingActionBar.setOnDetails(() -> shell.openRoute(WorkspaceRoute.REVIEW_AND_PROCESS));
            processingActionBar.setVisible(false);
            processingActionBar.setManaged(false);
        }
        if (inventoryModelListener != null) {
            appService.sourceInventoryModel().removeListener(inventoryModelListener);
        }
        inventoryModelListener = () -> {
            if (Platform.isFxApplicationThread()) {
                refreshInventoryPresentation();
            } else {
                Platform.runLater(this::refreshInventoryPresentation);
            }
        };
        appService.sourceInventoryModel().addListener(inventoryModelListener);
        if (matchingModelListener != null) {
            appService.definitionMatchingModel().removeListener(matchingModelListener);
        }
        matchingModelListener = () -> {
            if (Platform.isFxApplicationThread()) {
                refreshMappingPresentation();
            } else {
                Platform.runLater(this::refreshMappingPresentation);
            }
        };
        appService.definitionMatchingModel().addListener(matchingModelListener);
        if (dbcMappingTablePane != null) {
            // Mapping actions must NOT trigger a full Project Files rebuild.
            dbcMappingTablePane.bind(appService, this::refreshMappingPresentation);
        }
        updateProcessingControls();
        showWorkspace(ProjectFilesWorkspaceKind.SOURCES);
        definitionTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (refreshing || newVal == null) {
                return;
            }
            String currentId = appService.workspace().selectedDefinition().map(WorkspaceFile::id).orElse(null);
            if (!Objects.equals(currentId, newVal.id())) {
                appService.selectDefinition(newVal.id());
            }
            if (measurementSourceList != null) {
                measurementSourceList.refresh();
            }
        });
    }

    /**
     * Sources primary action. Enabled only once the project owns at least one usable source, and
     * always lands on the Decode stage rather than on a decode strategy directly.
     */
    @FXML
    private void onContinueToDecode() {
        if (appService == null || !appService.canContinueToDecode()) {
            return;
        }
        if (appService.prepareDirectProcessingSelection()) {
            appService.startLocalProcessing(Platform::runLater, shell == null ? () -> { } : shell::onProcessingUiTick);
            return;
        }
        if (shell != null) {
            shell.openRoute(WorkspaceRoute.DECODE);
        } else {
            openDecodeWorkspace();
        }
    }

    /**
     * Drag/drop add for measurement sources. Uses exactly the same lightweight add path as the
     * file chooser, so nothing extra is parsed, decoded or processed.
     */
    private void installMeasurementDropTarget(javafx.scene.Node target) {
        target.setOnDragOver(event -> {
            if (event.getGestureSource() != target && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });
        target.setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles()) {
                target.getStyleClass().add("sources-drop-active");
            }
            event.consume();
        });
        target.setOnDragExited(event -> {
            target.getStyleClass().remove("sources-drop-active");
            event.consume();
        });
        target.setOnDragDropped(event -> {
            target.getStyleClass().remove("sources-drop-active");
            boolean handled = false;
            if (appService != null && event.getDragboard().hasFiles()) {
                List<Path> paths = event.getDragboard().getFiles().stream().map(File::toPath).toList();
                if (!paths.isEmpty()) {
                    addMeasurementPaths(paths);
                    handled = true;
                }
            }
            event.setDropCompleted(handled);
            event.consume();
        });
    }

    /**
     * Repoints an unavailable source at the file the engineer located. The project source identity
     * is preserved, so this source keeps its inventory, mappings and processed artifacts.
     */
    private void onLocateSource(WorkspaceFile file) {
        if (appService == null || file == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Locate " + file.fileName());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                file.kind().displayName(), "*." + file.kind().displayName().toLowerCase(java.util.Locale.ROOT)));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        File located = chooser.showOpenDialog(window());
        if (located == null) {
            return;
        }
        var outcome = appService.relocateSourceDetailed(file.projectSourceId(), located.toPath());
        if (outcome == com.stellar.platform.desktop.presentation.RelocateSourceResult.DIFFERENT_CONTENT) {
            showTransientNotice(located.getName()
                    + " is not the same measurement. StellarAI will not attach processed analysis to different data.");
        } else if (!outcome.accepted()) {
            showTransientNotice(file.fileName()
                    + " could not be relocated to " + located.getName()
                    + ". Choose the same measurement in its new location.");
        }
        refresh();
    }

    /** Re-runs the lightweight inspection for a single source that previously failed. */
    private void onRetrySource(WorkspaceFile file) {
        if (appService == null || file == null) {
            return;
        }
        appService.startSourceInventory(file, Platform::runLater);
        refresh();
    }

    /**
     * Read-only inspector for the selected source. Renders only metadata the application already
     * holds — duration and capture start are not exposed by the inventory model, so they are
     * deliberately absent rather than invented.
     */
    private void updateSourceDetailsInspector(WorkspaceFile file) {
        if (sourceDetailsInspector == null) {
            return;
        }
        if (file == null || appService == null || !file.kind().measurement()) {
            sourceDetailsInspector.setVisible(false);
            sourceDetailsInspector.setManaged(false);
            return;
        }
        SourceStatusPresenter.View status = appService.sourceStatusView(file);
        sourceDetailsTitle.setText(file.fileName());
        StringBuilder facts = new StringBuilder();
        if (MeasurementSourceCapability.supportsDirectProcessing(file.kind())) {
            facts.append("Type ").append(file.kind().displayName());
            String detected = file.detectedFormatLabel();
            if (detected != null && !detected.isBlank()) {
                facts.append("   ·   Detected ").append(detected);
            }
            if (file.status() == com.stellar.platform.desktop.project.FileValidationStatus.UNSUPPORTED) {
                facts.append("   ·   Processing Unavailable");
            } else {
                facts.append("   ·   Processing Direct");
                facts.append("   ·   DBC Not required");
            }
            facts.append("   ·   Size ").append(UiFormat.bytes(file.sizeBytes()));
            facts.append("   ·   Status ").append(status.badgeText());
            sourceDetailsFacts.setText(facts.toString());
            sourceDetailsLocation.setText("Location  " + file.displayPath());
            sourceDetailsInspector.setVisible(true);
            sourceDetailsInspector.setManaged(true);
            return;
        }
        facts.append("Detected format ").append(file.formatDisplayName());
        String extension = originalExtension(file);
        if (!extension.isBlank() && !standardMeasurementExtension(extension)) {
            facts.append("   ·   Original extension ").append(extension.toUpperCase(java.util.Locale.ROOT));
        }
        facts.append("   ·   Size ").append(UiFormat.bytes(file.sizeBytes()));
        if (file.status() == com.stellar.platform.desktop.project.FileValidationStatus.UNSUPPORTED) {
            facts.append("   ·   Processing mode Unavailable");
        } else if (file.kind() == FileKind.MEASUREMENT_ASC || file.kind() == FileKind.MEASUREMENT_BLF) {
            facts.append("   ·   Processing mode CAN decode");
        } else if (file.kind() == FileKind.MEASUREMENT_MF4) {
            facts.append("   ·   Processing mode MF4 decode");
        }
        appService.sourceInventoryModel().inventoryFor(file.id()).ifPresent(inventory -> {
            if (file.status() == com.stellar.platform.desktop.project.FileValidationStatus.UNSUPPORTED) {
                return;
            }
            long channels = inventory.totalCanChannels();
            facts.append("   ·   Channels ").append(channels);
            facts.append("   ·   Unique CAN IDs ").append(inventory.globalUniqueCanIdentityCount());
            inventory.diagnostics().stream()
                    .filter(d -> d.code() == com.stellar.platform.domain.inventory.CanInventoryDiagnosticCode.MALFORMED_RECORD)
                    .map(com.stellar.platform.domain.inventory.CanInventoryDiagnostic::message)
                    .filter(message -> message != null && !message.isBlank())
                    .findFirst()
                    .ifPresent(message -> facts.append("   ·   ").append(message));
        });
        facts.append("   ·   Status ").append(status.badgeText());
        sourceDetailsFacts.setText(facts.toString());
        sourceDetailsLocation.setText("Location  " + file.displayPath());
        sourceDetailsInspector.setVisible(true);
        sourceDetailsInspector.setManaged(true);
    }

    private void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    @FXML
    private void onOpenAutoDecode() {
        openDecodeMode(DecisionWorkspaceMode.QUICK_DECODE);
    }

    @FXML
    private void onOpenCustomDecode() {
        openDecodeMode(DecisionWorkspaceMode.DETAILED_MAPPING);
    }

    private void openDecodeMode(DecisionWorkspaceMode mode) {
        if (sourceInventoryWorkspace == null) {
            return;
        }
        if (mode == DecisionWorkspaceMode.QUICK_DECODE) {
            sourceInventoryWorkspace.showQuickDecode();
        } else {
            sourceInventoryWorkspace.showDetailedMapping();
        }
        decisionWorkspaceModeInitialized = true;
        showWorkspace(workspaceKindForMode(mode));
    }

    @FXML
    private void onShowSourcesWorkspace() {
        showWorkspace(ProjectFilesWorkspaceKind.SOURCES);
    }

    @FXML
    private void onShowSourceInventoryWorkspace() {
        DecisionWorkspaceMode mode = appService == null
                ? DecisionWorkspaceMode.QUICK_DECODE
                : appService.decisionWorkspaceMode();
        showWorkspace(workspaceKindForMode(mode));
    }

    @FXML
    private void onShowDbcMappingWorkspace() {
        showWorkspace(ProjectFilesWorkspaceKind.DBC_MAPPING);
    }

    public void openSourcesWorkspace() {
        showWorkspace(ProjectFilesWorkspaceKind.SOURCES);
    }

    public void openSourceInventoryWorkspace() {
        showWorkspace(ProjectFilesWorkspaceKind.SOURCE_INVENTORY);
    }

    /**
     * Decode navigation entry. Opens whichever decode strategy is currently selected —
     * Auto Decode and Custom Decode keep their existing, distinct behaviour.
     */
    public void openDecodeWorkspace() {
        DecisionWorkspaceMode mode = appService == null
                ? DecisionWorkspaceMode.QUICK_DECODE
                : appService.decisionWorkspaceMode();
        showWorkspace(workspaceKindForMode(mode));
    }

    /** Public entry for Processing plan review navigation. */
    public void openDbcMappingWorkspace() {
        openDbcMappingWorkspace(null);
    }

    public void openDbcMappingWorkspace(com.stellar.platform.desktop.project.SourceChannelKey channel) {
        showWorkspace(ProjectFilesWorkspaceKind.DBC_MAPPING);
        if (channel != null && sourceInventoryWorkspace != null) {
            sourceInventoryWorkspace.focusChannel(channel);
        }
    }

    /**
     * Presentation-only workspace switching. Must not parse, inventory, or rematch.
     */
    private void configureWorkspaceNavigation() {
        if (sourcesWorkspace == null || sourceInventoryWorkspace == null || dbcMappingTablePane == null) {
            return;
        }
        workspaceHostSwitcher = new ProjectFilesWorkspaceHost(
                sourcesWorkspace, sourceInventoryWorkspace, dbcMappingTablePane);
        if (sourcesWorkspaceTab != null) {
            sourcesWorkspaceTab.setGraphic(StellarIcons.file());
            sourcesWorkspaceTab.setGraphicTextGap(6);
        }
        if (sourceInventoryWorkspaceTab != null) {
            sourceInventoryWorkspaceTab.setGraphic(StellarIcons.activity());
            sourceInventoryWorkspaceTab.setGraphicTextGap(6);
        }
        if (sourceAutoDecodeButton != null) {
            sourceAutoDecodeButton.setGraphic(StellarIcons.autoDecode());
            sourceAutoDecodeButton.setGraphicTextGap(7);
            sourceAutoDecodeButton.setTooltip(new Tooltip(
                    "Use the existing automatic recommendation path and decode ready channels."));
        }
        if (sourceCustomDecodeButton != null) {
            sourceCustomDecodeButton.setGraphic(StellarIcons.customDecode());
            sourceCustomDecodeButton.setGraphicTextGap(7);
            sourceCustomDecodeButton.setTooltip(new Tooltip(
                    "Use the existing engineer-controlled channel and DBC mapping path."));
        }
        if (dbcMappingWorkspaceTab != null) {
            dbcMappingWorkspaceTab.setGraphic(StellarIcons.alignment());
            dbcMappingWorkspaceTab.setGraphicTextGap(6);
        }
        if (projectFilesWorkspaceGroup != null) {
            projectFilesWorkspaceGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
                if (syncingWorkspaceTabs) {
                    return;
                }
                if (newToggle == null) {
                    syncingWorkspaceTabs = true;
                    try {
                        if (oldToggle != null) {
                            oldToggle.setSelected(true);
                        } else if (sourcesWorkspaceTab != null) {
                            sourcesWorkspaceTab.setSelected(true);
                        }
                    } finally {
                        syncingWorkspaceTabs = false;
                    }
                }
            });
        }
    }

    private void showWorkspace(ProjectFilesWorkspaceKind workspace) {
        if (workspace == null) {
            return;
        }
        activeWorkspace = workspace;
        if (shell != null) {
            shell.onProjectFilesWorkspaceChanged(workspace == ProjectFilesWorkspaceKind.SOURCES
                    ? NavigationPage.SOURCES
                    : NavigationPage.DECODE);
        }
        if (workspaceHostSwitcher == null) {
            if (sourcesWorkspace == null || sourceInventoryWorkspace == null || dbcMappingTablePane == null) {
                return;
            }
            workspaceHostSwitcher = new ProjectFilesWorkspaceHost(
                    sourcesWorkspace, sourceInventoryWorkspace, dbcMappingTablePane);
        }
        // Presentation-only: never parse, inventory, or rematch here. Mode activation only swaps
        // already-owned presentation roots; Quick Decode lazily builds its virtualized control tree.
        if (sourceInventoryWorkspace != null) {
            if (workspace == ProjectFilesWorkspaceKind.SOURCE_INVENTORY && !decisionWorkspaceModeInitialized) {
                sourceInventoryWorkspace.showQuickDecode();
                decisionWorkspaceModeInitialized = true;
            } else if (workspace == ProjectFilesWorkspaceKind.DBC_MAPPING) {
                // Explicit mapping navigation means the engineer asked for the channel study path.
                sourceInventoryWorkspace.showDetailedMapping();
                decisionWorkspaceModeInitialized = true;
            }
        }
        workspaceHostSwitcher.show(workspace);
        ensureWorkspaceNavVisible();
        retirePrematureProcessingUi();
        syncingWorkspaceTabs = true;
        try {
            if (sourcesWorkspaceTab != null) {
                sourcesWorkspaceTab.setSelected(workspace == ProjectFilesWorkspaceKind.SOURCES);
                sourcesWorkspaceTab.getStyleClass().remove("project-files-workspace-tab-active");
                if (workspace == ProjectFilesWorkspaceKind.SOURCES) {
                    sourcesWorkspaceTab.getStyleClass().add("project-files-workspace-tab-active");
                }
            }
            if (sourceInventoryWorkspaceTab != null) {
                boolean decision = workspace.isDecisionWorkspace();
                sourceInventoryWorkspaceTab.setSelected(decision);
                sourceInventoryWorkspaceTab.getStyleClass().remove("project-files-workspace-tab-active");
                if (decision) {
                    sourceInventoryWorkspaceTab.getStyleClass().add("project-files-workspace-tab-active");
                }
            }
            if (dbcMappingWorkspaceTab != null) {
                dbcMappingWorkspaceTab.setSelected(false);
                dbcMappingWorkspaceTab.setVisible(false);
                dbcMappingWorkspaceTab.setManaged(false);
                dbcMappingWorkspaceTab.getStyleClass().remove("project-files-workspace-tab-active");
            }
        } finally {
            syncingWorkspaceTabs = false;
        }
        updateSourceModeStripVisibility(workspace);
        updateDecodeModePresentation(presentationModeFor(workspace));
        updateWorkspaceBreadcrumb(workspace);
        if (appService != null) {
            updateSummary(
                    appService.workspace().measurementFiles(),
                    appService.workspace().definitionFiles().size());
            if (workspace == ProjectFilesWorkspaceKind.SOURCES) {
                // Availability is checked only where it is needed. Resume routing never does this.
                appService.refreshSourceAvailability(Platform::runLater);
            } else {
                updateDecodeReadinessSummary();
            }
        }
    }

    /**
     * The Auto/Custom chooser belongs to the Decode stage and must stay visible while decoding.
     * Sources is only about measurement data, so it never shows a decode strategy.
     */
    private void updateSourceModeStripVisibility(ProjectFilesWorkspaceKind workspace) {
        boolean decode = workspace != null && workspace.isDecisionWorkspace();
        if (sourceDecodeModeStrip != null) {
            sourceDecodeModeStrip.setVisible(decode);
            sourceDecodeModeStrip.setManaged(decode);
        }
        boolean sources = workspace == ProjectFilesWorkspaceKind.SOURCES;
        if (sourcesActionBar != null) {
            sourcesActionBar.setVisible(sources);
            sourcesActionBar.setManaged(sources);
        }
    }

    private void updateWorkspaceBreadcrumb(ProjectFilesWorkspaceKind workspace) {
        if (workspaceTitleLabel == null || workspace == null) {
            return;
        }
        workspaceTitleLabel.setText(switch (workspace) {
            case SOURCES -> "Sources";
            case SOURCE_INVENTORY, DBC_MAPPING -> modeDisplayName(
                    appService == null ? DecisionWorkspaceMode.QUICK_DECODE : appService.decisionWorkspaceMode());
        });
    }

    /**
     * Sources and Decode are permanent shell destinations and Auto/Custom are in-page strategies,
     * so the old in-page workspace tab strip is retired. Its toggles stay wired and keep their
     * managed state, which other presentation code and tests still read.
     */
    private void ensureWorkspaceNavVisible() {
        if (workspaceNavBar != null) {
            workspaceNavBar.setVisible(false);
            workspaceNavBar.setManaged(false);
        }
        if (sourcesWorkspaceTab != null) {
            sourcesWorkspaceTab.setVisible(true);
            sourcesWorkspaceTab.setManaged(true);
            sourcesWorkspaceTab.setDisable(false);
        }
        if (sourceInventoryWorkspaceTab != null) {
            boolean showDecodeTab = activeWorkspace != null && activeWorkspace.isDecisionWorkspace();
            sourceInventoryWorkspaceTab.setVisible(showDecodeTab);
            sourceInventoryWorkspaceTab.setManaged(showDecodeTab);
            sourceInventoryWorkspaceTab.setDisable(false);
        }
        if (dbcMappingWorkspaceTab != null) {
            dbcMappingWorkspaceTab.setVisible(false);
            dbcMappingWorkspaceTab.setManaged(false);
        }
    }

    private void retirePrematureProcessingUi() {
        applyLegacySelectionBarVisibility();
        if (channelSelectionPane != null) {
            channelSelectionPane.setVisible(false);
            channelSelectionPane.setManaged(false);
        }
    }

    private void applyLegacySelectionBarVisibility() {
        if (processingActionBar == null) {
            return;
        }
        boolean show = activeWorkspace != null && activeWorkspace.showLegacySelectionBar();
        processingActionBar.setVisible(show);
        processingActionBar.setManaged(show);
    }

    ProcessingActionBar processingActionBarForTest() {
        return processingActionBar;
    }

    ProjectFilesWorkspaceKind activeWorkspaceForTest() {
        return activeWorkspace;
    }

    // The Sources body lives inside a ScrollPane, whose content is not reachable by CSS lookup
    // until the pane is skinned. Tests read these nodes directly instead of forcing a render.

    VBox sourcesEmptyStateForTest() {
        return sourcesEmptyState;
    }

    HBox sourcesToolbarForTest() {
        return sourcesToolbar;
    }

    HBox sourcesWorkbenchForTest() {
        return sourcesWorkbench;
    }

    Button sourcesEmptyBrowseButtonForTest() {
        return sourcesEmptyBrowseButton;
    }

    HBox sourcesActionBarForTest() {
        return sourcesActionBar;
    }

    Button continueToDecodeButtonForTest() {
        return continueToDecodeButton;
    }

    Label sourcesReadinessLabelForTest() {
        return sourcesReadinessLabel;
    }

    Label inventorySummaryLabelForTest() {
        return inventorySummaryLabel;
    }

    Label addResultLabelForTest() {
        return addResultLabel;
    }

    List<String> definitionNamesForTest() {
        if (definitionTable == null || definitionTable.getItems() == null) {
            return List.of();
        }
        return definitionTable.getItems().stream().map(WorkspaceFile::fileName).toList();
    }

    void addDefinitionPathsForTest(List<Path> paths) {
        if (appService == null || paths == null || paths.isEmpty()) {
            return;
        }
        ProjectFilesAddResult result = appService.addDefinitionPaths(paths);
        showAddResult(result);
        if (!result.added().isEmpty()) {
            WorkspaceFile first = result.added().getFirst();
            if (first.kind() == FileKind.DEFINITION_DBC) {
                appService.selectDefinition(first.id());
            }
        }
        refresh();
    }

    void addMeasurementPathsForTest(List<Path> paths) {
        addMeasurementPaths(paths);
    }

    MeasurementSourceInventoryList measurementSourceListForTest() {
        return measurementSourceList;
    }

    Label measurementCountBadgeForTest() {
        return measurementCountBadge;
    }

    void setConfirmMultiFileAddForTest(BooleanSupplier decision) {
        confirmMultiFileAddForTest = decision;
    }

    void setOpenDialogForTest(Function<FileChooser, List<File>> dialog) {
        openDialogForTest = dialog;
    }

    private List<File> chooseFiles(FileChooser chooser) {
        if (openDialogForTest != null) {
            return openDialogForTest.apply(chooser);
        }
        return chooser.showOpenMultipleDialog(window());
    }

    private static ProjectFilesWorkspaceKind workspaceKindForMode(DecisionWorkspaceMode mode) {
        return mode == DecisionWorkspaceMode.DETAILED_MAPPING
                ? ProjectFilesWorkspaceKind.DBC_MAPPING
                : ProjectFilesWorkspaceKind.SOURCE_INVENTORY;
    }

    private void syncDecisionWorkspaceKindToMode(DecisionWorkspaceMode mode) {
        if (activeWorkspace == null || !activeWorkspace.isDecisionWorkspace()) {
            return;
        }
        ProjectFilesWorkspaceKind desired = workspaceKindForMode(mode);
        if (activeWorkspace == desired) {
            return;
        }
        activeWorkspace = desired;
        if (shell != null) {
            shell.onProjectFilesWorkspaceChanged(NavigationPage.DECODE);
        }
    }

    /**
     * Keeps the Sources landing-page default presentation on Auto Decode without mutating the
     * underlying decision-mode state. Once a decode workspace has actually been opened, the
     * presentation follows the real selected mode. This is intentionally presentation-only so
     * existing QUICK_DECODE / DETAILED_MAPPING processing semantics remain unchanged.
     */
    private DecisionWorkspaceMode presentationModeFor(ProjectFilesWorkspaceKind workspace) {
        if (!decisionWorkspaceModeInitialized && workspace == ProjectFilesWorkspaceKind.SOURCES) {
            return DecisionWorkspaceMode.QUICK_DECODE;
        }
        return appService == null
                ? DecisionWorkspaceMode.QUICK_DECODE
                : appService.decisionWorkspaceMode();
    }

    private void updateDecodeModePresentation(DecisionWorkspaceMode mode) {
        DecisionWorkspaceMode effective = mode == null ? DecisionWorkspaceMode.QUICK_DECODE : mode;
        boolean auto = effective == DecisionWorkspaceMode.QUICK_DECODE;
        if (sourceAutoDecodeButton != null) {
            sourceAutoDecodeButton.setSelected(auto);
        }
        if (sourceCustomDecodeButton != null) {
            sourceCustomDecodeButton.setSelected(!auto);
        }
        if (sourceInventoryWorkspaceTab != null) {
            sourceInventoryWorkspaceTab.setText(modeDisplayName(effective));
            sourceInventoryWorkspaceTab.setGraphic(auto ? StellarIcons.autoDecode() : StellarIcons.customDecode());
        }
    }

    private static String modeDisplayName(DecisionWorkspaceMode mode) {
        return mode == DecisionWorkspaceMode.DETAILED_MAPPING ? "Custom Decode" : "Auto Decode";
    }

    /**
     * Sources presentation state: empty state versus source workbench, and the single
     * Continue to Decode action. One unusable source never blocks the rest of the project.
     */
    private void updateSourcesPresentation() {
        if (appService == null) {
            return;
        }
        List<WorkspaceFile> measurements = appService.workspace().measurementFiles();
        boolean empty = measurements.isEmpty();
        setNodeShown(sourcesEmptyState, empty);
        setNodeShown(sourcesToolbar, !empty);
        setNodeShown(sourcesWorkbench, !empty);

        int usable = appService.usableDecodeSources().size();
        boolean canContinue = usable > 0;
        if (continueToDecodeButton != null) {
            boolean direct = appService.directProcessingActionAvailable();
            continueToDecodeButton.setText(direct ? "Direct Processing" : "Continue to Decode");
            continueToDecodeButton.setDisable(!canContinue);
            continueToDecodeButton.setTooltip(new Tooltip(!canContinue
                    ? "Add at least one readable measurement source to continue."
                    : direct
                    ? "Process this source directly. DBC is not required."
                    : "Continue to the Decode stage with the usable sources in this project."));
        }
        if (sourcesReadinessLabel != null) {
            sourcesReadinessLabel.setText(sourcesReadinessText(measurements, usable));
        }
        if (measurementSourceList != null) {
            updateSourceDetailsInspector(measurementSourceList.getSelectionModel().getSelectedItem());
        }
        notifyShellChrome();
    }

    private void notifyShellChrome() {
        if (shell != null) {
            shell.refreshProjectChrome();
        }
    }

    private String sourcesReadinessText(List<WorkspaceFile> measurements, int usable) {
        if (measurements.isEmpty()) {
            return "No measurement sources yet.";
        }
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (WorkspaceFile file : measurements) {
            counts.merge(appService.sourceStatusView(file).footerLabel(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(measurements.size()).append(measurements.size() == 1 ? " source" : " sources");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 0) {
                sb.append("   ·   ").append(entry.getValue()).append(' ').append(entry.getKey());
            }
        }
        if (usable == 0) {
            sb.append("   ·   Add a readable source to continue");
        }
        int pendingDirect = 0;
        for (WorkspaceFile file : appService.directMeasurementFiles()) {
            if (file.status().processingAllowed() && !appService.sourceProcessed(file.projectSourceId())) {
                pendingDirect++;
            }
        }
        if (pendingDirect > 0 && !appService.directProcessingActionAvailable()) {
            sb.append("   ·   ");
            sb.append(pendingDirect == 1
                    ? "1 file needs Direct Processing"
                    : pendingDirect + " files need Direct Processing");
        }
        return sb.toString();
    }

    /**
     * Decode readiness in engineer-facing terms. Derived from the existing decision readiness
     * semantics only — no new decode rules are introduced here.
     */
    private void updateDecodeReadinessSummary() {
        if (decodeReadinessLabel == null || appService == null) {
            return;
        }
        DesktopAppService.DecisionProcessingReadiness readiness = appService.decisionProcessingReadiness();
        if (readiness.requiredChannels() == 0) {
            decodeReadinessLabel.setText(readiness.hint());
            return;
        }
        int configured = readiness.dbcConfiguredChannels();
        StringBuilder sb = new StringBuilder();
        sb.append(readiness.requiredChannels())
                .append(readiness.requiredChannels() == 1 ? " channel" : " channels")
                .append("   ·   ").append(configured).append(" Configured");
        if (readiness.nativeOnlyChannels() > 0) {
            sb.append("   ·   ").append(readiness.nativeOnlyChannels()).append(" Ready with warning");
        }
        if (readiness.unresolvedChannels() > 0) {
            sb.append("   ·   ").append(readiness.unresolvedChannels()).append(" Needs Setup");
        }
        sb.append("   ·   ").append(readiness.canProceed() ? "Ready to process" : "Setup incomplete");
        decodeReadinessLabel.setText(sb.toString());
        decodeReadinessLabel.setTooltip(new Tooltip(
                readiness.canProceed() ? readiness.readyTooltip() : readiness.hint()));
    }

    private static void setNodeShown(javafx.scene.Node node, boolean shown) {
        if (node != null) {
            node.setVisible(shown);
            node.setManaged(shown);
        }
    }

    private void showTransientNotice(String message) {
        if (addResultLabel == null || message == null || message.isBlank()) {
            return;
        }
        addResultLabel.setText(message);
        addResultLabel.setVisible(true);
        addResultLabel.setManaged(true);
        Platform.runLater(() -> {
            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(6));
            pause.setOnFinished(e -> {
                addResultLabel.setVisible(false);
                addResultLabel.setManaged(false);
            });
            pause.play();
        });
    }

    private String currentSelectedDefinitionId() {
        return appService == null
                ? null
                : appService.workspace().selectedDefinition().map(WorkspaceFile::id).orElse(null);
    }

    private void applyDefinitionFilter(String selectedId) {
        if (definitionTable == null) {
            return;
        }
        String needle = definitionSearchField == null || definitionSearchField.getText() == null
                ? ""
                : definitionSearchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        List<WorkspaceFile> visible = needle.isBlank()
                ? definitionSnapshot
                : definitionSnapshot.stream()
                        .filter(file -> definitionMatches(file, needle))
                        .toList();
        ProjectFilesTableSync.replaceItemsAndSyncSelection(definitionTable, visible, selectedId);
        if (definitionCountBadge != null) {
            definitionCountBadge.setText(needle.isBlank()
                    ? String.valueOf(definitionSnapshot.size())
                    : visible.size() + " / " + definitionSnapshot.size());
        }
    }

    private void updateMeasurementCountBadge() {
        if (measurementCountBadge == null || measurementSourceList == null) {
            return;
        }
        boolean filtering = measurementSearchField != null
                && measurementSearchField.getText() != null
                && !measurementSearchField.getText().isBlank();
        int total = measurementSourceList.totalItemCount();
        int visible = measurementSourceList.visibleItemCount();
        measurementCountBadge.setText(filtering ? visible + " / " + total : String.valueOf(total));
    }

    private boolean definitionMatches(WorkspaceFile file, String needle) {
        if (file == null) {
            return false;
        }
        return containsNormalized(file.fileName(), needle)
                || containsNormalized(file.displayPath(), needle)
                || containsNormalized(typeLabel(file), needle)
                || containsNormalized(statusText(file), needle);
    }

    private static boolean containsNormalized(String value, String needle) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    private void wireDefinitionColumns() {
        defNameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fileName()));
        defTypeCol.setCellValueFactory(c -> new SimpleStringProperty(typeLabel(c.getValue())));
        defPathCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().displayPath()));
        defSizeCol.setCellValueFactory(c -> new SimpleStringProperty(UiFormat.bytes(c.getValue().sizeBytes())));
        defStatusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().projectInputStatusLabel()));
        installEllipsisTooltipColumn(defNameCol, false);
        installEllipsisTooltipColumn(defPathCol, true);
        installStatusTooltip(defStatusCol);
        installRemoveColumn(defRemoveCol);
    }

    private static void installEllipsisTooltipColumn(TableColumn<WorkspaceFile, String> column, boolean pathColumn) {
        if (column == null) {
            return;
        }
        column.setCellFactory(col -> new TableCell<>() {
            private final Tooltip tooltip = new Tooltip();

            {
                setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
                tooltip.setWrapText(true);
                tooltip.setPrefWidth(420);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                WorkspaceFile file = getTableRow() == null ? null : getTableRow().getItem();
                setText(item);
                String full = file == null
                        ? item
                        : pathColumn ? file.displayPath() : file.fileName();
                tooltip.setText(full);
                setTooltip(tooltip);
            }
        });
    }

    private void installRemoveColumn(TableColumn<WorkspaceFile, Void> column) {
        if (column == null) {
            return;
        }
        column.setSortable(false);
        column.setCellFactory(col -> new TableCell<>() {
            private final Button remove = new Button("Remove");

            {
                remove.getStyleClass().addAll("secondary-button", "compact-button", "project-remove-button");
                remove.setOnAction(e -> {
                    WorkspaceFile file = getTableRow() == null ? null : getTableRow().getItem();
                    if (file == null || appService == null) {
                        return;
                    }
                    appService.removeFile(file.definitionSourceId().value());
                    refresh();
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : remove);
            }
        });
    }

    private static void installStatusTooltip(TableColumn<WorkspaceFile, String> column) {
        column.setCellFactory(col -> new TableCell<>() {
            private final Tooltip tooltip = new Tooltip();

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item);
                WorkspaceFile file = getTableRow() == null ? null : getTableRow().getItem();
                String full = file == null ? item : statusText(file);
                tooltip.setText(full);
                tooltip.setWrapText(true);
                tooltip.setPrefWidth(420);
                setTooltip(tooltip);
            }
        });
    }

    private static String statusText(WorkspaceFile file) {
        if (file.validationMessage().isBlank()) {
            return file.projectInputStatusLabel();
        }
        return file.projectInputStatusLabel() + " — " + file.validationMessage();
    }

    private static String typeLabel(WorkspaceFile file) {
        if (file.detectedFormatLabel() != null && !file.detectedFormatLabel().isBlank()) {
            return file.formatDisplayName();
        }
        FileKind kind = file.kind();
        if (!kind.implemented()) {
            return kind.displayName() + " (Not yet implemented)";
        }
        return kind.displayName();
    }

    private static String originalExtension(WorkspaceFile file) {
        String name = file.fileName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1);
    }

    private static boolean standardMeasurementExtension(String extension) {
        String lower = extension.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("mf4") || lower.equals("mdf") || lower.equals("asc") || lower.equals("blf");
    }

    void requestAddMeasurement() {
        onAddMeasurement();
    }

    void requestAddDefinition() {
        onAddDefinition();
    }

    @FXML
    private void onAddMeasurement() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select measurement files");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(
                        "Measurement files (*.mf4, *.asc, *.blf, *.dat, *.log)",
                        "*.mf4", "*.MF4", "*.mdf", "*.MDF",
                        "*.asc", "*.ASC",
                        "*.blf", "*.BLF",
                        "*.dat", "*.DAT",
                        "*.log", "*.LOG"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );
        List<File> files = chooseFiles(chooser);
        if (files == null || files.isEmpty()) {
            return;
        }
        addMeasurementPaths(files.stream().map(File::toPath).toList());
    }

    /**
     * Single lightweight add path shared by Browse Files and drag/drop. Each path is validated
     * independently by the service, so an unsupported or unreadable file never discards its
     * valid peers, and adding a source never invalidates already processed sources.
     */
    private void addMeasurementPaths(List<Path> paths) {
        if (appService == null || paths == null || paths.isEmpty()) {
            return;
        }
        if (!confirmMeasurementAdd(paths.size())) {
            return;
        }
        ProjectFilesAddResult result = appService.addMeasurementPaths(paths, Platform::runLater);
        showAddResult(result);
        if (!result.added().isEmpty()) {
            WorkspaceFile first = result.added().getFirst();
            if (first.kind().measurement() && first.kind().implemented()) {
                appService.selectMeasurement(first.id());
            }
        }
        refresh();
    }

    private boolean confirmMeasurementAdd(int selectedCount) {
        if (!MeasurementAddConfirmation.requiresConfirmation(selectedCount)) {
            return true;
        }
        if (confirmMultiFileAddForTest != null) {
            return confirmMultiFileAddForTest.getAsBoolean();
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Add measurement files");
        alert.setHeaderText(MeasurementAddConfirmation.heading(selectedCount));
        alert.setContentText(MeasurementAddConfirmation.question(selectedCount));
        ButtonType add = new ButtonType("Add Files", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(add, cancel);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == add;
    }

    @FXML
    private void onAddDefinition() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select definition files");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("DBC", "*.dbc", "*.DBC"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );
        List<File> files = chooseFiles(chooser);
        if (files == null || files.isEmpty()) {
            return;
        }
        List<Path> paths = files.stream().map(File::toPath).toList();
        ProjectFilesAddResult result = appService.addDefinitionPaths(paths);
        showAddResult(result);
        if (!result.added().isEmpty()) {
            WorkspaceFile first = result.added().getFirst();
            if (first.kind() == FileKind.DEFINITION_DBC) {
                appService.selectDefinition(first.id());
            }
        }
        refresh();
    }

    @FXML
    private void onInspectSources() {
        if (appService == null) {
            return;
        }
        appService.inspectPendingSources(Platform::runLater);
        refresh();
    }

    private void showAddResult(ProjectFilesAddResult result) {
        if (addResultLabel == null) {
            return;
        }
        String message = result.summaryMessage();
        boolean show = result.addedCount() > 0
                || result.skippedCount() > 0
                || result.duplicateCount() > 0
                || result.warningCount() > 0;
        addResultLabel.setText(message);
        addResultLabel.setVisible(show);
        addResultLabel.setManaged(show);
        if (show) {
            // Transient notice — do not permanently reserve vertical shell space.
            Platform.runLater(() -> {
                javafx.animation.PauseTransition pause =
                        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(4));
                pause.setOnFinished(e -> {
                    addResultLabel.setVisible(false);
                    addResultLabel.setManaged(false);
                });
                pause.play();
            });
        }
    }

    @FXML
    private void onValidate() {
        InputValidationResult result = appService.validateCurrentSelection();
        if (result.processingAllowed()) {
            String warnings = result.warningMessages().stream().collect(Collectors.joining("\n"));
            if (warnings.isBlank()) {
                validationLabel.setText("Validation PASSED.");
            } else {
                validationLabel.setText("Validation PASSED WITH WARNINGS:\n" + warnings);
            }
        } else {
            validationLabel.setText("Validation failed:\n" + result.errorMessages().stream()
                    .collect(Collectors.joining("\n")));
        }
        refresh();
    }

    private void onProceedToProcessing() {
        if (appService != null) {
            appService.enterProcessingWorkspaceFromDecision();
        }
        if (shell != null) {
            shell.openRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
        }
    }

    @FXML
    private void onStartProcessing() {
        InputValidationResult result = appService.validateCurrentSelection();
        if (!result.valid()) {
            validationLabel.setText("Cannot start:\n" + String.join("\n", result.errorMessages()));
            refresh();
            return;
        }
        if (!appService.canStartLocalProcessing()) {
            validationLabel.setText("Cannot start:\n" + appService.processingSelectionHint());
            updateProcessingControls();
            return;
        }
        appService.startLocalProcessing(Platform::runLater, shell::onProcessingUiTick);
        applyProcessingControlState();
    }

    private void updateProcessingControls() {
        if (appService == null) {
            return;
        }
        appService.syncChannelSelection();
        applyProcessingControlState();
        if (channelSelectionPane != null) {
            channelSelectionPane.refresh();
        }
    }

    private void applyProcessingControlState() {
        if (appService == null) {
            return;
        }
        String hint = appService.processingSelectionHint();
        if (processingHintLabel != null) {
            processingHintLabel.setText(hint);
        }
        if (processingActionBar != null) {
            if (activeWorkspace != null && activeWorkspace.showLegacySelectionBar()) {
                ProjectProcessingContext.CompactProgressSnapshot progress = appService.compactProgress();
                processingActionBar.refresh(
                        appService.sourceInventoryModel(),
                        appService.channelSelectionModel(),
                        hint,
                        appService.canStartLocalProcessing(),
                        progress.running(),
                        progress.progressFraction(),
                        progress.processingChannelsLabel()
                );
            }
            applyLegacySelectionBarVisibility();
        }
    }

    @Override
    public void refresh() {
        if (refreshing || appService == null) {
            return;
        }
        refreshing = true;
        try {
            String desiredMeasId = appService.workspace().selectedMeasurement().map(WorkspaceFile::id).orElse(null);
            String desiredDefId = appService.workspace().selectedDefinition().map(WorkspaceFile::id).orElse(null);

            List<WorkspaceFile> measurements = appService.workspace().measurementFiles();
            List<WorkspaceFile> definitions = appService.workspace().definitionFiles();

            if (measurementSourceList != null) {
                measurementSourceList.replaceItems(measurements, desiredMeasId);
            }
            definitionSnapshot = List.copyOf(definitions);
            applyDefinitionFilter(desiredDefId);

            updateSummary(measurements, definitions.size());
        } finally {
            refreshing = false;
        }
        if (sourceInventoryWorkspace != null) {
            sourceInventoryWorkspace.refreshFromModel();
        }
        if (dbcMappingTablePane != null) {
            dbcMappingTablePane.refreshFromModel();
        }
        updateProcessingControls();
    }

    /**
     * Targeted Source Inventory update — inventory job progress must not rebuild
     * DBC Mapping or recreate Project Files tables unnecessarily.
     */
    private void refreshInventoryPresentation() {
        if (appService == null || refreshing) {
            return;
        }
        List<WorkspaceFile> measurements = appService.workspace().measurementFiles();
        if (measurementSourceList != null) {
            String desiredMeasId = appService.workspace().selectedMeasurement().map(WorkspaceFile::id).orElse(null);
            measurementSourceList.replaceItems(measurements, desiredMeasId);
        }
        updateSummary(measurements, appService.workspace().definitionFiles().size());
        if (sourceInventoryWorkspace != null) {
            sourceInventoryWorkspace.refreshFromModel();
        }
        ensureWorkspaceNavVisible();
        retirePrematureProcessingUi();
        updateProcessingControls();
    }

    /**
     * Targeted mapping presentation update — used after Confirm/Choose/mapping model changes.
     * Must not rebuild measurement/definition tables or Source Inventory.
     */
    private void refreshMappingPresentation() {
        if (appService == null || refreshing) {
            return;
        }
        updateSummary(
                appService.workspace().measurementFiles(),
                appService.workspace().definitionFiles().size());
        ensureWorkspaceNavVisible();
        retirePrematureProcessingUi();
        if (dbcMappingTablePane != null) {
            dbcMappingTablePane.refreshFromModel();
        }
        // Enrichment projection only — do not rebuild navigator / reparse inventory.
        if (sourceInventoryWorkspace != null) {
            sourceInventoryWorkspace.refreshEnrichmentOnly();
        }
    }

    private void updateSummary(List<WorkspaceFile> measurements, int definitionCount) {
        int measurementCount = measurements.size();
        String measText = measurementCount == 1 ? "1 Measurement File" : measurementCount + " Measurement Files";
        String defText = definitionCount == 1 ? "1 Definition" : definitionCount + " Definitions";
        String inventoryText = inventorySummaryText(measurements);
        if (measurementSummaryLabel != null) {
            measurementSummaryLabel.setText(measText);
        }
        if (definitionSummaryLabel != null) {
            definitionSummaryLabel.setText(defText);
        }
        if (inventorySummaryLabel != null) {
            inventorySummaryLabel.setText(inventoryText);
        }
        if (mappingSummaryLabel != null) {
            // Detailed mapping metrics belong inside DBC Mapping workspace, not the shell.
            mappingSummaryLabel.setText("");
        }
        if (shellSummaryLabel != null) {
            String ready = inventoryText == null || inventoryText.isBlank() ? "" : inventoryText;
            int canChannels = 0;
            if (appService != null) {
                for (WorkspaceFile file : measurements) {
                    canChannels += appService.sourceInventoryModel()
                            .inventoryFor(file.id())
                            .map(inv -> (int) inv.totalCanChannels())
                            .orElse(0);
                }
            }
            // Compact inline metadata matching Source Inventory chrome target.
            String compact = measurementCount + (measurementCount == 1 ? " Source" : " Sources")
                    + "  \u2022  "
                    + canChannels + (canChannels == 1 ? " CAN Channel" : " CAN Channels")
                    + "  \u2022  "
                    + definitionCount + (definitionCount == 1 ? " DBC / Definition" : " DBCs / Definitions");
            if (!ready.isBlank()) {
                compact += "  \u2022  " + ready;
            }
            shellSummaryLabel.setText(compact);
        }
        ensureWorkspaceNavVisible();
        if (measurementCountBadge != null) {
            updateMeasurementCountBadge();
        }
        if (definitionCountBadge != null && (definitionSearchField == null
                || definitionSearchField.getText() == null
                || definitionSearchField.getText().isBlank())) {
            definitionCountBadge.setText(String.valueOf(definitionCount));
        }
        if (measurementEmptyLabel != null) {
            boolean empty = measurementCount == 0;
            measurementEmptyLabel.setVisible(empty);
            measurementEmptyLabel.setManaged(empty);
        }
        if (definitionEmptyLabel != null) {
            boolean empty = definitionCount == 0;
            definitionEmptyLabel.setVisible(empty);
            definitionEmptyLabel.setManaged(empty);
        }
        if (measurementSourceList != null) {
            measurementSourceList.setVisible(measurementCount > 0);
            measurementSourceList.setManaged(measurementCount > 0);
        }
        if (definitionTable != null) {
            definitionTable.setVisible(definitionCount > 0);
            definitionTable.setManaged(definitionCount > 0);
        }
        updateSourcesPresentation();
        updateDecodeReadinessSummary();
    }

    private String mappingSummaryText() {
        if (appService == null) {
            return "";
        }
        ProjectDefinitionMatchingModel.MatchingSummary summary = appService.definitionMatchingModel().summary();
        if (summary.sourceChannelCount() == 0 && summary.candidateCount() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(summary.sourceChannelCount()).append(" Source Channels");
        int defs = appService.definitionMatchingCoordinator().definitionCount();
        if (defs > 0) {
            sb.append(" · ").append(defs).append(" DBCs");
            sb.append(" · ").append(summary.candidateCount()).append(" Comparisons");
        }
        if (summary.recommendedCount() > 0) {
            sb.append(" · ").append(summary.recommendedCount()).append(" Recommended");
        }
        if (summary.confirmedCount() > 0) {
            sb.append(" · ").append(summary.confirmedCount()).append(" Confirmed");
        }
        if (summary.manualCount() > 0) {
            sb.append(" · ").append(summary.manualCount()).append(" Manual");
        }
        if (summary.ambiguousCount() > 0) {
            sb.append(" · ").append(summary.ambiguousCount()).append(" Ambiguous");
        }
        if (summary.noMatchCount() > 0) {
            sb.append(" · ").append(summary.noMatchCount()).append(" No Match");
        }
        return sb.toString();
    }

    private String inventorySummaryText(List<WorkspaceFile> measurements) {
        if (measurements.isEmpty() || appService == null) {
            return "";
        }
        List<SourceStatusPresenter.View> views = new ArrayList<>();
        for (WorkspaceFile file : measurements) {
            views.add(appService.sourceStatusView(file));
        }
        return SourceWorkspaceHeaderSummary.rollup(views);
    }

    private Window window() {
        if (measurementSourceList != null && measurementSourceList.getScene() != null) {
            return measurementSourceList.getScene().getWindow();
        }
        if (definitionTable != null && definitionTable.getScene() != null) {
            return definitionTable.getScene().getWindow();
        }
        return null;
    }
}

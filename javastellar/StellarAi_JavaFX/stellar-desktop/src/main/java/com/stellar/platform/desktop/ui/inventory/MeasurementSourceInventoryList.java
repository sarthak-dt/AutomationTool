package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.SourceAliasRegistry;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.DisplayLabel;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.SourceRef;
import com.stellar.platform.desktop.presentation.SourceInventoryModel;
import com.stellar.platform.desktop.presentation.SourceStatus;
import com.stellar.platform.desktop.presentation.SourceStatusPresenter;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.ui.UiFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryDiagnosticCode;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Expandable measurement-source list for Project Files Sources workspace.
 * Uses the same {@link SourceAliasRegistry} / distinguishing-name resolver as Inventory and DBC Mapping.
 */
public final class MeasurementSourceInventoryList extends ListView<WorkspaceFile> {

    /** Complete project source list. The ListView renders a filtered virtualized projection only. */
    private final ObservableList<WorkspaceFile> allItems = FXCollections.observableArrayList();
    private final FilteredList<WorkspaceFile> filteredItems = new FilteredList<>(allItems, file -> true);
    private String filterText = "";
    private final Set<String> expandedIds = new HashSet<>();
    private final NumberFormat numberFormat = NumberFormat.getIntegerInstance(Locale.US);
    private Map<ProjectSourceId, DisplayLabel> labelsBySourceId = Map.of();

    private static final List<String> SOURCE_STATUS_STYLE_CLASSES =
            java.util.Arrays.stream(SourceStatus.values()).map(SourceStatus::styleClass).toList();

    private DesktopAppService appService;
    private SourceInventoryModel inventoryModel;
    private Consumer<WorkspaceFile> onRemove;
    private Consumer<WorkspaceFile> onLocate;
    private Consumer<WorkspaceFile> onRetry;
    private Runnable onSelectionCommitted;
    private boolean refreshing;

    public MeasurementSourceInventoryList() {
        setItems(filteredItems);
        getStyleClass().add("measurement-source-inventory-list");
        getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        setCellFactory(list -> new SourceCell());
        setFocusTraversable(true);
        getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (refreshing || newVal == null || appService == null) {
                return;
            }
            String currentId = appService.workspace().selectedMeasurement().map(WorkspaceFile::id).orElse(null);
            if (!Objects.equals(currentId, newVal.id())) {
                appService.selectMeasurement(newVal.id());
                if (onSelectionCommitted != null) {
                    onSelectionCommitted.run();
                }
            }
        });
    }

    public void bind(DesktopAppService appService, SourceInventoryModel inventoryModel) {
        this.appService = Objects.requireNonNull(appService, "appService");
        this.inventoryModel = Objects.requireNonNull(inventoryModel, "inventoryModel");
    }

    public void setOnRemove(Consumer<WorkspaceFile> onRemove) {
        this.onRemove = onRemove;
    }

    /** Recovery action offered when a source file is missing at its recorded location. */
    public void setOnLocate(Consumer<WorkspaceFile> onLocate) {
        this.onLocate = onLocate;
    }

    /** Recovery action offered when lightweight inspection of a source failed. */
    public void setOnRetry(Consumer<WorkspaceFile> onRetry) {
        this.onRetry = onRetry;
    }

    public void setOnSelectionCommitted(Runnable onSelectionCommitted) {
        this.onSelectionCommitted = onSelectionCommitted;
    }

    public void replaceItems(List<WorkspaceFile> files, String selectedId) {
        refreshing = true;
        try {
            allItems.setAll(files == null ? List.of() : files);
            expandedIds.retainAll(allItems.stream().map(WorkspaceFile::id).collect(Collectors.toSet()));
            rebuildLabels();
            applyFilter();
            if (selectedId != null) {
                for (WorkspaceFile file : filteredItems) {
                    if (selectedId.equals(file.id())) {
                        getSelectionModel().select(file);
                        scrollTo(file);
                        break;
                    }
                }
            } else {
                getSelectionModel().clearSelection();
            }
        } finally {
            refreshing = false;
        }
        refresh();
    }

    /**
     * Presentation-only source filtering for large projects. The complete source collection stays
     * intact, so aliases, inventory state and incremental-processing state are never pruned by UI search.
     */
    public void setFilterText(String text) {
        filterText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    public void collapseAll() {
        if (expandedIds.isEmpty()) {
            return;
        }
        expandedIds.clear();
        refresh();
    }

    public int totalItemCount() {
        return allItems.size();
    }

    public int visibleItemCount() {
        return filteredItems.size();
    }

    private void applyFilter() {
        final String needle = filterText;
        filteredItems.setPredicate(file -> {
            if (file == null || needle.isBlank()) {
                return file != null;
            }
            DisplayLabel label = labelsBySourceId.get(file.projectSourceId());
            String alias = label == null ? "" : label.alias();
            String compact = label == null ? file.fileName() : label.distinguishingName();
            String path = file.displayPath() == null ? "" : file.displayPath();
            String format = file.formatDisplayName();
            return containsIgnoreCase(alias, needle)
                    || containsIgnoreCase(compact, needle)
                    || containsIgnoreCase(file.fileName(), needle)
                    || containsIgnoreCase(path, needle)
                    || containsIgnoreCase(format, needle);
        });
        refresh();
    }

    private static boolean containsIgnoreCase(String value, String normalizedNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedNeedle);
    }

    private void rebuildLabels() {
        if (appService == null) {
            labelsBySourceId = Map.of();
            return;
        }
        SourceAliasRegistry aliases = appService.sourceAliasRegistry();
        List<SourceRef> refs = new ArrayList<>();
        Set<String> live = new HashSet<>();
        for (WorkspaceFile file : allItems) {
            if (file == null || !file.kind().measurement()) {
                continue;
            }
            ProjectSourceId sid = file.projectSourceId();
            live.add(sid.value());
            aliases.aliasFor(sid);
            refs.add(new SourceRef(sid, file.fileName(), file.path()));
        }
        aliases.retainOnly(live);
        Map<ProjectSourceId, DisplayLabel> resolved = SourceDisplayNameResolver.resolve(refs);
        Map<ProjectSourceId, DisplayLabel> stable = new LinkedHashMap<>();
        for (SourceRef ref : refs) {
            DisplayLabel base = resolved.get(ref.sourceId());
            String alias = aliases.aliasFor(ref.sourceId());
            if (base == null) {
                stable.put(ref.sourceId(), new DisplayLabel(
                        ref.sourceId(), alias, ref.fileName(), ref.fileName(),
                        ref.path() == null ? ref.fileName() : ref.path().toString()));
            } else {
                stable.put(ref.sourceId(), new DisplayLabel(
                        base.sourceId(), alias, base.distinguishingName(),
                        base.fullFileName(), base.fullPath()));
            }
        }
        labelsBySourceId = Map.copyOf(stable);
    }

    /** Test hook: alias shown for a source in the Sources list. */
    public String aliasForTest(ProjectSourceId sourceId) {
        DisplayLabel label = labelsBySourceId.get(sourceId);
        return label == null ? null : label.alias();
    }

    /** Test hook: compact distinguishing filename kept for search / inventory identity. */
    public String compactNameForTest(ProjectSourceId sourceId) {
        DisplayLabel label = labelsBySourceId.get(sourceId);
        return label == null ? null : label.distinguishingName();
    }

    /** Test hook: filename shown as the primary Sources-row label. */
    public String primaryNameForTest(ProjectSourceId sourceId) {
        DisplayLabel label = labelsBySourceId.get(sourceId);
        return label == null ? null : label.fullFileName();
    }


    private final class SourceCell extends ListCell<WorkspaceFile> {
        private final VBox root = new VBox(2);
        private final HBox header = new HBox(8);
        private final Label expandLabel = new Label("▶");
        private final Label aliasLabel = new Label();
        private final VBox titleBlock = new VBox(2);
        private final Label nameLabel = new Label();
        private final Label summaryLabel = new Label();
        private final Label statusLabel = new Label();
        private final Button locateButton = new Button("Locate File");
        private final Button retryButton = new Button("Retry");
        private final Button removeButton = new Button("Remove from Project");
        private final VBox channelBox = new VBox(2);

        private SourceCell() {
            root.getStyleClass().addAll("meas-source-row", "source-row");
            header.setAlignment(Pos.CENTER_LEFT);
            header.getStyleClass().add("meas-source-row-header");
            expandLabel.getStyleClass().add("meas-source-expand");
            aliasLabel.getStyleClass().add("meas-source-alias");
            aliasLabel.setVisible(false);
            aliasLabel.setManaged(false);
            nameLabel.getStyleClass().addAll("meas-source-name", "source-file-name");
            nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            nameLabel.setMinWidth(0);
            nameLabel.setMaxWidth(Double.MAX_VALUE);
            summaryLabel.getStyleClass().addAll("meas-source-summary", "source-meta");
            summaryLabel.setWrapText(false);
            summaryLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            summaryLabel.setMinWidth(0);
            statusLabel.getStyleClass().addAll("meas-source-status", "source-status");
            locateButton.getStyleClass().addAll("secondary-button", "compact-button", "source-locate-button");
            locateButton.setTooltip(new Tooltip(
                    "Point this project entry at the same measurement in its new location."));
            locateButton.setVisible(false);
            locateButton.setManaged(false);
            retryButton.getStyleClass().addAll("secondary-button", "compact-button", "source-retry-button");
            retryButton.setTooltip(new Tooltip("Inspect this source again."));
            retryButton.setVisible(false);
            retryButton.setManaged(false);
            removeButton.getStyleClass().addAll("secondary-button", "compact-button", "project-remove-button");
            removeButton.setTooltip(new Tooltip(
                    "Remove this source from the project. The measurement file on disk is not deleted."));
            channelBox.getStyleClass().add("meas-source-channels");
            channelBox.setPadding(new Insets(2, 0, 2, 22));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox nameRow = new HBox(8, nameLabel);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            nameRow.setMinWidth(0);
            HBox.setHgrow(nameLabel, Priority.ALWAYS);
            titleBlock.getChildren().setAll(nameRow, summaryLabel);
            titleBlock.setMinWidth(0);
            HBox.setHgrow(titleBlock, Priority.ALWAYS);
            header.setMinWidth(0);
            header.getChildren().setAll(
                    expandLabel, titleBlock, statusLabel, spacer, locateButton, retryButton, removeButton);
            root.setMinWidth(0);
            root.getChildren().setAll(header);
            root.setOnMouseClicked(e -> {
                WorkspaceFile file = getItem();
                if (file == null) {
                    return;
                }
                if (e.getClickCount() == 1 && e.getTarget() != removeButton) {
                    if (e.getTarget() == expandLabel || e.getTarget() == nameLabel
                            || e.getTarget() == summaryLabel) {
                        toggleExpanded(file.id());
                        updateItem(file, false);
                    }
                }
            });
            expandLabel.setOnMouseClicked(e -> {
                WorkspaceFile file = getItem();
                if (file != null) {
                    toggleExpanded(file.id());
                    updateItem(file, false);
                    e.consume();
                }
            });
            removeButton.setOnAction(e -> {
                WorkspaceFile file = getItem();
                if (file != null && onRemove != null) {
                    onRemove.accept(file);
                }
            });
            locateButton.setOnAction(e -> {
                WorkspaceFile file = getItem();
                if (file != null && onLocate != null) {
                    onLocate.accept(file);
                }
            });
            retryButton.setOnAction(e -> {
                WorkspaceFile file = getItem();
                if (file != null && onRetry != null) {
                    onRetry.accept(file);
                }
            });
        }

        private void toggleExpanded(String id) {
            if (!expandedIds.add(id)) {
                expandedIds.remove(id);
            }
        }

        @Override
        protected void updateItem(WorkspaceFile file, boolean empty) {
            super.updateItem(file, empty);
            if (empty || file == null || appService == null) {
                setGraphic(null);
                setText(null);
                setTooltip(null);
                return;
            }
            boolean expanded = expandedIds.contains(file.id());
            expandLabel.setText(expanded ? "▼" : "▶");
            DisplayLabel label = labelsBySourceId.get(file.projectSourceId());
            String alias = label != null
                    ? label.alias()
                    : appService.sourceAliasRegistry().aliasFor(file.projectSourceId());
            String fullName = label != null ? label.fullFileName() : file.fileName();
            String fullPath = label != null ? label.fullPath() : file.path().toString();
            aliasLabel.setText(alias);
            aliasLabel.setVisible(false);
            aliasLabel.setManaged(false);
            nameLabel.setText(fullName);
            Tooltip pathTip = new Tooltip(fullName + "\n" + fullPath);
            pathTip.setWrapText(true);
            setTooltip(pathTip);
            root.getStyleClass().remove("source-row-selected");
            if (isSelected()) {
                root.getStyleClass().add("source-row-selected");
            }

            ProjectInventoryJobState jobState = appService.inventoryJobState(file.projectSourceId());
            SourceStatus sourceStatus = appService.sourceStatus(file);
            SourceStatusPresenter.View statusView = appService.sourceStatusView(file);
            statusLabel.setText(statusView.badgeText());
            statusLabel.getStyleClass().removeAll(SOURCE_STATUS_STYLE_CLASSES);
            statusLabel.getStyleClass().removeAll(
                    "status-ready", "status-processed", "status-processing", "status-failed");
            statusLabel.getStyleClass().add(sourceStatus.styleClass());
            statusLabel.getStyleClass().add(statusView.styleClass());

            boolean showLocate = sourceStatus == SourceStatus.UNAVAILABLE && onLocate != null;
            locateButton.setVisible(showLocate);
            locateButton.setManaged(showLocate);
            boolean showRetry = sourceStatus == SourceStatus.NEEDS_ATTENTION
                    && onRetry != null
                    && file.kind().implemented();
            retryButton.setVisible(showRetry);
            retryButton.setManaged(showRetry);

            Optional<CanSourceInventory> inventoryOpt = inventoryModel == null
                    ? Optional.empty()
                    : inventoryModel.inventoryFor(file.id());
            summaryLabel.setText(summaryText(file, sourceStatus, jobState, inventoryOpt.orElse(null)));

            channelBox.getChildren().clear();
            root.getChildren().setAll(header);
            if (expanded && jobState == ProjectInventoryJobState.READY && inventoryOpt.isPresent()) {
                for (CanChannelInventory channel : inventoryOpt.get().channels()) {
                    SourceChannelKey key = SourceChannelKey.of(file.projectSourceId(), channel.channelId());
                    String mappingHint = appService.channelMapping(key)
                            .map(MeasurementSourceInventoryList.this::mappingHint)
                            .orElse("");
                    Label row = new Label(channelLine(channel) + mappingHint);
                    row.getStyleClass().add("meas-source-channel-line");
                    channelBox.getChildren().add(row);
                }
                if (channelBox.getChildren().isEmpty()) {
                    Label emptyChannels = new Label(
                            "No CAN channels observed (native measurements may still be present).");
                    emptyChannels.getStyleClass().add("meas-source-channel-line");
                    channelBox.getChildren().add(emptyChannels);
                }
                root.getChildren().add(channelBox);
            } else if (expanded && jobState == ProjectInventoryJobState.INDEXING) {
                Label inspecting = new Label("Inspecting source...");
                inspecting.getStyleClass().add("meas-source-channel-line");
                channelBox.getChildren().add(inspecting);
                root.getChildren().add(channelBox);
            }
            setGraphic(root);
            setText(null);
        }
    }

    /**
     * Source row detail: format, size and — once inspection finished — discovered channels.
     * Never exposes parser provider names or internal state enums.
     */
    private String summaryText(WorkspaceFile file,
                               SourceStatus status,
                               ProjectInventoryJobState state,
                               CanSourceInventory inventory) {
        String head = file.formatDisplayName() + " · " + UiFormat.bytes(file.sizeBytes());
        if (status == SourceStatus.UNAVAILABLE) {
            return head + " · File not found at " + file.displayPath();
        }
        if (status == SourceStatus.READING) {
            return head + (state == ProjectInventoryJobState.INDEXING
                    ? " · Reading source..."
                    : " · Waiting to be read");
        }
        if (status == SourceStatus.NEEDS_ATTENTION) {
            return head + " · " + attentionDetail(file, state, inventory);
        }
        if (inventory == null) {
            return head;
        }
        int channels = (int) inventory.totalCanChannels();
        long malformed = inventory.diagnostics().stream()
                .filter(d -> d.code() == CanInventoryDiagnosticCode.MALFORMED_RECORD)
                .count();
        String malformedNote = malformed == 0
                ? ""
                : " · " + malformed + (malformed == 1 ? " malformed line" : " malformed lines");
        return head
                + " · " + channels + (channels == 1 ? " CAN Channel" : " CAN Channels")
                + " · " + numberFormat.format(inventory.globalUniqueCanIdentityCount()) + " Unique CAN IDs"
                + " · " + numberFormat.format(inventory.totalCanFrames()) + " frames"
                + malformedNote;
    }

    private static String attentionDetail(WorkspaceFile file,
                                          ProjectInventoryJobState state,
                                          CanSourceInventory inventory) {
        if (!file.kind().implemented() || state == ProjectInventoryJobState.UNSUPPORTED
                || file.status() == com.stellar.platform.desktop.project.FileValidationStatus.UNSUPPORTED) {
            String message = file.validationMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            return "This measurement format cannot be read yet";
        }
        if (inventory != null && !inventory.diagnostics().isEmpty()) {
            return inventory.diagnostics().getFirst().message();
        }
        String message = file.validationMessage();
        return message == null || message.isBlank() ? "This source could not be read" : message;
    }

    private String channelLine(CanChannelInventory channel) {
        return channel.displayName()
                + "    " + numberFormat.format(channel.uniqueMessageCount()) + " IDs"
                + "    " + numberFormat.format(channel.frameCount()) + " frames";
    }

    private String mappingHint(SourceChannelMappingEntry entry) {
        if (entry.recommendedDisplayName() == null || entry.recommendedDisplayName().isBlank()) {
            if (entry.status() == com.stellar.platform.desktop.project.ChannelMappingStatus.NO_MATCH) {
                return "    No suitable DBC match";
            }
            return "";
        }
        return "    Best Match: " + entry.recommendedDisplayName()
                + "    " + numberFormat.format(entry.matchedUniqueMessageCount())
                + "/" + numberFormat.format(entry.observedUniqueMessageCount())
                + " · " + String.format(Locale.US, "%.2f", entry.coveragePercent()) + "%";
    }

}

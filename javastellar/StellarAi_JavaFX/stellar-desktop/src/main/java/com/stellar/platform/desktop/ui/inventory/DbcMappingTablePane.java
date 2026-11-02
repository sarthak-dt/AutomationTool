package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.CoverageClassificationPolicy;
import com.stellar.platform.desktop.presentation.DbcMappingWidthPolicy;
import com.stellar.platform.desktop.presentation.DbcMappingWidthPolicy.ActionDensity;
import com.stellar.platform.desktop.presentation.DbcMappingWidthPolicy.ColumnBudget;
import com.stellar.platform.desktop.presentation.CoverageClassificationPolicy.CoverageQuality;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProjectDefinitionMatchingModel;
import com.stellar.platform.desktop.presentation.SourceAliasRegistry;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.DisplayLabel;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.SourceRef;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelDefinitionComparison;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.ui.StellarIcons;
import com.stellar.platform.domain.inventory.CanIdMatchDiagnostic;
import com.stellar.platform.domain.inventory.CanIdMatchDiagnosticStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Source-grouped DBC Mapping workstation (presentation only).
 * Grouping key: {@link ProjectSourceId}. Matching commands are delegated unchanged.
 */
public final class DbcMappingTablePane extends VBox {

    private static final double ROW_HEIGHT = 50;
    private static final double GROUP_PAD_H = 20; // left+right padding of source group
    private static final double LIST_VBAR_RESERVE = 18;

    private final Label title = new Label("DBC MAPPING");
    private final Label explanation = new Label(
            "Each measurement source groups its CAN channels. "
                    + "StellarAI recommends the best DBC match — confirm or choose another definition.");
    private final Label legend = new Label(
            "Recommended = best uploaded candidate · Confirmed = accepted · "
                    + "Coverage quality is separate from recommendation state.");
    private final Label mappingCountsLabel = new Label("");
    private final Label emptyLabel = new Label(
            "Add READY measurement inventories and DBC definitions to compute channel matches.");

    private final TextField searchField = new TextField();
    private final ComboBox<String> stateFilter = new ComboBox<>();
    private final ComboBox<String> coverageFilter = new ComboBox<>();
    private final CheckBox needsAttention = new CheckBox("Needs Attention");
    private final Button clearFilters = new Button("Clear");
    private final HBox toolbar = new HBox(8);

    private final VBox groupBox = new VBox(7);
    private final ScrollPane groupScroll = new ScrollPane(groupBox);
    private final ObservableList<SourceMappingGroup> visibleGroups = FXCollections.observableArrayList();
    private final Map<String, Boolean> expandedBySourceId = new HashMap<>();
    private final Map<String, SourceGroupCard> cardsBySourceId = new LinkedHashMap<>();
    private int mappingRootRebuildCountForTest;
    private double lastViewportWidth = -1;
    /** Guards against viewportBounds ↔ content-width layout oscillation on the FX thread. */
    private boolean applyingViewportWidths;
    private final NumberFormat numberFormat = NumberFormat.getIntegerInstance(Locale.US);
    private final NumberFormat percentFormat = NumberFormat.getNumberInstance(Locale.US);
    private final CoverageClassificationPolicy coveragePolicy = CoverageClassificationPolicy.defaults();

    private DesktopAppService appService;
    private Runnable onChanged;
    private List<SourceMappingGroup> allGroups = List.of();
    private double preservedScroll = 0.0;

    public DbcMappingTablePane() {
        getStyleClass().addAll("dbc-mapping-pane", "dbc-mapping-root", "content-surface");
        setSpacing(8);
        setPadding(new Insets(10, 12, 10, 12));
        title.getStyleClass().add("section-subtitle");
        explanation.getStyleClass().add("muted");
        explanation.setWrapText(true);
        legend.getStyleClass().add("muted");
        legend.setWrapText(true);
        mappingCountsLabel.getStyleClass().addAll("project-summary-metric", "dbc-mapping-summary");
        emptyLabel.getStyleClass().add("project-empty-state");
        percentFormat.setMaximumFractionDigits(2);
        percentFormat.setMinimumFractionDigits(0);

        searchField.setPromptText("Search source, channel, DBC…");
        searchField.getStyleClass().add("search-field");
        searchField.setMinWidth(320);
        searchField.setPrefWidth(480);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        stateFilter.getItems().setAll("All", "Recommended", "Confirmed", "Manual", "Ambiguous", "No Match");
        stateFilter.getSelectionModel().select("All");
        stateFilter.setPromptText("State");
        stateFilter.setMinWidth(150);
        stateFilter.setPrefWidth(165);
        stateFilter.setMaxWidth(175);
        coverageFilter.getItems().setAll("All", "High", "Medium", "Low", "None");
        coverageFilter.getSelectionModel().select("All");
        coverageFilter.setPromptText("Coverage");
        coverageFilter.setMinWidth(150);
        coverageFilter.setPrefWidth(165);
        coverageFilter.setMaxWidth(175);
        clearFilters.getStyleClass().addAll("secondary-button", "mapping-filter-clear");
        clearFilters.setMinWidth(80);
        clearFilters.setPrefWidth(86);
        clearFilters.setMaxWidth(90);
        needsAttention.setMinWidth(Region.USE_PREF_SIZE);
        needsAttention.setMaxWidth(Region.USE_PREF_SIZE);
        for (Region control : List.of(searchField, stateFilter, coverageFilter, needsAttention, clearFilters)) {
            control.setMinHeight(32);
            control.setPrefHeight(32);
            control.setMaxHeight(34);
        }
        Label stateLabel = new Label("State");
        stateLabel.setMinWidth(Region.USE_PREF_SIZE);
        Label coverageLabel = new Label("Coverage");
        coverageLabel.setMinWidth(Region.USE_PREF_SIZE);
        toolbar.getStyleClass().add("dbc-mapping-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setMinHeight(34);
        toolbar.setPrefHeight(34);
        toolbar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(toolbar, Priority.ALWAYS);
        toolbar.getChildren().setAll(
                searchField,
                stateLabel, stateFilter,
                coverageLabel, coverageFilter,
                needsAttention,
                clearFilters
        );

        groupBox.getStyleClass().add("source-mapping-list");
        groupBox.setFillWidth(true);
        groupBox.setMaxWidth(Double.MAX_VALUE);
        groupScroll.getStyleClass().add("source-mapping-scroll");
        groupScroll.setFitToWidth(true);
        groupScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        groupScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        groupScroll.setPrefHeight(Region.USE_COMPUTED_SIZE);
        groupScroll.setMinHeight(180);
        groupScroll.setMaxHeight(Double.MAX_VALUE);
        groupScroll.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(groupScroll, Priority.ALWAYS);
        setMaxHeight(Double.MAX_VALUE);
        setFillWidth(true);
        groupScroll.viewportBoundsProperty().addListener((o, a, b) -> {
            if (applyingViewportWidths) {
                return;
            }
            double w = b == null ? 0 : b.getWidth();
            // Ignore vertical-scrollbar appear/disappear jitter (typically ~14–18px).
            if (lastViewportWidth > 0 && Math.abs(lastViewportWidth - w) < LIST_VBAR_RESERVE + 4) {
                return;
            }
            lastViewportWidth = w;
            applyingViewportWidths = true;
            try {
                // Width-only: never destroy source cards (dialogs must not rebuild the page).
                applyWidthsToExistingCards();
            } finally {
                applyingViewportWidths = false;
            }
        });
        setMaxWidth(Double.MAX_VALUE);
        setFillWidth(true);

        searchField.textProperty().addListener((o, a, b) -> applyFilters());
        stateFilter.valueProperty().addListener((o, a, b) -> applyFilters());
        coverageFilter.valueProperty().addListener((o, a, b) -> applyFilters());
        needsAttention.selectedProperty().addListener((o, a, b) -> applyFilters());
        clearFilters.setOnAction(e -> {
            searchField.clear();
            stateFilter.getSelectionModel().select("All");
            coverageFilter.getSelectionModel().select("All");
            needsAttention.setSelected(false);
            applyFilters();
        });

        getChildren().setAll(
                title, explanation, legend, mappingCountsLabel, toolbar, emptyLabel, groupScroll);
    }

    public void bind(DesktopAppService appService, Runnable onChanged) {
        this.appService = Objects.requireNonNull(appService, "appService");
        this.onChanged = onChanged;
    }

    public void refreshFromModel() {
        if (appService == null) {
            return;
        }
        preservedScroll = readListScroll();
        rebuildGroups();
        updateSummary();
        applyFilters();
        restoreListScroll(preservedScroll);
    }

    int mappingRootRebuildCountForTest() {
        return mappingRootRebuildCountForTest;
    }

    void resetMappingRootRebuildCountForTest() {
        mappingRootRebuildCountForTest = 0;
    }

    private double readListScroll() {
        return groupScroll.getVvalue();
    }

    private void restoreListScroll(double value) {
        groupScroll.setVvalue(value);
    }

    private void rebuildGroups() {
        List<SourceChannelMappingEntry> mappings = appService.definitionMatchingModel().allMappings();
        SourceAliasRegistry aliases = appService.sourceAliasRegistry();

        // Assign aliases in stable workspace insertion order (not mapping/UUID/filter order).
        java.util.LinkedHashSet<String> liveSourceIds = new java.util.LinkedHashSet<>();
        for (WorkspaceFile file : appService.workspace().measurementFiles()) {
            liveSourceIds.add(file.id());
            aliases.aliasFor(file.projectSourceId());
        }
        aliases.retainOnly(liveSourceIds);
        expandedBySourceId.keySet().removeIf(id -> !liveSourceIds.contains(id));

        // Group channels by source; HashMap is fine — presentation order comes from workspace.
        Map<ProjectSourceId, List<SourceChannelMappingEntry>> bySource = new HashMap<>();
        for (SourceChannelMappingEntry entry : mappings) {
            bySource.computeIfAbsent(entry.sourceChannelKey().sourceId(), k -> new ArrayList<>()).add(entry);
        }
        for (List<SourceChannelMappingEntry> channels : bySource.values()) {
            channels.sort(Comparator.comparingInt(e -> e.sourceChannelKey().channelId()));
        }

        List<ProjectSourceId> orderedSourceIds = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (WorkspaceFile file : appService.workspace().measurementFiles()) {
            ProjectSourceId sourceId = file.projectSourceId();
            if (bySource.containsKey(sourceId) && seen.add(sourceId.value())) {
                orderedSourceIds.add(sourceId);
            }
        }
        for (ProjectSourceId sourceId : bySource.keySet()) {
            if (seen.add(sourceId.value())) {
                orderedSourceIds.add(sourceId);
            }
        }

        List<SourceRef> refs = new ArrayList<>();
        for (ProjectSourceId sourceId : orderedSourceIds) {
            WorkspaceFile file = appService.workspace().findFile(sourceId.value()).orElse(null);
            refs.add(new SourceRef(
                    sourceId,
                    file == null ? sourceId.value() : file.fileName(),
                    file == null ? null : file.path()
            ));
        }
        Map<ProjectSourceId, DisplayLabel> labels = SourceDisplayNameResolver.resolve(refs);

        List<SourceMappingGroup> groups = new ArrayList<>();
        int index = 0;
        for (ProjectSourceId sourceId : orderedSourceIds) {
            List<SourceChannelMappingEntry> sourceChannels = bySource.getOrDefault(sourceId, List.of());
            DisplayLabel resolved = labels.get(sourceId);
            String alias = aliases.aliasFor(sourceId);
            DisplayLabel label = resolved == null
                    ? new DisplayLabel(sourceId, alias, sourceId.value(), sourceId.value(), sourceId.value())
                    : new DisplayLabel(
                            resolved.sourceId(),
                            alias,
                            resolved.distinguishingName(),
                            resolved.fullFileName(),
                            resolved.fullPath());
            CanSourceInventory inventory = appService.sourceInventoryModel()
                    .inventoryFor(sourceId.value())
                    .orElse(null);
            boolean expanded = expandedBySourceId.getOrDefault(
                    sourceId.value(),
                    orderedSourceIds.size() <= 5 && index == 0
            );
            expandedBySourceId.putIfAbsent(sourceId.value(), expanded);
            List<SourceChannelMappingEntry> full = List.copyOf(sourceChannels);
            groups.add(new SourceMappingGroup(sourceId, label, inventory, full, expanded, full));
            index++;
        }
        allGroups = List.copyOf(groups);
    }

    private void applyFilters() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        String state = stateFilter.getValue() == null ? "All" : stateFilter.getValue();
        String coverage = coverageFilter.getValue() == null ? "All" : coverageFilter.getValue();
        boolean attention = needsAttention.isSelected();

        List<SourceMappingGroup> filtered = new ArrayList<>();
        for (SourceMappingGroup group : allGroups) {
            boolean headerHit = q.isEmpty() || headerMatches(group, q);
            List<SourceChannelMappingEntry> rows = group.channels().stream()
                    .filter(entry -> matchesFilters(group, entry, q, state, coverage, attention, headerHit))
                    .toList();
            if (rows.isEmpty()) {
                continue;
            }
            boolean expanded = expandedBySourceId.getOrDefault(group.sourceId().value(), group.expanded());
            filtered.add(new SourceMappingGroup(
                    group.sourceId(),
                    group.label(),
                    group.inventory(),
                    rows,
                    expanded,
                    group.allSourceChannels()));
        }

        visibleGroups.setAll(filtered);
        boolean empty = visibleGroups.isEmpty() && allGroups.isEmpty();
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
        groupScroll.setVisible(!visibleGroups.isEmpty() || !allGroups.isEmpty());
        groupScroll.setManaged(!visibleGroups.isEmpty() || !allGroups.isEmpty());
        if (allGroups.isEmpty()) {
            emptyLabel.setVisible(true);
            emptyLabel.setManaged(true);
            groupScroll.setVisible(false);
            groupScroll.setManaged(false);
        }
        renderVisibleGroups();
    }

    private boolean headerMatches(SourceMappingGroup group, String q) {
        DisplayLabel label = group.label();
        return contains(label.alias(), q)
                || contains(label.distinguishingName(), q)
                || contains(label.fullFileName(), q)
                || contains(label.fullPath(), q);
    }

    private boolean matchesFilters(
            SourceMappingGroup group,
            SourceChannelMappingEntry entry,
            String q,
            String state,
            String coverage,
            boolean attention,
            boolean headerHit
    ) {
        if (!q.isEmpty()) {
            boolean rowHit = contains("ch" + entry.sourceChannelKey().channelId(), q)
                    || contains(dbcText(entry), q)
                    || contains(entry.status().badgeLabel(), q);
            if (!headerHit && !rowHit) {
                return false;
            }
        }
        if (!"All".equals(state) && !stateMatches(entry.status(), state)) {
            return false;
        }
        CoverageQuality quality = coveragePolicy.classify(entry.coverageRatio());
        if (!"All".equals(coverage) && !quality.name().equalsIgnoreCase(coverage)) {
            return false;
        }
        if (attention && !isNeedsAttention(entry, quality)) {
            return false;
        }
        return true;
    }

    private static boolean stateMatches(ChannelMappingStatus status, String filter) {
        return switch (filter) {
            case "Recommended" -> status == ChannelMappingStatus.RECOMMENDED;
            case "Confirmed" -> status == ChannelMappingStatus.CONFIRMED;
            case "Manual" -> status == ChannelMappingStatus.MANUAL;
            case "Ambiguous" -> status == ChannelMappingStatus.AMBIGUOUS;
            case "No Match" -> status == ChannelMappingStatus.NO_MATCH;
            default -> true;
        };
    }

    private static boolean isNeedsAttention(SourceChannelMappingEntry entry, CoverageQuality quality) {
        if (entry.status() == ChannelMappingStatus.AMBIGUOUS
                || entry.status() == ChannelMappingStatus.NO_MATCH) {
            return true;
        }
        return entry.status() == ChannelMappingStatus.RECOMMENDED
                && (quality == CoverageQuality.LOW || quality == CoverageQuality.NONE);
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private void updateSummary() {
        ProjectDefinitionMatchingModel.MatchingSummary summary = appService.definitionMatchingModel().summary();
        int defs = appService.definitionMatchingCoordinator().definitionCount();
        int workspaceDefs = appService.workspace().definitionSourceCount();
        int expected = summary.sourceChannelCount() * defs;
        String parsedHint = workspaceDefs != defs
                ? " · " + defs + " parsed / " + workspaceDefs + " uploaded"
                : "";
        mappingCountsLabel.setText(
                summary.sourceChannelCount() + " Source Channels · "
                        + defs + " DBCs"
                        + parsedHint
                        + " · "
                        + summary.candidateCount() + " Comparisons"
                        + (expected > 0 && summary.candidateCount() != expected
                        ? " (expected " + expected + ")"
                        : "")
                        + (summary.recommendedCount() > 0 ? " · " + summary.recommendedCount() + " Recommended" : "")
                        + (summary.confirmedCount() > 0 ? " · " + summary.confirmedCount() + " Confirmed" : "")
                        + (summary.manualCount() > 0 ? " · " + summary.manualCount() + " Manual" : "")
                        + (summary.ambiguousCount() > 0 ? " · " + summary.ambiguousCount() + " Ambiguous" : "")
                        + (summary.noMatchCount() > 0 ? " · " + summary.noMatchCount() + " No Match" : "")
        );
    }

    private void toggleExpanded(ProjectSourceId sourceId) {
        boolean next = !expandedBySourceId.getOrDefault(sourceId.value(), false);
        expandedBySourceId.put(sourceId.value(), next);
        applyFilters();
    }

    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private String dbcText(SourceChannelMappingEntry entry) {
        if (entry.status() == ChannelMappingStatus.MANUAL || entry.status() == ChannelMappingStatus.CONFIRMED) {
            if (!entry.mappedDisplayName().isBlank()) {
                return entry.mappedDisplayName();
            }
        }
        if (entry.status() == ChannelMappingStatus.NO_MATCH) {
            return "No suitable DBC";
        }
        if (entry.status() == ChannelMappingStatus.NOT_APPLICABLE) {
            return "—";
        }
        if (entry.status() == ChannelMappingStatus.AMBIGUOUS) {
            String name = entry.recommendedDisplayName();
            return name.isBlank() ? "Ambiguous" : name + " (ambiguous)";
        }
        return entry.recommendedDisplayName().isBlank() ? "—" : entry.recommendedDisplayName();
    }

    private void confirmOne(SourceChannelMappingEntry entry) {
        if (appService == null) {
            return;
        }
        preservedScroll = readListScroll();
        // Model listener performs a single targeted mapping presentation update.
        appService.confirmChannelRecommendation(entry.sourceChannelKey());
        restoreListScroll(preservedScroll);
    }

    private void confirmRecommendedForSource(SourceMappingGroup group) {
        List<SourceChannelMappingEntry> recommended = group.allSourceChannels().stream()
                .filter(e -> e.status() == ChannelMappingStatus.RECOMMENDED
                        && e.recommendedDefinitionId().isPresent())
                .toList();
        if (recommended.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder("Confirm ")
                .append(recommended.size())
                .append(" recommended DBC mapping")
                .append(recommended.size() == 1 ? "" : "s")
                .append("?\n\n");
        for (SourceChannelMappingEntry e : recommended) {
            CoverageQuality q = coveragePolicy.classify(e.coverageRatio());
            body.append("Ch").append(e.sourceChannelKey().channelId())
                    .append(" → ").append(e.recommendedDisplayName())
                    .append(" · ").append(percentFormat.format(e.coveragePercent())).append('%');
            if (q == CoverageQuality.LOW || q == CoverageQuality.NONE) {
                body.append("  (").append(q.badgeLabel()).append(')');
            }
            body.append('\n');
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Recommended");
        alert.setHeaderText(SourceDisplayNameResolver.primaryIdentity(group.label()));
        alert.setContentText(body.toString());
        ButtonType confirmType = new ButtonType(
                "Confirm " + recommended.size(), ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(confirmType, ButtonType.CANCEL);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != confirmType) {
            return;
        }
        preservedScroll = readListScroll();
        appService.confirmChannelRecommendations(
                recommended.stream().map(SourceChannelMappingEntry::sourceChannelKey).toList());
        restoreListScroll(preservedScroll);
    }

    private void openCandidateChooser(SourceChannelMappingEntry row) {
        if (appService == null) {
            return;
        }
        List<SourceChannelDefinitionCandidate> candidates = appService.candidatesFor(row.sourceChannelKey());
        if (candidates.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("DBC Candidates");
            alert.setHeaderText("Ch" + row.sourceChannelKey().channelId());
            alert.setContentText("No DBC candidates available.");
            alert.showAndWait();
            return;
        }
        Dialog<SourceChannelDefinitionCandidate> dialog = new Dialog<>();
        dialog.setTitle("Choose DBC");
        dialog.setHeaderText("Ch" + row.sourceChannelKey().channelId()
                + " — select a DBC (0% overlap is allowed as a manual mapping).");
        ButtonType selectType = new ButtonType("Select", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(selectType, ButtonType.CANCEL);
        ListView<String> list = new ListView<>();
        List<SourceChannelDefinitionCandidate> ranked = candidates.stream()
                .sorted(Comparator
                        .comparingLong(SourceChannelDefinitionCandidate::matchedUniqueMessageCount).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                SourceChannelDefinitionCandidate::coverageRatio).reversed()))
                .toList();
        list.getItems().setAll(ranked.stream().map(this::candidateLine).toList());
        list.getSelectionModel().select(0);
        list.setPrefHeight(200);
        dialog.getDialogPane().setContent(list);
        dialog.setResultConverter(button -> {
            if (button != selectType) {
                return null;
            }
            int idx = list.getSelectionModel().getSelectedIndex();
            return idx >= 0 && idx < ranked.size() ? ranked.get(idx) : null;
        });
        Optional<SourceChannelDefinitionCandidate> chosen = dialog.showAndWait();
        chosen.ifPresent(c -> {
            preservedScroll = readListScroll();
            appService.chooseChannelDefinition(row.sourceChannelKey(), c.definitionSourceId());
            restoreListScroll(preservedScroll);
        });
    }

    private void openComparisonView(SourceChannelMappingEntry row) {
        if (appService == null) {
            return;
        }
        List<SourceChannelDefinitionComparison> comparisons = appService.comparisonsFor(row.sourceChannelKey());
        StringBuilder body = new StringBuilder();
        body.append("Channel ").append(row.sourceChannelKey().channelId())
                .append('\n')
                .append("Candidates: ").append(comparisons.size()).append('\n');
        for (SourceChannelDefinitionComparison cmp : comparisons.stream()
                .sorted(Comparator.comparing(SourceChannelDefinitionComparison::definitionDisplayName))
                .toList()) {
            body.append('\n').append("DBC: ").append(cmp.definitionDisplayName()).append('\n');
            body.append("  Coverage: ")
                    .append(numberFormat.format(cmp.matchedUniqueMessageCount()))
                    .append(" / ")
                    .append(numberFormat.format(cmp.observedUniqueMessageCount()))
                    .append(" · ")
                    .append(percentFormat.format(cmp.coveragePercent()))
                    .append("%\n");
            body.append("  Matched CAN IDs:\n");
            boolean anyMatched = false;
            for (CanIdMatchDiagnostic d : cmp.diagnostics()) {
                if (d.status() != CanIdMatchDiagnosticStatus.MATCHED) {
                    continue;
                }
                anyMatched = true;
                body.append("    0x")
                        .append(Long.toHexString(d.observedCanId()).toUpperCase())
                        .append(' ')
                        .append(d.observedTypeLabel())
                        .append('\n');
            }
            if (!anyMatched) {
                body.append("    (none)\n");
            }
            body.append("  Unmatched observed CAN IDs:\n");
            boolean anyUnmatched = false;
            for (CanIdMatchDiagnostic d : cmp.diagnostics()) {
                if (d.status() == CanIdMatchDiagnosticStatus.MATCHED) {
                    continue;
                }
                anyUnmatched = true;
                body.append("    0x")
                        .append(Long.toHexString(d.observedCanId()).toUpperCase())
                        .append(' ')
                        .append(d.observedTypeLabel())
                        .append(" -> ")
                        .append(d.status())
                        .append('\n');
            }
            if (!anyUnmatched) {
                body.append("    (none)\n");
            }
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("CAN ID Comparison");
        dialog.setHeaderText("Ch" + row.sourceChannelKey().channelId()
                + " — read-only cached comparison (" + comparisons.size() + " DBC candidates)");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TextArea area = new TextArea(body.toString());
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(24);
        area.setPrefColumnCount(80);
        ScrollPane scroll = new ScrollPane(area);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(700, 460);
        dialog.getDialogPane().setContent(scroll);
        dialog.showAndWait();
    }

    private String candidateLine(SourceChannelDefinitionCandidate c) {
        String base = c.definitionDisplayName()
                + "    "
                + numberFormat.format(c.matchedUniqueMessageCount())
                + " / "
                + numberFormat.format(c.observedUniqueMessageCount())
                + "    "
                + percentFormat.format(c.coveragePercent())
                + "%";
        if (c.candidateKind() == DefinitionCandidateKind.HAS_OVERLAP) {
            return base;
        }
        return base + "    (" + c.overlapLabel() + ")";
    }

    /** Visible mapping content width from ScrollPane viewport (excludes v-scrollbar). */
    private double mappingViewportWidth() {
        double vw = groupScroll.getViewportBounds().getWidth();
        if (vw > 1) {
            return vw;
        }
        double listW = groupScroll.getWidth();
        if (listW <= 1) {
            return 0;
        }
        return Math.max(320, listW - LIST_VBAR_RESERVE - 4);
    }

    /** Inner width shared by source header and channel rows. */
    private double sourceGroupInnerWidth() {
        double viewport = mappingViewportWidth();
        if (viewport <= 1) {
            return 0;
        }
        return Math.max(300, viewport - GROUP_PAD_H);
    }

    private ColumnBudget currentBudget() {
        return DbcMappingWidthPolicy.budget(sourceGroupInnerWidth());
    }

    private void renderVisibleGroups() {
        syncStableCards();
    }

    private void applyWidthsToExistingCards() {
        ColumnBudget budget = currentBudget();
        double viewport = mappingViewportWidth();
        if (viewport > 20) {
            // maxWidth only — prefWidth(viewport) + fitToWidth causes viewport feedback loops.
            groupBox.setMinWidth(0);
            groupBox.setMaxWidth(viewport);
        }
        for (SourceGroupCard card : cardsBySourceId.values()) {
            card.applyWidths(budget, viewport);
        }
    }

    private void syncStableCards() {
        double scroll = groupScroll.getVvalue();
        ColumnBudget budget = currentBudget();
        double viewport = mappingViewportWidth();
        if (viewport > 20) {
            groupBox.setMinWidth(0);
            groupBox.setMaxWidth(viewport);
        }
        List<Node> nextChildren = new ArrayList<>();
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (SourceMappingGroup group : visibleGroups) {
            String id = group.sourceId().value();
            keep.add(id);
            SourceGroupCard card = cardsBySourceId.get(id);
            if (card == null) {
                card = new SourceGroupCard();
                cardsBySourceId.put(id, card);
                mappingRootRebuildCountForTest++;
            }
            card.update(group, budget, viewport);
            nextChildren.add(card.root);
        }
        cardsBySourceId.keySet().removeIf(id -> !keep.contains(id));
        if (!groupBox.getChildren().equals(nextChildren)) {
            boolean structural = groupBox.getChildren().size() != nextChildren.size()
                    || !new java.util.HashSet<>(groupBox.getChildren()).equals(new java.util.HashSet<>(nextChildren));
            if (structural) {
                mappingRootRebuildCountForTest++;
            }
            groupBox.getChildren().setAll(nextChildren);
        }
        groupScroll.setVvalue(scroll);
    }

    private final class SourceGroupCard {
        private final VBox root = new VBox(4);
        private final Map<Integer, HBox> rowsByChannel = new LinkedHashMap<>();
        private ProjectSourceId boundSourceId;
        private final Button expandBtn = new Button();
        private final Label aliasLabel = new Label();
        private final Label nameLabel = new Label();
        private final Label metaLabel = new Label();
        private final Label stateSummary = new Label();
        private final Button bulkConfirm = new Button();
        private final MenuButton overflow = new MenuButton();
        private final MenuItem copyPathItem = new MenuItem("Copy full path");
        private final BorderPane headerPane = new BorderPane();
        private final VBox header = new VBox(2);
        private final VBox channelBox = new VBox(2);
        private final HBox colHeader = buildColumnHeader();

        SourceGroupCard() {
            root.getStyleClass().add("source-mapping-group");
            header.getStyleClass().add("source-mapping-header");
            aliasLabel.getStyleClass().add("source-alias");
            nameLabel.getStyleClass().add("source-distinguishing-name");
            metaLabel.getStyleClass().add("source-metadata");
            metaLabel.setWrapText(true);
            stateSummary.getStyleClass().add("source-mapping-state-summary");
            stateSummary.setTextOverrun(OverrunStyle.ELLIPSIS);
            stateSummary.setMinWidth(180);
            stateSummary.setPrefWidth(200);
            stateSummary.setMaxWidth(220);
            expandBtn.getStyleClass().addAll("secondary-button", "source-expand-button");
            expandBtn.setMinSize(34, 34);
            expandBtn.setPrefSize(34, 34);
            expandBtn.setMaxSize(36, 36);
            expandBtn.setText(null);
            bulkConfirm.getStyleClass().addAll("primary-button", "source-bulk-confirm");
            bulkConfirm.setGraphic(StellarIcons.check());
            bulkConfirm.setGraphicTextGap(5);
            bulkConfirm.setMinHeight(32);
            bulkConfirm.setPrefHeight(32);
            bulkConfirm.setMaxWidth(210);
            overflow.getStyleClass().addAll("secondary-button", "source-overflow-button");
            overflow.setText("⋯");
            overflow.setMinSize(34, 32);
            overflow.setPrefSize(36, 32);
            copyPathItem.setGraphic(StellarIcons.copy());
            overflow.getItems().setAll(copyPathItem);
            nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            nameLabel.setMaxWidth(220);
            aliasLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            aliasLabel.setMinWidth(0);
            aliasLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(aliasLabel, Priority.ALWAYS);

            HBox identityRow = new HBox(8, aliasLabel, nameLabel);
            identityRow.setAlignment(Pos.CENTER_LEFT);
            VBox leftIdentity = new VBox(2, identityRow, metaLabel);
            HBox left = new HBox(8, expandBtn, leftIdentity);
            left.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(leftIdentity, Priority.SOMETIMES);

            HBox right = new HBox(8, stateSummary, bulkConfirm, overflow);
            right.setAlignment(Pos.CENTER_RIGHT);
            right.setMaxWidth(Region.USE_PREF_SIZE);
            headerPane.setLeft(left);
            headerPane.setRight(right);
            BorderPane.setAlignment(left, Pos.CENTER_LEFT);
            BorderPane.setAlignment(right, Pos.CENTER_RIGHT);
            header.getChildren().setAll(headerPane);
            channelBox.getStyleClass().add("channel-mapping-table");
            root.getChildren().setAll(header, channelBox);
            root.setPadding(new Insets(8, 10, 8, 10));
            root.setMaxWidth(Double.MAX_VALUE);
        }

        private void constrainToViewport(double viewport) {
            if (viewport <= 20) {
                return;
            }
            // Clamp with maxWidth only. Setting prefWidth==viewport re-enters
            // viewportBounds listeners when the vertical scrollbar appears/disappears.
            root.setMinWidth(0);
            root.setMaxWidth(viewport);
            double inner = Math.max(300, viewport - GROUP_PAD_H);
            channelBox.setMaxWidth(inner);
            header.setMaxWidth(inner);
        }

        private void applyWidths(ColumnBudget budget, double viewport) {
            constrainToViewport(viewport);
            applyColumnHeaderWidths(colHeader, budget);
            for (Node n : channelBox.getChildren()) {
                if (n.getStyleClass().contains("channel-mapping-row") && n instanceof Region row) {
                    double inner = sourceGroupInnerWidth();
                    if (inner > 1) {
                        // maxWidth only — avoid prefWidth feedback into the ScrollPane viewport.
                        row.setMaxWidth(inner);
                    }
                    if (row instanceof HBox hbox && hbox.getChildren().size() >= 6) {
                        sizeFixed((Region) hbox.getChildren().get(0), budget.channel());
                        sizeFixed((Region) hbox.getChildren().get(1), budget.ids());
                        sizeFixed((Region) hbox.getChildren().get(2), budget.dbc());
                        sizeFixed((Region) hbox.getChildren().get(3), budget.match());
                        sizeFixed((Region) hbox.getChildren().get(4), budget.state());
                        sizeFixed((Region) hbox.getChildren().get(5), budget.actions());
                    }
                }
            }
        }

        private void update(SourceMappingGroup group, ColumnBudget budget, double viewport) {
            boolean expanded = expandedBySourceId.getOrDefault(group.sourceId().value(), group.expanded());
            Node chevron = StellarIcons.expandRight();
            if (expanded) {
                chevron.setRotate(90);
            }
            expandBtn.setGraphic(chevron);
            expandBtn.setOnAction(e -> toggleExpanded(group.sourceId()));
            aliasLabel.setText(SourceDisplayNameResolver.primaryIdentity(group.label()));
            nameLabel.setVisible(false);
            nameLabel.setManaged(false);
            Tooltip.install(aliasLabel, new Tooltip(
                    SourceDisplayNameResolver.identityTooltip(group.label())));
            copyPathItem.setOnAction(e -> {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(group.label().fullPath());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            });

            long uniqueIds;
            long frames;
            String format;
            int channels;
            if (group.inventory() != null) {
                uniqueIds = group.inventory().globalUniqueCanIdentityCount();
                frames = group.inventory().totalCanFrames();
                format = group.inventory().format().name();
                channels = (int) group.inventory().totalCanChannels();
            } else {
                uniqueIds = -1L;
                frames = 0L;
                format = "—";
                channels = group.allSourceChannels().size();
            }
            String uniqueText = uniqueIds >= 0
                    ? numberFormat.format(uniqueIds) + " Unique CAN IDs"
                    : channels + " channel row" + (channels == 1 ? "" : "s");
            String framesText = frames > 0
                    ? SourceDisplayNameResolver.compactFrameCount(frames) + " Frames"
                    : "— Frames";
            metaLabel.setText(format + " · " + channels + " Ch · " + uniqueText + " · " + framesText);
            if (frames > 0) {
                Tooltip.install(metaLabel, new Tooltip(numberFormat.format(frames) + " frames (exact)"));
            }

            // Full-source counts (not filter-visible only).
            int rec = 0;
            int conf = 0;
            int man = 0;
            int amb = 0;
            int none = 0;
            for (SourceChannelMappingEntry e : group.allSourceChannels()) {
                switch (e.status()) {
                    case RECOMMENDED -> rec++;
                    case CONFIRMED -> conf++;
                    case MANUAL -> man++;
                    case AMBIGUOUS -> amb++;
                    case NO_MATCH -> none++;
                    default -> {
                    }
                }
            }
            List<String> parts = new ArrayList<>();
            if (rec > 0) {
                parts.add(rec + " Recommended");
            }
            if (conf > 0) {
                parts.add(conf + " Confirmed");
            }
            if (man > 0) {
                parts.add(man + " Manual");
            }
            if (amb > 0) {
                parts.add(amb + " Ambiguous");
            }
            if (none > 0) {
                parts.add(none + " No Match");
            }
            stateSummary.setText(parts.isEmpty() ? "—" : String.join(" · ", parts));
            boolean compactBulk = budget.density() == ActionDensity.NARROW_ICON
                    || sourceGroupInnerWidth() < DbcMappingWidthPolicy.WIDE_MIN_INNER;
            if (compactBulk) {
                bulkConfirm.setText("Confirm " + rec);
                Tooltip.install(bulkConfirm, new Tooltip(
                        "Confirm " + rec + " recommended mapping" + (rec == 1 ? "" : "s")));
            } else {
                bulkConfirm.setText("Confirm Recommended (" + rec + ")");
                Tooltip.install(bulkConfirm, new Tooltip("Confirm all recommended mappings for this source"));
            }
            bulkConfirm.setMaxWidth(compactBulk ? 120 : 210);
            bulkConfirm.setMinWidth(120);
            bulkConfirm.setPrefWidth(compactBulk ? 120 : 200);
            bulkConfirm.setMaxWidth(210);
            bulkConfirm.setVisible(rec > 0);
            bulkConfirm.setManaged(true);
            bulkConfirm.setDisable(rec <= 0);
            if (rec <= 0) {
                bulkConfirm.setText("");
                bulkConfirm.setGraphic(null);
            } else if (bulkConfirm.getGraphic() == null) {
                bulkConfirm.setGraphic(StellarIcons.check());
            }
            bulkConfirm.setOnAction(e -> confirmRecommendedForSource(group));

            boundSourceId = group.sourceId();
            if (expanded) {
                applyColumnHeaderWidths(colHeader, budget);
                List<Node> rowNodes = new ArrayList<>();
                rowNodes.add(colHeader);
                java.util.Set<Integer> keepChannels = new java.util.HashSet<>();
                for (SourceChannelMappingEntry entry : group.channels()) {
                    int chId = entry.sourceChannelKey().channelId();
                    keepChannels.add(chId);
                    HBox row = rowsByChannel.get(chId);
                    if (row == null) {
                        row = (HBox) buildChannelRow(entry, budget);
                        rowsByChannel.put(chId, row);
                    } else {
                        updateChannelRow(row, entry, budget);
                    }
                    rowNodes.add(row);
                }
                rowsByChannel.keySet().removeIf(id -> !keepChannels.contains(id));
                if (!channelBox.getChildren().equals(rowNodes)) {
                    channelBox.getChildren().setAll(rowNodes);
                }
                channelBox.setVisible(true);
                channelBox.setManaged(true);
            } else {
                channelBox.setVisible(false);
                channelBox.setManaged(false);
            }
            constrainToViewport(viewport);
        }

    }

    private HBox buildColumnHeader() {
        HBox header = new HBox(DbcMappingWidthPolicy.COL_GAP);
        header.getStyleClass().add("channel-mapping-col-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().setAll(
                colHeaderLabel("Channel"),
                colHeaderLabel("IDs"),
                colHeaderLabel("DBC"),
                colHeaderLabel("Match / Coverage"),
                colHeaderLabel("State"),
                colHeaderLabel("Actions")
        );
        return header;
    }

    private static Label colHeaderLabel(String text) {
        Label label = new Label(text);
        label.setTextOverrun(OverrunStyle.CLIP);
        return label;
    }

    private void applyColumnHeaderWidths(HBox header, ColumnBudget budget) {
        if (header.getChildren().size() < 6) {
            return;
        }
        sizeFixed((Region) header.getChildren().get(0), budget.channel());
        sizeFixed((Region) header.getChildren().get(1), budget.ids());
        sizeFixed((Region) header.getChildren().get(2), budget.dbc());
        sizeFixed((Region) header.getChildren().get(3), budget.match());
        sizeFixed((Region) header.getChildren().get(4), budget.state());
        sizeFixed((Region) header.getChildren().get(5), budget.actions());
        header.setMaxWidth(sourceGroupInnerWidth());
    }

    private static void sizeFixed(Region node, double width) {
        node.setMinWidth(width);
        node.setPrefWidth(width);
        node.setMaxWidth(width);
    }

    private void updateChannelRow(HBox row, SourceChannelMappingEntry entry, ColumnBudget budget) {
        double inner = sourceGroupInnerWidth();
        boolean narrow = budget.density() == ActionDensity.NARROW_ICON;
        if (row.getChildren().size() < 6) {
            return;
        }
        Label ch = (Label) row.getChildren().get(0);
        ch.setText("Ch" + entry.sourceChannelKey().channelId());
        sizeFixed(ch, budget.channel());

        Label ids = (Label) row.getChildren().get(1);
        ids.setText(numberFormat.format(entry.observedUniqueMessageCount()));
        sizeFixed(ids, budget.ids());

        Label dbc = (Label) row.getChildren().get(2);
        dbc.setText(dbcText(entry));
        sizeFixed(dbc, budget.dbc());
        Tooltip.install(dbc, new Tooltip(dbcText(entry)));

        VBox matchBox = (VBox) row.getChildren().get(3);
        sizeFixed(matchBox, budget.match());
        CoverageQuality quality = coveragePolicy.classify(entry.coverageRatio());
        if (!matchBox.getChildren().isEmpty() && matchBox.getChildren().get(0) instanceof Label match) {
            match.setText(matchText(entry));
        }
        if (matchBox.getChildren().size() > 1 && matchBox.getChildren().get(1) instanceof HBox barLine) {
            if (barLine.getChildren().size() > 1 && barLine.getChildren().get(1) instanceof Label qualityBadge) {
                qualityBadge.setText(quality.badgeLabel());
                qualityBadge.getStyleClass().setAll("coverage-quality-badge", quality.styleClass());
            }
            if (!barLine.getChildren().isEmpty()) {
                barLine.getChildren().set(0, coverageBar(entry.coverageRatio()));
            }
        }
        Tooltip.install(matchBox, new Tooltip(
                numberFormat.format(entry.matchedUniqueMessageCount())
                        + " of "
                        + numberFormat.format(entry.observedUniqueMessageCount())
                        + " observed CAN IDs match this DBC."));

        Label state = (Label) row.getChildren().get(4);
        state.setText(entry.status().badgeLabel());
        state.getStyleClass().setAll("mapping-state-badge", stateStyle(entry.status()));
        sizeFixed(state, budget.state());
        Tooltip.install(state, new Tooltip(entry.status().displayLabel()));

        HBox actions = (HBox) row.getChildren().get(5);
        sizeFixed(actions, budget.actions());
        rebuildActionButtons(actions, entry, narrow);

        row.setMinHeight(ROW_HEIGHT);
        row.setPrefHeight(ROW_HEIGHT);
        row.setMaxHeight(ROW_HEIGHT);
        if (inner > 1) {
            row.setMinWidth(0);
            row.setMaxWidth(inner);
        }
    }

    private void rebuildActionButtons(HBox actions, SourceChannelMappingEntry entry, boolean narrow) {
        boolean canConfirm = entry.status() == ChannelMappingStatus.RECOMMENDED
                && entry.recommendedDefinitionId().isPresent();
        boolean notApplicable = entry.status() == ChannelMappingStatus.NOT_APPLICABLE;
        boolean hasCandidates = appService != null
                && !appService.definitionMatchingModel().candidatesFor(entry.sourceChannelKey()).isEmpty();
        boolean hasComparisons = appService != null
                && !appService.comparisonsFor(entry.sourceChannelKey()).isEmpty();

        List<Node> next = new ArrayList<>();
        if (canConfirm) {
            Button confirm = actionButton("Confirm", StellarIcons.check(), true, narrow);
            Tooltip.install(confirm, new Tooltip("Confirm recommended DBC mapping"));
            confirm.setOnAction(e -> confirmOne(entry));
            next.add(confirm);
        }
        if (!notApplicable) {
            Button choose = actionButton("Choose", StellarIcons.swap(), false, narrow);
            Tooltip.install(choose, new Tooltip("Choose another DBC"));
            choose.setDisable(!hasCandidates);
            choose.setOnAction(e -> openCandidateChooser(entry));
            next.add(choose);

            Button compare = actionButton("Compare", StellarIcons.compare(), false, narrow);
            Tooltip.install(compare, new Tooltip("Compare DBC candidates"));
            compare.setDisable(!hasComparisons);
            compare.setOnAction(e -> openComparisonView(entry));
            next.add(compare);
        }
        // Keep outer actions width fixed; only swap children.
        if (!actions.getChildren().equals(next)) {
            actions.getChildren().setAll(next);
        }
    }

    private HBox buildChannelRow(SourceChannelMappingEntry entry, ColumnBudget budget) {
        double inner = sourceGroupInnerWidth();
        boolean narrow = budget.density() == ActionDensity.NARROW_ICON;

        Label ch = new Label("Ch" + entry.sourceChannelKey().channelId());
        ch.getStyleClass().add("channel-col");
        sizeFixed(ch, budget.channel());

        Label ids = new Label(numberFormat.format(entry.observedUniqueMessageCount()));
        ids.getStyleClass().add("ids-col");
        sizeFixed(ids, budget.ids());

        Label dbc = new Label(dbcText(entry));
        dbc.getStyleClass().add("dbc-col");
        sizeFixed(dbc, budget.dbc());
        dbc.setTextOverrun(OverrunStyle.ELLIPSIS);
        Tooltip.install(dbc, new Tooltip(dbcText(entry)));

        CoverageQuality quality = coveragePolicy.classify(entry.coverageRatio());
        VBox matchBox = new VBox(2);
        matchBox.getStyleClass().add("match-coverage-cell");
        sizeFixed(matchBox, budget.match());
        matchBox.setAlignment(Pos.CENTER_LEFT);
        Label match = new Label(matchText(entry));
        match.getStyleClass().add("match-col");
        Label qualityBadge = new Label(quality.badgeLabel());
        qualityBadge.getStyleClass().addAll("coverage-quality-badge", quality.styleClass());
        qualityBadge.setMinWidth(108);
        qualityBadge.setPrefWidth(108);
        qualityBadge.setMaxWidth(108);
        qualityBadge.setTextOverrun(OverrunStyle.CLIP);
        HBox barLine = new HBox(6, coverageBar(entry.coverageRatio()), qualityBadge);
        barLine.setAlignment(Pos.CENTER_LEFT);
        matchBox.getChildren().setAll(match, barLine);
        Tooltip.install(matchBox, new Tooltip(
                numberFormat.format(entry.matchedUniqueMessageCount())
                        + " of "
                        + numberFormat.format(entry.observedUniqueMessageCount())
                        + " observed CAN IDs match this DBC."));

        Label state = new Label(entry.status().badgeLabel());
        state.getStyleClass().addAll("mapping-state-badge", stateStyle(entry.status()));
        sizeFixed(state, budget.state());
        state.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(state, new Tooltip(entry.status().displayLabel()));

        HBox actions = new HBox(6);
        actions.getStyleClass().add("channel-mapping-actions");
        actions.setAlignment(Pos.CENTER_LEFT);
        sizeFixed(actions, budget.actions());

        rebuildActionButtons(actions, entry, narrow);

        HBox row = new HBox(DbcMappingWidthPolicy.COL_GAP, ch, ids, dbc, matchBox, state, actions);
        row.getStyleClass().add("channel-mapping-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(ROW_HEIGHT);
        row.setPrefHeight(ROW_HEIGHT);
        row.setMaxHeight(ROW_HEIGHT);
        if (inner > 1) {
            row.setMinWidth(0);
            row.setMaxWidth(inner);
        } else {
            row.setMaxWidth(Double.MAX_VALUE);
        }
        return row;
    }

    private Button actionButton(String text, Node icon, boolean primary, boolean narrow) {
        Button b = new Button(narrow ? "" : text);
        b.setGraphic(icon);
        b.setGraphicTextGap(5);
        b.setAlignment(Pos.CENTER);
        b.getStyleClass().addAll(
                primary ? "primary-button" : "secondary-button",
                "mapping-action-button",
                primary ? "mapping-action-primary" : "mapping-action-secondary"
        );
        if (narrow) {
            b.getStyleClass().add("mapping-action-compact");
            b.setMinWidth(34);
            b.setPrefWidth(36);
            b.setMaxWidth(38);
            b.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        } else {
            double width = text.startsWith("Comp") ? 90 : 80;
            b.setMinWidth(width);
            b.setPrefWidth(width);
            b.setMaxWidth(width);
            b.setContentDisplay(javafx.scene.control.ContentDisplay.LEFT);
        }
        b.setMinHeight(30);
        b.setPrefHeight(32);
        b.setMaxHeight(32);
        b.setEllipsisString("");
        b.setWrapText(false);
        return b;
    }

    private Node coverageBar(double ratio) {
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        double width = 56;
        double height = 5;
        Rectangle track = new Rectangle(width, height);
        track.getStyleClass().add("coverage-bar-track");
        track.setFill(javafx.scene.paint.Color.web("#E4EAF0"));
        track.setStrokeWidth(0);
        Rectangle fill = new Rectangle(Math.max(0.0, width * clamped), height);
        fill.getStyleClass().add("coverage-bar-fill");
        fill.setFill(com.stellar.platform.desktop.ui.theme.StellarTheme.INFO);
        fill.setStrokeWidth(0);
        StackPane stack = new StackPane(track, fill);
        stack.setAlignment(Pos.CENTER_LEFT);
        stack.getStyleClass().add("coverage-bar");
        stack.setMaxWidth(width);
        stack.setPrefWidth(width);
        stack.setMinWidth(width);
        return stack;
    }

    private String matchText(SourceChannelMappingEntry entry) {
        if (entry.status() == ChannelMappingStatus.NOT_APPLICABLE) {
            return "—";
        }
        if (entry.observedUniqueMessageCount() <= 0L) {
            return "0 / 0";
        }
        return numberFormat.format(entry.matchedUniqueMessageCount())
                + " / "
                + numberFormat.format(entry.observedUniqueMessageCount())
                + " · "
                + percentFormat.format(entry.coveragePercent())
                + "%";
    }

    private static String stateStyle(ChannelMappingStatus status) {
        return switch (status) {
            case RECOMMENDED -> "state-recommended";
            case CONFIRMED -> "state-confirmed";
            case MANUAL -> "state-manual";
            case AMBIGUOUS -> "state-ambiguous";
            case NO_MATCH -> "state-no-match";
            case UNMAPPED -> "state-unmapped";
            case NOT_APPLICABLE -> "state-na";
        };
    }

    /** Presentation group — not a domain entity. */
    public record SourceMappingGroup(
            ProjectSourceId sourceId,
            DisplayLabel label,
            CanSourceInventory inventory,
            List<SourceChannelMappingEntry> channels,
            boolean expanded,
            List<SourceChannelMappingEntry> allSourceChannels
    ) {
        public SourceMappingGroup {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(label, "label");
            channels = List.copyOf(channels == null ? List.of() : channels);
            allSourceChannels = List.copyOf(
                    allSourceChannels == null ? channels : allSourceChannels);
        }

        public int recommendedCount() {
            return (int) allSourceChannels.stream()
                    .filter(c -> c.status() == ChannelMappingStatus.RECOMMENDED)
                    .count();
        }

        public int confirmedCount() {
            return (int) allSourceChannels.stream()
                    .filter(c -> c.status() == ChannelMappingStatus.CONFIRMED)
                    .count();
        }
    }

    // Test hooks (presentation-only)
    List<SourceMappingGroup> allGroupsForTest() {
        return allGroups;
    }

    List<SourceMappingGroup> visibleGroupsForTest() {
        return List.copyOf(visibleGroups);
    }

    Map<String, Boolean> expansionStateForTest() {
        return Map.copyOf(expandedBySourceId);
    }

    void setSearchTextForTest(String text) {
        searchField.setText(text == null ? "" : text);
    }

    void toggleExpandedForTest(ProjectSourceId sourceId) {
        toggleExpanded(sourceId);
    }

    CoverageClassificationPolicy coveragePolicyForTest() {
        return coveragePolicy;
    }

    ScrollPane groupScrollForTest() {
        return groupScroll;
    }

    VBox groupBoxForTest() {
        return groupBox;
    }

    HBox toolbarForTest() {
        return toolbar;
    }

    double mappingViewportWidthForTest() {
        return mappingViewportWidth();
    }

    double sourceGroupInnerWidthForTest() {
        return sourceGroupInnerWidth();
    }

    ColumnBudget currentBudgetForTest() {
        return currentBudget();
    }
}

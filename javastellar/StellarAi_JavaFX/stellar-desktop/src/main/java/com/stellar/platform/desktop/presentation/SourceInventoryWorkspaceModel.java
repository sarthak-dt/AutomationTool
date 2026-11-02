package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.DisplayLabel;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.SourceRef;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.CanTypeFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.ContentFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.MatchFilter;
import com.stellar.platform.desktop.project.MeasurementSourceCapability;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * Presentation state for the multi-source Source Inventory workspace.
 * Selection/expansion/filters only — never reparses or mutates matching.
 */
public final class SourceInventoryWorkspaceModel {

    public record ChannelView(
            int channelId,
            String label,
            long uniqueIds,
            long frames
    ) {
    }

    public record SourceView(
            ProjectSourceId sourceId,
            String alias,
            String compactName,
            String fullFileName,
            String fullPath,
            String formatBadge,
            ProjectInventoryJobState jobState,
            long channelCount,
            long uniqueIds,
            long frames,
            boolean expanded,
            List<ChannelView> channels,
            CanSourceInventory inventory
    ) {
    }

    public record WorkspaceSnapshot(
            List<SourceView> sources,
            int measurementSourceCount,
            int totalCanChannels,
            ProjectSourceId selectedSourceId,
            OptionalInt selectedChannelId,
            String searchText,
            CanTypeFilter canTypeFilter,
            MatchFilter matchFilter,
            ContentFilter contentFilter
    ) {
        public WorkspaceSnapshot(
                List<SourceView> sources,
                int measurementSourceCount,
                int totalCanChannels,
                ProjectSourceId selectedSourceId,
                OptionalInt selectedChannelId,
                String searchText,
                CanTypeFilter canTypeFilter
        ) {
            this(sources, measurementSourceCount, totalCanChannels, selectedSourceId, selectedChannelId,
                    searchText, canTypeFilter, MatchFilter.ALL, ContentFilter.ALL);
        }
    }

    private ProjectSourceId selectedSourceId;
    private Integer selectedChannelId; // null = All Channels for selected source
    private final Map<String, Boolean> expandedBySourceId = new LinkedHashMap<>();
    private String searchText = "";
    private CanTypeFilter canTypeFilter = CanTypeFilter.ALL;
    private MatchFilter matchFilter = MatchFilter.ALL;
    private ContentFilter contentFilter = ContentFilter.ALL;

    public ProjectSourceId selectedSourceId() {
        return selectedSourceId;
    }

    public OptionalInt selectedChannelId() {
        return selectedChannelId == null ? OptionalInt.empty() : OptionalInt.of(selectedChannelId);
    }

    public boolean isAllChannelsSelected() {
        return selectedChannelId == null;
    }

    public String searchText() {
        return searchText;
    }

    public CanTypeFilter canTypeFilter() {
        return canTypeFilter;
    }

    public MatchFilter matchFilter() {
        return matchFilter;
    }

    public ContentFilter contentFilter() {
        return contentFilter;
    }

    public boolean isExpanded(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return false;
        }
        return expandedBySourceId.getOrDefault(sourceId.value(), true);
    }

    public void setExpanded(ProjectSourceId sourceId, boolean expanded) {
        if (sourceId == null) {
            return;
        }
        expandedBySourceId.put(sourceId.value(), expanded);
    }

    public void setSearchText(String text) {
        this.searchText = text == null ? "" : text;
    }

    public void setCanTypeFilter(CanTypeFilter filter) {
        this.canTypeFilter = filter == null ? CanTypeFilter.ALL : filter;
    }

    public void setMatchFilter(MatchFilter filter) {
        this.matchFilter = filter == null ? MatchFilter.ALL : filter;
    }

    public void setContentFilter(ContentFilter filter) {
        this.contentFilter = filter == null ? ContentFilter.ALL : filter;
    }

    public void clearFilters() {
        this.searchText = "";
        this.canTypeFilter = CanTypeFilter.ALL;
        this.matchFilter = MatchFilter.ALL;
        this.contentFilter = ContentFilter.ALL;
    }

    public void selectSource(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return;
        }
        this.selectedSourceId = sourceId;
        this.selectedChannelId = null;
        expandedBySourceId.putIfAbsent(sourceId.value(), true);
    }

    public void selectAllChannels(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return;
        }
        this.selectedSourceId = sourceId;
        this.selectedChannelId = null;
        expandedBySourceId.put(sourceId.value(), true);
    }

    public void selectChannel(ProjectSourceId sourceId, int channelId) {
        Objects.requireNonNull(sourceId, "sourceId");
        this.selectedSourceId = sourceId;
        this.selectedChannelId = channelId;
        expandedBySourceId.put(sourceId.value(), true);
    }

    /**
     * Rebuild navigator snapshot from workspace order. Preserves selection/expansion when possible.
     */
    public WorkspaceSnapshot rebuild(
            List<WorkspaceFile> measurements,
            SourceInventoryModel inventoryModel,
            SourceAliasRegistry aliasRegistry,
            Function<ProjectSourceId, ProjectInventoryJobState> jobStateFn
    ) {
        List<WorkspaceFile> files = measurements == null ? List.of() : measurements;
        SourceInventoryModel inventories = inventoryModel == null ? new SourceInventoryModel() : inventoryModel;
        SourceAliasRegistry aliases = aliasRegistry == null ? new SourceAliasRegistry() : aliasRegistry;
        Function<ProjectSourceId, ProjectInventoryJobState> jobs = jobStateFn == null
                ? id -> ProjectInventoryJobState.WAITING
                : jobStateFn;

        List<SourceRef> refs = new ArrayList<>();
        for (WorkspaceFile file : files) {
            if (file == null || !file.kind().measurement()) {
                continue;
            }
            ProjectSourceId sid = file.projectSourceId();
            aliases.aliasFor(sid);
            refs.add(new SourceRef(sid, file.fileName(), file.path()));
        }
        Map<ProjectSourceId, DisplayLabel> labels = SourceDisplayNameResolver.resolve(refs);
        // Overlay stable registry aliases (not list-index aliases from resolve).
        Map<ProjectSourceId, DisplayLabel> stableLabels = new LinkedHashMap<>();
        for (SourceRef ref : refs) {
            DisplayLabel base = labels.get(ref.sourceId());
            String alias = aliases.aliasFor(ref.sourceId());
            if (base == null) {
                stableLabels.put(ref.sourceId(), new DisplayLabel(
                        ref.sourceId(), alias, ref.fileName(), ref.fileName(),
                        ref.path() == null ? ref.fileName() : ref.path().toString()));
            } else {
                stableLabels.put(ref.sourceId(), new DisplayLabel(
                        base.sourceId(), alias, base.distinguishingName(),
                        base.fullFileName(), base.fullPath()));
            }
        }

        java.util.Set<String> live = new java.util.LinkedHashSet<>();
        for (WorkspaceFile file : files) {
            live.add(file.id());
        }
        aliases.retainOnly(live);
        expandedBySourceId.keySet().removeIf(id -> !live.contains(id));

        if (selectedSourceId != null && !live.contains(selectedSourceId.value())) {
            selectedSourceId = null;
            selectedChannelId = null;
        }
        if (selectedSourceId == null && !files.isEmpty()) {
            selectedSourceId = files.getFirst().projectSourceId();
            selectedChannelId = null;
            expandedBySourceId.putIfAbsent(selectedSourceId.value(), true);
        }

        List<SourceView> sources = new ArrayList<>();
        int totalChannels = 0;
        for (WorkspaceFile file : files) {
            ProjectSourceId sid = file.projectSourceId();
            DisplayLabel label = stableLabels.get(sid);
            CanSourceInventory inv = inventories.inventoryFor(sid.value()).orElse(null);
            ProjectInventoryJobState job = jobs.apply(sid);
            List<ChannelView> channels = new ArrayList<>();
            long channelCount = 0;
            long unique = 0;
            long frames = 0;
            if (inv != null) {
                channelCount = inv.totalCanChannels();
                unique = inv.globalUniqueCanIdentityCount();
                frames = inv.totalCanFrames();
                for (CanChannelInventory ch : inv.channels()) {
                    channels.add(new ChannelView(
                            ch.channelId(),
                            "Ch" + ch.channelId(),
                            ch.uniqueMessageCount(),
                            ch.frameCount()
                    ));
                }
            }
            totalChannels += (int) channelCount;
            boolean expanded = expandedBySourceId.getOrDefault(sid.value(), sources.isEmpty());
            expandedBySourceId.putIfAbsent(sid.value(), expanded);
            String format = inv != null && inv.format() != null
                    ? inv.format().name()
                    : formatBadge(file);
            sources.add(new SourceView(
                    sid,
                    label == null ? aliases.aliasFor(sid) : label.alias(),
                    label == null ? file.fileName() : label.distinguishingName(),
                    label == null ? file.fileName() : label.fullFileName(),
                    label == null ? file.path().toString() : label.fullPath(),
                    format,
                    job,
                    channelCount,
                    unique,
                    frames,
                    expandedBySourceId.getOrDefault(sid.value(), expanded),
                    List.copyOf(channels),
                    inv
            ));
        }

        if (selectedSourceId != null && selectedChannelId != null) {
            Optional<SourceView> sel = sources.stream()
                    .filter(s -> s.sourceId().equals(selectedSourceId))
                    .findFirst();
            if (sel.isPresent()) {
                boolean channelExists = sel.get().channels().stream()
                        .anyMatch(c -> c.channelId() == selectedChannelId);
                if (!channelExists) {
                    selectedChannelId = null;
                }
            }
        }

        return new WorkspaceSnapshot(
                List.copyOf(sources),
                files.size(),
                totalChannels,
                selectedSourceId,
                selectedChannelId(),
                searchText,
                canTypeFilter,
                matchFilter,
                contentFilter
        );
    }

    public SourceInventoryWorkspaceQuery.MessageQueryResult querySelected(WorkspaceSnapshot snapshot) {
        return querySelected(snapshot, key -> SourceChannelEnrichmentContext.noDbc(key));
    }

    public SourceInventoryWorkspaceQuery.MessageQueryResult querySelected(
            WorkspaceSnapshot snapshot,
            java.util.function.Function<SourceChannelKey, SourceChannelEnrichmentContext> enrichmentLookup
    ) {
        if (snapshot == null || snapshot.selectedSourceId() == null) {
            return new SourceInventoryWorkspaceQuery.MessageQueryResult(List.of(), 0, 0, 0, 0, 0);
        }
        SourceView source = snapshot.sources().stream()
                .filter(s -> s.sourceId().equals(snapshot.selectedSourceId()))
                .findFirst()
                .orElse(null);
        if (source == null || source.inventory() == null) {
            return new SourceInventoryWorkspaceQuery.MessageQueryResult(List.of(), 0, 0, 0, 0, 0);
        }
        return SourceInventoryWorkspaceQuery.queryMessages(
                source.sourceId(),
                source.inventory(),
                snapshot.selectedChannelId(),
                snapshot.searchText(),
                snapshot.canTypeFilter(),
                snapshot.matchFilter(),
                snapshot.contentFilter(),
                enrichmentLookup
        );
    }

    public static String statusLabel(ProjectInventoryJobState state, CanSourceInventory inventory) {
        if (state != null) {
            return state.displayLabel().toUpperCase(java.util.Locale.ROOT);
        }
        if (inventory == null) {
            return "WAITING";
        }
        return switch (inventory.status()) {
            case INVENTORY_READY -> "READY";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case DISCOVERING_CHANNELS, CHANNELS_DISCOVERED, INDEXING_MESSAGE_IDS, FILE_ACCEPTED -> "INDEXING";
        };
    }

    private static String formatBadge(WorkspaceFile file) {
        if (file == null || file.kind() == null) {
            return "";
        }
        if (MeasurementSourceCapability.supportsDirectProcessing(file.kind())) {
            return file.kind().displayName();
        }
        if (file.detectedFormatLabel() != null && !file.detectedFormatLabel().isBlank()) {
            return file.formatDisplayName();
        }
        return switch (file.kind()) {
            case MEASUREMENT_MF4 -> "MF4";
            case MEASUREMENT_ASC -> "ASC";
            case MEASUREMENT_BLF -> "BLF";
            default -> file.kind().displayName();
        };
    }
}

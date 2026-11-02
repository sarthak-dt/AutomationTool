package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ObservedTimingMetadata;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.plan.ChannelMappingView;
import com.stellar.platform.processing.plan.ProcessingPlanInputs;
import com.stellar.platform.processing.plan.SourcePlanInput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Assembles {@link ProcessingPlanInputs} from existing desktop project state only.
 * Never scans inventories, reparses DBC, or decodes frames.
 */
public final class ProcessingPlanInputAssembler {

    /** Optional test/hook map; default empty — never invents timing offsets. */
    private Map<String, ObservedTimingMetadata> observedTimingBySourceId = Map.of();

    public void setObservedTimingBySourceId(Map<String, ObservedTimingMetadata> timingBySourceId) {
        if (timingBySourceId == null || timingBySourceId.isEmpty()) {
            this.observedTimingBySourceId = Map.of();
        } else {
            LinkedHashMap<String, ObservedTimingMetadata> copy = new LinkedHashMap<>();
            for (Map.Entry<String, ObservedTimingMetadata> e : timingBySourceId.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    copy.put(e.getKey(), e.getValue().copy());
                }
            }
            this.observedTimingBySourceId = Map.copyOf(copy);
        }
    }

    public Map<String, ObservedTimingMetadata> observedTimingBySourceId() {
        return observedTimingBySourceId;
    }

    public ProcessingPlanInputs assemble(DesktopAppService app) {
        Objects.requireNonNull(app, "app");
        List<WorkspaceFile> measurements = app.workspace().measurementFiles();
        Set<String> definitionIds = new HashSet<>();
        for (WorkspaceFile def : app.workspace().definitionFiles()) {
            definitionIds.add(def.id());
        }

        List<SourcePlanInput> sources = new ArrayList<>(measurements.size());
        int order = 0;
        for (WorkspaceFile file : measurements) {
            CanSourceInventory inventory = app.sourceInventoryModel().inventoryFor(file.id())
                    .orElseGet(() -> emptyInventoryStub(file));
            ObservedTimingMetadata timing = observedTimingBySourceId.get(file.id());
            sources.add(new SourcePlanInput(file.id(), order++, inventory, timing));
        }

        List<ChannelMappingView> mappings = new ArrayList<>();
        Set<SourceChannelKey> mappedKeys = new HashSet<>();
        if (app.processingDecisionMode() == DecisionWorkspaceMode.QUICK_DECODE) {
            for (SourceChannelMappingEntry entry : app.definitionMatchingModel().allMappings()) {
                mappings.add(toQuickChannelMappingView(entry.sourceChannelKey(), definitionIds, app));
                mappedKeys.add(entry.sourceChannelKey());
            }
            // Inventory-ready channels without a mapping row still resolve (typically NO_MATCH).
            for (WorkspaceFile file : measurements) {
                CanSourceInventory inventory = app.sourceInventoryModel().inventoryFor(file.id()).orElse(null);
                if (inventory == null || inventory.status() != CanInventoryStatus.INVENTORY_READY) {
                    continue;
                }
                for (var channel : inventory.channels()) {
                    SourceChannelKey key = SourceChannelKey.of(file.projectSourceId(), channel.channelId());
                    if (mappedKeys.add(key)) {
                        mappings.add(toQuickChannelMappingView(key, definitionIds, app));
                    }
                }
            }
        } else {
            for (SourceChannelMappingEntry entry : app.definitionMatchingModel().allMappings()) {
                mappings.add(toChannelMappingView(entry, definitionIds, app));
            }
        }
        return new ProcessingPlanInputs(sources, mappings);
    }


    static ChannelMappingView toQuickChannelMappingView(
            SourceChannelKey key,
            Set<String> definitionIds,
            DesktopAppService app
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(app, "app");
        QuickDecodeDecisionResolver.Decision decision = app.quickDecodeDecisionResolver().resolve(key);
        SourceChannelDefinitionCandidate selected = decision.selected();
        String definitionId = selected == null ? null : selected.definitionSourceId().value();
        String definitionName = selected == null ? "" : selected.definitionDisplayName();
        long observed = selected == null
                ? app.channelMapping(key).map(SourceChannelMappingEntry::observedUniqueMessageCount).orElse(0L)
                : selected.observedUniqueMessageCount();
        long matched = selected == null ? 0L : selected.matchedUniqueMessageCount();
        double coverage = selected == null ? 0.0 : selected.coverageRatio();
        boolean definitionPresent = definitionAvailable(definitionId, definitionIds, app);
        return new ChannelMappingView(
                SourceChannelRef.of(key.sourceId().value(), key.channelId()),
                decision.planLifecycle(),
                definitionId,
                definitionName,
                observed,
                matched,
                coverage,
                selected == null ? 0L : selected.observedFrameCount(),
                selected == null ? 0L : selected.matchedFrameCount(),
                selected == null ? 0.0 : selected.frameCoverageRatio(),
                definitionPresent
        );
    }

    static ChannelMappingView toChannelMappingView(SourceChannelMappingEntry entry, Set<String> definitionIds) {
        return toChannelMappingView(entry, definitionIds, null);
    }

    static ChannelMappingView toChannelMappingView(
            SourceChannelMappingEntry entry,
            Set<String> definitionIds,
            DesktopAppService app
    ) {
        Objects.requireNonNull(entry, "entry");
        EffectiveResolution effective = resolveEffective(entry);
        MappingLifecycle lifecycle;
        String definitionId = null;
        String definitionDisplayName = "";
        if (effective.resolved()) {
            lifecycle = effective.planLifecycle();
            definitionId = effective.definitionId().value();
            definitionDisplayName = effective.displayName();
        } else if (isNativeOnly(entry)) {
            lifecycle = MappingLifecycle.NO_MATCH;
        } else {
            lifecycle = MappingLifecycle.valueOf(entry.status().name());
            if (lifecycle == MappingLifecycle.RECOMMENDED) {
                definitionId = entry.recommendedDefinitionId().map(DefinitionSourceId::value).orElse(null);
                definitionDisplayName = entry.recommendedDisplayName();
            }
        }
        boolean definitionPresent = definitionAvailable(definitionId, definitionIds, app);
        SourceChannelDefinitionCandidate effectiveCandidate = null;
        if (app != null && definitionId != null) {
            DefinitionSourceId effectiveId = DefinitionSourceId.of(definitionId);
            effectiveCandidate = app.candidatesFor(entry.sourceChannelKey()).stream()
                    .filter(candidate -> candidate.definitionSourceId().equals(effectiveId))
                    .findFirst()
                    .orElse(null);
        }
        long matchedIds = effectiveCandidate == null
                ? entry.matchedUniqueMessageCount() : effectiveCandidate.matchedUniqueMessageCount();
        double idCoverage = effectiveCandidate == null
                ? entry.coverageRatio() : effectiveCandidate.coverageRatio();
        SourceChannelRef channel = SourceChannelRef.of(
                entry.sourceChannelKey().sourceId().value(),
                entry.sourceChannelKey().channelId());
        return new ChannelMappingView(
                channel,
                lifecycle,
                definitionId,
                definitionDisplayName,
                entry.observedUniqueMessageCount(),
                matchedIds,
                idCoverage,
                effectiveCandidate == null ? 0L : effectiveCandidate.observedFrameCount(),
                effectiveCandidate == null ? 0L : effectiveCandidate.matchedFrameCount(),
                effectiveCandidate == null ? 0.0 : effectiveCandidate.frameCoverageRatio(),
                definitionPresent
        );
    }

    private static boolean definitionAvailable(
            String definitionId,
            Set<String> definitionIds,
            DesktopAppService app
    ) {
        if (definitionId == null || definitionId.isBlank()) {
            return false;
        }
        if (definitionIds != null && definitionIds.contains(definitionId)) {
            return true;
        }
        return app != null
                && app.definitionMatchingCoordinator()
                .parsedDefinition(DefinitionSourceId.of(definitionId))
                .isPresent();
    }

    private static CanSourceInventory emptyInventoryStub(WorkspaceFile file) {
        return CanSourceInventory.empty(file.path(), formatOf(file.kind()), CanInventoryStatus.FILE_ACCEPTED);
    }

    public enum EffectiveOrigin {
        MANUAL,
        CONFIRMED,
        RECOMMENDED_FALLBACK
    }

    public record EffectiveResolution(
            boolean resolved,
            DefinitionSourceId definitionId,
            String displayName,
            EffectiveOrigin origin
    ) {
        public MappingLifecycle planLifecycle() {
            if (!resolved || origin == null) {
                return MappingLifecycle.UNMAPPED;
            }
            return switch (origin) {
                case MANUAL -> MappingLifecycle.MANUAL;
                case CONFIRMED -> MappingLifecycle.CONFIRMED;
                case RECOMMENDED_FALLBACK -> MappingLifecycle.RECOMMENDED_FALLBACK;
            };
        }

        /**
         * Decision Workspace → Processing eligibility.
         * Only explicit user acceptance (CONFIRMED / MANUAL) creates a processing job.
         * A recommendation is advisory and is not an accepted processing selection.
         */
        public boolean acceptedForProcessing() {
            return resolved
                    && (origin == EffectiveOrigin.MANUAL || origin == EffectiveOrigin.CONFIRMED);
        }
    }

    /** A channel with no accepted DBC and no ID overlap. Never inherits another channel's DBC. */
    private static boolean isNativeOnly(SourceChannelMappingEntry entry) {
        if (entry.status() == ChannelMappingStatus.MANUAL || entry.status() == ChannelMappingStatus.CONFIRMED) {
            return false;
        }
        return entry.status() == ChannelMappingStatus.NO_MATCH
                || entry.recommendationKind() == ChannelRecommendationKind.NO_MATCH;
    }

    private static final EffectiveResolution UNRESOLVED =
            new EffectiveResolution(false, null, "", null);

    /**
     * Central effective-DBC policy. Does not mutate mapping state or re-rank recommendations.
     */
    public static EffectiveResolution resolveEffective(SourceChannelMappingEntry mapping) {
        if (mapping == null) {
            return UNRESOLVED;
        }
        if (mapping.status() == ChannelMappingStatus.MANUAL && mapping.mappedDefinitionId().isPresent()) {
            return new EffectiveResolution(true, mapping.mappedDefinitionId().orElseThrow(),
                    mapping.mappedDisplayName(), EffectiveOrigin.MANUAL);
        }
        if (mapping.status() == ChannelMappingStatus.CONFIRMED && mapping.mappedDefinitionId().isPresent()) {
            return new EffectiveResolution(true, mapping.mappedDefinitionId().orElseThrow(),
                    mapping.mappedDisplayName(), EffectiveOrigin.CONFIRMED);
        }
        if (mapping.status() == ChannelMappingStatus.RECOMMENDED
                && mapping.recommendationKind() == ChannelRecommendationKind.CLEAR_BEST_MATCH
                && mapping.recommendedDefinitionId().isPresent()) {
            return new EffectiveResolution(true, mapping.recommendedDefinitionId().orElseThrow(),
                    mapping.recommendedDisplayName(), EffectiveOrigin.RECOMMENDED_FALLBACK);
        }
        return UNRESOLVED;
    }

    private static MeasurementSourceFormat formatOf(FileKind kind) {
        if (kind == null) {
            return MeasurementSourceFormat.UNKNOWN;
        }
        return switch (kind) {
            case MEASUREMENT_MF4 -> MeasurementSourceFormat.MF4;
            case MEASUREMENT_ASC -> MeasurementSourceFormat.ASC;
            case MEASUREMENT_BLF -> MeasurementSourceFormat.BLF;
            default -> MeasurementSourceFormat.UNKNOWN;
        };
    }
}
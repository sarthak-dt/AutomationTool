package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.processing.catalog.CompositeSignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds Signals views for completed Quick Decode runs.
 * <p>
 * Project Explore Signals uses {@link ProjectMergedSignalStore}: equivalent engineering signals
 * across measurement files collapse to one logical catalog row (measurement file is not part of
 * logical identity). Source View Signals keeps a source-scoped composite without cross-file merge.
 * Physical samples remain in member stores (zero-copy).
 */
public final class QuickDecodeSignalsViewAssembler {

    private static final System.Logger LOG = System.getLogger(QuickDecodeSignalsViewAssembler.class.getName());
    /** Catalog namespace for native-only jobs. Not a DBC identity and not persisted as definitionId. */
    private static final String NATIVE_ONLY_DEFINITION_IDENTITY = "_none_";

    public record View(
            SignalSeriesStore store,
            String hint,
            SignalsProcessingContext context,
            int successfulChannels
    ) {
        public View {
            Objects.requireNonNull(store, "store");
            hint = hint == null ? "" : hint;
            Objects.requireNonNull(context, "context");
            if (successfulChannels < 1) {
                throw new IllegalArgumentException("successfulChannels must be >= 1");
            }
        }
    }

    public Optional<View> assemble(
            MultiSourceProcessingResult batch,
            Function<String, String> sourceDisplayName
    ) {
        if (batch == null) {
            return Optional.empty();
        }
        return assembleResults(batch.jobResults(), sourceDisplayName);
    }

    /** Project-wide Explore Signals: logically merged across all successful jobs. */
    public Optional<View> assembleResults(
            List<IndependentJobResult> results,
            Function<String, String> sourceDisplayName
    ) {
        if (results == null) {
            return Optional.empty();
        }
        List<IndependentJobResult> successful = results.stream()
                .filter(IndependentJobResult::successful)
                .toList();
        return assembleProjectWide(successful, sourceDisplayName);
    }

    /**
     * Builds one consolidated Quick Decode Signals view for a single measurement source.
     * All successful channel stores for that source are included; no cross-source merge.
     */
    public Optional<View> assembleForSource(
            MultiSourceProcessingResult batch,
            String projectSourceId,
            Function<String, String> sourceDisplayName
    ) {
        return batch == null
                ? Optional.empty()
                : assembleForSource(batch.jobResults(), projectSourceId, sourceDisplayName);
    }

    /** Source-level View Signals from the exact current-plan result set. */
    public Optional<View> assembleForSource(
            List<IndependentJobResult> results,
            String projectSourceId,
            Function<String, String> sourceDisplayName
    ) {
        if (results == null || projectSourceId == null || projectSourceId.isBlank()) {
            return Optional.empty();
        }
        List<IndependentJobResult> sourceResults = results.stream()
                .filter(IndependentJobResult::successful)
                .filter(result -> projectSourceId.equals(result.projectSourceId()))
                .toList();
        return assembleSourceScoped(sourceResults, sourceDisplayName);
    }

    private Optional<View> assembleProjectWide(
            List<IndependentJobResult> successfulResults,
            Function<String, String> sourceDisplayName
    ) {
        PreparedMembers prepared = prepareMembers(successfulResults, sourceDisplayName);
        if (prepared == null) {
            return Optional.empty();
        }
        List<ProjectMergedSignalStore.Member> mergedMembers = new ArrayList<>(prepared.members.size());
        for (CompositeSignalCatalogStore.Member m : prepared.members) {
            mergedMembers.add(new ProjectMergedSignalStore.Member(
                    m.store(),
                    m.measurementIdentity(),
                    m.definitionIdentity(),
                    m.measurementDisplayName(),
                    m.definitionDisplayName()
            ));
        }
        ProjectMergedSignalStore store = ProjectMergedSignalStore.fromMembers(
                mergedMembers, prepared.sourceSummary, prepared.definitionSummary);
        validateMergedRoutes(store);
        return Optional.of(new View(
                store,
                prepared.hint,
                SignalsProcessingContext.quickDecode(
                        prepared.sourceSummary,
                        prepared.definitionSummary,
                        prepared.channelSummary,
                        SignalViewScope.PROJECT),
                prepared.successful.size()
        ));
    }

    /**
     * Catalog metadata with samples must resolve to a live member route before Explore opens.
     * Cheap retained-count probe only — never loads full series / reparses MF4.
     */
    private static void validateMergedRoutes(ProjectMergedSignalStore store) {
        for (var entry : store.snapshot().entries()) {
            if (entry.sampleCount() <= 0) {
                continue;
            }
            if (!store.hasResolvableSamples(entry.key())) {
                LOG.log(System.Logger.Level.WARNING, () ->
                        "Signal data unavailable (broken merged route) key=" + entry.key()
                                + " scope=PROJECT"
                                + " store=" + store.getClass().getSimpleName()
                                + " metaSamples=" + entry.sampleCount()
                                + " retained=" + store.retainedSampleCount(entry.key()));
            }
        }
    }

    private Optional<View> assembleSourceScoped(
            List<IndependentJobResult> successfulResults,
            Function<String, String> sourceDisplayName
    ) {
        PreparedMembers prepared = prepareMembers(successfulResults, sourceDisplayName);
        if (prepared == null) {
            return Optional.empty();
        }
        CompositeSignalCatalogStore composite = CompositeSignalCatalogStore.fromMembers(
                prepared.members, prepared.sourceSummary, prepared.definitionSummary);
        return Optional.of(new View(
                composite,
                prepared.hint,
                SignalsProcessingContext.quickDecode(
                        prepared.sourceSummary,
                        prepared.definitionSummary,
                        prepared.channelSummary,
                        SignalViewScope.SOURCE),
                prepared.successful.size()
        ));
    }

    private PreparedMembers prepareMembers(
            List<IndependentJobResult> successfulResults,
            Function<String, String> sourceDisplayName
    ) {
        Function<String, String> sourceLabeler = sourceDisplayName == null
                ? id -> id
                : sourceDisplayName;

        List<IndependentJobResult> successful = successfulResults.stream()
                .sorted(Comparator.comparing(IndependentJobResult::projectSourceId)
                        .thenComparingInt(IndependentJobResult::channelId)
                        .thenComparing(r -> r.definitionSourceId().orElse("")))
                .toList();
        if (successful.isEmpty()) {
            return null;
        }

        List<CompositeSignalCatalogStore.Member> members = new ArrayList<>(successful.size());
        Set<String> sourceIds = new LinkedHashSet<>();
        Map<String, String> definitionNamesById = new LinkedHashMap<>();
        for (IndependentJobResult result : successful) {
            Object handle = result.catalogHandle().orElseThrow(() -> new IllegalStateException(
                    "Successful processing result has no signal catalog: " + result.jobKey()));
            if (!(handle instanceof SignalSeriesStore store)) {
                throw new IllegalStateException(
                        "Unsupported signal catalog handle for " + result.jobKey() + ": "
                                + handle.getClass().getName());
            }
            String definitionId = result.definitionSourceId()
                    .filter(id -> !id.isBlank())
                    .orElse(NATIVE_ONLY_DEFINITION_IDENTITY);
            String sourceId = result.projectSourceId();
            String sourceName = nonBlank(sourceLabeler.apply(sourceId), sourceId);
            String definitionName = NATIVE_ONLY_DEFINITION_IDENTITY.equals(definitionId)
                    ? "none"
                    : nonBlank(result.definitionDisplayName(), definitionId);
            sourceIds.add(sourceId);
            definitionNamesById.putIfAbsent(definitionId, definitionName);
            members.add(new CompositeSignalCatalogStore.Member(
                    store,
                    sourceId,
                    definitionId,
                    sourceName,
                    definitionName
            ));
        }

        String sourceSummary = sourceIds.size() == 1
                ? nonBlank(sourceLabeler.apply(sourceIds.iterator().next()), sourceIds.iterator().next())
                : sourceIds.size() + " measurements";
        String definitionSummary = nativeOnlySummary(definitionNamesById);
        String channelSummary = successful.size() + (successful.size() == 1 ? " Channel" : " Channels");
        String hint = "Auto Decode · " + sourceSummary + " · " + channelSummary + " · " + definitionSummary;
        return new PreparedMembers(successful, members, sourceSummary, definitionSummary, channelSummary, hint);
    }

    private record PreparedMembers(
            List<IndependentJobResult> successful,
            List<CompositeSignalCatalogStore.Member> members,
            String sourceSummary,
            String definitionSummary,
            String channelSummary,
            String hint
    ) {
    }

    private static String nativeOnlySummary(Map<String, String> definitionNamesById) {
        List<String> applied = new ArrayList<>();
        for (Map.Entry<String, String> entry : definitionNamesById.entrySet()) {
            String id = entry.getKey();
            if (!AppliedDbcPresentation.isAppliedIdentity(id)) {
                continue;
            }
            String name = entry.getValue();
            if (!AppliedDbcPresentation.isAppliedName(name)) {
                name = id;
            }
            applied.add(name.trim());
        }
        if (applied.isEmpty()) {
            return AppliedDbcPresentation.NATIVE_ONLY;
        }
        if (applied.size() == 1) {
            return applied.getFirst();
        }
        return applied.size() + " DBCs";
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

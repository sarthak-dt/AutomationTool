package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelDefinitionComparison;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanIdMatchDiagnostic;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.inventory.CanDefinitionMatchingService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MF-4 project-level multi-source &times; multi-DBC cross matching.
 * Uses existing {@link CanDefinitionMatchingService} semantics. No measurement parsing.
 * Observed ID counts always come from {@link CanChannelInventory}, never from empty placeholders.
 */
public final class ProjectDefinitionMatchingCoordinator {

    private static final System.Logger LOG = System.getLogger(ProjectDefinitionMatchingCoordinator.class.getName());

    public record ParsedDefinition(
            DefinitionSourceId definitionSourceId,
            String displayName,
            DbcDatabase database
    ) {
    }

    private final CanDefinitionMatchingService matchingService;
    private final RecommendationRankingPolicy rankingPolicy;
    private final ProjectDefinitionMatchingModel model;
    private final Map<String, CanSourceInventory> readyInventories = new LinkedHashMap<>();
    private final Map<String, ParsedDefinition> definitions = new LinkedHashMap<>();

    public ProjectDefinitionMatchingCoordinator(
            CanDefinitionMatchingService matchingService,
            ProjectDefinitionMatchingModel model
    ) {
        this(matchingService, new RecommendationRankingPolicy(), model);
    }

    public ProjectDefinitionMatchingCoordinator(
            CanDefinitionMatchingService matchingService,
            RecommendationRankingPolicy rankingPolicy,
            ProjectDefinitionMatchingModel model
    ) {
        this.matchingService = Objects.requireNonNull(matchingService, "matchingService");
        this.rankingPolicy = Objects.requireNonNull(rankingPolicy, "rankingPolicy");
        this.model = Objects.requireNonNull(model, "model");
    }

    public ProjectDefinitionMatchingModel model() {
        return model;
    }

    public void clear() {
        readyInventories.clear();
        definitions.clear();
        model.clear();
    }

    public void registerDefinition(DefinitionSourceId id, String displayName, DbcDatabase database) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(database, "database");
        definitions.put(id.value(), new ParsedDefinition(id, displayName == null ? id.value() : displayName, database));
        recomputeAll();
    }

    public void unregisterDefinition(DefinitionSourceId id) {
        if (id == null) {
            return;
        }
        definitions.remove(id.value());
        model.removeDefinition(id);
        recomputeAll();
    }

    public Optional<ParsedDefinition> parsedDefinition(DefinitionSourceId id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(id.value()));
    }

    public Map<String, ParsedDefinition> parsedDefinitions() {
        return Map.copyOf(definitions);
    }

    public int readySourceChannelCount() {
        int n = 0;
        for (CanSourceInventory inventory : readyInventories.values()) {
            n += inventory.channels().size();
        }
        return n;
    }

    public int definitionCount() {
        return definitions.size();
    }

    public void onInventoryReady(ProjectSourceId sourceId, CanSourceInventory inventory) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (!ensureReadyInventory(sourceId, inventory)) {
            return;
        }
        recomputeAll();
    }

    /**
     * Store a READY inventory without recomputing. Used when batching sync before definition register.
     * @return true if inventory was accepted as READY
     */
    public boolean ensureReadyInventory(ProjectSourceId sourceId, CanSourceInventory inventory) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (!isReady(inventory)) {
            onInventoryRemoved(sourceId);
            return false;
        }
        readyInventories.put(sourceId.value(), inventory);
        return true;
    }

    public void onInventoryRemoved(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return;
        }
        readyInventories.remove(sourceId.value());
        model.removeSource(sourceId);
    }

    public void recomputeAll() {
        for (ParsedDefinition def : definitions.values()) {
            logDefinitionIdentities(def);
        }
        List<SourceChannelDefinitionCandidate> allCandidates = new ArrayList<>();
        List<SourceChannelDefinitionComparison> allComparisons = new ArrayList<>();
        List<SourceChannelMappingEntry> nextMappings = new ArrayList<>();
        int sourceChannels = 0;
        for (Map.Entry<String, CanSourceInventory> e : readyInventories.entrySet()) {
            ProjectSourceId sourceId = ProjectSourceId.of(e.getKey());
            sourceChannels += e.getValue().channels().size();
            computeSource(sourceId, e.getValue(), allCandidates, allComparisons, nextMappings);
        }
        int defs = definitions.size();
        int expected = sourceChannels * defs;
        LOG.log(System.Logger.Level.INFO,
                "DBC cross-match: sourceChannels={0}, definitions={1}, expectedComparisons={2}, actualComparisons={3}",
                sourceChannels, defs, expected, allCandidates.size());
        model.replaceCandidates(allCandidates);
        model.replaceComparisons(allComparisons);
        model.replaceMappings(nextMappings);
    }

    /** Read-only: ID-level comparisons already computed for a source channel. */
    public List<SourceChannelDefinitionComparison> comparisonsFor(SourceChannelKey key) {
        return model.comparisonsFor(key);
    }

    public void recomputeSource(ProjectSourceId sourceId) {
        recomputeAll();
    }

    public void confirmRecommendation(SourceChannelKey key) {
        Objects.requireNonNull(key, "key");
        SourceChannelMappingEntry confirmed = confirmedEntryFromRecommendation(key);
        if (confirmed != null) {
            model.putMapping(confirmed);
        }
    }

    /**
     * Confirm multiple clear recommendations in one model notification (avoids multi-step UI flicker).
     */
    public void confirmRecommendations(List<SourceChannelKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<SourceChannelMappingEntry> batch = new ArrayList<>();
        for (SourceChannelKey key : keys) {
            if (key == null) {
                continue;
            }
            SourceChannelMappingEntry confirmed = confirmedEntryFromRecommendation(key);
            if (confirmed != null) {
                batch.add(confirmed);
            }
        }
        if (!batch.isEmpty()) {
            model.putMappings(batch);
        }
    }

    private SourceChannelMappingEntry confirmedEntryFromRecommendation(SourceChannelKey key) {
        SourceChannelMappingEntry current = model.mapping(key).orElse(null);
        if (current == null
                || current.recommendedDefinitionId().isEmpty()
                || current.status() != ChannelMappingStatus.RECOMMENDED
                || current.recommendationKind() != ChannelRecommendationKind.CLEAR_BEST_MATCH) {
            // Confirm only accepts a clear StellarAI recommendation — not Ambiguous / No Match.
            return null;
        }
        DefinitionSourceId defId = current.recommendedDefinitionId().orElseThrow();
        String name = current.recommendedDisplayName();
        return new SourceChannelMappingEntry(
                key,
                ChannelMappingStatus.CONFIRMED,
                current.recommendationKind(),
                defId,
                name,
                defId,
                name,
                current.observedUniqueMessageCount(),
                current.matchedUniqueMessageCount(),
                current.coverageRatio()
        );
    }

    public void chooseDefinition(SourceChannelKey key, DefinitionSourceId definitionId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(definitionId, "definitionId");
        SourceChannelMappingEntry current = model.mapping(key).orElse(null);
        SourceChannelDefinitionCandidate chosen = model.candidatesFor(key).stream()
                .filter(c -> c.definitionSourceId().equals(definitionId))
                .findFirst()
                .orElse(null);
        if (chosen == null) {
            return;
        }
        ChannelRecommendationKind kind = current == null
                ? ChannelRecommendationKind.CLEAR_BEST_MATCH
                : current.recommendationKind();
        DefinitionSourceId recommendedId = current == null ? null : current.recommendedDefinitionId().orElse(null);
        String recommendedName = current == null ? "" : current.recommendedDisplayName();
        // MANUAL = engineer selected a definition that is not the clear recommendation.
        // Selecting the clear recommended definition via Choose Another is equivalent to Confirm.
        boolean acceptingClearRecommendation = current != null
                && current.status() == ChannelMappingStatus.RECOMMENDED
                && current.recommendationKind() == ChannelRecommendationKind.CLEAR_BEST_MATCH
                && recommendedId != null
                && recommendedId.equals(definitionId);
        ChannelMappingStatus status = acceptingClearRecommendation
                ? ChannelMappingStatus.CONFIRMED
                : ChannelMappingStatus.MANUAL;
        model.putMapping(new SourceChannelMappingEntry(
                key,
                status,
                kind,
                recommendedId,
                recommendedName,
                definitionId,
                chosen.definitionDisplayName(),
                chosen.observedUniqueMessageCount(),
                chosen.matchedUniqueMessageCount(),
                chosen.coverageRatio()
        ));
    }

    public void clearMapping(SourceChannelKey key) {
        if (key == null) {
            return;
        }
        CanSourceInventory inventory = readyInventories.get(key.sourceId().value());
        if (inventory == null) {
            model.removeMapping(key);
            return;
        }
        CanChannelInventory channel = inventory.channels().stream()
                .filter(c -> c.channelId() == key.channelId())
                .findFirst()
                .orElse(null);
        if (channel == null) {
            model.removeMapping(key);
            return;
        }
        List<SourceChannelDefinitionCandidate> candidates = new ArrayList<>();
        List<SourceChannelDefinitionComparison> comparisons = new ArrayList<>();
        List<SourceChannelMappingEntry> mappings = new ArrayList<>();
        computeChannel(key.sourceId(), channel, candidates, comparisons, mappings, null);
        if (!mappings.isEmpty()) {
            model.putMapping(mappings.getFirst());
        }
    }

    private void computeSource(
            ProjectSourceId sourceId,
            CanSourceInventory inventory,
            List<SourceChannelDefinitionCandidate> allCandidates,
            List<SourceChannelDefinitionComparison> allComparisons,
            List<SourceChannelMappingEntry> nextMappings
    ) {
        for (CanChannelInventory channel : inventory.channels()) {
            SourceChannelKey key = SourceChannelKey.of(sourceId, channel.channelId());
            SourceChannelMappingEntry previous = model.mapping(key).orElse(null);
            computeChannel(sourceId, channel, allCandidates, allComparisons, nextMappings, previous);
        }
    }

    private void computeChannel(
            ProjectSourceId sourceId,
            CanChannelInventory channel,
            List<SourceChannelDefinitionCandidate> allCandidates,
            List<SourceChannelDefinitionComparison> allComparisons,
            List<SourceChannelMappingEntry> nextMappings,
            SourceChannelMappingEntry previous
    ) {
        SourceChannelKey key = SourceChannelKey.of(sourceId, channel.channelId());
        long observed = observedUniqueCount(channel);
        logObservedIdentities(sourceId, channel, observed);

        if (observed <= 0L) {
            nextMappings.add(new SourceChannelMappingEntry(
                    key,
                    ChannelMappingStatus.NOT_APPLICABLE,
                    ChannelRecommendationKind.NOT_APPLICABLE,
                    null,
                    "",
                    null,
                    "",
                    0L,
                    0L,
                    0.0
            ));
            return;
        }

        if (definitions.isEmpty()) {
            // CAN inventory exists; definitions not yet available — keep observed IDs visible.
            nextMappings.add(new SourceChannelMappingEntry(
                    key,
                    ChannelMappingStatus.UNMAPPED,
                    ChannelRecommendationKind.NO_MATCH,
                    null,
                    "",
                    null,
                    "",
                    observed,
                    0L,
                    0.0
            ));
            return;
        }

        List<SourceChannelDefinitionCandidate> channelCandidates = new ArrayList<>();
        for (ParsedDefinition def : definitions.values()) {
            int defMessageCount = def.database().messages().size();
            CanChannelMatchSummary summary = matchingService.matchChannel(channel, def.database());
            List<CanIdMatchDiagnostic> diagnostics = matchingService.diagnoseChannel(channel, def.database());
            long matched = summary.matchedCount();
            long unmatched = Math.max(0L, observed - matched);
            double ratio = observed <= 0L ? 0.0 : (matched * 1.0) / observed;
            long observedFrames = Math.max(0L, channel.frameCount());
            long matchedFrames = summary.messages().stream()
                    .filter(com.stellar.platform.domain.inventory.CanMessageMatch::matched)
                    .mapToLong(com.stellar.platform.domain.inventory.CanMessageMatch::observedFrameCount)
                    .sum();
            matchedFrames = Math.min(observedFrames, Math.max(0L, matchedFrames));
            double frameRatio = observedFrames <= 0L ? 0.0 : (matchedFrames * 1.0) / observedFrames;
            DefinitionCandidateKind kind = resolveCandidateKind(defMessageCount, matched);
            SourceChannelDefinitionCandidate candidate = new SourceChannelDefinitionCandidate(
                    key,
                    def.definitionSourceId(),
                    def.displayName(),
                    defMessageCount,
                    kind,
                    observed,
                    matched,
                    unmatched,
                    ratio,
                    observedFrames,
                    matchedFrames,
                    frameRatio
            );
            SourceChannelDefinitionComparison comparison = new SourceChannelDefinitionComparison(
                    key,
                    def.definitionSourceId(),
                    def.displayName(),
                    defMessageCount,
                    kind,
                    observed,
                    matched,
                    ratio,
                    diagnostics
            );
            channelCandidates.add(candidate);
            allCandidates.add(candidate);
            allComparisons.add(comparison);
            LOG.log(System.Logger.Level.DEBUG,
                    "match source={0} ch={1} def={2} ({3}) observed={4} matched={5} idCoverage={6} frameCoverage={7} kind={8}",
                    sourceId.value(),
                    channel.channelId(),
                    def.definitionSourceId().value(),
                    def.displayName(),
                    observed,
                    matched,
                    ratio,
                    frameRatio,
                    kind);
            for (CanIdMatchDiagnostic d : diagnostics) {
                LOG.log(System.Logger.Level.DEBUG,
                        "  id=0x{0} {1} -> {2}{3}",
                        Long.toHexString(d.observedCanId()).toUpperCase(),
                        d.observedTypeLabel(),
                        d.status(),
                        d.matchedDbcMessageName().map(n -> " (" + n + ")").orElse(""));
            }
        }

        RecommendationRankingPolicy.RankedRecommendation ranked = rankingPolicy.rank(channelCandidates);
        nextMappings.add(mergeWithPrevious(key, ranked, previous, observed));
    }

    private static DefinitionCandidateKind resolveCandidateKind(int definitionMessageCount, long matched) {
        if (definitionMessageCount <= 0) {
            return DefinitionCandidateKind.EMPTY_DEFINITION;
        }
        if (matched <= 0L) {
            return DefinitionCandidateKind.NO_OVERLAP;
        }
        return DefinitionCandidateKind.HAS_OVERLAP;
    }

    private void logObservedIdentities(ProjectSourceId sourceId, CanChannelInventory channel, long observed) {
        String ids = channel.messages().stream()
                .map(m -> String.format(
                        "0x%X %s (raw=%d)",
                        m.canId(),
                        m.extended() ? "EXTENDED" : "STANDARD",
                        m.canId()))
                .collect(Collectors.joining(", "));
        LOG.log(System.Logger.Level.DEBUG,
                "Observed IDs source={0} ch={1} count={2}: [{3}]",
                sourceId.value(),
                channel.channelId(),
                observed,
                ids);
    }

    private void logDefinitionIdentities(ParsedDefinition def) {
        String named = def.database().messages().stream()
                .map(m -> {
                    CanIdKey k = m.canId();
                    return m.name() + "=0x" + Long.toHexString(k.canId()).toUpperCase()
                            + (k.extended() ? "E" : "S")
                            + " (rawDbc=0x" + Long.toHexString(k.toDbcRawId()).toUpperCase() + ")";
                })
                .collect(Collectors.joining(", "));
        LOG.log(System.Logger.Level.DEBUG,
                "Parsed DBC def={0} name={1} messageCount={2}: [{3}]",
                def.definitionSourceId().value(),
                def.displayName(),
                def.database().messages().size(),
                named);
    }

    private SourceChannelMappingEntry mergeWithPrevious(
            SourceChannelKey key,
            RecommendationRankingPolicy.RankedRecommendation ranked,
            SourceChannelMappingEntry previous,
            long observed
    ) {
        if (previous != null
                && (previous.status() == ChannelMappingStatus.CONFIRMED
                || previous.status() == ChannelMappingStatus.MANUAL)
                && previous.mappedDefinitionId().isPresent()) {
            DefinitionSourceId mappedId = previous.mappedDefinitionId().orElseThrow();
            if (definitions.containsKey(mappedId.value())) {
                SourceChannelDefinitionCandidate mappedCandidate = ranked.ranked().stream()
                        .filter(c -> c.definitionSourceId().equals(mappedId))
                        .findFirst()
                        .orElse(null);
                long matched = mappedCandidate == null ? 0L : mappedCandidate.matchedUniqueMessageCount();
                double ratio = mappedCandidate == null ? 0.0 : mappedCandidate.coverageRatio();
                String mappedName = mappedCandidate == null
                        ? previous.mappedDisplayName()
                        : mappedCandidate.definitionDisplayName();
                DefinitionSourceId recommendedId = ranked.bestOrNull() == null
                        ? null : ranked.bestOrNull().definitionSourceId();
                String recommendedName = ranked.bestOrNull() == null
                        ? "" : ranked.bestOrNull().definitionDisplayName();
                return new SourceChannelMappingEntry(
                        key,
                        previous.status(),
                        ranked.kind(),
                        recommendedId,
                        recommendedName,
                        mappedId,
                        mappedName,
                        observed,
                        matched,
                        ratio
                );
            }
        }
        return toEntryFromRecommendation(key, ranked, observed);
    }

    private SourceChannelMappingEntry toEntryFromRecommendation(
            SourceChannelKey key,
            RecommendationRankingPolicy.RankedRecommendation ranked,
            long observed
    ) {
        return switch (ranked.kind()) {
            case NOT_APPLICABLE -> new SourceChannelMappingEntry(
                    key,
                    ChannelMappingStatus.NOT_APPLICABLE,
                    ranked.kind(),
                    null,
                    "",
                    null,
                    "",
                    observed,
                    0L,
                    0.0
            );
            case NO_MATCH -> new SourceChannelMappingEntry(
                    key,
                    ChannelMappingStatus.NO_MATCH,
                    ranked.kind(),
                    null,
                    "",
                    null,
                    "",
                    observed,
                    0L,
                    0.0
            );
            case AMBIGUOUS -> new SourceChannelMappingEntry(
                    key,
                    ChannelMappingStatus.AMBIGUOUS,
                    ranked.kind(),
                    ranked.bestOrNull() == null ? null : ranked.bestOrNull().definitionSourceId(),
                    ranked.bestOrNull() == null ? "" : ranked.bestOrNull().definitionDisplayName(),
                    null,
                    "",
                    observed,
                    ranked.bestOrNull() == null ? 0L : ranked.bestOrNull().matchedUniqueMessageCount(),
                    ranked.bestOrNull() == null ? 0.0 : ranked.bestOrNull().coverageRatio()
            );
            case CLEAR_BEST_MATCH -> new SourceChannelMappingEntry(
                    key,
                    ChannelMappingStatus.RECOMMENDED,
                    ranked.kind(),
                    ranked.bestOrNull().definitionSourceId(),
                    ranked.bestOrNull().definitionDisplayName(),
                    null,
                    "",
                    observed,
                    ranked.bestOrNull().matchedUniqueMessageCount(),
                    ranked.bestOrNull().coverageRatio()
            );
        };
    }

    /** Prefer message list size when uniqueMessageCount drifts; never invent IDs. */
    static long observedUniqueCount(CanChannelInventory channel) {
        if (channel == null) {
            return 0L;
        }
        long fromField = channel.uniqueMessageCount();
        long fromMessages = channel.messages().size();
        return Math.max(fromField, fromMessages);
    }

    private static boolean isReady(CanSourceInventory inventory) {
        return inventory != null
                && inventory.channelDiscoveryComplete()
                && inventory.status() == CanInventoryStatus.INVENTORY_READY;
    }
}

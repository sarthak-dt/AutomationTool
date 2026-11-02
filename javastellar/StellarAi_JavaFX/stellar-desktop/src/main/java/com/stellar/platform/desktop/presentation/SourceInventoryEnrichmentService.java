package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.desktop.presentation.ProjectDefinitionMatchingCoordinator.ParsedDefinition;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Presentation-only enrichment: observed inventory + current SourceChannel mapping + parsed DBC.
 * Does not rematch, reparse measurements, or decode samples.
 * Message lookup uses {@link DbcDatabase#findMessage(CanIdKey)} (same identity as DBC Mapping).
 */
public final class SourceInventoryEnrichmentService {

    private SourceInventoryEnrichmentService() {
    }

    /**
     * Resolve effective DBC for a channel from the current mapping entry.
     * CONFIRMED/MANUAL → mapped DBC; RECOMMENDED → recommended DBC (unconfirmed);
     * AMBIGUOUS → no authoritative DBC; otherwise NO DBC.
     */
    public static SourceChannelEnrichmentContext contextFor(
            SourceChannelKey key,
            SourceChannelMappingEntry mapping,
            Function<DefinitionSourceId, Optional<ParsedDefinition>> definitionLookup
    ) {
        Objects.requireNonNull(key, "key");
        Function<DefinitionSourceId, Optional<ParsedDefinition>> lookup =
                definitionLookup == null ? id -> Optional.empty() : definitionLookup;
        if (mapping == null) {
            return SourceChannelEnrichmentContext.noDbc(key);
        }
        long observed = mapping.observedUniqueMessageCount();
        long matched = mapping.matchedUniqueMessageCount();
        ChannelMappingStatus status = mapping.status();
        return switch (status) {
            case AMBIGUOUS -> SourceChannelEnrichmentContext.ambiguous(key, observed, matched);
            case CONFIRMED, MANUAL -> resolveMapped(key, mapping, status, lookup);
            case RECOMMENDED -> resolveRecommended(key, mapping, lookup);
            case UNMAPPED, NO_MATCH, NOT_APPLICABLE -> SourceChannelEnrichmentContext.noDbc(key);
        };
    }

    /**
     * Preview a candidate DBC without persisting MANUAL/CONFIRMED mapping state.
     */
    public static SourceChannelEnrichmentContext contextForPreview(
            SourceChannelKey key,
            SourceChannelDefinitionCandidate candidate,
            Function<DefinitionSourceId, Optional<ParsedDefinition>> definitionLookup
    ) {
        Objects.requireNonNull(key, "key");
        if (candidate == null) {
            return SourceChannelEnrichmentContext.noDbc(key);
        }
        Function<DefinitionSourceId, Optional<ParsedDefinition>> lookup =
                definitionLookup == null ? id -> Optional.empty() : definitionLookup;
        Optional<ParsedDefinition> parsed = lookup.apply(candidate.definitionSourceId());
        if (parsed.isEmpty()) {
            return SourceChannelEnrichmentContext.noDbc(key);
        }
        ParsedDefinition def = parsed.get();
        String name = candidate.definitionDisplayName().isBlank()
                ? def.displayName() : candidate.definitionDisplayName();
        return SourceChannelEnrichmentContext.withDefinition(
                key,
                ChannelMappingStatus.MANUAL,
                def.definitionSourceId(),
                name,
                def.database(),
                false,
                candidate.observedUniqueMessageCount(),
                candidate.matchedUniqueMessageCount());
    }

    private static SourceChannelEnrichmentContext resolveMapped(
            SourceChannelKey key,
            SourceChannelMappingEntry mapping,
            ChannelMappingStatus status,
            Function<DefinitionSourceId, Optional<ParsedDefinition>> lookup
    ) {
        Optional<DefinitionSourceId> id = mapping.mappedDefinitionId();
        if (id.isEmpty()) {
            return SourceChannelEnrichmentContext.noDbc(key);
        }
        Optional<ParsedDefinition> parsed = lookup.apply(id.get());
        if (parsed.isEmpty()) {
            return SourceChannelEnrichmentContext.noDbc(key);
        }
        ParsedDefinition def = parsed.get();
        String name = mapping.mappedDisplayName().isBlank() ? def.displayName() : mapping.mappedDisplayName();
        return SourceChannelEnrichmentContext.withDefinition(
                key, status, def.definitionSourceId(), name, def.database(),
                false, mapping.observedUniqueMessageCount(), mapping.matchedUniqueMessageCount());
    }

    private static SourceChannelEnrichmentContext resolveRecommended(
            SourceChannelKey key,
            SourceChannelMappingEntry mapping,
            Function<DefinitionSourceId, Optional<ParsedDefinition>> lookup
    ) {
        Optional<DefinitionSourceId> id = mapping.recommendedDefinitionId();
        if (id.isEmpty()) {
            return SourceChannelEnrichmentContext.noDbc(key);
        }
        Optional<ParsedDefinition> parsed = lookup.apply(id.get());
        if (parsed.isEmpty()) {
            return SourceChannelEnrichmentContext.noDbc(key);
        }
        ParsedDefinition def = parsed.get();
        String name = mapping.recommendedDisplayName().isBlank() ? def.displayName() : mapping.recommendedDisplayName();
        return SourceChannelEnrichmentContext.withDefinition(
                key, ChannelMappingStatus.RECOMMENDED, def.definitionSourceId(), name, def.database(),
                true, mapping.observedUniqueMessageCount(), mapping.matchedUniqueMessageCount());
    }

    public static CanMessageDefinitionMatch enrich(
            ProjectSourceId sourceId,
            SourceChannelKey channelKey,
            long canId,
            boolean extended,
            long frames,
            SourceChannelEnrichmentContext context
    ) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(channelKey, "channelKey");
        SourceChannelEnrichmentContext ctx = context == null
                ? SourceChannelEnrichmentContext.noDbc(channelKey)
                : context;
        if (ctx.ambiguous()) {
            return CanMessageDefinitionMatch.ambiguous(sourceId, channelKey, canId, extended, frames);
        }
        if (!ctx.hasDbc()) {
            return CanMessageDefinitionMatch.noDbc(sourceId, channelKey, canId, extended, frames);
        }
        DbcDatabase db = ctx.database().orElseThrow();
        Optional<DbcMessage> message = db.findMessage(CanIdKey.fromFrame(canId, extended));
        if (message.isEmpty()) {
            return new CanMessageDefinitionMatch(
                    sourceId, channelKey, canId, extended, frames,
                    InventoryEnrichmentMatchState.UNMATCHED,
                    ctx.definitionSourceId().orElse(null),
                    ctx.definitionDisplayName(),
                    ctx.mappingStatus().orElse(null),
                    null, null, List.of());
        }
        DbcMessage msg = message.get();
        List<DbcSignalDefinitionView> signals = new ArrayList<>(msg.signals().size());
        for (DbcSignal signal : msg.signals()) {
            signals.add(DbcSignalDefinitionView.from(signal));
        }
        return new CanMessageDefinitionMatch(
                sourceId, channelKey, canId, extended, frames,
                InventoryEnrichmentMatchState.MATCHED,
                ctx.definitionSourceId().orElse(null),
                ctx.definitionDisplayName(),
                ctx.mappingStatus().orElse(null),
                msg.name(),
                msg.sizeBytes(),
                signals);
    }
}

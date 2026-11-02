package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;

import java.util.Objects;
import java.util.Optional;

/**
 * Effective DBC context for one SourceChannelKey, derived from current mapping state.
 * Does not run recommendation; consumes existing mapping entry only.
 */
public final class SourceChannelEnrichmentContext {

    private final SourceChannelKey channelKey;
    private final ChannelMappingStatus mappingStatus;
    private final boolean ambiguous;
    private final DefinitionSourceId definitionSourceId;
    private final String definitionDisplayName;
    private final DbcDatabase database;
    private final boolean recommendedUnconfirmed;
    private final long observedUniqueMessageCount;
    private final long matchedUniqueMessageCount;

    private SourceChannelEnrichmentContext(
            SourceChannelKey channelKey,
            ChannelMappingStatus mappingStatus,
            boolean ambiguous,
            DefinitionSourceId definitionSourceId,
            String definitionDisplayName,
            DbcDatabase database,
            boolean recommendedUnconfirmed,
            long observedUniqueMessageCount,
            long matchedUniqueMessageCount
    ) {
        this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
        this.mappingStatus = mappingStatus;
        this.ambiguous = ambiguous;
        this.definitionSourceId = definitionSourceId;
        this.definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        this.database = database;
        this.recommendedUnconfirmed = recommendedUnconfirmed;
        this.observedUniqueMessageCount = Math.max(0L, observedUniqueMessageCount);
        this.matchedUniqueMessageCount = Math.max(0L, matchedUniqueMessageCount);
    }

    public static SourceChannelEnrichmentContext noDbc(SourceChannelKey key) {
        return new SourceChannelEnrichmentContext(
                key, ChannelMappingStatus.UNMAPPED, false, null, "", null, false, 0, 0);
    }

    public static SourceChannelEnrichmentContext ambiguous(SourceChannelKey key, long observed, long matched) {
        return new SourceChannelEnrichmentContext(
                key, ChannelMappingStatus.AMBIGUOUS, true, null, "", null, false, observed, matched);
    }

    public static SourceChannelEnrichmentContext withDefinition(
            SourceChannelKey key,
            ChannelMappingStatus status,
            DefinitionSourceId definitionId,
            String displayName,
            DbcDatabase database,
            boolean recommendedUnconfirmed,
            long observed,
            long matched
    ) {
        return new SourceChannelEnrichmentContext(
                key, status, false, definitionId, displayName, database, recommendedUnconfirmed, observed, matched);
    }

    public SourceChannelKey channelKey() { return channelKey; }
    public Optional<ChannelMappingStatus> mappingStatus() { return Optional.ofNullable(mappingStatus); }
    public boolean ambiguous() { return ambiguous; }
    public Optional<DefinitionSourceId> definitionSourceId() { return Optional.ofNullable(definitionSourceId); }
    public String definitionDisplayName() { return definitionDisplayName; }
    public Optional<DbcDatabase> database() { return Optional.ofNullable(database); }
    public boolean hasDbc() { return database != null; }
    public boolean recommendedUnconfirmed() { return recommendedUnconfirmed; }
    public long observedUniqueMessageCount() { return observedUniqueMessageCount; }
    public long matchedUniqueMessageCount() { return matchedUniqueMessageCount; }
    public long unmatchedUniqueMessageCount() {
        return Math.max(0L, observedUniqueMessageCount - matchedUniqueMessageCount);
    }
}

package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable enrichment projection for one observed SourceChannelKey + CAN identity.
 * Definition metadata only — no sample decoding.
 */
public final class CanMessageDefinitionMatch {

    private final ProjectSourceId sourceId;
    private final SourceChannelKey channelKey;
    private final long canId;
    private final boolean extended;
    private final long observedFrameCount;
    private final InventoryEnrichmentMatchState matchState;
    private final DefinitionSourceId definitionSourceId;
    private final String definitionDisplayName;
    private final ChannelMappingStatus mappingStatus;
    private final String dbcMessageName;
    private final Integer dlc;
    private final List<DbcSignalDefinitionView> signalDefinitions;

    public CanMessageDefinitionMatch(
            ProjectSourceId sourceId,
            SourceChannelKey channelKey,
            long canId,
            boolean extended,
            long observedFrameCount,
            InventoryEnrichmentMatchState matchState,
            DefinitionSourceId definitionSourceId,
            String definitionDisplayName,
            ChannelMappingStatus mappingStatus,
            String dbcMessageName,
            Integer dlc,
            List<DbcSignalDefinitionView> signalDefinitions
    ) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
        this.canId = canId;
        this.extended = extended;
        this.observedFrameCount = Math.max(0L, observedFrameCount);
        this.matchState = Objects.requireNonNull(matchState, "matchState");
        this.definitionSourceId = definitionSourceId;
        this.definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        this.mappingStatus = mappingStatus;
        this.dbcMessageName = dbcMessageName;
        this.dlc = dlc;
        this.signalDefinitions = List.copyOf(Objects.requireNonNullElse(signalDefinitions, List.of()));
    }

    public static CanMessageDefinitionMatch noDbc(
            ProjectSourceId sourceId, SourceChannelKey channelKey, long canId, boolean extended, long frames) {
        return new CanMessageDefinitionMatch(
                sourceId, channelKey, canId, extended, frames,
                InventoryEnrichmentMatchState.NO_DBC, null, "", null, null, null, List.of());
    }

    public static CanMessageDefinitionMatch ambiguous(
            ProjectSourceId sourceId, SourceChannelKey channelKey, long canId, boolean extended, long frames) {
        return new CanMessageDefinitionMatch(
                sourceId, channelKey, canId, extended, frames,
                InventoryEnrichmentMatchState.AMBIGUOUS, null, "", ChannelMappingStatus.AMBIGUOUS, null, null, List.of());
    }

    public ProjectSourceId sourceId() { return sourceId; }
    public SourceChannelKey channelKey() { return channelKey; }
    public long canId() { return canId; }
    public boolean extended() { return extended; }
    public long observedFrameCount() { return observedFrameCount; }
    public InventoryEnrichmentMatchState matchState() { return matchState; }
    public Optional<DefinitionSourceId> definitionSourceId() { return Optional.ofNullable(definitionSourceId); }
    public String definitionDisplayName() { return definitionDisplayName; }
    public Optional<ChannelMappingStatus> mappingStatus() { return Optional.ofNullable(mappingStatus); }
    public Optional<String> dbcMessageName() { return Optional.ofNullable(dbcMessageName); }
    public String dbcMessageDisplay() { return dbcMessageName == null || dbcMessageName.isBlank() ? "\u2014" : dbcMessageName; }
    public Optional<Integer> dlc() { return Optional.ofNullable(dlc); }
    public int signalCount() { return signalDefinitions.size(); }
    public String signalsDisplay() {
        return matchState == InventoryEnrichmentMatchState.MATCHED ? signalCount() + " signals" : "\u2014";
    }
    public List<DbcSignalDefinitionView> signalDefinitions() { return signalDefinitions; }
    public boolean matched() { return matchState == InventoryEnrichmentMatchState.MATCHED; }
}

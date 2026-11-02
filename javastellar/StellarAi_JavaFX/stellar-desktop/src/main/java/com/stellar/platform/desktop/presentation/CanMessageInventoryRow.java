package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.domain.inventory.CanMessageInventory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Presentation row for one SourceChannelKey + CAN identity observation (+ optional DBC enrichment). */
public final class CanMessageInventoryRow {

    private final ProjectSourceId sourceId;
    private final SourceChannelKey channelKey;
    private final long canId;
    private final boolean extended;
    private final long frameCount;
    private final CanMessageDefinitionMatch enrichment;

    public CanMessageInventoryRow(
            ProjectSourceId sourceId,
            SourceChannelKey channelKey,
            long canId,
            boolean extended,
            long frameCount
    ) {
        this(sourceId, channelKey, canId, extended, frameCount, null);
    }

    public CanMessageInventoryRow(
            ProjectSourceId sourceId,
            SourceChannelKey channelKey,
            long canId,
            boolean extended,
            long frameCount,
            CanMessageDefinitionMatch enrichment
    ) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
        this.canId = canId;
        this.extended = extended;
        this.frameCount = Math.max(0L, frameCount);
        this.enrichment = enrichment;
    }

    public static CanMessageInventoryRow from(ProjectSourceId sourceId, CanMessageInventory message) {
        Objects.requireNonNull(message, "message");
        SourceChannelKey key = SourceChannelKey.of(sourceId, message.channel());
        return new CanMessageInventoryRow(
                sourceId, key, message.canId(), message.extended(), message.frameCount());
    }

    public static CanMessageInventoryRow enriched(
            ProjectSourceId sourceId,
            CanMessageInventory message,
            CanMessageDefinitionMatch enrichment
    ) {
        Objects.requireNonNull(message, "message");
        SourceChannelKey key = SourceChannelKey.of(sourceId, message.channel());
        return new CanMessageInventoryRow(
                sourceId, key, message.canId(), message.extended(), message.frameCount(), enrichment);
    }

    public ProjectSourceId sourceId() { return sourceId; }
    public SourceChannelKey channelKey() { return channelKey; }
    public int channelId() { return channelKey.channelId(); }
    public String channelLabel() { return "Ch" + channelKey.channelId(); }
    public long canId() { return canId; }
    public boolean extended() { return extended; }
    public long frameCount() { return frameCount; }

    public Optional<CanMessageDefinitionMatch> enrichment() {
        return Optional.ofNullable(enrichment);
    }

    public InventoryEnrichmentMatchState matchState() {
        return enrichment == null ? InventoryEnrichmentMatchState.NO_DBC : enrichment.matchState();
    }

    public String matchBadge() {
        return matchState().badgeLabel();
    }

    public String dbcMessageDisplay() {
        return enrichment == null ? "\u2014" : enrichment.dbcMessageDisplay();
    }

    public String signalsDisplay() {
        return enrichment == null ? "\u2014" : enrichment.signalsDisplay();
    }

    public int signalCount() {
        return enrichment == null ? 0 : enrichment.signalCount();
    }

    public List<DbcSignalDefinitionView> signalDefinitions() {
        return enrichment == null ? List.of() : enrichment.signalDefinitions();
    }

    public Optional<DefinitionSourceId> definitionSourceId() {
        return enrichment == null ? Optional.empty() : enrichment.definitionSourceId();
    }

    public String definitionDisplayName() {
        return enrichment == null ? "" : enrichment.definitionDisplayName();
    }

    public Optional<ChannelMappingStatus> mappingStatus() {
        return enrichment == null ? Optional.empty() : enrichment.mappingStatus();
    }

    public Optional<Integer> dlc() {
        return enrichment == null ? Optional.empty() : enrichment.dlc();
    }

    public String canIdHex() {
        return "0x" + Long.toHexString(canId).toUpperCase(Locale.ROOT);
    }

    public String canIdDecimal() {
        return Long.toUnsignedString(canId);
    }

    public String typeCompact() { return extended ? "EXT" : "STD"; }
    public String typeFull() { return extended ? "Extended" : "Standard"; }
}

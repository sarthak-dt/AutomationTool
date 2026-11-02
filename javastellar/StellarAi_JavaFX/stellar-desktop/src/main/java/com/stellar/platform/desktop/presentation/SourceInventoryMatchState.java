package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanDefinitionMatchStatus;
import com.stellar.platform.domain.inventory.CanDefinitionMatchSummary;
import com.stellar.platform.domain.inventory.CanMessageMatch;
import com.stellar.platform.domain.inventory.CanSourceInventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Presentation snapshot for DBC Message-ID matching against the focused source inventory.
 * Holds the already-parsed {@link DbcDatabase} for signal-definition browsing only.
 * No matching algorithm lives here.
 */
public final class SourceInventoryMatchState {

    private final CanDefinitionMatchStatus status;
    private final String dbcFileName;
    private final CanDefinitionMatchSummary summary;
    private final String statusDetail;
    private final DbcDatabase dbcDatabase;

    private SourceInventoryMatchState(
            CanDefinitionMatchStatus status,
            String dbcFileName,
            CanDefinitionMatchSummary summary,
            String statusDetail,
            DbcDatabase dbcDatabase
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.dbcFileName = dbcFileName == null || dbcFileName.isBlank() ? null : dbcFileName;
        this.summary = summary;
        this.statusDetail = statusDetail == null || statusDetail.isBlank() ? null : statusDetail;
        this.dbcDatabase = dbcDatabase;
    }

    public static SourceInventoryMatchState noDbcSelected() {
        return new SourceInventoryMatchState(
                CanDefinitionMatchStatus.NO_DBC_SELECTED,
                null,
                CanDefinitionMatchSummary.noDbcSelected(),
                null,
                null
        );
    }

    public static SourceInventoryMatchState waiting(String dbcFileName, int dbcMessageCount) {
        return new SourceInventoryMatchState(
                CanDefinitionMatchStatus.WAITING_FOR_SOURCE_INVENTORY,
                dbcFileName,
                CanDefinitionMatchSummary.waitingForInventory(dbcMessageCount),
                null,
                null
        );
    }

    public static SourceInventoryMatchState matching(String dbcFileName) {
        return new SourceInventoryMatchState(
                CanDefinitionMatchStatus.MATCHING,
                dbcFileName,
                null,
                null,
                null
        );
    }

    public static SourceInventoryMatchState ofSummary(String dbcFileName, CanDefinitionMatchSummary summary) {
        return ofSummary(dbcFileName, summary, null);
    }

    public static SourceInventoryMatchState ofSummary(
            String dbcFileName,
            CanDefinitionMatchSummary summary,
            DbcDatabase dbcDatabase
    ) {
        Objects.requireNonNull(summary, "summary");
        return new SourceInventoryMatchState(
                summary.matchingStatus(),
                dbcFileName,
                summary,
                summary.failureReason().orElse(null),
                dbcDatabase
        );
    }

    public static SourceInventoryMatchState failed(String dbcFileName, String reason) {
        return new SourceInventoryMatchState(
                CanDefinitionMatchStatus.FAILED,
                dbcFileName,
                CanDefinitionMatchSummary.failed(reason),
                reason,
                null
        );
    }

    public CanDefinitionMatchStatus status() {
        return status;
    }

    public Optional<String> dbcFileName() {
        return Optional.ofNullable(dbcFileName);
    }

    public Optional<CanDefinitionMatchSummary> summary() {
        return Optional.ofNullable(summary);
    }

    public Optional<String> statusDetail() {
        return Optional.ofNullable(statusDetail);
    }

    public Optional<DbcDatabase> dbcDatabase() {
        return Optional.ofNullable(dbcDatabase);
    }

    public Optional<CanChannelMatchSummary> channelMatch(int channelId) {
        if (summary == null) {
            return Optional.empty();
        }
        return summary.perChannelResults().stream()
                .filter(c -> c.channelId() == channelId)
                .findFirst();
    }

    public Optional<CanMessageMatch> messageMatch(int channelId, long canId, boolean extended) {
        return channelMatch(channelId).flatMap(ch -> ch.messages().stream()
                .filter(m -> m.canId() == canId && m.extended() == extended)
                .findFirst());
    }

    /**
     * Resolves the already-parsed DBC message using the same CanIdKey identity as matching.
     * Does not reparse the DBC.
     */
    public Optional<DbcMessage> dbcMessage(long canId, boolean extended) {
        if (dbcDatabase == null) {
            return Optional.empty();
        }
        return dbcDatabase.findMessage(CanIdKey.fromFrame(canId, extended));
    }

    /**
     * Signal definitions for a matched message. Empty when unmatched / no DBC / no signals.
     */
    public List<DbcSignal> signalDefinitions(long canId, boolean extended) {
        return dbcMessage(canId, extended)
                .map(DbcMessage::signals)
                .orElse(List.of());
    }

    public static long globalObservedUniqueCount(CanSourceInventory inventory, SourceInventoryMatchState match) {
        if (match != null && match.summary().isPresent()
                && match.status() != CanDefinitionMatchStatus.NO_DBC_SELECTED
                && match.status() != CanDefinitionMatchStatus.WAITING_FOR_SOURCE_INVENTORY
                && match.status() != CanDefinitionMatchStatus.MATCHING
                && match.status() != CanDefinitionMatchStatus.FAILED) {
            return match.summary().orElseThrow().globalObservedUniqueCount();
        }
        return computeGlobalObservedUnique(inventory);
    }

    public static long computeGlobalObservedUnique(CanSourceInventory inventory) {
        if (inventory == null) {
            return 0L;
        }
        return inventory.globalUniqueCanIdentityCount();
    }

    public Map<Integer, CanChannelMatchSummary> channelMatchById() {
        Map<Integer, CanChannelMatchSummary> map = new HashMap<>();
        if (summary != null) {
            for (CanChannelMatchSummary ch : summary.perChannelResults()) {
                map.put(ch.channelId(), ch);
            }
        }
        return map;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SourceInventoryMatchState that)) {
            return false;
        }
        return status == that.status
                && Objects.equals(dbcFileName, that.dbcFileName)
                && Objects.equals(statusDetail, that.statusDetail)
                && Objects.equals(summary, that.summary)
                && Objects.equals(dbcDatabase, that.dbcDatabase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, dbcFileName, statusDetail, summary, dbcDatabase);
    }
}

package com.stellar.platform.domain.inventory;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of matching a {@link CanSourceInventory} against a DBC Message-ID set.
 * Global counts are UNIQUE across channels (union), not sums of per-channel counts.
 */
public final class CanDefinitionMatchSummary {
    private final CanDefinitionMatchStatus matchingStatus;
    private final int dbcMessageCount;
    private final long globalObservedUniqueCount;
    private final long globalMatchedUniqueCount;
    private final long globalUnmatchedUniqueCount;
    private final double overallMatchPercentage;
    private final List<CanChannelMatchSummary> perChannelResults;
    private final String failureReason;

    public CanDefinitionMatchSummary(
            CanDefinitionMatchStatus matchingStatus,
            int dbcMessageCount,
            long globalObservedUniqueCount,
            long globalMatchedUniqueCount,
            long globalUnmatchedUniqueCount,
            double overallMatchPercentage,
            List<CanChannelMatchSummary> perChannelResults,
            String failureReason
    ) {
        this.matchingStatus = Objects.requireNonNull(matchingStatus, "matchingStatus");
        this.dbcMessageCount = Math.max(0, dbcMessageCount);
        this.globalObservedUniqueCount = Math.max(0L, globalObservedUniqueCount);
        this.globalMatchedUniqueCount = Math.max(0L, globalMatchedUniqueCount);
        this.globalUnmatchedUniqueCount = Math.max(0L, globalUnmatchedUniqueCount);
        this.overallMatchPercentage = overallMatchPercentage;
        this.perChannelResults = List.copyOf(Objects.requireNonNull(perChannelResults, "perChannelResults"))
                .stream()
                .sorted(Comparator.comparingInt(CanChannelMatchSummary::channelId))
                .toList();
        this.failureReason = failureReason == null || failureReason.isBlank() ? null : failureReason;
    }

    public static CanDefinitionMatchSummary failed(String reason) {
        return new CanDefinitionMatchSummary(
                CanDefinitionMatchStatus.FAILED,
                0,
                0,
                0,
                0,
                0.0,
                List.of(),
                reason
        );
    }

    public static CanDefinitionMatchSummary noDbcSelected() {
        return new CanDefinitionMatchSummary(
                CanDefinitionMatchStatus.NO_DBC_SELECTED,
                0,
                0,
                0,
                0,
                0.0,
                List.of(),
                null
        );
    }

    public static CanDefinitionMatchSummary waitingForInventory(int dbcMessageCount) {
        return new CanDefinitionMatchSummary(
                CanDefinitionMatchStatus.WAITING_FOR_SOURCE_INVENTORY,
                Math.max(0, dbcMessageCount),
                0,
                0,
                0,
                0.0,
                List.of(),
                null
        );
    }

    public CanDefinitionMatchStatus matchingStatus() {
        return matchingStatus;
    }

    public int dbcMessageCount() {
        return dbcMessageCount;
    }

    public long globalObservedUniqueCount() {
        return globalObservedUniqueCount;
    }

    public long globalMatchedUniqueCount() {
        return globalMatchedUniqueCount;
    }

    public long globalUnmatchedUniqueCount() {
        return globalUnmatchedUniqueCount;
    }

    public double overallMatchPercentage() {
        return overallMatchPercentage;
    }

    public List<CanChannelMatchSummary> perChannelResults() {
        return perChannelResults;
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CanDefinitionMatchSummary that)) {
            return false;
        }
        return matchingStatus == that.matchingStatus
                && dbcMessageCount == that.dbcMessageCount
                && globalObservedUniqueCount == that.globalObservedUniqueCount
                && globalMatchedUniqueCount == that.globalMatchedUniqueCount
                && globalUnmatchedUniqueCount == that.globalUnmatchedUniqueCount
                && Double.compare(overallMatchPercentage, that.overallMatchPercentage) == 0
                && Objects.equals(perChannelResults, that.perChannelResults)
                && Objects.equals(failureReason, that.failureReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                matchingStatus,
                dbcMessageCount,
                globalObservedUniqueCount,
                globalMatchedUniqueCount,
                globalUnmatchedUniqueCount,
                overallMatchPercentage,
                perChannelResults,
                failureReason
        );
    }

}

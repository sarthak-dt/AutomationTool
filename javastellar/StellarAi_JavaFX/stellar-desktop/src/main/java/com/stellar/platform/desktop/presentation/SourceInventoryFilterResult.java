package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageMatch;

import java.util.List;
import java.util.Objects;

/**
 * Filtered presentation hierarchy for Source Inventory. Does not mutate source models.
 */
public final class SourceInventoryFilterResult {

    private final List<FilteredChannel> channels;
    private final long visibleMessageCount;
    private final long visibleMatchedCount;
    private final long visibleSignalCount;
    private final boolean autoExpandApplied;

    public SourceInventoryFilterResult(
            List<FilteredChannel> channels,
            long visibleMessageCount,
            long visibleMatchedCount,
            long visibleSignalCount,
            boolean autoExpandApplied
    ) {
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        this.visibleMessageCount = Math.max(0L, visibleMessageCount);
        this.visibleMatchedCount = Math.max(0L, visibleMatchedCount);
        this.visibleSignalCount = Math.max(0L, visibleSignalCount);
        this.autoExpandApplied = autoExpandApplied;
    }

    public static SourceInventoryFilterResult empty() {
        return new SourceInventoryFilterResult(List.of(), 0, 0, 0, false);
    }

    public List<FilteredChannel> channels() {
        return channels;
    }

    public long visibleMessageCount() {
        return visibleMessageCount;
    }

    public long visibleMatchedCount() {
        return visibleMatchedCount;
    }

    public long visibleSignalCount() {
        return visibleSignalCount;
    }

    public boolean autoExpandApplied() {
        return autoExpandApplied;
    }

    /**
     * @param filtersActive when false, prefer global unique/matched totals for the unfiltered view
     * @param globalUniqueCount global unique observed IDs (union across channels)
     * @param globalMatchedCount global unique matched IDs
     */
    public String summaryText(boolean filtersActive, long globalUniqueCount, long globalMatchedCount) {
        if (!filtersActive) {
            return "Showing " + globalUniqueCount + " IDs \u00B7 "
                    + globalMatchedCount + " Matched";
        }
        if (visibleSignalCount > 0 && visibleSignalCount != visibleMessageCount) {
            return "Showing " + visibleMessageCount + " Messages \u00B7 "
                    + visibleSignalCount + " Signals";
        }
        return "Showing " + visibleMessageCount + " IDs \u00B7 "
                + visibleMatchedCount + " Matched";
    }

    public record FilteredChannel(
            CanChannelInventory channel,
            CanChannelMatchSummary channelMatch,
            List<FilteredMessage> messages,
            boolean expand
    ) {
        public FilteredChannel {
            Objects.requireNonNull(channel, "channel");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }
    }

    public record FilteredMessage(
            CanMessageInventory message,
            CanMessageMatch messageMatch,
            List<DbcSignal> visibleSignals,
            boolean showSignalChildren,
            boolean expand,
            boolean eagerlyLoadSignals
    ) {
        public FilteredMessage {
            Objects.requireNonNull(message, "message");
            visibleSignals = List.copyOf(Objects.requireNonNull(visibleSignals, "visibleSignals"));
        }
    }
}

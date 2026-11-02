package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.presentation.CanIdSearchQuery.Parsed;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.CanTypeFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.ContentFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.MatchFilter;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanSourceInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * Read-only Source Inventory projection/query helpers (Phase 1 + Phase 2 enrichment filters).
 * Never reparses sources or mutates matching/project state.
 */
public final class SourceInventoryWorkspaceQuery {

    public record MessageQueryResult(
            List<CanMessageInventoryRow> rows,
            long totalRowsBeforeFilter,
            long uniqueIdentitiesInScope,
            long framesInScope,
            long matchedInScope,
            long unmatchedInScope
    ) {
        public MessageQueryResult(
                List<CanMessageInventoryRow> rows,
                long totalRowsBeforeFilter,
                long uniqueIdentitiesInScope,
                long framesInScope
        ) {
            this(rows, totalRowsBeforeFilter, uniqueIdentitiesInScope, framesInScope, -1L, -1L);
        }
    }

    private SourceInventoryWorkspaceQuery() {
    }

    public static MessageQueryResult queryMessages(
            ProjectSourceId sourceId,
            CanSourceInventory inventory,
            OptionalInt channelId,
            String searchText,
            CanTypeFilter canTypeFilter
    ) {
        return queryMessages(
                sourceId, inventory, channelId, searchText, canTypeFilter,
                MatchFilter.ALL, ContentFilter.ALL, key -> SourceChannelEnrichmentContext.noDbc(key));
    }

    public static MessageQueryResult queryMessages(
            ProjectSourceId sourceId,
            CanSourceInventory inventory,
            OptionalInt channelId,
            String searchText,
            CanTypeFilter canTypeFilter,
            MatchFilter matchFilter,
            ContentFilter contentFilter,
            Function<SourceChannelKey, SourceChannelEnrichmentContext> enrichmentLookup
    ) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (inventory == null) {
            return new MessageQueryResult(List.of(), 0, 0, 0, 0, 0);
        }
        CanTypeFilter type = canTypeFilter == null ? CanTypeFilter.ALL : canTypeFilter;
        MatchFilter match = matchFilter == null ? MatchFilter.ALL : matchFilter;
        ContentFilter content = contentFilter == null ? ContentFilter.ALL : contentFilter;
        Function<SourceChannelKey, SourceChannelEnrichmentContext> lookup = enrichmentLookup == null
                ? key -> SourceChannelEnrichmentContext.noDbc(key)
                : enrichmentLookup;
        Parsed search = CanIdSearchQuery.parse(searchText);

        Map<SourceChannelKey, SourceChannelEnrichmentContext> contextCache = new HashMap<>();
        List<CanMessageInventoryRow> allInScope = new ArrayList<>();
        long frames = 0L;
        long matchedScope = 0L;
        long unmatchedScope = 0L;

        for (CanChannelInventory channel : inventory.channels()) {
            if (channelId != null && channelId.isPresent() && channel.channelId() != channelId.getAsInt()) {
                continue;
            }
            frames += channel.frameCount();
            SourceChannelKey key = SourceChannelKey.of(sourceId, channel.channelId());
            SourceChannelEnrichmentContext ctx = contextCache.computeIfAbsent(key, lookup);
            for (CanMessageInventory message : channel.messages()) {
                CanMessageDefinitionMatch enrichment = SourceInventoryEnrichmentService.enrich(
                        sourceId, key, message.canId(), message.extended(), message.frameCount(), ctx);
                CanMessageInventoryRow row = CanMessageInventoryRow.enriched(sourceId, message, enrichment);
                allInScope.add(row);
                if (enrichment.matched()) {
                    matchedScope++;
                } else if (enrichment.matchState() == InventoryEnrichmentMatchState.UNMATCHED) {
                    unmatchedScope++;
                }
            }
        }

        long unique;
        if (channelId != null && channelId.isPresent()) {
            unique = allInScope.stream()
                    .map(r -> r.canId() + (r.extended() ? "|E" : "|S"))
                    .distinct()
                    .count();
        } else {
            unique = inventory.globalUniqueCanIdentityCount();
        }

        List<CanMessageInventoryRow> filtered = new ArrayList<>();
        for (CanMessageInventoryRow row : allInScope) {
            if (!passesType(type, row)) {
                continue;
            }
            if (!passesMatch(match, row)) {
                continue;
            }
            if (!passesContent(content, row)) {
                continue;
            }
            if (!passesSearch(search, row)) {
                continue;
            }
            filtered.add(row);
        }
        filtered.sort(Comparator
                .comparingInt(CanMessageInventoryRow::channelId)
                .thenComparingLong(CanMessageInventoryRow::canId)
                .thenComparing(CanMessageInventoryRow::extended));
        return new MessageQueryResult(
                List.copyOf(filtered), allInScope.size(), unique, frames, matchedScope, unmatchedScope);
    }

    public static boolean channelFrameCountMatchesMessageSum(CanChannelInventory channel) {
        if (channel == null) {
            return true;
        }
        long sum = channel.messages().stream().mapToLong(CanMessageInventory::frameCount).sum();
        return sum == channel.frameCount();
    }

    private static boolean passesType(CanTypeFilter type, CanMessageInventoryRow row) {
        return switch (type) {
            case ALL, CAN_FD -> true;
            case STANDARD -> !row.extended();
            case EXTENDED -> row.extended();
        };
    }

    private static boolean passesMatch(MatchFilter filter, CanMessageInventoryRow row) {
        InventoryEnrichmentMatchState state = row.matchState();
        return switch (filter) {
            case ALL -> true;
            case MATCHED -> state == InventoryEnrichmentMatchState.MATCHED;
            case UNMATCHED -> state == InventoryEnrichmentMatchState.UNMATCHED;
            case NO_DBC -> state == InventoryEnrichmentMatchState.NO_DBC;
            case AMBIGUOUS -> state == InventoryEnrichmentMatchState.AMBIGUOUS;
        };
    }

    private static boolean passesContent(ContentFilter content, CanMessageInventoryRow row) {
        return switch (content) {
            case ALL, MESSAGES_ONLY, MATCHED_MESSAGES, SIGNALS_ONLY -> true;
            case MESSAGES_WITH_SIGNALS, HAS_SIGNALS -> row.signalCount() > 0;
            case NO_SIGNALS -> row.signalCount() == 0;
        };
    }

    private static boolean passesSearch(Parsed search, CanMessageInventoryRow row) {
        if (search == null || search.rawNormalized().isEmpty()) {
            return true;
        }
        if (CanIdSearchQuery.matches(search, row.canId(), row.extended(), row.channelLabel())) {
            return true;
        }
        String token = search.textToken();
        if (token == null || token.isBlank()) {
            token = search.rawNormalized();
        }
        String q = token.toLowerCase(Locale.ROOT);
        if (row.dbcMessageDisplay() != null
                && !row.dbcMessageDisplay().equals("\u2014")
                && row.dbcMessageDisplay().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (DbcSignalDefinitionView signal : row.signalDefinitions()) {
            if (signal.name().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }
}

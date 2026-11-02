package com.stellar.platform.desktop.presentation;

import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.CanTypeFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.ContentFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.MatchFilter;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterResult.FilteredChannel;
import com.stellar.platform.desktop.presentation.SourceInventoryFilterResult.FilteredMessage;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanDefinitionMatchStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageMatch;
import com.stellar.platform.domain.inventory.CanSourceInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Format-agnostic Source Inventory metadata filter. Operates only on already-built
 * {@link CanSourceInventory}, match summary and cached {@link com.stellar.platform.dbc.model.DbcDatabase}.
 * Does not reparse sources or decode signal values.
 */
public final class SourceInventoryFilterEngine {

    /** Safe auto-expand threshold: avoid expanding huge result sets. */
    public static final int AUTO_EXPAND_MESSAGE_THRESHOLD = 40;

    public SourceInventoryFilterEngine() {
    }

    public SourceInventoryFilterResult filter(
            CanSourceInventory inventory,
            SourceInventoryMatchState matchState,
            SourceInventoryFilterCriteria criteria
    ) {
        if (inventory == null) {
            return SourceInventoryFilterResult.empty();
        }
        SourceInventoryMatchState match = matchState == null
                ? SourceInventoryMatchState.noDbcSelected()
                : matchState;
        SourceInventoryFilterCriteria safe = criteria == null
                ? SourceInventoryFilterCriteria.cleared()
                : criteria;

        boolean dbcReady = isDbcMatchReady(match);
        MatchFilter matchFilter = safe.matchFilter();
        if (!dbcReady && matchFilter != MatchFilter.ALL) {
            matchFilter = MatchFilter.ALL;
        }

        String query = safe.normalizedSearch();
        ContentFilter content = safe.contentFilter();

        List<FilteredChannel> channels = new ArrayList<>();
        long messageCount = 0;
        long matchedCount = 0;
        long signalCount = 0;

        for (CanChannelInventory channel : inventory.channels()) {
            if (safe.channelId().isPresent() && safe.channelId().getAsInt() != channel.channelId()) {
                continue;
            }
            Optional<CanChannelMatchSummary> channelMatch = match.channelMatch(channel.channelId());
            List<FilteredMessage> messages = new ArrayList<>();

            for (CanMessageInventory message : channel.messages()) {
                Optional<CanMessageMatch> messageMatch = match.messageMatch(
                        channel.channelId(), message.canId(), message.extended());
                CanMessageMatch mm = messageMatch.orElse(null);
                boolean matched = mm != null && mm.matched();

                if (!passesMatchFilter(matchFilter, matched, dbcReady)) {
                    continue;
                }
                if (!passesCanTypeFilter(safe.canTypeFilter(), message)) {
                    continue;
                }
                if (content == ContentFilter.MATCHED_MESSAGES && !matched) {
                    continue;
                }

                List<DbcSignal> allSignals = matched
                        ? match.signalDefinitions(message.canId(), message.extended())
                        : List.of();
                if ((content == ContentFilter.MESSAGES_WITH_SIGNALS
                        || content == ContentFilter.HAS_SIGNALS) && allSignals.isEmpty()) {
                    continue;
                }
                if (content == ContentFilter.NO_SIGNALS && !allSignals.isEmpty()) {
                    continue;
                }

                SearchHit hit = evaluateSearch(query, message, mm, allSignals);
                if (hit == SearchHit.NONE && !query.isEmpty()) {
                    continue;
                }

                List<DbcSignal> visibleSignals = resolveVisibleSignals(content, hit, allSignals, query);
                if (content == ContentFilter.SIGNALS_ONLY && visibleSignals.isEmpty()) {
                    continue;
                }

                boolean showSignals = shouldShowSignalChildren(content, matched, visibleSignals);
                boolean signalSearch = !query.isEmpty()
                        && (hit == SearchHit.SIGNAL || hit == SearchHit.BOTH)
                        && visibleSignals.stream().anyMatch(s -> containsIgnoreCase(s.name(), query));
                boolean eager = showSignals && signalSearch;

                messages.add(new FilteredMessage(
                        message,
                        mm,
                        showSignals ? visibleSignals : List.of(),
                        showSignals,
                        false,
                        eager
                ));
                messageCount++;
                if (matched) {
                    matchedCount++;
                }
                if (showSignals) {
                    signalCount += visibleSignals.size();
                }
            }

            if (messages.isEmpty()) {
                continue;
            }
            channels.add(new FilteredChannel(channel, channelMatch.orElse(null), messages, false));
        }

        boolean autoExpand = safe.autoExpandMatches()
                && (!query.isEmpty() || content == ContentFilter.SIGNALS_ONLY)
                && messageCount > 0
                && messageCount <= AUTO_EXPAND_MESSAGE_THRESHOLD;

        if (autoExpand) {
            List<FilteredChannel> expanded = new ArrayList<>(channels.size());
            for (FilteredChannel ch : channels) {
                List<FilteredMessage> msgs = new ArrayList<>(ch.messages().size());
                for (FilteredMessage m : ch.messages()) {
                    boolean expandMsg = m.showSignalChildren();
                    boolean eager = m.eagerlyLoadSignals() || expandMsg;
                    msgs.add(new FilteredMessage(
                            m.message(),
                            m.messageMatch(),
                            m.visibleSignals(),
                            m.showSignalChildren(),
                            expandMsg,
                            eager
                    ));
                }
                expanded.add(new FilteredChannel(ch.channel(), ch.channelMatch(), msgs, true));
            }
            channels = expanded;
        }

        return new SourceInventoryFilterResult(
                channels, messageCount, matchedCount, signalCount, autoExpand);
    }

    public static boolean isDbcMatchReady(SourceInventoryMatchState match) {
        if (match == null) {
            return false;
        }
        CanDefinitionMatchStatus status = match.status();
        return status == CanDefinitionMatchStatus.READY
                || status == CanDefinitionMatchStatus.NO_MATCHING_MESSAGE_IDS;
    }

    public static boolean matchesCanIdText(CanMessageInventory message, String normalizedQuery) {
        Objects.requireNonNull(message, "message");
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return true;
        }
        String q = normalizedQuery.trim().toLowerCase(Locale.ROOT);
        String hex = Long.toHexString(message.canId()).toLowerCase(Locale.ROOT);
        String prefixed = "0x" + hex;
        String decimal = Long.toString(message.canId());
        return hex.contains(q)
                || prefixed.contains(q)
                || decimal.contains(q)
                || ("0x" + q).equals(prefixed)
                || q.equals(hex);
    }

    public static boolean containsIgnoreCase(String haystack, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return true;
        }
        if (haystack == null || haystack.isBlank()) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static boolean passesMatchFilter(MatchFilter filter, boolean matched, boolean dbcReady) {
        if (!dbcReady || filter == MatchFilter.ALL) {
            return true;
        }
        return switch (filter) {
            case ALL -> true;
            case MATCHED -> matched;
            case UNMATCHED -> !matched;
            case NO_DBC, AMBIGUOUS -> false;
        };
    }

    private static boolean passesCanTypeFilter(CanTypeFilter filter, CanMessageInventory message) {
        return switch (filter) {
            case ALL -> true;
            case STANDARD -> !message.extended();
            case EXTENDED -> message.extended();
            case CAN_FD -> message.canFdObserved();
        };
    }

    private static boolean shouldShowSignalChildren(
            ContentFilter content,
            boolean matched,
            List<DbcSignal> visibleSignals
    ) {
        if (!matched || visibleSignals.isEmpty()) {
            return false;
        }
        return switch (content) {
            case MESSAGES_ONLY, MATCHED_MESSAGES, NO_SIGNALS -> false;
            case ALL, SIGNALS_ONLY, MESSAGES_WITH_SIGNALS, HAS_SIGNALS -> true;
        };
    }

    private static List<DbcSignal> resolveVisibleSignals(
            ContentFilter content,
            SearchHit hit,
            List<DbcSignal> allSignals,
            String query
    ) {
        if (allSignals.isEmpty()) {
            return List.of();
        }
        if (content == ContentFilter.MESSAGES_ONLY || content == ContentFilter.MATCHED_MESSAGES) {
            return List.of();
        }
        if (query.isEmpty()) {
            return allSignals;
        }
        if (hit == SearchHit.SIGNAL || hit == SearchHit.BOTH) {
            List<DbcSignal> matchedSignals = allSignals.stream()
                    .filter(s -> containsIgnoreCase(s.name(), query))
                    .toList();
            if (hit == SearchHit.SIGNAL) {
                return matchedSignals;
            }
            // BOTH: message metadata also matched — keep full signal list unless content is signals-only
            if (content == ContentFilter.SIGNALS_ONLY && !matchedSignals.isEmpty()) {
                return matchedSignals;
            }
            return allSignals;
        }
        // MESSAGE hit only: keep all signals when hierarchy shows them
        return allSignals;
    }

    private static SearchHit evaluateSearch(
            String query,
            CanMessageInventory message,
            CanMessageMatch match,
            List<DbcSignal> signals
    ) {
        if (query.isEmpty()) {
            return SearchHit.MESSAGE;
        }
        boolean messageHit = matchesCanIdText(message, query);
        if (!messageHit && match != null && match.matched()) {
            messageHit = match.dbcMessageName().map(n -> containsIgnoreCase(n, query)).orElse(false);
        }
        boolean signalHit = signals.stream().anyMatch(s -> containsIgnoreCase(s.name(), query));
        if (messageHit && signalHit) {
            return SearchHit.BOTH;
        }
        if (messageHit) {
            return SearchHit.MESSAGE;
        }
        if (signalHit) {
            return SearchHit.SIGNAL;
        }
        return SearchHit.NONE;
    }

    private enum SearchHit {
        NONE,
        MESSAGE,
        SIGNAL,
        BOTH
    }
}

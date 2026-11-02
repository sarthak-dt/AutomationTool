package com.stellar.platform.desktop.presentation;

import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Immutable Source Inventory browse/filter criteria. Presentation-only; does not mutate inventory.
 */
public final class SourceInventoryFilterCriteria {

    public enum MatchFilter {
        ALL,
        MATCHED,
        UNMATCHED,
        NO_DBC,
        AMBIGUOUS
    }

    public enum CanTypeFilter {
        ALL,
        STANDARD,
        EXTENDED,
        CAN_FD
    }

    public enum ContentFilter {
        ALL,
        MESSAGES_ONLY,
        MATCHED_MESSAGES,
        MESSAGES_WITH_SIGNALS,
        SIGNALS_ONLY,
        HAS_SIGNALS,
        NO_SIGNALS
    }

    private final String searchText;
    private final OptionalInt channelId;
    private final MatchFilter matchFilter;
    private final CanTypeFilter canTypeFilter;
    private final ContentFilter contentFilter;
    private final boolean autoExpandMatches;

    private SourceInventoryFilterCriteria(
            String searchText,
            OptionalInt channelId,
            MatchFilter matchFilter,
            CanTypeFilter canTypeFilter,
            ContentFilter contentFilter,
            boolean autoExpandMatches
    ) {
        this.searchText = searchText == null ? "" : searchText;
        this.channelId = channelId == null ? OptionalInt.empty() : channelId;
        this.matchFilter = Objects.requireNonNullElse(matchFilter, MatchFilter.ALL);
        this.canTypeFilter = Objects.requireNonNullElse(canTypeFilter, CanTypeFilter.ALL);
        this.contentFilter = Objects.requireNonNullElse(contentFilter, ContentFilter.ALL);
        this.autoExpandMatches = autoExpandMatches;
    }

    public static SourceInventoryFilterCriteria cleared() {
        return new SourceInventoryFilterCriteria(
                "",
                OptionalInt.empty(),
                MatchFilter.ALL,
                CanTypeFilter.ALL,
                ContentFilter.ALL,
                false
        );
    }

    public SourceInventoryFilterCriteria withSearchText(String searchText) {
        return new SourceInventoryFilterCriteria(
                searchText, channelId, matchFilter, canTypeFilter, contentFilter, autoExpandMatches);
    }

    public SourceInventoryFilterCriteria withChannelId(OptionalInt channelId) {
        return new SourceInventoryFilterCriteria(
                searchText, channelId, matchFilter, canTypeFilter, contentFilter, autoExpandMatches);
    }

    public SourceInventoryFilterCriteria withMatchFilter(MatchFilter matchFilter) {
        return new SourceInventoryFilterCriteria(
                searchText, channelId, matchFilter, canTypeFilter, contentFilter, autoExpandMatches);
    }

    public SourceInventoryFilterCriteria withCanTypeFilter(CanTypeFilter canTypeFilter) {
        return new SourceInventoryFilterCriteria(
                searchText, channelId, matchFilter, canTypeFilter, contentFilter, autoExpandMatches);
    }

    public SourceInventoryFilterCriteria withContentFilter(ContentFilter contentFilter) {
        return new SourceInventoryFilterCriteria(
                searchText, channelId, matchFilter, canTypeFilter, contentFilter, autoExpandMatches);
    }

    public SourceInventoryFilterCriteria withAutoExpandMatches(boolean autoExpandMatches) {
        return new SourceInventoryFilterCriteria(
                searchText, channelId, matchFilter, canTypeFilter, contentFilter, autoExpandMatches);
    }

    public String searchText() {
        return searchText;
    }

    /** Normalized query: trimmed, lower-cased, whitespace collapsed. Empty when inactive. */
    public String normalizedSearch() {
        if (searchText.isBlank()) {
            return "";
        }
        return searchText.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public OptionalInt channelId() {
        return channelId;
    }

    public MatchFilter matchFilter() {
        return matchFilter;
    }

    public CanTypeFilter canTypeFilter() {
        return canTypeFilter;
    }

    public ContentFilter contentFilter() {
        return contentFilter;
    }

    public boolean autoExpandMatches() {
        return autoExpandMatches;
    }

    public boolean hasActiveBrowseFilters() {
        return !normalizedSearch().isEmpty()
                || channelId.isPresent()
                || matchFilter != MatchFilter.ALL
                || canTypeFilter != CanTypeFilter.ALL
                || contentFilter != ContentFilter.ALL;
    }

    public boolean isCleared() {
        return normalizedSearch().isEmpty()
                && channelId.isEmpty()
                && matchFilter == MatchFilter.ALL
                && canTypeFilter == CanTypeFilter.ALL
                && contentFilter == ContentFilter.ALL
                && !autoExpandMatches;
    }
}

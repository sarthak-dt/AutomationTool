package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Metadata-only Signal Browser projection. This class never mutates catalog/sample data.
 */
public final class SignalBrowserProjection {

    /** Stable default browser order: Channel 1 first, then engineering identity. */
    private static final Comparator<SignalCatalogEntry> DEFAULT_ORDER =
            Comparator.comparingInt(SignalBrowserProjection::channelSortKey)
                    .thenComparing(e -> e.signalName(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparingLong(SignalCatalogEntry::canId)
                    .thenComparing(e -> e.key().sourceFile(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(e -> e.key().sourceDatabase(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(SignalCatalogEntry::messageName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(e -> e.sourceType().name());

    private static final Comparator<SignalCatalogEntry> FIRST_OCCURRENCE = DEFAULT_ORDER;

    private SignalBrowserProjection() {
    }

    public static List<SignalCatalogEntry> apply(
            List<SignalCatalogEntry> entries,
            SignalBrowserViewMode mode
    ) {
        Objects.requireNonNull(entries, "entries");
        SignalBrowserViewMode effective = mode == null
                ? SignalBrowserViewMode.ALL_INSTANCES
                : mode;
        if (entries.size() < 2) {
            return List.copyOf(entries);
        }
        if (effective == SignalBrowserViewMode.ALL_INSTANCES) {
            List<SignalCatalogEntry> ordered = new ArrayList<>(entries);
            ordered.sort(DEFAULT_ORDER);
            return List.copyOf(ordered);
        }

        // Exact-name grouping is deliberate. Case-only differences can represent distinct
        // engineering definitions and therefore must not be hidden automatically.
        Map<DuplicateViewKey, SignalCatalogEntry> firstByName = new LinkedHashMap<>();
        for (SignalCatalogEntry entry : entries) {
            // Duplicate hiding is scoped to one physical measurement source. Two different
            // recordings may legitimately expose the same signal name and must never hide
            // each other in an all-sources workspace. SignalCatalogKey.sourceFile() carries
            // the stable measurement identity used by CompositeSignalCatalogStore.
            DuplicateViewKey key = new DuplicateViewKey(entry.key().sourceFile(), entry.signalName());
            firstByName.merge(key, entry,
                    (existing, candidate) -> FIRST_OCCURRENCE.compare(candidate, existing) < 0
                            ? candidate
                            : existing);
        }
        List<SignalCatalogEntry> ordered = new ArrayList<>(firstByName.values());
        ordered.sort(DEFAULT_ORDER);
        return List.copyOf(ordered);
    }

    /**
     * Physical keys whose exact signal name occurs more than once in the same measurement source.
     * This intentionally uses the same duplicate semantics as Hide Duplicates.
     */
    public static Set<SignalCatalogKey> duplicatePhysicalKeys(
            List<SignalCatalogEntry> entries
    ) {
        Objects.requireNonNull(entries, "entries");
        if (entries.size() < 2) {
            return Set.of();
        }
        Map<DuplicateViewKey, Integer> counts = new HashMap<>();
        for (SignalCatalogEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            DuplicateViewKey key = new DuplicateViewKey(entry.key().sourceFile(), entry.signalName());
            counts.merge(key, 1, Integer::sum);
        }
        Set<SignalCatalogKey> duplicates = new HashSet<>();
        for (SignalCatalogEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            DuplicateViewKey key = new DuplicateViewKey(entry.key().sourceFile(), entry.signalName());
            if (counts.getOrDefault(key, 0) > 1) {
                duplicates.add(entry.key());
            }
        }
        return Set.copyOf(duplicates);
    }


    /**
     * For every duplicate physical signal, returns the other channels that expose the
     * same exact signal name in the same measurement source. The current physical
     * occurrence is excluded so a Ch1 row can say "Duplicate: Ch2, Ch3" while the
     * Ch2 row says "Duplicate: Ch1, Ch3". Channels are unique and numeric-sorted.
     */
    public static Map<SignalCatalogKey, List<Integer>> duplicatePeerChannels(
            List<SignalCatalogEntry> entries
    ) {
        Objects.requireNonNull(entries, "entries");
        if (entries.size() < 2) {
            return Map.of();
        }

        Map<DuplicateViewKey, List<SignalCatalogEntry>> groups = new HashMap<>();
        for (SignalCatalogEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            DuplicateViewKey key = new DuplicateViewKey(entry.key().sourceFile(), entry.signalName());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }

        Map<SignalCatalogKey, List<Integer>> peersByKey = new HashMap<>();
        for (List<SignalCatalogEntry> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            for (SignalCatalogEntry current : group) {
                List<Integer> peerChannels = group.stream()
                        .filter(candidate -> !candidate.key().equals(current.key()))
                        .map(SignalCatalogEntry::channel)
                        .distinct()
                        .sorted()
                        .toList();
                peersByKey.put(current.key(), peerChannels);
            }
        }
        return Map.copyOf(peersByKey);
    }

    private static int channelSortKey(SignalCatalogEntry entry) {
        return entry != null && entry.sourceType() == SignalSourceType.DBC_CAN
                ? entry.channel()
                : Integer.MAX_VALUE;
    }

    public static int uniqueExactNameCount(List<SignalCatalogEntry> entries) {
        return apply(entries, SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES).size();
    }

    private record DuplicateViewKey(String measurementIdentity, String signalName) {
    }
}

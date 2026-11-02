package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Presentation-only grouping for repeated DBC-defined signals across CAN channels.
 * <p>
 * It never merges samples and never changes physical {@link SignalCatalogKey} identity.
 * A DBC signal is grouped only when source recording, selected definition, message/CAN id,
 * signal name and unit are identical; channel is the only permitted difference. Native MF4
 * measurements remain one row per physical key because their channel index is part of the
 * measurement definition rather than duplicate CAN-bus provenance.
 */
public final class LogicalSignalGroupIndex {

    private static final Comparator<SignalCatalogEntry> REPRESENTATIVE_ORDER = Comparator
            .comparingInt(SignalCatalogEntry::channel)
            .thenComparing(SignalCatalogEntry::signalName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(SignalCatalogEntry::messageName, String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(SignalCatalogEntry::canId)
            .thenComparing(e -> e.key().sourceFile(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> e.key().sourceDatabase(), String.CASE_INSENSITIVE_ORDER);

    private record LogicalKey(
            SignalSourceType sourceType,
            String sourceIdentity,
            String definitionIdentity,
            String signalName,
            String messageName,
            long canId,
            String unit,
            int nativeChannelDiscriminator
    ) {
    }

    private final List<SignalCatalogEntry> representatives;
    private final Map<SignalCatalogKey, List<SignalCatalogEntry>> instancesByRepresentative;
    private final Map<SignalCatalogKey, SignalCatalogKey> representativeByPhysical;

    private LogicalSignalGroupIndex(
            List<SignalCatalogEntry> representatives,
            Map<SignalCatalogKey, List<SignalCatalogEntry>> instancesByRepresentative,
            Map<SignalCatalogKey, SignalCatalogKey> representativeByPhysical
    ) {
        this.representatives = List.copyOf(representatives);
        this.instancesByRepresentative = Map.copyOf(instancesByRepresentative);
        this.representativeByPhysical = Map.copyOf(representativeByPhysical);
    }

    public static LogicalSignalGroupIndex empty() {
        return new LogicalSignalGroupIndex(List.of(), Map.of(), Map.of());
    }

    public static LogicalSignalGroupIndex build(List<SignalCatalogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return empty();
        }
        Map<LogicalKey, List<SignalCatalogEntry>> grouped = new LinkedHashMap<>();
        for (SignalCatalogEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            grouped.computeIfAbsent(logicalKey(entry), ignored -> new ArrayList<>()).add(entry);
        }

        List<SignalCatalogEntry> representatives = new ArrayList<>(grouped.size());
        Map<SignalCatalogKey, List<SignalCatalogEntry>> instances = new LinkedHashMap<>();
        Map<SignalCatalogKey, SignalCatalogKey> representativeByPhysical = new LinkedHashMap<>();

        for (List<SignalCatalogEntry> group : grouped.values()) {
            group.sort(REPRESENTATIVE_ORDER);
            SignalCatalogEntry representative = group.getFirst();
            List<SignalCatalogEntry> immutableGroup = List.copyOf(group);
            representatives.add(representative);
            instances.put(representative.key(), immutableGroup);
            for (SignalCatalogEntry physical : immutableGroup) {
                representativeByPhysical.put(physical.key(), representative.key());
            }
        }

        representatives.sort(Comparator
                .comparing(SignalCatalogEntry::signalName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.sourceType().name())
                .thenComparing(e -> e.sourceFile().toLowerCase(Locale.ROOT))
                .thenComparing(e -> e.sourceDatabase().toLowerCase(Locale.ROOT))
                .thenComparingInt(SignalCatalogEntry::channel)
                .thenComparing(SignalCatalogEntry::messageName, String.CASE_INSENSITIVE_ORDER));
        return new LogicalSignalGroupIndex(representatives, instances, representativeByPhysical);
    }

    private static LogicalKey logicalKey(SignalCatalogEntry entry) {
        SignalCatalogKey key = Objects.requireNonNull(entry.key(), "entry.key()");
        boolean dbc = entry.sourceType() == SignalSourceType.DBC_CAN;
        return new LogicalKey(
                entry.sourceType(),
                key.sourceFile(),
                key.sourceDatabase(),
                key.signalName(),
                key.messageName(),
                key.canId(),
                entry.unit(),
                dbc ? -1 : key.channel()
        );
    }

    /** One catalog row per logical presentation group. */
    public List<SignalCatalogEntry> representatives() {
        return representatives;
    }

    /** Physical instances represented by a browser row. */
    public List<SignalCatalogEntry> instances(SignalCatalogKey representativeOrPhysicalKey) {
        if (representativeOrPhysicalKey == null) {
            return List.of();
        }
        SignalCatalogKey representative = representativeByPhysical.getOrDefault(
                representativeOrPhysicalKey,
                representativeOrPhysicalKey
        );
        return instancesByRepresentative.getOrDefault(representative, List.of());
    }

    public int instanceCount(SignalCatalogKey representativeOrPhysicalKey) {
        return instances(representativeOrPhysicalKey).size();
    }

    public SignalCatalogKey representativeKey(SignalCatalogKey physicalKey) {
        if (physicalKey == null) {
            return null;
        }
        return representativeByPhysical.getOrDefault(physicalKey, physicalKey);
    }
}

package com.stellar.platform.domain.processing;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable, format-independent processing selection: which source-scoped CAN channels
 * should proceed to DBC signal decoding. Lives above parsers.
 */
public final class ProcessingSelection {

    private final Set<SelectedChannelRef> selectedChannels;

    private ProcessingSelection(Set<SelectedChannelRef> selectedChannels) {
        this.selectedChannels = Set.copyOf(selectedChannels);
    }

    public static ProcessingSelection none() {
        return new ProcessingSelection(Set.of());
    }

    public static ProcessingSelection of(Collection<SelectedChannelRef> channels) {
        Objects.requireNonNull(channels, "channels");
        LinkedHashSet<SelectedChannelRef> copy = new LinkedHashSet<>();
        for (SelectedChannelRef ref : channels) {
            if (ref != null) {
                copy.add(ref);
            }
        }
        return new ProcessingSelection(copy);
    }

    public static ProcessingSelection forSource(String sourceIdentity, Collection<Integer> channelIds) {
        Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        LinkedHashSet<SelectedChannelRef> refs = new LinkedHashSet<>();
        if (channelIds != null) {
            for (Integer id : channelIds) {
                if (id != null) {
                    refs.add(SelectedChannelRef.of(sourceIdentity, id));
                }
            }
        }
        return new ProcessingSelection(refs);
    }

    public static String sourceIdentity(Path measurementPath) {
        Objects.requireNonNull(measurementPath, "measurementPath");
        return measurementPath.toAbsolutePath().normalize().toString();
    }

    public Set<SelectedChannelRef> selectedChannels() {
        return selectedChannels;
    }

    public boolean isEmpty() {
        return selectedChannels.isEmpty();
    }

    public int selectedChannelCount() {
        return selectedChannels.size();
    }

    public boolean includes(String sourceIdentity, int channelId) {
        if (sourceIdentity == null || sourceIdentity.isBlank() || channelId < 0) {
            return false;
        }
        return selectedChannels.contains(SelectedChannelRef.of(sourceIdentity, channelId));
    }

    public List<Integer> channelIdsFor(String sourceIdentity) {
        return selectedChannels.stream()
                .filter(r -> r.sourceIdentity().equals(sourceIdentity))
                .map(SelectedChannelRef::channelId)
                .sorted()
                .toList();
    }

    public String summaryLabel() {
        if (selectedChannels.isEmpty()) {
            return "None";
        }
        return selectedChannels.stream()
                .sorted(Comparator.comparing(SelectedChannelRef::sourceIdentity)
                        .thenComparingInt(SelectedChannelRef::channelId))
                .map(SelectedChannelRef::displayName)
                .collect(Collectors.joining(", "));
    }

    public String fingerprint() {
        return selectedChannels.stream()
                .sorted(Comparator.comparing(SelectedChannelRef::sourceIdentity)
                        .thenComparingInt(SelectedChannelRef::channelId))
                .map(r -> r.sourceIdentity() + "#" + r.channelId())
                .collect(Collectors.joining("|"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProcessingSelection that)) {
            return false;
        }
        return selectedChannels.equals(that.selectedChannels);
    }

    @Override
    public int hashCode() {
        return selectedChannels.hashCode();
    }
}

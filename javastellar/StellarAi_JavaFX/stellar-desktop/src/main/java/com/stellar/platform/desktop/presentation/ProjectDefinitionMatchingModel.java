package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelDefinitionComparison;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Observable MF-4 cross-match / mapping state. Mutations are command-driven and idempotent.
 */
public final class ProjectDefinitionMatchingModel {

    public record MatchingSummary(
            int sourceChannelCount,
            int candidateCount,
            int recommendedCount,
            int ambiguousCount,
            int noMatchCount,
            int confirmedCount,
            int manualCount,
            int unmappedCount
    ) {
    }

    private final Map<String, SourceChannelDefinitionCandidate> candidates = new LinkedHashMap<>();
    /** O(1) channel lookup used heavily by the virtualized Quick Decode table. */
    private final Map<SourceChannelKey, List<SourceChannelDefinitionCandidate>> candidatesByChannel =
            new LinkedHashMap<>();
    private final Map<String, SourceChannelDefinitionComparison> comparisons = new LinkedHashMap<>();
    private final Map<SourceChannelKey, SourceChannelMappingEntry> mappings = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public synchronized void clear() {
        boolean changed = !candidates.isEmpty() || !mappings.isEmpty() || !comparisons.isEmpty();
        candidates.clear();
        candidatesByChannel.clear();
        comparisons.clear();
        mappings.clear();
        if (changed) {
            notifyListeners();
        }
    }

    public synchronized void replaceCandidates(List<SourceChannelDefinitionCandidate> next) {
        Map<String, SourceChannelDefinitionCandidate> rebuilt = new LinkedHashMap<>();
        for (SourceChannelDefinitionCandidate c : next == null ? List.<SourceChannelDefinitionCandidate>of() : next) {
            rebuilt.put(candidateKey(c.sourceChannelKey(), c.definitionSourceId()), c);
        }
        if (candidates.equals(rebuilt)) {
            return;
        }
        candidates.clear();
        candidates.putAll(rebuilt);
        rebuildCandidateIndex();
        notifyListeners();
    }

    /**
     * Replace ID-level comparisons. Idempotent — identical content does not notify.
     */
    public synchronized void replaceComparisons(List<SourceChannelDefinitionComparison> next) {
        Map<String, SourceChannelDefinitionComparison> rebuilt = new LinkedHashMap<>();
        for (SourceChannelDefinitionComparison c : next == null
                ? List.<SourceChannelDefinitionComparison>of() : next) {
            rebuilt.put(candidateKey(c.sourceChannelKey(), c.definitionSourceId()), c);
        }
        if (comparisons.equals(rebuilt)) {
            return;
        }
        comparisons.clear();
        comparisons.putAll(rebuilt);
        notifyListeners();
    }

    public synchronized Optional<SourceChannelDefinitionComparison> comparison(
            SourceChannelKey key,
            DefinitionSourceId definitionId
    ) {
        if (key == null || definitionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(comparisons.get(candidateKey(key, definitionId)));
    }

    public synchronized List<SourceChannelDefinitionComparison> comparisonsFor(SourceChannelKey key) {
        List<SourceChannelDefinitionComparison> out = new ArrayList<>();
        for (SourceChannelDefinitionComparison c : comparisons.values()) {
            if (c.sourceChannelKey().equals(key)) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    public synchronized List<SourceChannelDefinitionComparison> allComparisons() {
        return List.copyOf(comparisons.values());
    }

    public synchronized void putMapping(SourceChannelMappingEntry entry) {
        Objects.requireNonNull(entry, "entry");
        SourceChannelMappingEntry previous = mappings.get(entry.sourceChannelKey());
        if (Objects.equals(previous, entry)) {
            return;
        }
        mappings.put(entry.sourceChannelKey(), entry);
        notifyListeners();
    }

    public synchronized void putMappings(List<SourceChannelMappingEntry> entries) {
        boolean changed = false;
        for (SourceChannelMappingEntry entry : entries == null ? List.<SourceChannelMappingEntry>of() : entries) {
            SourceChannelMappingEntry previous = mappings.get(entry.sourceChannelKey());
            if (!Objects.equals(previous, entry)) {
                mappings.put(entry.sourceChannelKey(), entry);
                changed = true;
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public synchronized void removeSource(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return;
        }
        boolean candidatesChanged = candidates.entrySet().removeIf(e ->
                e.getValue().sourceChannelKey().sourceId().equals(sourceId));
        boolean changed = candidatesChanged;
        changed |= comparisons.entrySet().removeIf(e ->
                e.getValue().sourceChannelKey().sourceId().equals(sourceId));
        changed |= mappings.keySet().removeIf(k -> k.sourceId().equals(sourceId));
        if (candidatesChanged) {
            rebuildCandidateIndex();
        }
        if (changed) {
            notifyListeners();
        }
    }

    public synchronized void removeDefinition(DefinitionSourceId definitionId) {
        if (definitionId == null) {
            return;
        }
        boolean candidatesChanged = candidates.entrySet().removeIf(e ->
                e.getValue().definitionSourceId().equals(definitionId));
        boolean changed = candidatesChanged;
        changed |= comparisons.entrySet().removeIf(e ->
                e.getValue().definitionSourceId().equals(definitionId));
        if (candidatesChanged) {
            rebuildCandidateIndex();
        }
        if (changed) {
            notifyListeners();
        }
    }

    public synchronized void removeMapping(SourceChannelKey key) {
        if (key == null) {
            return;
        }
        if (mappings.remove(key) != null) {
            notifyListeners();
        }
    }

    /** Replace all mappings idempotently. */
    public synchronized void replaceMappings(List<SourceChannelMappingEntry> next) {
        Map<SourceChannelKey, SourceChannelMappingEntry> rebuilt = new LinkedHashMap<>();
        for (SourceChannelMappingEntry e : next == null ? List.<SourceChannelMappingEntry>of() : next) {
            rebuilt.put(e.sourceChannelKey(), e);
        }
        if (mappings.equals(rebuilt)) {
            return;
        }
        mappings.clear();
        mappings.putAll(rebuilt);
        notifyListeners();
    }

    public synchronized List<SourceChannelDefinitionCandidate> candidatesFor(SourceChannelKey key) {
        if (key == null) {
            return List.of();
        }
        return candidatesByChannel.getOrDefault(key, List.of());
    }

    private void rebuildCandidateIndex() {
        Map<SourceChannelKey, List<SourceChannelDefinitionCandidate>> grouped = new LinkedHashMap<>();
        for (SourceChannelDefinitionCandidate candidate : candidates.values()) {
            grouped.computeIfAbsent(candidate.sourceChannelKey(), ignored -> new ArrayList<>()).add(candidate);
        }
        candidatesByChannel.clear();
        for (Map.Entry<SourceChannelKey, List<SourceChannelDefinitionCandidate>> entry : grouped.entrySet()) {
            candidatesByChannel.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
    }

    public synchronized List<SourceChannelDefinitionCandidate> allCandidates() {
        return List.copyOf(candidates.values());
    }

    public synchronized Optional<SourceChannelMappingEntry> mapping(SourceChannelKey key) {
        return Optional.ofNullable(mappings.get(key));
    }

    public synchronized List<SourceChannelMappingEntry> allMappings() {
        return List.copyOf(mappings.values());
    }

    public synchronized MatchingSummary summary() {
        int recommended = 0;
        int ambiguous = 0;
        int noMatch = 0;
        int confirmed = 0;
        int manual = 0;
        int unmapped = 0;
        for (SourceChannelMappingEntry e : mappings.values()) {
            switch (e.status()) {
                case RECOMMENDED -> recommended++;
                case AMBIGUOUS -> ambiguous++;
                case NO_MATCH -> noMatch++;
                case CONFIRMED -> confirmed++;
                case MANUAL -> manual++;
                case UNMAPPED, NOT_APPLICABLE -> unmapped++;
            }
        }
        return new MatchingSummary(
                mappings.size(),
                candidates.size(),
                recommended,
                ambiguous,
                noMatch,
                confirmed,
                manual,
                unmapped
        );
    }

    private static String candidateKey(SourceChannelKey channelKey, DefinitionSourceId definitionId) {
        return channelKey + "|" + definitionId.value();
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}

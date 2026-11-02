package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Transient per-channel DBC working selection for Decision Workspace.
 * Keyed by {@link SourceChannelKey} (ProjectSourceId + channel). Not a confirmation.
 */
public final class WorkingDbcSelectionStore {

    public enum DisplayState {
        NONE,
        RECOMMENDED,
        SELECTED,
        CONFIRMED,
        NEEDS_ATTENTION
    }

    private final Map<SourceChannelKey, DefinitionSourceId> selections = new LinkedHashMap<>();

    public Optional<DefinitionSourceId> get(SourceChannelKey key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(selections.get(key));
    }

    public void put(SourceChannelKey key, DefinitionSourceId definitionId) {
        Objects.requireNonNull(key, "key");
        if (definitionId == null) {
            selections.remove(key);
            return;
        }
        selections.put(key, definitionId);
    }

    public void clear(SourceChannelKey key) {
        if (key != null) {
            selections.remove(key);
        }
    }

    public void clearAll() {
        selections.clear();
    }

    public Optional<DefinitionSourceId> displayedDefinition(
            SourceChannelKey key,
            SourceChannelMappingEntry mapping
    ) {
        Optional<DefinitionSourceId> working = get(key);
        if (working.isPresent()) {
            return working;
        }
        if (mapping == null) {
            return Optional.empty();
        }
        return switch (mapping.status()) {
            case CONFIRMED, MANUAL -> mapping.mappedDefinitionId();
            case RECOMMENDED -> mapping.recommendedDefinitionId();
            default -> Optional.empty();
        };
    }

    public DisplayState displayState(SourceChannelKey key, SourceChannelMappingEntry mapping) {
        if (mapping != null
                && (mapping.status() == ChannelMappingStatus.AMBIGUOUS
                || mapping.status() == ChannelMappingStatus.NO_MATCH)
                && get(key).isEmpty()) {
            return DisplayState.NEEDS_ATTENTION;
        }
        Optional<DefinitionSourceId> displayed = displayedDefinition(key, mapping);
        if (displayed.isEmpty()) {
            return DisplayState.NONE;
        }
        Optional<DefinitionSourceId> working = get(key);
        Optional<DefinitionSourceId> mapped = mapping == null ? Optional.empty() : mapping.mappedDefinitionId();
        Optional<DefinitionSourceId> recommended = mapping == null
                ? Optional.empty() : mapping.recommendedDefinitionId();
        boolean accepted = mapping != null
                && (mapping.status() == ChannelMappingStatus.CONFIRMED
                || mapping.status() == ChannelMappingStatus.MANUAL)
                && mapped.isPresent();
        if (accepted && (working.isEmpty() || working.get().equals(mapped.get()))) {
            return DisplayState.CONFIRMED;
        }
        if (recommended.isPresent() && displayed.get().equals(recommended.get())
                && (working.isEmpty() || working.get().equals(recommended.get()))) {
            return DisplayState.RECOMMENDED;
        }
        return DisplayState.SELECTED;
    }
}

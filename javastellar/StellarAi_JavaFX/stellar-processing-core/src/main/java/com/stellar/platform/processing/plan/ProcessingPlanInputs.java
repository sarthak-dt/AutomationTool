package com.stellar.platform.processing.plan;

import com.stellar.platform.domain.processing.plan.SourceChannelRef;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bound project snapshot for plan construction — metadata only. */
public final class ProcessingPlanInputs {

    private final List<SourcePlanInput> sources;
    private final Map<SourceChannelRef, ChannelMappingView> mappings;

    public ProcessingPlanInputs(
            Collection<SourcePlanInput> sources,
            Collection<ChannelMappingView> mappings
    ) {
        LinkedHashMap<String, SourcePlanInput> byId = new LinkedHashMap<>();
        if (sources != null) {
            for (SourcePlanInput source : sources) {
                if (source != null) {
                    byId.put(source.projectSourceId(), source);
                }
            }
        }
        this.sources = List.copyOf(byId.values());
        LinkedHashMap<SourceChannelRef, ChannelMappingView> map = new LinkedHashMap<>();
        if (mappings != null) {
            for (ChannelMappingView view : mappings) {
                if (view != null) {
                    map.put(view.channel(), view);
                }
            }
        }
        this.mappings = Map.copyOf(map);
    }

    public List<SourcePlanInput> sources() {
        return sources;
    }

    public Optional<SourcePlanInput> source(String projectSourceId) {
        if (projectSourceId == null) {
            return Optional.empty();
        }
        for (SourcePlanInput source : sources) {
            if (source.projectSourceId().equals(projectSourceId)) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    public Optional<ChannelMappingView> mapping(SourceChannelRef channel) {
        return Optional.ofNullable(mappings.get(Objects.requireNonNull(channel, "channel")));
    }

    public Map<SourceChannelRef, ChannelMappingView> mappings() {
        return mappings;
    }
}

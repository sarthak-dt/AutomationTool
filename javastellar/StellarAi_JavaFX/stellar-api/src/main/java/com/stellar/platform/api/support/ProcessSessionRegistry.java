package com.stellar.platform.api.support;

import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Holds post-process signal stores keyed by project (latest successful job wins). */
public final class ProcessSessionRegistry {

    private final Map<String, SignalSeriesStore> stores = new ConcurrentHashMap<>();

    public void put(String projectId, SignalSeriesStore store) {
        if (projectId == null || projectId.isBlank() || store == null) {
            return;
        }
        stores.put(projectId, store);
    }

    public Optional<SignalSeriesStore> get(String projectId) {
        return Optional.ofNullable(stores.get(projectId));
    }

    public void clear(String projectId) {
        stores.remove(projectId);
    }
}

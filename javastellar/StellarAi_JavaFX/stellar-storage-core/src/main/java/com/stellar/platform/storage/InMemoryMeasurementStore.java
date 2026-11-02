package com.stellar.platform.storage;

import com.stellar.platform.domain.MeasurementDescriptor;
import com.stellar.platform.domain.MeasurementId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory skeleton store for wiring/smoke tests only.
 */
public final class InMemoryMeasurementStore implements MeasurementStore {

    private final Map<MeasurementId, MeasurementDescriptor> byId = new ConcurrentHashMap<>();

    @Override
    public void save(MeasurementDescriptor descriptor) {
        byId.put(descriptor.id(), descriptor);
    }

    @Override
    public Optional<MeasurementDescriptor> findById(MeasurementId id) {
        return Optional.ofNullable(byId.get(id));
    }
}

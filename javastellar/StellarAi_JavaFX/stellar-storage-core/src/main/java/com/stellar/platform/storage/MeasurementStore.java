package com.stellar.platform.storage;

import com.stellar.platform.domain.MeasurementDescriptor;
import com.stellar.platform.domain.MeasurementId;

import java.util.Optional;

/**
 * Storage abstraction for measurement descriptors.
 * No database implementation at this stage.
 */
public interface MeasurementStore {

    void save(MeasurementDescriptor descriptor);

    Optional<MeasurementDescriptor> findById(MeasurementId id);
}

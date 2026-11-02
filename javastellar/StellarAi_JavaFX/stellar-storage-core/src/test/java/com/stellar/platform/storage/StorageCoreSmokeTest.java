package com.stellar.platform.storage;

import com.stellar.platform.domain.MeasurementDescriptor;
import com.stellar.platform.domain.MeasurementId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageCoreSmokeTest {

    @Test
    void inMemoryStoreRoundTrip() {
        MeasurementStore store = new InMemoryMeasurementStore();
        MeasurementId id = new MeasurementId("m-1");
        MeasurementDescriptor descriptor = new MeasurementDescriptor(id, "One");
        store.save(descriptor);
        assertTrue(store.findById(id).isPresent());
        assertEquals("One", store.findById(id).orElseThrow().displayName());
    }
}

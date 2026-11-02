package com.stellar.platform.testkit;

import com.stellar.platform.domain.MeasurementDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestkitSmokeTest {

    @Test
    void sampleDescriptorIsWired() {
        MeasurementDescriptor descriptor = MeasurementFixtures.sampleDescriptor("fix-1", "Fixture");
        assertEquals("fix-1", descriptor.id().value());
        assertEquals("Fixture", descriptor.displayName());
    }
}

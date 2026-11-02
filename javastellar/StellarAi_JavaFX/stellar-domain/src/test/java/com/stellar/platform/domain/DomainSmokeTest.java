package com.stellar.platform.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainSmokeTest {

    @Test
    void measurementIdRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new MeasurementId(" "));
    }

    @Test
    void measurementDescriptorHoldsId() {
        MeasurementId id = new MeasurementId("session-1");
        MeasurementDescriptor descriptor = new MeasurementDescriptor(id, "Session 1");
        assertEquals(id, descriptor.id());
    }

    @Test
    void canonicalMeasurementSample_mapsToCanonicalSignal() {
        CanonicalMeasurementSample sample = new CanonicalMeasurementSample(
                "EngineRPM",
                2_010_050_000L,
                1,
                1125.0,
                4500L,
                "rpm",
                null,
                new SourceReference(1, 100)
        );
        CanonicalSignal signal = sample.toCanonicalSignal("MF4_MEASUREMENT");
        assertEquals("EngineRPM", signal.signalName());
        assertEquals("MF4_MEASUREMENT", signal.messageName());
        assertEquals(0L, signal.canId());
        assertEquals(1125.0, signal.physicalValue(), 1e-9);
        assertEquals(4500L, signal.rawValue());
    }
}

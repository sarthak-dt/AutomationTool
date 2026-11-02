package com.stellar.platform.processing.drive.speed;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedUnitTest {

    @Test
    void knownUnitsConvertToKilometresPerHour() {
        assertEquals(36.0, SpeedUnit.parse("m/s").orElseThrow().toKilometresPerHour(10), 1e-9);
        assertEquals(100.0, SpeedUnit.parse("km/h").orElseThrow().toKilometresPerHour(100), 1e-9);
        assertEquals(100.0, SpeedUnit.parse("kmph").orElseThrow().toKilometresPerHour(100), 1e-9);
        assertEquals(100.0, SpeedUnit.parse("kph").orElseThrow().toKilometresPerHour(100), 1e-9);
        assertEquals(1.609344 * 60.0, SpeedUnit.parse("mph").orElseThrow().toKilometresPerHour(60), 1e-9);
    }

    @Test
    void ambiguousUnitsAreNotGuessed() {
        assertEquals(Optional.empty(), SpeedUnit.parse(""));
        assertEquals(Optional.empty(), SpeedUnit.parse("ticks"));
        assertEquals(Optional.empty(), SpeedUnit.parse("%"));
        assertEquals(Optional.empty(), SpeedUnit.parse("rpm"));
        assertTrue(SpeedUnit.parse(null).isEmpty());
    }
}

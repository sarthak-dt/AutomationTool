package com.stellar.platform.processing.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoDistanceTest {

    @Test
    void haversineOfKnownEquatorDegreeIsAbout111Kilometres() {
        double meters = GeoDistance.haversineMeters(0.0, 0.0, 0.0, 1.0);
        assertEquals(111_195.0, meters, 50.0);
        assertEquals(meters / 1000.0, meters / 1000.0, 1e-12);
    }

    @Test
    void haversineOfOneDegreeLatitudeIsAbout111Kilometres() {
        double meters = GeoDistance.haversineMeters(0.0, 0.0, 1.0, 0.0);
        assertEquals(111_195.0, meters, 50.0);
    }

    @Test
    void haversineUsesMetresNotKilometres() {
        double meters = GeoDistance.haversineMeters(12.9716, 77.5946, 12.9716, 77.6046);
        assertTrue(meters > 1_000.0 && meters < 1_200.0, "1e-2 deg longitude at Bengaluru is ~1.08 km, got " + meters);
    }

    @Test
    void identicalPointsHaveZeroDistance() {
        assertEquals(0.0, GeoDistance.haversineMeters(18.5, 73.8, 18.5, 73.8), 1e-9);
    }

    @Test
    void coordinateValidityRejectsNonFiniteAndOutOfRange() {
        assertTrue(GeoDistance.validLatitude(90.0));
        assertTrue(GeoDistance.validLongitude(-180.0));
        assertFalse(GeoDistance.validLatitude(90.1));
        assertFalse(GeoDistance.validLongitude(180.1));
        assertFalse(GeoDistance.validLatitude(Double.NaN));
        assertFalse(GeoDistance.validLongitude(Double.POSITIVE_INFINITY));
        assertFalse(GeoDistance.validPosition(18.5, Double.NEGATIVE_INFINITY));
        assertTrue(GeoDistance.validPosition(0.0, 0.0));
    }
}

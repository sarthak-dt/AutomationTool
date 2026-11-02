package com.stellar.platform.processing.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalNameNormalizerTest {

    @Test
    void foldsSeparatorsAndCase() {
        assertEquals("gpslatitude", SignalNameNormalizer.normalize("GPS_Latitude"));
        assertEquals("gpslatitude", SignalNameNormalizer.normalize("gps latitude"));
        assertEquals("gpslatitude", SignalNameNormalizer.normalize("gps-latitude"));
        assertEquals("gpslatitude", SignalNameNormalizer.normalize("GPSLatitude"));
        assertEquals("gpslatitude", SignalNameNormalizer.normalize("GPS.Latitude"));
    }
}
